package jo.codeeditor.completion;

import java.util.*;

/**
 * Cache de complétion + filtre côté client.
 * Stocke une liste de candidats classée par le backend et peut la
 * restreindre localement quand l'utilisateur continue à taper dans
 * le même token.
 * Reprend le design du {@code CompletionSession.kt} de CodeAssist.
 */
public class CompletionSession {

    /** Un élément de complétion. */
    public static final class Item {
        public final String label;
        public final String detail;
        public final String insertText;
        public final String icon;
        public final int kind; // LSP moderne : 1=Text, 2=Method, 3=Function, 4=Constructor, 5=Field, 6=Variable, 7=Class, 8=Interface, 9=Module, 10=Property, 11=Unit, 12=Value, 13=Enum, 14=Keyword, 15=Snippet, 16=Color, 17=File, 18=Reference, 19=Folder, 20=EnumMember, 21=Constant, 22=Struct, 23=Event, 24=Operator, 25=TypeParameter
        public final int sortScore; // plus élevé = meilleur
        public final boolean isKeyword;
        public final boolean isSnippet;
        /**
         * Raffinement du kind quand le protocole LSP seul ne suffit pas
         * à distinguer deux natures (ex: une ANNOTATION Java voyage en
         * CompletionItemKind.Class faute de kind dédié — le serveur
         * lspjava la tague alors {@code "annotation"} via le champ LSP
         * {@code data}). Valeurs connues : {@code "annotation"},
         * {@code "package"}, {@code "record"}. Null = pas de raffinement.
         */
        public final String kindTag;
        /**
         * Payload opaque attaché au candidat par le backend
         * (ex: Runnable d'auto-import LSP exécuté à l'acceptation).
         * Jamais interprété par la lib — l'hôte/le pont décide.
         */
        public final Object attachment;

        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet) {
            this(label, detail, insertText, icon, kind, sortScore, isKeyword, isSnippet, null);
        }

        /** Variante avec pièce jointe (auto-import, données de snippet…). */
        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet,
                    Object attachment) {
            this(label, detail, insertText, icon, kind, sortScore, isKeyword,
                    isSnippet, null, attachment);
        }

        /**
         * Variante complète avec raffinement de kind
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

    /** Offset où commence l'identifiant partiel. */
    public final int tokenStart;

    /** Liste complète de candidats classée par le backend. */
    public final List<Item> base;

    /** Indique si le client peut restreindre la liste sans ré-interroger le backend. */
    public final boolean canFilterLocally;

    /** Indique si la liste de base a été tronquée par le backend. */
    public final boolean isIncomplete;

    /** Paliers de classement pour la qualité de correspondance. */
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
     * Filtre les éléments par préfixe ou sous-séquence camel-hump, puis
     * re-classe. Les éléments de buffer/mots-clés sont classés sous les
     * éléments sémantiques.
     *
     * @param prefix le texte tapé par l'utilisateur jusqu'ici
     * @return liste filtrée et re-classée
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
     * Vérifie si l'élément correspond au préfixe (exact, préfixe, ou
     * sous-séquence camel-hump).
     */
    private boolean matchesFilter(String label, String prefix) {
        if (label == null || label.isEmpty()) return false;
        String lowerLabel = label.toLowerCase(java.util.Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);

        // Exact
        if (lowerLabel.equals(lowerPrefix)) return true;
        // Préfixe
        if (lowerLabel.startsWith(lowerPrefix)) return true;
        // Sous-séquence camel-hump
        return isCamelHumpSubsequence(label, prefix);
    }

    /**
     * Re-classe les éléments : les mots du buffer passent sous les
     * éléments sémantiques. Mots-clés et snippets en priorité plus basse.
     */
    private List<Item> reRanked(List<Item> items) {
        List<Item> result = new ArrayList<>(items);
        result.sort((a, b) -> {
            // Mots-clés/snippets sous les éléments sémantiques
            if (a.isKeyword != b.isKeyword) return a.isKeyword ? 1 : -1;
            if (a.isSnippet != b.isSnippet) return a.isSnippet ? 1 : -1;
            // sortScore le plus élevé d'abord
            int scoreCmp = Integer.compare(b.sortScore, a.sortScore);
            if (scoreCmp != 0) return scoreCmp;
            // Ordre alphabétique
            return a.label.compareToIgnoreCase(b.label);
        });
        return result;
    }

    /**
     * Indique si la session couvre encore la position du caret donnée.
     *
     * @param text   le texte complet du document
     * @param caret  offset courant du caret
     * @param extra  caractères supplémentaires tapés au-delà de tokenStart
     * @return true si cette session est encore valide
     */
    public boolean coversCaret(CharSequence text, int caret, int extra) {
        if (caret < tokenStart) return false;
        // Le token au caret doit toujours correspondre par préfixe à au moins un élément
        if (caret == tokenStart) return true;
        String typed = text.subSequence(tokenStart, caret).toString();
        if (typed.isEmpty()) return true;
        for (Item item : base) {
            if (matchesFilter(item.label, typed)) return true;
        }
        return false;
    }

    /**
     * Correspondance de sous-séquence camel-hump renvoyant les positions.
     * Pour "getString" avec le préfixe "gS", renvoie les positions [0, 3].
     *
     * @param label  le libellé de complétion
     * @param prefix le préfixe tapé
     * @return liste des positions de correspondance dans label, ou vide si aucune
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
                // Privilégie les correspondances majuscules (camel-hump)
                if (Character.isUpperCase(label.charAt(li)) || pi == 0 || positions.isEmpty()) {
                    positions.add(li);
                    pi++;
                } else {
                    // Vérifie si cela peut être une correspondance de sous-séquence valide
                    positions.add(li);
                    pi++;
                }
            }
        }

        return pi == prefix.length() ? positions : Collections.emptyList();
    }

    /**
     * Palier de classement de la correspondance.
     *
     * @param label  le libellé de complétion
     * @param prefix le préfixe tapé
     * @return constante de palier
     */
    public static int matchTier(String label, String prefix) {
        if (prefix == null || prefix.isEmpty()) return TIER_EXACT;
        if (label == null || label.isEmpty()) return TIER_NONE;

        String lowerLabel = label.toLowerCase(java.util.Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);

        if (lowerLabel.equals(lowerPrefix)) return TIER_EXACT;
        if (lowerLabel.startsWith(lowerPrefix)) return TIER_PREFIX;

        // Vérifie camel-hump : chaque caractère du préfixe correspond à une majuscule ou à un début de mot
        if (isCamelHumpSubsequence(label, prefix)) return TIER_CAMEL_HUMP;

        // Sous-séquence simple
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
     * Vérification de sous-séquence camel-hump : chaque caractère du
     * préfixe correspond soit à une majuscule du libellé, soit au début
     * d'un mot.
     */
    static boolean isCamelHumpSubsequence(String label, String prefix) {
        if (prefix.isEmpty()) return true;
        int pi = 0;
        boolean prevMatched = false;

        for (int li = 0; li < label.length() && pi < prefix.length(); li++) {
            char lc = label.charAt(li);
            char pc = prefix.charAt(pi);

            // Correspondance directe (insensible à la casse)
            if (Character.toLowerCase(lc) == Character.toLowerCase(pc)) {
                // Privilégie les correspondances en début de mot (majuscule ou après un underscore)
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
