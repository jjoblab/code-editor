package jo.codeeditor.shift;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticShiftTest {

    @Test
    void diffEdit_insert() {
        EditSpan s = DiagnosticShift.diffEdit("hello", "hello world");
        assertEquals(5, s.start);
        assertEquals(0, s.removed);
        assertEquals(6, s.added);
        assertEquals(6, s.delta());
    }

    @Test
    void diffEdit_delete() {
        EditSpan s = DiagnosticShift.diffEdit("hello world", "hello");
        assertEquals(5, s.start);
        assertEquals(6, s.removed);
        assertEquals(0, s.added);
        assertEquals(-6, s.delta());
    }

    @Test
    void diffEdit_replace() {
        EditSpan s = DiagnosticShift.diffEdit("hello world", "hello there");
        assertEquals(6, s.start);
        assertEquals(5, s.removed);
        assertEquals(5, s.added);
        assertEquals(0, s.delta());
    }

    @Test
    void diffEdit_noChange() {
        EditSpan s = DiagnosticShift.diffEdit("hello", "hello");
        assertEquals(0, s.delta());
        assertEquals(0, s.removed);
        assertEquals(0, s.added);
    }

    @Test
    void mapStart_beforeEdit() {
        EditSpan s = new EditSpan(5, 0, 3);
        assertEquals(3, DiagnosticShift.mapStart(3, s));
    }

    @Test
    void mapStart_afterEdit() {
        EditSpan s = new EditSpan(5, 0, 3);
        assertEquals(9, DiagnosticShift.mapStart(6, s));
    }

    @Test
    void mapEnd_beforeEdit() {
        EditSpan s = new EditSpan(5, 0, 3);
        assertEquals(3, DiagnosticShift.mapEnd(3, s));
    }

    @Test
    void mapEnd_afterEdit() {
        EditSpan s = new EditSpan(5, 0, 3);
        assertEquals(9, DiagnosticShift.mapEnd(6, s));
    }

    @Test
    void mapStart_insideDelete() {
        EditSpan s = new EditSpan(5, 3, 0);
        // Une position dans la plage supprimée est bornée au début de l'édition
        int mapped = DiagnosticShift.mapStart(6, s);
        assertTrue(mapped >= 5 && mapped <= 8, "Expected 5-8, got " + mapped);
    }

    @Test
    void shiftDiagnostics_insert() {
        var diags = List.of(
            new DiagnosticShift.Diagnostic(10, 15, 1, "error"),
            new DiagnosticShift.Diagnostic(20, 25, 2, "warning")
        );
        EditSpan edit = new EditSpan(5, 0, 3);
        var shifted = DiagnosticShift.shiftDiagnostics(diags, edit);

        assertEquals(13, shifted.get(0).start);
        assertEquals(18, shifted.get(0).end);
        assertEquals(23, shifted.get(1).start);
        assertEquals(28, shifted.get(1).end);
    }

    @Test
    void shiftDiagnostics_delete() {
        var diags = List.of(
            new DiagnosticShift.Diagnostic(10, 15, 1, "error")
        );
        EditSpan edit = new EditSpan(5, 3, 0);
        var shifted = DiagnosticShift.shiftDiagnostics(diags, edit);

        assertEquals(7, shifted.get(0).start);
        assertEquals(12, shifted.get(0).end);
    }

    @Test
    void shiftDiagnostics_tagUnnecessary_préservé() {
        // Le tag Unnecessary (peinture estompée du code inutilisé)
        // survit au décalage : l'import mort reste grisé après l'édition.
        var diags = List.of(
            new DiagnosticShift.Diagnostic(10, 15, 2, "unused import", true),
            new DiagnosticShift.Diagnostic(20, 25, 1, "info", false)
        );
        EditSpan edit = new EditSpan(5, 0, 3);
        var shifted = DiagnosticShift.shiftDiagnostics(diags, edit);

        assertTrue(shifted.get(0).unnecessary, "le tag voyage avec le décalage");
        assertFalse(shifted.get(1).unnecessary);
        assertEquals(13, shifted.get(0).start, "offsets décalés à l'identique");
    }

    @Test
    void shiftDiagnostics_codeEtSource_préservés() {
        // D4 (lot 2, J5) : le code et la source du diagnostic voyagent
        // avec le décalage incrémental — l'en-tête enrichi du popup
        // reste juste après l'édition.
        var diags = List.of(
            new DiagnosticShift.Diagnostic(10, 15, 3, "err", false, "ecj:67", "java"),
            new DiagnosticShift.Diagnostic(20, 25, 2, "warn") // nu : défaut vide
        );
        EditSpan edit = new EditSpan(5, 0, 3);
        var shifted = DiagnosticShift.shiftDiagnostics(diags, edit);

        assertEquals("ecj:67", shifted.get(0).code, "le code survit au décalage");
        assertEquals("java", shifted.get(0).source, "la source survit au décalage");
        assertEquals(13, shifted.get(0).start, "offsets décalés à l'identique");
        assertEquals("", shifted.get(1).code, "constructeur historique : code vide");
        assertEquals("", shifted.get(1).source, "constructeur historique : source vide");
    }

    @Test
    void diagnostic_constructeurs_nullsDéfensifs() {
        // D4 : null est normalisé en chaîne vide (jamais de NPE au
        // rendu de l'en-tête).
        var d = new DiagnosticShift.Diagnostic(0, 1, 1, "m", false, null, null);
        assertEquals("", d.code);
        assertEquals("", d.source);
    }

    @Test
    void shiftDiagnostics_deleteConsumesDiagnostic() {
        var diags = List.of(
            new DiagnosticShift.Diagnostic(2, 4, 1, "error")
        );
        EditSpan edit = new EditSpan(1, 5, 0);
        var shifted = DiagnosticShift.shiftDiagnostics(diags, edit);
        assertTrue(shifted.isEmpty());
    }

    @Test
    void shiftSemanticTokens() {
        var tokens = List.of(
            new DiagnosticShift.SemanticToken(10, 5, 0),
            new DiagnosticShift.SemanticToken(20, 5, 1)
        );
        EditSpan edit = new EditSpan(5, 0, 3);
        var shifted = DiagnosticShift.shiftSemanticTokens(tokens, edit);

        assertEquals(13, shifted.get(0).start);
        assertEquals(23, shifted.get(1).start);
    }

    @Test
    void shiftFoldRegions() {
        var regions = List.of(
            new DiagnosticShift.FoldRegion(10, 20, "...")
        );
        EditSpan edit = new EditSpan(5, 0, 5);
        var shifted = DiagnosticShift.shiftFoldRegions(regions, edit);

        assertEquals(15, shifted.get(0).start);
        assertEquals(25, shifted.get(0).end);
    }

    @Test
    void shiftInlayHints() {
        var hints = List.of(
            new DiagnosticShift.InlayHint(10, "hint", true),
            new DiagnosticShift.InlayHint(20, "hint2", false)
        );
        EditSpan edit = new EditSpan(5, 0, 3);
        var shifted = DiagnosticShift.shiftInlayHints(hints, edit);

        assertEquals(13, shifted.get(0).offset);
        assertEquals(23, shifted.get(1).offset);
    }

    @Test
    void shiftDiagnostics_noOp() {
        var diags = List.of(
            new DiagnosticShift.Diagnostic(5, 10, 1, "error")
        );
        EditSpan edit = new EditSpan(0, 0, 0);
        var shifted = DiagnosticShift.shiftDiagnostics(diags, edit);
        assertEquals(1, shifted.size());
        assertEquals(5, shifted.get(0).start);
    }
}
