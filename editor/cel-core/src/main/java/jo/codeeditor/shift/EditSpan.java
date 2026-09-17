package jo.codeeditor.shift;

/**
 * Représente une plage d'édition pour le décalage diagnostics/jetons.
 * Décrit une édition de texte : suppression de [start, start+removed)
 * et insertion de added caractères.
 */
public final class EditSpan {
    public final int start;
    public final int removed;
    public final int added;

    public EditSpan(int start, int removed, int added) {
        this.start = start;
        this.removed = removed;
        this.added = added;
    }

    /** Variation nette de la longueur du document. */
    public int delta() {
        return added - removed;
    }

    /** Fin de la plage supprimée. */
    public int end() {
        return start + removed;
    }

    @Override
    public String toString() {
        return "EditSpan(start=" + start + ", removed=" + removed + ", added=" + added + ")";
    }
}
