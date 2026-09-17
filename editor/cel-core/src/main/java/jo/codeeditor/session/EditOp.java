package jo.codeeditor.session;

/**
 * Opération d'édition réversible unitaire.
 * Mémorise la plage qui a été remplacée et le texte qui l'a remplacée.
 */
public final class EditOp {
    public final int start;
    public final String removed;
    public final String inserted;

    public EditOp(int start, String removed, String inserted) {
        this.start = start;
        this.removed = removed != null ? removed : "";
        this.inserted = inserted != null ? inserted : "";
    }

    /** Retourne l'offset de fin de la plage supprimée. */
    public int removedEnd() {
        return start + removed.length();
    }

    /** Retourne l'opération inverse (échange removed ↔ inserted). */
    public EditOp inverse() {
        return new EditOp(start, inserted, removed);
    }

    @Override
    public String toString() {
        return "EditOp(start=" + start + ", removed=\"" + removed + "\", inserted=\"" + inserted + "\")";
    }
}
