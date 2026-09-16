package jo.codeeditor.completion;

import java.util.*;

/**
 * Client-side completion cache + filter.
 * Stores a ranked candidate list from the backend and can narrow it locally
 * when the user continues typing within the same token.
 * Ported from CodeAssist CompletionSession.kt.
 
 *
 * @since v1.0.5
*/
public class CompletionSession {

    /** A single completion item. */
    public static final class Item {
        public final String label;
        public final String detail;
        public final String insertText;
        public final String icon;
        public final int kind; // LSP moderne : 1=Text, 2=Method, 3=Function, 4=Constructor, 5=Field, 6=Variable, 7=Class, 8=Interface, 9=Module, 10=Property, 11=Unit, 12=Value, 13=Enum, 14=Keyword, 15=Snippet, 16=Color, 17=File, 18=Reference, 19=Folder, 20=EnumMember, 21=Constant, 22=Struct, 23=Event, 24=Operator, 25=TypeParameter
        public final int sortScore; // higher = better
        public final boolean isKeyword;
        public final boolean isSnippet;
        /**
         * ★ v2.30 : raffinement du kind quand le protocole LSP seul ne
         * suffit pas à distinguer deux natures (ex: une ANNOTATION Java
         * voyage en CompletionItemKind.Class faute de kind dédié — le
         * serveur lspjava la tague alors {@code "annotation"} via le champ
         * LSP {@code data}). Valeurs connues : {@code "annotation"},
         * {@code "package"}, {@code "record"}. Null = pas de raffinement.
         */
        public final String kindTag;
        /**
         * v0.1.0.50: Payload opaque attaché au candidat par le backend
         * (ex: Runnable d'auto-import LSP exécuté à l'acceptation).
         * Jamais interprété par la lib — l'hôte/le pont décide.
         */
        public final Object attachment;

        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet) {
            this(label, detail, insertText, icon, kind, sortScore, isKeyword, isSnippet, null);
        }

