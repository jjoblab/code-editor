package jo.codeeditor.lang;


import jo.codeeditor.lang.model.CodeBlock;

import jo.codeeditor.highlight.StyledLine;

import java.util.List;

/**
 * Analyseur incrémental : produit les {@link StyledLine}s (coloration
 * syntaxique), les blocs de code (folding) et les appariements de crochets
 * à partir du texte du document.
 *
 * <p>L'analyseur travaille sur un thread de travail. Il reçoit les éditions
 * du texte via {@link #onReplace} / {@link #reset} et pousse les mises à
 * jour vers l'éditeur via le {@link StyleReceiver} installé par
 * {@link #setReceiver} (le receiver se charge de rebasculer sur le thread
 * UI pour invalider les lignes concernées).</p>
 *
 * <p>Les implémentations devraient utiliser l'<b>algorithme de convergence
 * d'état</b> : re-tokeniser à partir de la ligne éditée et s'arrêter dès que
 * l'état d'entrée de la nouvelle ligne rejoint l'état stocké. On obtient un
 * coût en O(lignes éditées) par édition au lieu de O(fichier entier).</p>
 */
public interface Analyzer {

    /**
     * Installe le receiver vers lequel l'analyseur pousse ses mises à jour
     * de style. Appelée une fois sur le thread UI à l'attachement de
     * l'analyseur.
     */
    void setReceiver(StyleReceiver receiver);

    /**
     * Appelée lorsque le texte du document change. L'analyseur doit
     * re-tokeniser les lignes affectées et pousser les mises à jour de
     * style au {@link StyleReceiver}.
     *
     * @param text      le texte complet du document
     * @param editStart l'offset où l'édition a commencé
     * @param editEnd   l'offset où l'édition s'est terminée (après l'édition)
     * @param inserted  le texte inséré (peut être vide)
     */
    void onReplace(CharSequence text, int editStart, int editEnd, CharSequence inserted);

    /**
     * Appelée au chargement ou à la réinitialisation du document.
     * L'analyseur doit re-tokeniser le document entier.
     *
     * @param text le texte complet du document
     */
    void reset(CharSequence text);

    /**
     * Retourne la {@link StyledLine} de la ligne demandée. Consultée sur le
     * thread UI pendant le rendu. Si l'analyseur n'a pas encore tokenisé
     * cette ligne, retourner {@code null} — l'éditeur retombe alors sur du
     * texte brut.
     *
     * @param line l'index de ligne (0-based)
     * @return la ligne stylée, ou {@code null} si pas encore disponible
     */
    StyledLine styledLine(int line);

    /**
     * Retourne les blocs de code (régions de folding) calculés par
     * l'analyseur. Consulté sur le thread UI, notamment après le callback
     * {@link StyleReceiver#onBlocksUpdated()}. Ne doit jamais retourner
     * {@code null} — retourner une liste vide s'il n'y a aucun bloc.
     */
    List<CodeBlock> computeBlocks();

    /**
     * Retourne l'appariement du crochet situé à l'offset donné, ou
     * {@code null} s'il n'y en a pas.
     *
     * @param offset l'offset du caret
     * @return l'appariement de crochets, ou {@code null}
     */
    BracketMatch computeBracketMatch(int offset);

    /**
     * Libère les ressources (threads, parseurs). Appelée sur le thread UI.
     */
    void destroy();
}
