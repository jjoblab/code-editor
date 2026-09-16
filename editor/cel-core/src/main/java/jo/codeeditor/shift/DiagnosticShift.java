package jo.codeeditor.shift;

import java.util.*;

/**
 * Shifts diagnostic offsets, semantic tokens, fold regions, and inlay hints
 * across text edits. Ported from CodeAssist DiagnosticShift.kt.
 * <p>
 * Uses right-gravity for starts (they move with the edit) and left-gravity
 * for ends (they stay put when at the edit boundary).
 
 *
 * @since v1.0.0
*/
public final class DiagnosticShift {

    private DiagnosticShift() {}

    /**
     * Recover a minimal EditSpan by comparing old and new text.
     * Uses common prefix/suffix.
     */
    public static EditSpan diffEdit(CharSequence oldText, CharSequence newText) {
        int oldLen = oldText.length();
        int newLen = newText.length();

        // Common prefix
        int prefix = 0;
        int maxPrefix = Math.min(oldLen, newLen);
        while (prefix < maxPrefix && oldText.charAt(prefix) == newText.charAt(prefix)) {
            prefix++;
        }

        // Common suffix (not overlapping with prefix)
        int suffix = 0;
        int maxSuffix = Math.min(oldLen - prefix, newLen - prefix);
        while (suffix < maxSuffix
            && oldText.charAt(oldLen - 1 - suffix) == newText.charAt(newLen - 1 - suffix)) {
            suffix++;
        }

        int removed = oldLen - prefix - suffix;
        int added = newLen - prefix - suffix;

        return new EditSpan(prefix, removed, added);
    }

    /**
     * Map a start offset through an edit span (right-gravity).
     * If offset is at the edit start, it moves with the edit.
     */
    public static int mapStart(int offset, EditSpan span) {
        if (offset <= span.start) return offset;
        if (offset <= span.end()) return span.start + span.added;
        return offset + span.delta();
    }

    /**
     * Map an end offset through an edit span (left-gravity).
     * If offset is at the edit end, it stays.
     */
    public static int mapEnd(int offset, EditSpan span) {
        if (offset < span.start) return offset;
        if (offset < span.end()) return span.start;
        return offset + span.delta();
    }

    /**
     * Diagnostic with offset info to be shifted.
     */
    public static final class Diagnostic {
        public int start;
        public int end;
        public final int severity;
        public final String message;

        public Diagnostic(int start, int end, int severity, String message) {
            this.start = start;
            this.end = end;
            this.severity = severity;
            this.message = message;
        }
    }

    /**
     * Shift a list of diagnostics across an edit.
     * Removes diagnostics that collapse to zero length.
     */
    public static List<Diagnostic> shiftDiagnostics(List<Diagnostic> diagnostics, EditSpan span) {
        List<Diagnostic> result = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            int newStart = mapStart(d.start, span);
            int newEnd = mapEnd(d.end, span);
            if (newEnd > newStart) {
                result.add(new Diagnostic(newStart, newEnd, d.severity, d.message));
            }
        }
        return result;
    }

    /**
     * Semantic token with offset info.
     */
    public static final class SemanticToken {
        public int start;
        public int length;
        public final int type;

        public SemanticToken(int start, int length, int type) {
            this.start = start;
            this.length = length;
            this.type = type;
        }

        public int end() { return start + length; }
    }

    /**
     * Shift semantic tokens across an edit.
     */
    public static List<SemanticToken> shiftSemanticTokens(List<SemanticToken> tokens, EditSpan span) {
        List<SemanticToken> result = new ArrayList<>();
        for (SemanticToken t : tokens) {
            int newStart = mapStart(t.start, span);
            int newEnd = mapEnd(t.start + t.length, span);
            int newLen = newEnd - newStart;
            if (newLen > 0) {
                result.add(new SemanticToken(newStart, newLen, t.type));
            }
        }
        return result;
    }

    /**
     * Fold region with offset info.
     */
    public static final class FoldRegion {
        public int start;
        public int end;
        public final String placeholder;
        /** Fold kind: "block", "comment", "imports", "region"… (purely informational). */
        public final String kind;
        /** True when the fold is currently collapsed (hidden). */
        public final boolean collapsed;
        /**
         * ★ v2.33 — repliée PAR DÉFAUT (CodeAssist {@code collapsedByDefault}) :
         * appliquée UNE SEULE FOIS par document (imports) puis l'état
         * utilisateur l'emporte — cf. {@code EditorSession.applyCodeFolds}.
         */
        public final boolean collapsedByDefault;

        public FoldRegion(int start, int end, String placeholder) {
            this(start, end, placeholder, "block", false);
        }

        public FoldRegion(int start, int end, String placeholder, String kind, boolean collapsed) {
            this(start, end, placeholder, kind, collapsed, false);
        }

        /**
         * ★ v2.33 — variante complète (collapsedByDefault pour le pliage
         * serveur : groupe d'imports replié à l'ouverture comme
         * CodeAssist/IntelliJ).
         */
        public FoldRegion(int start, int end, String placeholder, String kind,
                boolean collapsed, boolean collapsedByDefault) {
            this.start = start;
            this.end = end;
            this.placeholder = placeholder != null ? placeholder : "...";
            this.kind = kind != null ? kind : "block";
            this.collapsed = collapsed;
            this.collapsedByDefault = collapsedByDefault;
        }
    }

    /**
     * Shift fold regions across an edit.
     */
    public static List<FoldRegion> shiftFoldRegions(List<FoldRegion> regions, EditSpan span) {
        List<FoldRegion> result = new ArrayList<>();
        for (FoldRegion r : regions) {
            int newStart = mapStart(r.start, span);
            int newEnd = mapEnd(r.end, span);
            if (newEnd > newStart) {
                result.add(new FoldRegion(newStart, newEnd, r.placeholder, r.kind, r.collapsed));
            }
        }
        return result;
    }

    /**
     * Inlay hint with offset info.
     */
    public static final class InlayHint {
        public int offset;
        public final String text;
        public final boolean before;

        public InlayHint(int offset, String text, boolean before) {
            this.offset = offset;
            this.text = text;
            this.before = before;
        }
    }

    /**
     * Shift inlay hints across an edit.
     */
    public static List<InlayHint> shiftInlayHints(List<InlayHint> hints, EditSpan span) {
        List<InlayHint> result = new ArrayList<>();
        for (InlayHint h : hints) {
            int newOffset = mapStart(h.offset, span);
            result.add(new InlayHint(newOffset, h.text, h.before));
        }
        return result;
    }
}
