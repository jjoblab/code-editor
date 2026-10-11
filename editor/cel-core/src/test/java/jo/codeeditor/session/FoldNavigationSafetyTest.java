package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot A.4 (audit v3.41.0) — navigation clavier autour des plis
 * repliés. Le caret ne doit jamais s'arrêter sur une position
 * invisible sans se déplier.
 *
 * <p>Avant le Lot A.4, les déplacements purs de caret
 * (moveHorizontal, moveVertical, moveLineStart, moveLineEnd,
 * moveDocBoundary) ne dépliaient pas les plis repliés — le caret
 * pouvait se retrouver à l'intérieur strict d'un pli (invisible)
 * après une navigation clavier. Le Lot A.2 couvrait les mutations
 * via {@code replaceRangeWithCaret}, mais pas les déplacements
 * purs.</p>
 *
 * <p>Le correctif : {@link EditorSession#setSelection(Selection)}
 * déplie automatiquement le pli si le caret (start ou end de la
 * sélection) se retrouve à l'intérieur strict d'un pli replié.
 * Ainsi, tous les chemins de déplacement (qui passent par
 * setSelection) sont couverts en un seul endroit.</p>
 */
class FoldNavigationSafetyTest {

    private static final String JSON_DOC =
            "{\n"                                              // 0
          + "  \"parameters\": {\n"                          // 1 ← ligne de début du pli
          + "    \"a\": 1,\n"                                 // 2 (masquée si pli replié)
          + "    \"b\": 2,\n"                                 // 3 (masquée)
          + "    \"c\": 3,\n"                                 // 4 (masquée)
          + "    \"d\": 4,\n"                                 // 5 (masquée)
          + "  },\n"                                         // 6 ← ligne de fin du pli
          + "  \"name\": \"x\"\n"                             // 7
          + "}\n";                                            // 8

    private EditorSession newSessionWithFold() {
        EditorSession session = new EditorSession(EditorDocument.of(JSON_DOC));
        EditorDocument d = session.getDocument();
        int foldStart = d.lineStart(1) + "  \"parameters\": {".length();
        int foldEnd = d.lineStart(6) + 2;
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(foldStart, foldEnd,
                "{…}", "block", true));
        session.setFoldRegions(folds);
        return session;
    }

    /**
     * Cas 1 : moveRight depuis le début du document, on finit par
     * entrer dans le pli. Le pli doit être déplié quand le caret
     * arrive dans la zone masquée.
     */
    @Test
    void moveRight_intoFold_expandsFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);

        // Caret au début du document.
        session.setSelection(Selection.cursor(0));
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "Précondition : pli replié");

        // Déplace le caret vers la droite jusqu'à entrer dans le
        // pli. On utilise setSelection directement pour simuler
        // un moveRight qui atterrit dans le pli.
        int insideFoldOffset = fold.start + 5; // à l'intérieur strict du pli
        session.setSelection(Selection.cursor(insideFoldOffset));

        // Le pli doit avoir été déplié (le caret ne doit pas être
        // invisible).
        assertFalse(session.getFoldRegions().get(0).collapsed,
                "setSelection à l'intérieur d'un pli doit le déplier — "
                        + "le caret ne doit pas être invisible");
    }

    /**
     * Cas 2 : moveLeft depuis la fin du document, on finit par
     * entrer dans le pli. Le pli doit être déplié.
     */
    @Test
    void moveLeft_intoFold_expandsFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);
        EditorDocument d = session.getDocument();

        // Caret à la fin du document.
        session.setSelection(Selection.cursor(d.length()));
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "Précondition : pli replié");

        // Déplace le caret vers la gauche jusqu'à entrer dans le
        // pli. On simule un moveLeft qui atterrit dans le pli.
        int insideFoldOffset = fold.end - 1; // à l'intérieur strict du pli
        session.setSelection(Selection.cursor(insideFoldOffset));

        assertFalse(session.getFoldRegions().get(0).collapsed,
                "setSelection à l'intérieur d'un pli doit le déplier");
    }

    /**
     * Cas 3 : moveVertical (haut/bas) peut atterrir dans la zone
     * masquée d'un pli. Le pli doit être déplié.
     */
    @Test
    void moveVertical_intoFold_expandsFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);
        EditorDocument d = session.getDocument();

        // Caret sur la ligne 7 (après le pli).
        session.setSelection(Selection.cursor(d.lineStart(7)));
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "Précondition : pli replié");

        // Simule un moveVertical(-3) qui atterrit sur la ligne 4
        // (masquée par le pli).
        int maskedLine = 4;
        int maskedOffset = d.lineStart(maskedLine) + 2; // à l'intérieur de la ligne masquée
        session.setSelection(Selection.cursor(maskedOffset));

        assertFalse(session.getFoldRegions().get(0).collapsed,
                "setSelection sur une ligne masquée par un pli doit le déplier");
    }

    /**
     * Cas 4 : setSelection à l'extérieur du pli ne doit PAS le
     * déplier. Garde-fou pour éviter le dépliage intempestif.
     */
    @Test
    void setSelection_outsideFold_doesNotExpandFold() {
        EditorSession session = newSessionWithFold();

        // Caret sur la ligne 0 (avant le pli).
        session.setSelection(Selection.cursor(0));
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "setSelection hors du pli ne doit pas le déplier");

        // Caret sur la ligne 7 (après le pli).
        EditorDocument d = session.getDocument();
        session.setSelection(Selection.cursor(d.lineStart(7)));
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "setSelection hors du pli ne doit pas le déplier");
    }

    /**
     * Cas 5 : setSelection à r.start (juste avant la chip) ou à
     * r.end (juste après la chip, sur le fermant du suffixe) ne
     * déplie PAS le pli — ce sont des positions visibles valides.
     */
    @Test
    void setSelection_atFoldBounds_doesNotExpandFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);

        // Caret à fold.start (juste avant la chip, position visible).
        session.setSelection(Selection.cursor(fold.start));
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "setSelection à fold.start (position visible) ne doit pas déplier");

        // Caret à fold.end (juste après la chip, position visible
        // car le fermant du suffixe est affiché).
        session.setSelection(Selection.cursor(fold.end));
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "setSelection à fold.end (position visible) ne doit pas déplier");
    }

    /**
     * Cas 6 : moveHorizontal réel (pas via setSelection directe)
     * — simule une navigation clavier. Le caret se déplace
     * caractère par caractère ; quand il entre dans le pli, le
     * pli doit être déplié.
     */
    @Test
    void moveHorizontal_stepByStep_intoFold_expandsFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);

        // Place le caret juste avant le pli (à fold.start).
        session.setSelection(Selection.cursor(fold.start));

        // Un seul moveRight : caret à fold.start + 1 (à l'intérieur
        // strict du pli — la chip affiche {...} mais la position
        // logique est masquée).
        session.moveHorizontal(1, false);

        // Le pli doit avoir été déplié (le caret est à l'intérieur
        // strict, position invisible).
        assertFalse(session.getFoldRegions().get(0).collapsed,
                "moveHorizontal dans le pli doit le déplier — "
                        + "le caret ne doit pas s'arrêter sur une position invisible");
    }

    /**
     * Cas 7 : moveLineEnd depuis une ligne avant le pli peut
     * atterrir à la fin de la ligne de début du pli (juste avant
     // la chip). C'est une position visible — pas de dépliage.
     * Mais moveLineStart depuis la fin du pli peut atterrir dans
     * la zone masquée. Test du moveLineStart vers la zone masquée.
     */
    @Test
    void moveLineStart_intoFold_expandsFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);
        EditorDocument d = session.getDocument();

        // Caret à la fin de la ligne 6 (ligne de fin du pli).
        session.setSelection(Selection.cursor(d.lineEnd(6)));

        // moveLineStart depuis la fin de la ligne 6 : doit aller au
        // début de la ligne 6, qui est visible (suffixe composite).
        // Pas de dépliage attendu.
        session.moveLineStart(false);
        assertTrue(session.getFoldRegions().get(0).collapsed,
                "moveLineStart sur la ligne de fin du pli (position visible) "
                        + "ne doit pas déplier");

        // Maintenant, simule un moveLineStart qui atterrit dans la
        // zone masquée (ligne 3, par exemple via moveVertical puis
        // moveLineStart).
        int maskedLine = 3;
        int maskedOffset = d.lineStart(maskedLine) + 2;
        session.setSelection(Selection.cursor(maskedOffset));
        assertFalse(session.getFoldRegions().get(0).collapsed,
                "setSelection sur une ligne masquée doit déplier le pli");
    }
}
