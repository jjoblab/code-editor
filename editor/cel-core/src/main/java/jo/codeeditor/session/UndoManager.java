package jo.codeeditor.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Piles undo/redo avec prise en charge de la coalescence.
 * <p>
 * Les frappes consécutives d'un seul caractère à des positions adjacentes
 * sont fusionnées en une seule étape d'annulation pour une meilleure UX.
 */
public final class UndoManager {

    private final List<UndoStep> undoStack = new ArrayList<>();
    private final List<UndoStep> redoStack = new ArrayList<>();
    private final int maxDepth;

    /** Suit la séquence coalescible en cours. */
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
     * Enregistre une étape d'annulation ; le cas échéant, elle est fusionnée
     * avec l'étape précédente.
     *
     * @param step l'étape d'annulation à empiler
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
     * Tente de coalescer une frappe d'un seul caractère avec la dernière
     * étape d'annulation.
     *
     * @param edit l'opération d'édition
     * @param cursorAfter position du curseur après l'édition
     * @return vrai si coalescée, faux s'il faut créer une nouvelle étape
     */
    public boolean tryCoalesce(EditOp edit, int cursorAfter) {
        if (undoStack.isEmpty()) return false;

        UndoStep last = undoStack.get(undoStack.size() - 1);
        long now = System.currentTimeMillis();

        // Ne coalescer que les insertions d'un seul caractère à la position attendue
        boolean canCoalesce =
            edit.inserted.length() == 1
            && edit.removed.isEmpty()
            && edit.start == lastEditEnd
            && (now - lastEditTime) < COALESCE_TIMEOUT_MS;

        if (canCoalesce) {
            // Fusion : étendre les éditions de la dernière étape
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
     * Dépile la dernière étape d'annulation en vue de l'undo.
     * Retourne null si la pile d'annulation est vide.
     */
    public UndoStep undo() {
        if (undoStack.isEmpty()) return null;
        UndoStep step = undoStack.remove(undoStack.size() - 1);
        redoStack.add(step);
        lastEditEnd = -1;
        return step;
    }

    /**
     * Dépile la dernière étape de refaire en vue du redo.
     * Retourne null si la pile de refaire est vide.
     */
    public UndoStep redo() {
        if (redoStack.isEmpty()) return null;
        UndoStep step = redoStack.remove(redoStack.size() - 1);
        undoStack.add(step);
        lastEditEnd = -1;
        return step;
    }

    /** Retourne vrai s'il existe des étapes annulables. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Retourne vrai s'il existe des étapes à refaire. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Vide les deux piles. */
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
