package jo.codeeditor.document;

/**
 * Représente une sélection de texte avec offsets de début et de fin.
 * <p>
 * Quand start == end, la sélection est un curseur (caret) sans texte
 * sélectionné. Start est toujours &lt;= end (normalisé).
 */
public final class Selection {
    public final int start;
    public final int end;

    public Selection(int start, int end) {
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Negative offset: start=" + start + ", end=" + end);
        }
        this.start = Math.min(start, end);
        this.end = Math.max(start, end);
    }

    /** Crée un curseur (sans sélection) à l'offset donné. */
    public static Selection cursor(int offset) {
        return new Selection(offset, offset);
    }

    /** Crée une sélection couvrant [start, end). */
    public static Selection range(int start, int end) {
        return new Selection(start, end);
    }

    /** Renvoie true si c'est un curseur (aucun texte sélectionné). */
    public boolean isCursor() {
        return start == end;
    }

    /** Renvoie la longueur de la sélection. */
    public int length() {
        return end - start;
    }

    /** Renvoie une nouvelle sélection décalée du delta donné. */
    public Selection shift(int delta) {
        return new Selection(start + delta, end + delta);
    }

    /**
     * Ajuste cette sélection après un remplacement de texte.
     *
     * @param editStart  où l'édition a commencé
     * @param removedLen nombre de caractères supprimés
     * @param insertedLen nombre de caractères insérés
     * @return sélection ajustée
     */
    public Selection adjustForEdit(int editStart, int removedLen, int insertedLen) {
        int delta = insertedLen - removedLen;
        int newStart = adjustOffset(start, editStart, removedLen, insertedLen, delta);
        int newEnd = adjustOffset(end, editStart, removedLen, insertedLen, delta);
        return new Selection(newStart, newEnd);
    }

    private static int adjustOffset(int offset, int editStart, int removedLen, int insertedLen, int delta) {
        if (offset <= editStart) {
            return offset;
        }
        if (offset <= editStart + removedLen) {
            // À l'intérieur de la plage supprimée — replié sur le point d'édition
            return editStart + insertedLen;
        }
        return offset + delta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Selection)) return false;
        Selection s = (Selection) o;
        return start == s.start && end == s.end;
    }

    @Override
    public int hashCode() {
        return 31 * start + end;
    }

    @Override
    public String toString() {
        return "Selection(" + start + ", " + end + ")";
    }
}
