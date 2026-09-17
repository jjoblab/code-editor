package jo.codeeditor.lang.model;

/**
 * Une édition de texte élémentaire (remplace [start, end) par
 * {@code newText}). Utilisée par {@link RenameResult} et les autres
 * opérations productrices d'éditions.
 */
public final class TextEdit {

    /** Offset de début (inclus). */
    public final int start;
    /** Offset de fin (exclu). */
    public final int end;
    /** Le texte à insérer. */
    public final String newText;

    public TextEdit(int start, int end, String newText) {
        this.start = start;
        this.end = end;
        this.newText = newText != null ? newText : "";
    }
}
