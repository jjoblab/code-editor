package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageRegistry;

/**
 * Façade d'édition intelligente sous forme de fonctions pures sur CharSequence.
 * Éditions intelligentes (paires, indentation, skip-over).
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

    /**
     * Lot 3 (audit v3.41.0) — vrai si la position {@code pos} se
     * trouve à l'intérieur d'un littéral de chaîne/char, d'un
     * commentaire de ligne ou d'un commentaire de bloc. Wrapper
     * public pour {@code CodeContext.isInStringOrComment} (qui est
     * package-private). Utilisé par la couche UI pour le context
     * gate de la complétion et de l'aide de signature.
     *
     * <p>Le prédicat est conscient des chaînes échappées et des
     * commentaires de bloc non terminés (renvoie true si la position
     * est dans une chaîne ouverte). Le coût est O(pos) par appel —
     * acceptable car les debounces limitent la fréquence.</p>
     */
    public static boolean isInStringOrComment(CharSequence text, int pos) {
        return CodeContext.isInStringOrComment(text, pos);
    }

    /**
     * ADR-002 (C1-é4) — variante profilée : les délimiteurs de
     * chaîne/commentaire viennent du {@code LanguageProfile} du
     * langage donné (résolu par le registre). Un commentaire
     * {@code #} Python, un bloc Lua {@code --[[ ]]}, une backtick
     * déclarée par un hôte sont désormais vus — l'ancien scanner ne
     * connaissait que les délimiteurs C. Un langage inconnu ou
     * {@code null} retombe sur les délimiteurs C historiques
     * (compatibilité des appelants existants).
     */
    public static boolean isInStringOrComment(CharSequence text, int pos, String language) {
        return CodeContext.isInStringOrComment(text, pos,
                language != null ? LanguageRegistry.forName(language) : null);
    }

    /**
     * Vue {@link CharSequence} paresseuse « préfixe + insertion »
     * (P-01, audit 3.69.0) : sert la simulation du document après
     * insertion SANS matérialiser le préfixe (l'ancien chemin copiait
     * O(pos) caractères par frappe multi-caractères). Les lecteurs
     * bornés — complétion postfix, aide à la frappe — y lisent via
     * {@code charAt} sur la source sous-jacente ; les tranches
     * demandées seules sont matérialisées.
     *
     * @param prefix  préfixe du document (immuable pendant la lecture,
     *                p. ex. la rope d'une révision)
     * @param inserted texte frappé, inséré juste après le préfixe
     * @return la vue concaténée paresseuse
     */
    public static CharSequence simulatedInsert(CharSequence prefix, String inserted) {
        return new PrefixedInsertView(prefix, inserted);
    }

    /**
     * Résultat immuable d'une complétion postfix (D-04) — retourné PAR
     * APPEL, sans aucun état partagé entre sessions ou threads.
     */
    public static final class PostfixResult {
        /** Début de la plage à remplacer. */
        public final int start;
        /** Fin de la plage à remplacer (exclusive). */
        public final int end;
        /** Position du caret après insertion. */
        public final int caret;
        /** Texte à insérer. */
        public final String insertText;

        PostfixResult(int start, int end, int caret, String insertText) {
            this.start = start;
            this.end = end;
            this.caret = caret;
            this.insertText = insertText;
        }
    }

    /**
     * Tente de déclencher une complétion postfix (D-04) — objet
     * résultat IMMUABLE retourné par appel : deux sessions (ou deux
     * threads) peuvent interroger simultanément sans qu'aucun état
     * global ne soit partagé.
     *
     * @param text le texte complet du document
     * @param pos la position du caret
     * @param language l'identifiant de langage
     * @return le résultat (plage + caret + texte à insérer), ou null si
     *         aucun postfix n'est détecté
     */
    public static PostfixResult tryPostfix(CharSequence text, int pos, String language) {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(text, pos, language);
        if (r == null) return null;
        return new PostfixResult(r.start, r.end, r.caret, r.insertText);
    }

    // ── Renommage lié des balises XML (4.5.0) ───────────────────

    /**
     * Les deux offsets d'insertion d'une frappe LIÉE dans le nom d'une
     * balise ouvrante XML — le caret (nom ouvrant) et la position
     * alignée du nom fermant apparié — ou null si le caret n'y est
     * pas. La session applique les deux insertions en un seul lot
     * d'annulation.
     *
     * @param text  le texte complet du document
     * @param caret le caret (fin de frappe, dans le nom ouvrant)
     * @param ch    le caractère frappé
     * @return {insertionOuvrante, insertionFermante}, ou null
     * @since 4.5.0
     */
    public static int[] tryXmlLinkedTypeChar(CharSequence text, int caret, char ch) {
        return XmlLinkedTagEdits.typeCharEdit(text, caret, ch);
    }

    /**
     * Les deux débuts de suppression d'un backspace LIÉ dans le nom
     * d'une balise ouvrante XML — le caractère avant le caret (nom
     * ouvrant) et le caractère aligné du nom fermant apparié — ou
     * null. La session applique les deux suppressions en un seul lot
     * d'annulation.
     *
     * @param text  le texte complet du document
     * @param caret le caret (le caractère à supprimer est avant)
     * @return {débutSuppressionOuvrante, débutSuppressionFermante}, ou null
     * @since 4.5.0
     */
    public static int[] tryXmlLinkedBackspace(CharSequence text, int caret) {
        return XmlLinkedTagEdits.backspaceEdit(text, caret);
    }


}
