package jo.codeeditor.completion;

import java.util.*;

/**
 * Parameter info popup controller.
 * Shows function signature help when the caret is inside a function call.
 * Supports explicit trigger (Ctrl+P), automatic resolution on caret move,
 * and dismissal per call.
 * Ported from CodeAssist SignatureHelpController.kt.
 
 *
 * @since v1.0.7
*/
public class SignatureHelpController {

    // ── Data types ────────────────────────────────────────────────

    /** A single parameter in a signature. */
    public static final class Parameter {
        public final String label;
        public final String documentation;

        public Parameter(String label, String documentation) {
            this.label = label != null ? label : "";
            this.documentation = documentation != null ? documentation : "";
        }

        @Override
        public String toString() {
            return "Parameter(\"" + label + "\")";
        }
    }

    /** A function signature with its parameters. */
    public static final class Signature {
        public final String label;
        public final String documentation;
        public final List<Parameter> parameters;
        public final int activeParameter;

        public Signature(String label, String documentation, List<Parameter> parameters, int activeParameter) {
            this.label = label != null ? label : "";
            this.documentation = documentation != null ? documentation : "";
            this.parameters = parameters != null ? Collections.unmodifiableList(parameters) : Collections.emptyList();
            this.activeParameter = activeParameter;
        }

        @Override
        public String toString() {
            return "Signature(\"" + label + "\", params=" + parameters.size() + ", active=" + activeParameter + ")";
        }
    }

    /** Full signature help response. */
    public static final class SignatureHelp {
        public final List<Signature> signatures;
        public final int activeSignature;
        public final int activeParameter;

        public SignatureHelp(List<Signature> signatures, int activeSignature, int activeParameter) {
            this.signatures = signatures != null ? Collections.unmodifiableList(signatures) : Collections.emptyList();
            this.activeSignature = activeSignature;
            this.activeParameter = activeParameter;
        }

        /** Returns the currently active signature. */
        public Signature getActiveSignature() {
            if (signatures.isEmpty()) return null;
            int idx = Math.min(activeSignature, signatures.size() - 1);
            return signatures.get(idx);
        }

        @Override
        public String toString() {
            return "SignatureHelp(sigs=" + signatures.size() + ", active=" + activeSignature + ")";
        }
    }

    // ── State ─────────────────────────────────────────────────────

    /** Current signature help or null. */
    private SignatureHelp help;

    /** Whether the user dismissed the popup for the current call. */
    private boolean dismissed = false;

    /**
     * v2.39: User-chosen active signature override. When {@code >= 0},
     * the renderer uses this index instead of {@link SignatureHelp#activeSignature}.
     *
     * <p>This is what makes Up/Down keyboard navigation between overloads
     * work: the LSP server returns its "best guess" activeSignature in
     * each response, but the user can switch overloads manually. We
     * persist the override across {@link #resolve(CharSequence, int)}
     * refreshes within the same call so the chosen overload doesn't
     * "snap back" to the server's preference while the user is typing.
     *
     * <p>Reset to {@code -1} when the call boundary changes (new
     * opening paren) or in {@link #reset()}.
     */
    private int userOverrideActiveSignature = -1;

    /** Epoch counter to avoid stale results. */
    private long epoch = 0;

    /** Listener for resolving signature help. */
    private Resolver listener;

    /** Last known call open position (for tracking which call we're in). */
    private int lastCallOpenPos = -1;

    /**
     * Interface for resolving signature help from the language server.
     */
    public interface Resolver {
        /**
         * Resolve signature help at the given offset.
         * @param offset caret offset
         * @param callOpenPos offset of the opening '('
         * @return signature help, or null
         */
        SignatureHelp resolve(int offset, int callOpenPos);
    }

    // ── Constructors ──────────────────────────────────────────────

    public SignatureHelpController() {
        this(null);
    }

    public SignatureHelpController(Resolver listener) {
        this.listener = listener;
    }

    // ── Accessors ─────────────────────────────────────────────────

    public SignatureHelp getHelp() { return help; }
    public boolean isDismissed() { return dismissed; }
    public long getEpoch() { return epoch; }

    public void setListener(Resolver listener) { this.listener = listener; }

    /**
     * v2.39: Returns the effective active signature index — the user's
     * override if set and in range, otherwise the LSP server's
     * {@link SignatureHelp#activeSignature}.
     *
     * <p>Renderers should read this instead of {@code help.activeSignature}
     * so Up/Down keyboard navigation (see {@link #cycleActiveSignature})
     * is reflected in the popup.
     *
     * @return the active signature index, or {@code -1} if no help is available
     * @since v2.39
     */
    public int getEffectiveActiveSignature() {
        if (help == null || help.signatures.isEmpty()) return -1;
        int max = help.signatures.size() - 1;
        if (userOverrideActiveSignature >= 0 && userOverrideActiveSignature <= max) {
            return userOverrideActiveSignature;
        }
        return Math.max(0, Math.min(help.activeSignature, max));
    }

    /**
     * v2.39: Returns the user-chosen active signature, or {@code -1} if
     * the user hasn't overridden the server's choice. Useful for tests.
     *
     * @since v2.39
     */
    public int getUserOverrideActiveSignature() {
        return userOverrideActiveSignature;
    }

