package jo.codeeditor.navigation;

import java.util.*;

/**
 * Go-to navigation: declaration, implementation, type declaration, super.
 * Resolves navigation targets and handles single/multiple results.
 * Ported from CodeAssist navigation module.
 
 *
 * @since v1.0.7
*/
public class NavigationMenu {

    // ── Enums & Data types ────────────────────────────────────────

    /** Type of navigation target. */
    public enum NavKind {
        DECLARATION,
        IMPLEMENTATION,
        TYPE_DECLARATION,
        SUPER
    }

    /** A single navigation target location. */
    public static final class NavTarget {
        public final String path;
        public final int offset;
        public final String displayName;

        public NavTarget(String path, int offset, String displayName) {
            this.path = path != null ? path : "";
            this.offset = offset;
            this.displayName = displayName != null ? displayName : "";
        }

        public NavTarget(String path, int offset) {
            this(path, offset, "");
        }

        @Override
        public String toString() {
            return "NavTarget(\"" + path + ":" + offset + "\", \"" + displayName + "\")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NavTarget)) return false;
            NavTarget t = (NavTarget) o;
            return offset == t.offset && path.equals(t.path);
        }

        @Override
        public int hashCode() {
            return 31 * path.hashCode() + offset;
        }
    }

    /** A navigation option with a label and its targets. */
    public static final class NavOption {
        public final String label;
        public final List<NavTarget> targets;

        public NavOption(String label, List<NavTarget> targets) {
            this.label = label != null ? label : "";
            this.targets = targets != null ? Collections.unmodifiableList(targets) : Collections.emptyList();
        }

        @Override
        public String toString() {
            return "NavOption(\"" + label + "\", targets=" + targets.size() + ")";
        }
    }

    /**
     * A document symbol for go-to-symbol navigation (v1.0.7 — Gap 6).
     * Mirrors LSP's DocumentSymbol: a name, an offset, a kind (class /
     * method / field / etc.) and the container (declaring class or file).
     */
    public static final class Symbol {
        public final String name;
        public final int offset;
        public final String kind;
        public final String container;

        public Symbol(String name, int offset, String kind, String container) {
            this.name = name != null ? name : "";
            this.offset = offset;
            this.kind = kind != null ? kind : "";
            this.container = container != null ? container : "";
        }

        @Override
        public String toString() {
            return "Symbol(\"" + name + "\", offset=" + offset + ", kind=" + kind
                + ", container=" + container + ")";
        }
    }

