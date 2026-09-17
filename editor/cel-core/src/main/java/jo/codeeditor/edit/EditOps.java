package jo.codeeditor.edit;

/**
 * Façade d'édition intelligente sous forme de fonctions pures sur CharSequence.
 * Portée depuis EditOps.kt de CodeAssist.
 * <p>
 * Toutes les méthodes sont sans état ; elles prennent le texte + les infos
 * de curseur et retournent un RangeEdit. L'implémentation est déléguée à des
 * collaborateurs du package : {@link SmartTyping} (frappe : insertion, backspace,
 * delete), {@link SmartNewline} et {@link XmlNewline} (sauts de ligne),
 * {@link WordBounds} (navigation mot-par-mot), {@link BracketPairs} (paires),
 * {@link IndentDetection} (unité d'indentation), le tout appuyé par
 * {@link EditTextUtils}, {@link LanguageIds} et {@link CodeContext}.
 */
public final class EditOps {

    private EditOps() {}

    // ── Paires crochets / guillemets (délégation à BracketPairs) ──

    public static char closerFor(char opener) {
        return BracketPairs.closerFor(opener);
    }

    public static char openerFor(char closer) {
        return BracketPairs.openerFor(closer);
    }

    public static boolean isOpener(char ch) {
        return BracketPairs.isOpener(ch);
    }

    public static boolean isCloser(char ch) {
        return BracketPairs.isCloser(ch);
    }

    public static boolean isPair(char open, char close) {
        return BracketPairs.isPair(open, close);
    }

    // ── Saisie intelligente (délégation à SmartTyping) ──────────

    /**
     * Insertion intelligente de caractère avec skip-over, auto-close, désindentation.
     *
     * @param text     texte complet du document
     * @param selStart début de la sélection
     * @param selEnd   fin de la sélection
     * @param ch       caractère frappé
     * @param language identifiant de langage
     * @return RangeEdit décrivant l'édition
     */
    public static RangeEdit smartInsert(CharSequence text, int selStart, int selEnd, char ch, String language) {
        return SmartTyping.smartInsert(text, selStart, selEnd, ch, language);
    }

    /**
     * Backspace intelligent avec suppression de paire vide, collapse de lignes vides, indentation intelligente.
     */
    public static RangeEdit smartBackspace(CharSequence text, int selStart, int selEnd, String language) {
        return SmartTyping.smartBackspace(text, selStart, selEnd, language);
    }

    /**
     * Suppression avant intelligente : sensible aux paires.
     */
    public static RangeEdit smartDeleteForward(CharSequence text, int selStart, int selEnd, String language) {
        return SmartTyping.smartDeleteForward(text, selStart, selEnd, language);
    }

    // ── smartEnter / NewlineHandler (délégation à SmartNewline) ──────────────────────

    /**
     * Smart Enter : poursuite de l'indentation, plus profonde après les ouvreurs, expansion de crochets, etc.
     */
    public static RangeEdit smartEnter(CharSequence text, int pos, String language) {
        return SmartNewline.smartEnter(text, pos, language);
    }

    // ── smartEnter (complétion d'instruction) ──────────────────

    /**
     * Complétion d'instruction : terminer la ligne par ; si besoin, ajouter un bloc { } pour le contrôle de flux.
     */
    public static RangeEdit smartEnterComplete(CharSequence text, int pos, String language) {
        return SmartNewline.smartEnterComplete(text, pos, language);
    }

    // ── Bornes de mots (délégation à WordBounds) ────────────────

    /**
     * Trouve la borne de mot à gauche de pos.
     */
    public static int wordBoundaryLeft(CharSequence text, int pos) {
        return WordBounds.wordBoundaryLeft(text, pos);
    }

    /**
     * Trouve la borne de mot à droite de pos.
     */
    public static int wordBoundaryRight(CharSequence text, int pos) {
        return WordBounds.wordBoundaryRight(text, pos);
    }

    /**
     * Trouve la plage du mot à la position donnée.
     */
    public static int[] wordRangeAt(CharSequence text, int pos) {
        return WordBounds.wordRangeAt(text, pos);
    }

    // ── Détection de l'unité d'indentation (délégation à IndentDetection) ──

    /**
     * Détecte l'unité d'indentation : "\t", "  ", "    " ou "        " (tabulation contre 2/4/8 espaces).
     */
    public static String detectIndentUnit(CharSequence text) {
        return IndentDetection.detectIndentUnit(text);
    }

    /**
     * Retourne la taille de tabulation détectée (nombre d'espaces) pour le backspace.
     */
    public static int detectTabSize(CharSequence text) {
        return IndentDetection.detectTabSize(text);
    }
}