    /**
     * v2.39: Cycles the active signature by {@code +1} or {@code -1},
     * wrapping around. Used by Up/Down keyboard navigation in the
     * signature help popup.
     *
     * <p>The override persists across {@link #resolve} refreshes within
     * the same call (so the chosen overload stays selected while the
     * user types more arguments) and is reset to {@code -1} when the
     * caret moves to a different call.
     *
     * <p>No-op if no help is available or only one signature exists.
     *
     * @param direction {@code +1} for next overload (Down), {@code -1}
     *                  for previous overload (Up)
     * @return the new effective active signature index, or {@code -1}
     *         if no cycling happened
     * @since v2.39
     */
    public int cycleActiveSignature(int direction) {
        if (help == null || help.signatures.isEmpty()) return -1;
        int n = help.signatures.size();
        if (n == 1) return 0;
        int current = getEffectiveActiveSignature();
        if (current < 0) current = 0;
        // Wrap around: ((current + direction) % n + n) % n
        int next = ((current + direction) % n + n) % n;
        userOverrideActiveSignature = next;
        return next;
    }

    /**
     * v2.39: Explicitly sets the user's chosen overload index. Used by
     * tests and programmatic clients (e.g. a "1/3" tab UI in the popup).
     *
     * @param idx 0-based signature index, or {@code -1} to clear the
     *            override and fall back to the server's choice
     * @since v2.39
     */
    public void setUserActiveSignature(int idx) {
        if (idx < -1) idx = -1;
        userOverrideActiveSignature = idx;
    }

    // ── Trigger ───────────────────────────────────────────────────

    /**
     * Force show signature help (Ctrl+P or explicit trigger).
     * Resets dismissed state and increments epoch.
     *
     * @param text   document text
     * @param caret  current caret offset
     */
    public void triggerExplicit(CharSequence text, int caret) {
        dismissed = false;
        epoch++;
        resolve(text, caret);
    }

    /**
     * Dismiss signature help for the current call.
     * Sets dismissed flag so it won't reappear until the caret
     * moves to a different call.
     */
    public void dismiss() {
        dismissed = true;
        help = null;
    }

    /**
     * Re-resolve signature help when caret moves.
     * Only resolves if caret is inside a call and not dismissed
     * for the current call.
     *
     * <p>v2.39: when the caret moves to a different call (the opening
     * paren position changes), the user's chosen overload override is
     * reset so the popup shows the new call's default active signature.
     *
     * @param text   document text
     * @param caret  current caret offset
     */
    public void resolve(CharSequence text, int caret) {
        // Find the enclosing open paren FIRST — even if dismissed, we
        // need to know whether the caret moved to a different call so
        // we can reset the dismissed flag.
        int callOpen = findCallOpen(text, caret);
        if (callOpen < 0) {
            help = null;
            lastCallOpenPos = -1;
            // v2.39: leaving the call entirely → reset override too.
            userOverrideActiveSignature = -1;
            return;
        }

        // If we moved to a different call, reset dismissed so the popup
        // can reappear for the new call.
        if (callOpen != lastCallOpenPos) {
            dismissed = false;
            lastCallOpenPos = callOpen;
            // v2.39: new call → forget the user's previous overload choice.
            userOverrideActiveSignature = -1;
        }

        if (dismissed) return;

        // Resolve
        if (listener != null) {
            epoch++;
            help = listener.resolve(caret, callOpen);
        }
    }

    /**
     * Cheap gate check: is the caret inside a function call?
     * Scans backward for an unmatched '('.
     *
     * @param chars document text
     * @param caret current caret offset
     * @return true if caret is inside a call
     */
    public static boolean caretInsideCall(CharSequence chars, int caret) {
        return findCallOpen(chars, caret) >= 0;
    }

    /**
     * Find the position of the unmatched '(' before the caret.
     * Returns -1 if not inside a call.
     */
    public static int findCallOpen(CharSequence chars, int caret) {
        int depth = 0;
        int limit = Math.min(caret, chars.length());
        for (int i = limit - 1; i >= 0; i--) {
            char ch = chars.charAt(i);
            if (ch == ')') depth++;
            else if (ch == '(') {
                if (depth == 0) return i;
                depth--;
            } else if (ch == ';' || ch == '{') {
                // Stop scanning at statement/block boundaries
                break;
            }
        }
        return -1;
    }

    /**
     * Count the number of commas between the open paren and the caret
     * to determine the active parameter index.
     *
     * @param chars    document text
     * @param callOpen position of the '('
     * @param caret    current caret offset
     * @return parameter index (0-based)
     */
    public static int activeParameterIndex(CharSequence chars, int callOpen, int caret) {
        if (callOpen < 0 || caret <= callOpen) return 0;
        int depth = 0;
        int commas = 0;
        int start = callOpen + 1;
        int end = Math.min(caret, chars.length());
        for (int i = start; i < end; i++) {
            char ch = chars.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
            else if (ch == ',' && depth == 0) commas++;
        }
        return commas;
    }

    // ── Reset ─────────────────────────────────────────────────────

    /**
     * Resets all controller state: help, dismissed flag, epoch,
     * last-call-open position, and (v2.39) the user's overload override.
     */
    public void reset() {
        help = null;
        dismissed = false;
        epoch = 0;
        lastCallOpenPos = -1;
        // v2.39: clear the overload override too.
        userOverrideActiveSignature = -1;
    }
}
