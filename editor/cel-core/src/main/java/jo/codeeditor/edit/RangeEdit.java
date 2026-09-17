package jo.codeeditor.edit;

/**
 * Un remplacement : supprime [start, end) et insère un texte, avec le caret
 * résultant.
 */
public final class RangeEdit {
    public final int start;
    public final int end;
    public final String text;
    public final int caret;

    public RangeEdit(int start, int end, String text, int caret) {
        this.start = start;
        this.end = end;
        this.text = text != null ? text : "";
        this.caret = caret;
    }

    public int removedLength() {
        return end - start;
    }

    public int delta() {
        return text.length() - removedLength();
    }

    @Override
    public String toString() {
        return "RangeEdit([" + start + "," + end + ") -> \"" + text + "\", caret=" + caret + ")";
    }
}
