package jo.codeeditor.lsp;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Application d'éditions LSP à la session d'éditeur en UN SEUL groupe
 * d'annulation ({@link SessionEditGroup}).
 *
 * <p>L'ancien chemin reconstruisait le texte entier dans un
 * StringBuilder puis appelait {@code session.replaceRange(0,
 * doc.length(), newText)} : UNE étape d'undo géante qui écrasait caret,
 * sélection et plis, et coûtait O(n) par correctif rapide. Le groupe
 * applique chaque édition INDIVIDUELLEMENT (pipeline commun
 * {@code replaceRange}, qui décale plis/annotations/composition) entre
 * {@code beginBatch()}/{@code endBatch()} : un seul undo, plis et
 * sélection préservés.</p>
 */
class SessionEditGroupTest {

    private static EditorSession session(String text) {
        return new EditorSession(EditorDocument.of(text));
    }

    @Test
    void troisEditions_appliqueesEtUndoEnUnSeulPas() {
        EditorSession s = session("aaaa\nbbbb\ncccc\ndddd\n");
        List<SessionEditGroup.Edit> edits = new ArrayList<>();
        edits.add(new SessionEditGroup.Edit(0, 4, "AAAA"));   // ligne 0
        edits.add(new SessionEditGroup.Edit(5, 9, "BBBB"));   // ligne 1
        edits.add(new SessionEditGroup.Edit(15, 19, "DDDD")); // ligne 3
        assertTrue(SessionEditGroup.apply(s, edits));
        assertEquals("AAAA\nBBBB\ncccc\nDDDD\n", s.getText().toString());

        // UN SEUL undo restaure l'état exact d'avant.
        assertTrue(s.undo());
        assertEquals("aaaa\nbbbb\ncccc\ndddd\n", s.getText().toString());
        assertFalse(s.getUndoManager().canUndo(), "le groupe doit former exactement UNE étape");
    }

    @Test
    void editionsFourniesDansLeDesordre_appliqueesCorrectement() {
        EditorSession s = session("0123456789");
        List<SessionEditGroup.Edit> edits = new ArrayList<>();
        // désordre croissant volontaire
        edits.add(new SessionEditGroup.Edit(2, 4, "XY"));
        edits.add(new SessionEditGroup.Edit(0, 1, "A"));
        edits.add(new SessionEditGroup.Edit(7, 8, "Z"));
        assertTrue(SessionEditGroup.apply(s, edits));
        assertEquals("A1XY456Z89", s.getText().toString());
        assertTrue(s.undo());
        assertEquals("0123456789", s.getText().toString());
    }

    @Test
    void chevauchement_refuseSansAucuneModification() {
        EditorSession s = session("0123456789");
        List<SessionEditGroup.Edit> edits = new ArrayList<>();
        edits.add(new SessionEditGroup.Edit(0, 5, "X"));
        edits.add(new SessionEditGroup.Edit(3, 7, "Y"));
        assertFalse(SessionEditGroup.apply(s, edits));
        assertEquals("0123456789", s.getText().toString(), "rien ne doit bouger");
        assertFalse(s.getUndoManager().canUndo(), "aucune étape d'annulation ne doit être empilée");
    }

    @Test
    void plisHorsZoneModifiee_conserves() {
        // Le pli couvre la fin du document (lignes 4..9) ; les éditions
        // touchent les lignes 0..1 (AVANT le pli).
        EditorSession s = session("l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\n");
        jo.codeeditor.document.EditorDocument d = s.getDocument();
        int foldStart = d.lineStart(4);
        int foldEnd = d.lineEnd(9);
        java.util.List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
                new ArrayList<>();
        folds.add(new jo.codeeditor.shift.DiagnosticShift.FoldRegion(
                foldStart, foldEnd, "…", "block", true));
        s.setFoldRegions(folds);
        assertFalse(s.getCollapsedFolds().isEmpty());

        List<SessionEditGroup.Edit> edits = new ArrayList<>();
        edits.add(new SessionEditGroup.Edit(0, 2, "L0"));
        edits.add(new SessionEditGroup.Edit(3, 5, "L1"));
        assertTrue(SessionEditGroup.apply(s, edits));

        // Le pli survit à l'application (le pipeline commun décale les
        // plis au lieu de les détruire).
        assertFalse(s.getCollapsedFolds().isEmpty(),
                "le pli hors zone modifiée doit être conservé");
        // …et survit à l'annulation.
        assertTrue(s.undo());
        assertFalse(s.getCollapsedFolds().isEmpty());
    }

    @Test
    void selectionPreserveeApresApplication() {
        EditorSession s = session("abcdefghij");
        s.setSelection(7);
        List<SessionEditGroup.Edit> edits = new ArrayList<>();
        edits.add(new SessionEditGroup.Edit(0, 3, "XYZ"));
        assertTrue(SessionEditGroup.apply(s, edits));
        assertEquals(7, s.getSelection().start,
                "la sélection doit être restaurée (clampée) après le correctif");
    }

    @Test
    void vide_null_etListeNull_reussiteSansEffet() {
        EditorSession s = session("abc");
        assertTrue(SessionEditGroup.apply(s, null));
        assertTrue(SessionEditGroup.apply(s, new ArrayList<>()));
        assertEquals("abc", s.getText().toString());
        assertFalse(s.getUndoManager().canUndo());
    }
}