        /** v0.1.0.50: variante avec pièce jointe (auto-import, snippet data…). */
        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet,
                    Object attachment) {
            this(label, detail, insertText, icon, kind, sortScore, isKeyword,
                    isSnippet, null, attachment);
        }

        /**
         * ★ v2.30 : variante complète avec raffinement de kind
         * ({@link #kindTag}) — badge de type précis dans le popup
         * (portage du KindBadge de CodeAssist : glyphe + couleur par kind).
         */
        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet,
                    String kindTag, Object attachment) {
            this.label = label != null ? label : "";
            this.detail = detail != null ? detail : "";
            this.insertText = insertText != null ? insertText : label;
            this.icon = icon != null ? icon : "";
            this.kind = kind;
            this.sortScore = sortScore;
            this.isKeyword = isKeyword;
            this.isSnippet = isSnippet;
            this.kindTag = kindTag;
            this.attachment = attachment;
        }

        public Item(String label) {
            this(label, "", label, "", 1, 0, false, false);
        }

        @Override
        public String toString() {
            return "Item(\"" + label + "\", kind=" + kind + ", score=" + sortScore + ")";
        }
    }

    /** Offset where the partial identifier begins. */
    public final int tokenStart;

    /** Full ranked candidate list from backend. */
    public final List<Item> base;

    /** Whether the client can narrow the list without re-querying. */
    public final boolean canFilterLocally;

    /** Whether the base list was truncated by the backend. */
    public final boolean isIncomplete;

    /** Ranking tiers for match quality. */
    public static final int TIER_EXACT = 0;
    public static final int TIER_PREFIX = 1;
    public static final int TIER_CAMEL_HUMP = 2;
    public static final int TIER_SUBSEQUENCE = 3;
    public static final int TIER_NONE = 4;

    public CompletionSession(int tokenStart, List<Item> base, boolean canFilterLocally, boolean isIncomplete) {
        this.tokenStart = tokenStart;
        this.base = base != null ? Collections.unmodifiableList(new ArrayList<>(base)) : Collections.emptyList();
        this.canFilterLocally = canFilterLocally;
        this.isIncomplete = isIncomplete;
    }

    /**
     * Filter items by prefix or camel-hump subsequence, then re-rank.
     * Buffer/keyword items are ranked below semantic items.
     *
     * @param prefix the text the user has typed so far
     * @return filtered and re-ranked list
     */
    public List<Item> filtered(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return reRanked(base);
        }

        List<Item> matched = new ArrayList<>();
        for (Item item : base) {
            if (matchesFilter(item.label, prefix)) {
                matched.add(item);
            }
        }
        return reRanked(matched);
    }

    /**
     * Check if the item matches the prefix (exact, prefix, or camel-hump subsequence).
     */
    private boolean matchesFilter(String label, String prefix) {
        if (label == null || label.isEmpty()) return false;
        String lowerLabel = label.toLowerCase(java.util.Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);

        // Exact
        if (lowerLabel.equals(lowerPrefix)) return true;
        // Prefix
        if (lowerLabel.startsWith(lowerPrefix)) return true;
        // Camel-hump subsequence
        return isCamelHumpSubsequence(label, prefix);
    }

    /**
     * Re-rank items: buffer words ranked below semantic items.
     * Keywords and snippets get lower priority.
     */
    private List<Item> reRanked(List<Item> items) {
        List<Item> result = new ArrayList<>(items);
        result.sort((a, b) -> {
            // Keywords/snippets below semantic items
            if (a.isKeyword != b.isKeyword) return a.isKeyword ? 1 : -1;
            if (a.isSnippet != b.isSnippet) return a.isSnippet ? 1 : -1;
            // Higher sortScore first
            int scoreCmp = Integer.compare(b.sortScore, a.sortScore);
            if (scoreCmp != 0) return scoreCmp;
            // Alphabetical
            return a.label.compareToIgnoreCase(b.label);
        });
        return result;
    }

    /**
     * Whether the session still covers the given caret position.
     *
     * @param text   the full document text
     * @param caret  current caret offset
     * @param extra  extra characters typed beyond tokenStart
     * @return true if this session is still valid
     */
    public boolean coversCaret(CharSequence text, int caret, int extra) {
        if (caret < tokenStart) return false;
        // The token at caret must still be a prefix match for at least one item
        if (caret == tokenStart) return true;
        String typed = text.subSequence(tokenStart, caret).toString();
        if (typed.isEmpty()) return true;
        for (Item item : base) {
            if (matchesFilter(item.label, typed)) return true;
        }
        return false;
    }

    /**
     * Camel-hump subsequence matching returning match positions.
     * For "getString" with prefix "gS", returns positions [0, 3].
     *
     * @param label  the completion label
     * @param prefix the typed prefix
     * @return list of match positions in label, or empty if no match
     */
    public static List<Integer> matchPositions(String label, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < label.length(); i++) result.add(i);
            return result;
        }
        if (label == null || label.isEmpty()) return Collections.emptyList();

        String lowerLabel = label.toLowerCase(java.util.Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);

        List<Integer> positions = new ArrayList<>();
        int pi = 0;

        for (int li = 0; li < label.length() && pi < prefix.length(); li++) {
            char lc = lowerLabel.charAt(li);
            char pc = lowerPrefix.charAt(pi);

            if (lc == pc) {
                // Prefer uppercase (camel-hump) matches
                if (Character.isUpperCase(label.charAt(li)) || pi == 0 || positions.isEmpty()) {
                    positions.add(li);
                    pi++;
                } else {
                    // Check if this could be a valid subsequence match
                    positions.add(li);
                    pi++;
                }
            }
        }

        return pi == prefix.length() ? positions : Collections.emptyList();
    }

    /**
     * Ranking tier for the match.
     *
     * @param label  the completion label
     * @param prefix the typed prefix
     * @return tier constant
     */
    public static int matchTier(String label, String prefix) {
        if (prefix == null || prefix.isEmpty()) return TIER_EXACT;
        if (label == null || label.isEmpty()) return TIER_NONE;

        String lowerLabel = label.toLowerCase(java.util.Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);

        if (lowerLabel.equals(lowerPrefix)) return TIER_EXACT;
        if (lowerLabel.startsWith(lowerPrefix)) return TIER_PREFIX;

        // Check camel-hump: each prefix char matches an uppercase letter or word start
        if (isCamelHumpSubsequence(label, prefix)) return TIER_CAMEL_HUMP;

        // Plain subsequence
        int pi = 0;
        for (int li = 0; li < label.length() && pi < prefix.length(); li++) {
            if (Character.toLowerCase(label.charAt(li)) == Character.toLowerCase(prefix.charAt(pi))) {
                pi++;
            }
        }
        if (pi == prefix.length()) return TIER_SUBSEQUENCE;

        return TIER_NONE;
    }

    /**
     * Camel-hump subsequence check: each prefix char matches either
     * an uppercase letter in the label or the start of a word boundary.
     */
    static boolean isCamelHumpSubsequence(String label, String prefix) {
        if (prefix.isEmpty()) return true;
        int pi = 0;
        boolean prevMatched = false;

        for (int li = 0; li < label.length() && pi < prefix.length(); li++) {
            char lc = label.charAt(li);
            char pc = prefix.charAt(pi);

            // Direct match (case-insensitive)
            if (Character.toLowerCase(lc) == Character.toLowerCase(pc)) {
                // Prefer word-boundary matches (uppercase or after underscore)
                boolean isWordStart = Character.isUpperCase(lc) || (li > 0 && label.charAt(li - 1) == '_');
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

    @Override
    public String toString() {
        return "CompletionSession(tokenStart=" + tokenStart + ", items=" + base.size()
            + ", filter=" + canFilterLocally + ", incomplete=" + isIncomplete + ")";
    }
}
