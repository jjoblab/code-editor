package jo.codeeditor.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the undo/redo manager with coalescing.
 */
class UndoManagerTest {

    @Test
    void pushAndUndo() {
        UndoManager mgr = new UndoManager();
        EditOp op = new EditOp(0, "", "hello");
        UndoStep step = UndoStep.single(op, 0, 5);
        mgr.pushStep(step);

        assertTrue(mgr.canUndo());
        UndoStep undone = mgr.undo();
        assertNotNull(undone);
        assertEquals(0, undone.selBefore);
        assertFalse(mgr.canUndo());
    }

    @Test
    void undoThenRedo() {
        UndoManager mgr = new UndoManager();
        EditOp op = new EditOp(0, "", "hello");
        mgr.pushStep(UndoStep.single(op, 0, 5));

        UndoStep undone = mgr.undo();
        assertNotNull(undone);
        assertTrue(mgr.canRedo());

        UndoStep redone = mgr.redo();
        assertNotNull(redone);
        assertTrue(mgr.canUndo());
    }

    @Test
    void undo_empty_returnsNull() {
        UndoManager mgr = new UndoManager();
        assertNull(mgr.undo());
    }

    @Test
    void redo_empty_returnsNull() {
        UndoManager mgr = new UndoManager();
        assertNull(mgr.redo());
    }

    @Test
    void newEditClearsRedoStack() {
        UndoManager mgr = new UndoManager();
        mgr.pushStep(UndoStep.single(new EditOp(0, "", "a"), 0, 1));
        mgr.undo();
        assertTrue(mgr.canRedo());

        // New edit should clear redo
        mgr.pushStep(UndoStep.single(new EditOp(0, "", "b"), 0, 1));
        assertFalse(mgr.canRedo());
    }

    @Test
    void maxDepth_trimsOldest() {
        UndoManager mgr = new UndoManager(3);
        for (int i = 0; i < 5; i++) {
            mgr.pushStep(UndoStep.single(new EditOp(i, "", String.valueOf(i)), i, i + 1));
        }
        assertEquals(3, mgr.undoDepth());
    }

    @Test
    void clear_resetsAll() {
        UndoManager mgr = new UndoManager();
        mgr.pushStep(UndoStep.single(new EditOp(0, "", "a"), 0, 1));
        mgr.undo();
        mgr.clear();
        assertFalse(mgr.canUndo());
        assertFalse(mgr.canRedo());
    }

    @Test
    void depth_counts() {
        UndoManager mgr = new UndoManager();
        assertEquals(0, mgr.undoDepth());
        assertEquals(0, mgr.redoDepth());

        mgr.pushStep(UndoStep.single(new EditOp(0, "", "a"), 0, 1));
        assertEquals(1, mgr.undoDepth());

        mgr.undo();
        assertEquals(0, mgr.undoDepth());
        assertEquals(1, mgr.redoDepth());
    }
}