    /**
     * Filters a list of symbols by prefix using case-insensitive prefix
     * match OR camel-hump subsequence (e.g. "gS" matches "getString").
     * Returns a new list, preserving the input order for stable ranking.
     *
     * <p>Pure-Java and unit-testable. The view's go-to-symbol popup calls
     * this on every keystroke in the filter field.
     *
     * @param symbols the full symbol list
     * @param prefix  the typed filter prefix (may be empty → returns all)
     * @return a new list of matching symbols
     */
    public static List<Symbol> filter(List<Symbol> symbols, String prefix) {
        if (symbols == null) return Collections.emptyList();
        if (prefix == null || prefix.isEmpty()) return new ArrayList<>(symbols);
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        List<Symbol> out = new ArrayList<>();
        for (Symbol s : symbols) {
            if (s.name == null || s.name.isEmpty()) continue;
            String lowerName = s.name.toLowerCase(java.util.Locale.ROOT);
            if (lowerName.startsWith(lowerPrefix)
                || isCamelHumpSubsequence(s.name, prefix)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Returns true if every char in {@code prefix} matches a char in
     * {@code label}, in order, where each match is either:
     * <ul>
     *   <li>The first char of the prefix (any position in the label),</li>
     *   <li>A word-boundary position (uppercase or after _ / . / whitespace),</li>
     *   <li>Or immediately follows the previous matched char (contiguous run).</li>
     * </ul>
     * This means both "gS" (camel-hump) and "get" (contiguous prefix) match
     * "getString". Algorithm matches {@link jo.codeeditor.completion.CompletionSession#isCamelHumpSubsequence}.
     */
    public static boolean isCamelHumpSubsequence(String label, String prefix) {
        if (prefix == null || prefix.isEmpty()) return true;
        if (label == null || label.isEmpty()) return false;
        int pi = 0;
        boolean prevMatched = false;
        for (int li = 0; li < label.length() && pi < prefix.length(); li++) {
            char lc = label.charAt(li);
            char pc = prefix.charAt(pi);
            if (Character.toLowerCase(lc) == Character.toLowerCase(pc)) {
                boolean isWordStart = Character.isUpperCase(lc)
                    || li == 0
                    || (li > 0 && (label.charAt(li - 1) == '_'
                        || label.charAt(li - 1) == '.'
                        || Character.isWhitespace(label.charAt(li - 1))));
                if (pi == 0 || isWordStart || !prevMatched) {
                    pi++;
                    prevMatched = true;
                    continue;
                }
            }
            prevMatched = false;
        }
        return pi == prefix.length();
    }

    // ── State ─────────────────────────────────────────────────────

    /** Current navigation options. */
    private List<NavOption> options = new ArrayList<>();

    /** Navigation resolver. */
    private NavigationResolver resolver;

    /** Navigation result listener. */
    private NavigationListener listener;

    /** Current selected option (for picker). */
    private int selectedOption = 0;

    /** Whether the picker is open. */
    private boolean pickerOpen = false;

    /**
     * Interface for resolving navigation targets from the language server.
     */
    public interface NavigationResolver {
        /**
         * Resolve navigation targets for the given kind at the caret.
         */
        List<NavTarget> resolve(NavKind kind, String filePath, int offset);
    }

    /**
     * Interface for navigation result events.
     */
    public interface NavigationListener {
        /** Called when a single target should be navigated to. */
        void onNavigate(NavTarget target);
        /** Called when multiple targets need a picker. */
        void onShowPicker(List<NavTarget> targets);
    }

    // ── Constructors ──────────────────────────────────────────────

    public NavigationMenu() {
        this(null, null);
    }

    public NavigationMenu(NavigationResolver resolver, NavigationListener listener) {
        this.resolver = resolver;
        this.listener = listener;
    }

    // ── Accessors ─────────────────────────────────────────────────

    public List<NavOption> getOptions() { return Collections.unmodifiableList(options); }
    public boolean isPickerOpen() { return pickerOpen; }
    public int getSelectedOption() { return selectedOption; }

    public void setResolver(NavigationResolver resolver) { this.resolver = resolver; }
    public void setListener(NavigationListener listener) { this.listener = listener; }

    // ── Navigation ────────────────────────────────────────────────

    /**
     * Run navigation of the given kind at the current position.
     * If a single target is found, navigates directly.
     * If multiple targets, opens a picker.
     *
     * @param kind     navigation kind
     * @param filePath current file path
     * @param offset   current caret offset
     */
    public void runNav(NavKind kind, String filePath, int offset) {
        if (resolver == null) return;

        List<NavTarget> targets = resolver.resolve(kind, filePath, offset);
        if (targets == null || targets.isEmpty()) return;

        if (targets.size() == 1) {
            // Single target: navigate directly
            if (listener != null) {
                listener.onNavigate(targets.get(0));
            }
        } else {
            // Multiple targets: show picker
            if (listener != null) {
                listener.onShowPicker(targets);
            }
            openPicker(targets);
        }
    }

    /**
     * Open the navigation picker with the given targets.
     */
    private void openPicker(List<NavTarget> targets) {
        options = new ArrayList<>();
        for (NavTarget t : targets) {
            String label = t.displayName.isEmpty()
                ? t.path + ":" + t.offset
                : t.displayName;
            options.add(new NavOption(label, Collections.singletonList(t)));
        }
        pickerOpen = true;
        selectedOption = 0;
    }

    /**
     * Close the picker.
     */
    public void closePicker() {
        pickerOpen = false;
        selectedOption = 0;
    }

    /**
     * Move picker selection.
     */
    public void movePickerSelection(int dir) {
        if (!pickerOpen || options.isEmpty()) return;
        selectedOption = Math.max(0, Math.min(options.size() - 1, selectedOption + dir));
    }

    /**
     * Select the current picker option and navigate.
     */
    public void selectPickerOption() {
        if (!pickerOpen || selectedOption < 0 || selectedOption >= options.size()) return;
        NavOption option = options.get(selectedOption);
        closePicker();
        if (listener != null && !option.targets.isEmpty()) {
            listener.onNavigate(option.targets.get(0));
        }
    }

    /**
     * Navigate to a specific option by index.
     */
    public void navigateToOption(int index) {
        if (index < 0 || index >= options.size()) return;
        NavOption option = options.get(index);
        closePicker();
        if (listener != null && !option.targets.isEmpty()) {
            listener.onNavigate(option.targets.get(0));
        }
    }

    // ── Reset ─────────────────────────────────────────────────────

    public void reset() {
        options = new ArrayList<>();
        pickerOpen = false;
        selectedOption = 0;
    }
}
