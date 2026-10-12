package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undo/redo doit passer par le pipeline d'édition commun
 * ({@code doReplaceRange}) : décalage des diagnostics/plis/composition,
 * garde lecture-seule et notification des listeners d'édition (le pont
 * LSP s'y branche). L'ancien chemin mutait le document directement.
 */
class UndoPipelineTest {

    private static final String DOC = "int a = 1;\nint b = 2;\n";

    @Test
    void undoNotifiesTextEditListeners() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<String> calls = new ArrayList<>();
        s.addOnTextEditListener((start, end, inserted) -> calls.add(start + ":" + inserted));

        s.replaceRange(0, 0, "// ");
        assertTrue(s.undo());
        assertEquals("int a = 1;\nint b = 2;\n", s.getDocument().getText());
        // Le listener d'édition doit être notifié de l'annulation (le pont
        // LSP tire sinon sur une version périmée du document).
        assertFalse(calls.isEmpty(), "undo doit notifier les listeners d'édition (pont LSP)");
        // L'insertion inverse ("// " retiré) est notifiée à la position 0.
        assertEquals("0:", calls.get(calls.size() - 1));
    }

    @Test
    void undoRespectsReadOnly_andKeepsTheStackIntact() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        s.replaceRange(0, 0, "x");
        assertEquals("xint a = 1;\nint b = 2;\n", s.getDocument().getText());

        s.setReadOnly(true);
        // L'annulation ne doit RIEN modifier en lecture seule — et ne pas
        // consommer l'étape (elle reste disponible après déverrouillage).
        assertFalse(s.undo(), "undo en lecture seule doit échouer proprement");
        assertEquals("xint a = 1;\nint b = 2;\n", s.getDocument().getText());

        s.setReadOnly(false);
        assertTrue(s.undo());
        assertEquals(DOC, s.getDocument().getText());
    }

    @Test
    void undoShiftsDiagnosticsBack() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        // Un diagnostic sur la 2e ligne : plage [11, 22).
        s.setDiagnostics(new ArrayList<>(Collections.singletonList(
                new DiagnosticShift.Diagnostic(11, 22, 1, "b unused"))));

        // Insère 3 caractères au tout début : le diagnostic est décalé de +3.
        s.replaceRange(0, 0, "/**");
        assertEquals(14, s.getDiagnostics().get(0).start);

        // L'annulation doit décaler le diagnostic dans l'AUTRE sens (le
        // chemin direct ne décalait pas : le diagnostic restait à 14).
        assertTrue(s.undo());
        assertEquals(DOC, s.getDocument().getText());
        assertEquals(11, s.getDiagnostics().get(0).start,
                "le diagnostic doit revenir à sa position d'origine");
        assertEquals(22, s.getDiagnostics().get(0).end);
    }

    @Test
    void redoAppliesThroughPipelineToo() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<String> calls = new ArrayList<>();
        s.addOnTextEditListener((start, end, inserted) -> calls.add(inserted));

        s.replaceRange(0, 0, "x");
        assertTrue(s.undo());
        calls.clear();
        assertTrue(s.redo());
        assertEquals("xint a = 1;\nint b = 2;\n", s.getDocument().getText());
        assertFalse(calls.isEmpty(), "redo doit notifier les listeners d'édition");
        // Redo ne doit pas polluer la pile undo d'une NOUVELLE étape :
        // un undo immédiat doit revenir à l'état d'origine, pas sauter
        // une étape.
        assertTrue(s.undo());
        assertEquals(DOC, s.getDocument().getText());
    }
}
