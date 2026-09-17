package jo.codeeditor.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Enregistrement des étapes d'annulation d'une session : détient la pile
 * UndoManager, la coalescence des frappes simples et le tampon d'édition
 * par lots. Existe pour isoler de {@link EditorSession} cette comptabilité
 * d'annulation sans en changer le comportement ; la session délègue
 * begin/endBatch, isInBatch et l'enregistrement de chaque édition.
 */
final class UndoRecorder {

    private final UndoManager undoManager;

    private int batchDepth = 0;
    private List<EditOp> batchEdits = new ArrayList<>();
    private int batchSelBefore = -1;

    UndoRecorder(int maxUndoSteps) {
        this.undoManager = new UndoManager(maxUndoSteps);
    }

    /** La pile d'annulation sous-jacente. */
    UndoManager manager() { return undoManager; }

    /**
     * Commence une édition par lots. Les éditions multiples d'un lot sont
     * regroupées en une seule étape d'annulation.
     */
    void begin(int selStart) {
        if (batchDepth == 0) {
            batchEdits.clear();
            batchSelBefore = selStart;
        }
        batchDepth++;
    }

    /**
     * Termine une édition par lots. Si cela referme le lot le plus externe,
     * les éditions groupées sont poussées comme une seule étape d'annulation.
     */
    void end(int selEnd) {
        batchDepth--;
        if (batchDepth <= 0) {
            batchDepth = 0;
            if (!batchEdits.isEmpty()) {
                UndoStep step = new UndoStep(batchEdits, batchSelBefore, selEnd);
                undoManager.pushStep(step);
                batchEdits.clear();
            }
        }
    }

    /** Vrai si une édition par lots est ouverte. */
    boolean inBatch() { return batchDepth > 0; }

    /**
     * Enregistre une édition : ajoutée au tampon de lot ouvert, sinon
     * coalescée avec l'étape précédente (frappe d'un seul caractère) ou
     * poussée comme nouvelle étape.
     */
    void record(EditOp op, int selBefore, int caretAfter) {
        if (inBatch()) {
            batchEdits.add(op);
        } else if (!undoManager.tryCoalesce(op, caretAfter)) {
            UndoStep step = UndoStep.single(op, selBefore, caretAfter);
            undoManager.pushStep(step);
        }
    }
}
