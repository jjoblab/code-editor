package jo.codeeditor.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Étape annulable unitaire, pouvant regrouper plusieurs EditOps (en cas de
 * coalescence). Mémorise l'état de la sélection avant et après l'édition.
 *
 * <p><b>Copie défensive :</b> le constructeur copie la liste d'éditions
 * fournie afin que les mutations ultérieures de la liste de l'appelant
 * (p. ex. le vidage du tampon de lot dans {@link EditorSession#endBatch()})
 * n'affectent pas l'étape enregistrée. Une simple vue non modifiable
 * ({@code Collections.unmodifiableList(edits)}) ne suffirait pas : elle
 * resterait une <em>vue</em> en lecture seule de la liste de l'appelant —
 * vider celle-ci après coup effacerait les éditions enregistrées et
 * casserait l'annulation par lot.
 */
public final class UndoStep {
    public final List<EditOp> edits;
    public final int selBefore;
    public final int selAfter;

    public UndoStep(List<EditOp> edits, int selBefore, int selAfter) {
        // Copie défensive — voir la javadoc de la classe.
        this.edits = Collections.unmodifiableList(new ArrayList<>(edits));
        this.selBefore = selBefore;
        this.selAfter = selAfter;
    }

    /** Crée une étape à édition unique. */
    public static UndoStep single(EditOp edit, int selBefore, int selAfter) {
        return new UndoStep(Collections.singletonList(edit), selBefore, selAfter);
    }

    /**
     * Retourne l'étape inverse pour refaire (redo), avec sélections échangées.
     */
    public UndoStep inverse() {
        // Inverser les éditions et les appliquer en ordre inverse
        EditOp[] inv = new EditOp[edits.size()];
        for (int i = 0; i < edits.size(); i++) {
            inv[i] = edits.get(edits.size() - 1 - i).inverse();
        }
        List<EditOp> invList = new ArrayList<>();
        for (EditOp e : inv) invList.add(e);
        return new UndoStep(invList, selAfter, selBefore);
    }
}
