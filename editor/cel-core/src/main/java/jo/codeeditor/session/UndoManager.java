package jo.codeeditor.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Undo/redo stack with coalescing support.
 * <p>
 * Consecutive single-character typing edits at adjacent positions
 * are merged into a single undo step for better UX.
 
 *
 * @since v1.0.0
*/
public final class UndoManager {

    private final List<UndoStep> undoStack = new ArrayList<>();
    private final List<UndoStep> redoStack = new ArrayList<>();
    private final int maxDepth;

    /** Tracks whether we're in a coalescible sequence. */
    private int lastEditEnd = -1;
    private long lastEditTime = 0;
    private static final long COALESCE_TIMEOUT_MS = 500;

    public UndoManager() {
        this(1000);
    }

    public UndoManager(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /**
     * Records an undo step. If it can be coalesced with the previous step, merges them.
     *
     * @param step the undo step to push
     */
    public void pushStep(UndoStep step) {
        undoStack.add(step);
        if (undoStack.size() > maxDepth) {
            undoStack.remove(0);
        }
        redoStack.clear();
        lastEditEnd = -1;
        lastEditTime = 0;
    }

    /**
     * Attempts to coalesce a single-char typing edit with the last undo step.
     *
     * @param edit the edit operation
     * @param cursorAfter cursor position after the edit
     * @return true if coalesced, false if a new step should be created
     */
    public boolean tryCoalesce(EditOp edit, int cursorAfter) {
        if (undoStack.isEmpty()) return false;

        UndoStep last = undoStack.get(undoStack.size() - 1);
        long now = System.currentTimeMillis();

        // Only coalesce single-char insertions at the expected position
        boolean canCoalesce =
            edit.inserted.length() == 1
            && edit.removed.isEmpty()
            && edit.start == lastEditEnd
            && (now - lastEditTime) < COALESCE_TIMEOUT_MS;

        if (canCoalesce) {
            // Merge: extend the last step's edits
            List<EditOp> merged = new ArrayList<>(last.edits);
            merged.add(edit);
            UndoStep newStep = new UndoStep(merged, last.selBefore, cursorAfter);
            undoStack.set(undoStack.size() - 1, newStep);
            lastEditEnd = cursorAfter;
            lastEditTime = now;
            return true;
        }

        return false;
    }

    /**
     * Pops the most recent undo step for undoing.
     * Returns null if the undo stack is empty.
     */
    public UndoStep undo() {
        if (undoStack.isEmpty()) return null;
        UndoStep step = undoStack.remove(undoStack.size() - 1);
        redoStack.add(step);
        lastEditEnd = -1;
        return step;
    }

    /**
     * Pops the most recent redo step for redoing.
     * Returns null if the redo stack is empty.
     */
    public UndoStep redo() {
        if (redoStack.isEmpty()) return null;
        UndoStep step = redoStack.remove(redoStack.size() - 1);
        undoStack.add(step);
        lastEditEnd = -1;
        return step;
    }

    /** Returns true if there are undo steps available. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Returns true if there are redo steps available. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Clears both stacks. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        lastEditEnd = -1;
        lastEditTime = 0;
    }

    public int undoDepth() {
        return undoStack.size();
    }

    public int redoDepth() {
        return redoStack.size();
    }
}
