package jo.codeeditor.completion;

import java.util.*;

/**
 * Cache de complétion + filtre côté client.
 * Stocke une liste de candidats classée par le backend et peut la
 * restreindre localement quand l'utilisateur continue à taper dans
 * le même token.
 * Session de complétion : filtrage local + ranking par genre.
 *
 * <p>★ 4.3.0 : l'appariement (paliers, camel humps, positions de
 * surbrillance) est unifié dans {@link CompletionMatcher} — les
 * méthodes de cette classe délègent ; seuls {@link #filtered} et son
 * classement par score historiques restent propres à la session (ils
 * ne servent plus en production, le classement du popup vit dans son
 * filtre local sur les paliers/groupes du matcher).</p>
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
         * Texte de filtrage (champ LSP {@code filterText}) — utilisé
         * quand le préfixe tapé n'apparie pas le label. Null = label.
         */
        public final String filterText;
        /**
         * Clé de tri secondaire (champ LSP {@code sortText}) — départage
         * les candidats de même score (ordre naturel croissant, null en
         * dernier). Null = départage par label.
         */
        public final String sortText;
        /**
         * Plage de remplacement optionnelle en offsets document (champ
         * LSP {@code textEdit}, sémantique remplacement — voir la
         * Javadoc de l'item SPI). {@code -1} = non spécifiée →
         * remplacement du token {@code [tokenStart, caret)}.
         */
        public final int replaceStart;
        /** Fin de plage de remplacement (exclusive) ; {@code -1} = non spécifiée. */
        public final int replaceEnd;
        /**
         * Payload opaque attaché au candidat par le backend
         * (ex: Runnable d'auto-import LSP exécuté à l'acceptation).
         * Jamais interprété par la lib — l'hôte/le pont décide.
         */
        public final Object attachment;
        /**
         * Origine du symbole (paquet d'un type, classe déclarante d'un
         * membre — alignée à droite dans le popup). Null = inconnue.
         *
         * @since 4.3.0
         */
        public final String container;
        /**
         * Documentation du candidat (texte brut ou Markdown transporté
         * tel quel ; son rendu est du ressort de l'hôte). Null = absente.
         *
         * @since 4.3.0
         */
        public final String documentation;

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
         * (glyphe + couleur par genre).
         */
        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet,
                    String kindTag, Object attachment) {
            this(label, detail, insertText, icon, kind, sortScore, isKeyword, isSnippet,
                    kindTag, null, null, -1, -1, attachment);
        }

        /**
         * Variante étendue (L1/D-01) : transport de {@code filterText},
         * {@code sortText} et de la plage de remplacement optionnelle
         * (sémantique remplacement — l'acceptation remplace
         * EXACTEMENT cette plage, jamais le token ET la plage).
         */
        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet,
                    String kindTag, String filterText, String sortText,
                    int replaceStart, int replaceEnd, Object attachment) {
            this(label, detail, insertText, icon, kind, sortScore, isKeyword, isSnippet,
                    kindTag, filterText, sortText, replaceStart, replaceEnd,
                    attachment, null, null);
        }

        /**
         * Variante riche (4.3.0) : ajoute l'origine du symbole
         * ({@code container}, alignée à droite dans le popup) et la
         * documentation. Les constructeurs existants délèguent avec
         * ces champs absents — rétrocompatibles.
         */
        public Item(String label, String detail, String insertText, String icon,
                    int kind, int sortScore, boolean isKeyword, boolean isSnippet,
                    String kindTag, String filterText, String sortText,
                    int replaceStart, int replaceEnd, Object attachment,
                    String container, String documentation) {
            this.label = label != null ? label : "";
            this.detail = detail != null ? detail : "";
            this.insertText = insertText != null ? insertText : label;
            this.icon = icon != null ? icon : "";
            this.kind = kind;
            this.sortScore = sortScore;
            this.isKeyword = isKeyword;
            this.isSnippet = isSnippet;
            this.kindTag = kindTag;
            this.filterText = filterText;
            this.sortText = sortText;
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
            this.attachment = attachment;
            this.container = container;
            this.documentation = documentation;
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

    public CompletionSession(int tokenStart, List<Item> base, boolean canFilterLocally, boolean isIncomplete) {
        this.tokenStart = tokenStart;
        this.base = base != null ? Collections.unmodifiableList(new ArrayList<>(base)) : Collections.emptyList();
        this.canFilterLocally = canFilterLocally;
        this.isIncomplete = isIncomplete;
    }

    /**
     * Vérifie si l'élément correspond au préfixe (exact, préfixe, ou
     * sous-séquence camel-hump). Le {@code filterText} (quand il est
     * posé) sert de repli : un candidat dont le label ne matche pas
     * mais dont le texte de filtrage matche reste visible (LSP 3.17 —
     * champ {@code filterText}).
     *
     * <p>★ 4.3.0 : délègue à {@link CompletionMatcher#matches} (échelle
     * à 6 niveaux, identifiant de tête, camel humps canoniques).</p>
     */
    private boolean matchesFilter(String label, String prefix) {
        return CompletionMatcher.tier(label, null, prefix) != CompletionMatcher.TIER_NONE;
    }

    /**
     * Appariement d'un item au préfixe tapé, label d'abord puis
     * {@code filterText} en repli.
     */
    private boolean matchesItem(Item item, String prefix) {
        if (matchesFilter(item.label, prefix)) return true;
        return item.filterText != null && !item.filterText.isEmpty()
                && matchesFilter(item.filterText, prefix);
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
            if (matchesItem(item, typed)) return true;
        }
        return false;
    }

    /**
     * Correspondance de sous-séquence camel-hump renvoyant les positions.
     * Pour "getString" avec le préfixe "gS", renvoie les positions [0, 3].
     *
     * <p>★ 4.3.0 : délègue à {@link CompletionMatcher#matchPositionsForItem}
     * — le texte de référence est l'identifiant de tête du libellé (la
     * signature ne participe jamais), les positions sont celles qui ont
     * fait matcher.</p>
     *
     * @param label  le libellé de complétion
     * @param prefix le préfixe tapé
     * @return liste des positions de correspondance dans label, ou vide si aucune
     */
    public static List<Integer> matchPositions(String label, String prefix) {
        return CompletionMatcher.matchPositionsForItem(label, null, prefix);
    }

    /**
     * Vérification de sous-séquence camel-hump : chaque caractère du
     * préfixe correspond soit à une majuscule du libellé, soit au début
     * d'un mot.
     *
     * <p>★ 4.3.0 : délègue à la définition canonique de
     * {@link CompletionMatcher#camelHumpSubsequence(String, String)}.</p>
     */
    static boolean isCamelHumpSubsequence(String label, String prefix) {
        return CompletionMatcher.camelHumpSubsequence(label, prefix);
    }

    @Override
    public String toString() {
        return "CompletionSession(tokenStart=" + tokenStart + ", items=" + base.size()
            + ", filter=" + canFilterLocally + ", incomplete=" + isIncomplete + ")";
    }
}
