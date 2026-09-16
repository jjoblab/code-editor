package jo.codeeditor.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single undoable step, potentially consisting of multiple EditOps
 * (when coalesced). Records the selection state before and after the edit.
 *
 * <p><b>Defensive copy:</b> the constructor copies the supplied edit list so
 * that subsequent mutations to the caller's list (e.g. clearing a batch
 * buffer in {@link EditorSession#endBatch()}) don't affect the recorded step.
 * Previously, {@code Collections.unmodifiableList(edits)} was used, which only
 * creates a read-only <em>view</em> of the caller's list — clearing the
 * caller's list afterwards wiped the recorded edits, breaking batch undo.
 
 *
 * @since v1.0.0
*/
public final class UndoStep {
    public final List<EditOp> edits;
    public final int selBefore;
    public final int selAfter;

    public UndoStep(List<EditOp> edits, int selBefore, int selAfter) {
        // Defensive copy — see class javadoc.
        this.edits = Collections.unmodifiableList(new ArrayList<>(edits));
        this.selBefore = selBefore;
        this.selAfter = selAfter;
    }

    /** Creates a single-edit step. */
    public static UndoStep single(EditOp edit, int selBefore, int selAfter) {
        return new UndoStep(Collections.singletonList(edit), selBefore, selAfter);
    }

    /**
     * Returns the inverse step for redoing, with swapped selection.
     */
    public UndoStep inverse() {
        // Reverse the edits and apply them in reverse order
        EditOp[] inv = new EditOp[edits.size()];
        for (int i = 0; i < edits.size(); i++) {
            inv[i] = edits.get(edits.size() - 1 - i).inverse();
        }
        List<EditOp> invList = new ArrayList<>();
        for (EditOp e : inv) invList.add(e);
        return new UndoStep(invList, selAfter, selBefore);
    }
}
