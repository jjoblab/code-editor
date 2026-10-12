package jo.codeeditor.lang;

/**
 * Résultat d'un appariement de crochets. Retourné par
 * {@link Analyzer#computeBracketMatch(int)}.
 */
public final class BracketMatch {

    /** L'offset du crochet sous le caret (inclus). */
    public final int bracketOffset;
    /** L'offset du crochet apparié (inclus). */
    public final int matchOffset;
    /** Vrai si le crochet sous le caret est un crochet ouvrant. */
    public final boolean isOpening;

    public BracketMatch(int bracketOffset, int matchOffset, boolean isOpening) {
        this.bracketOffset = bracketOffset;
        this.matchOffset = matchOffset;
        this.isOpening = isOpening;
    }
}
