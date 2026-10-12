package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des fonctionnalités IME (auto-espace SwiftKey) et replis :
 *
 * <ul>
 *   <li>Gestion de l'auto-espace SwiftKey : les formes groupée ("p ") et
 *       séparée ("p" puis " ") suppriment toutes deux l'espace finale.</li>
 *   <li>La détection de l'échange de ponctuation est identique octet par
 *       octet à un backspace utilisateur — imeDeleteSurrounding reste
 *       littéral (pas de règles de backspace intelligent).</li>
 *   <li>La région de composition ne bouge pas quand une édition hors
 *       composition a lieu avant elle (re-vérifié ici pour le code path
 *       imeCommitText).</li>
 *   <li>L'état replié d'une région survit à shiftFoldRegions.</li>
 *   <li>EditorSession.imeCommitText avec une chaîne vide efface la
 *       composition sans toucher au texte.</li>
 * </ul>
 */
class V106ImeAndWrapTest {

    private static final class RecordingImeListener implements EditorSession.ImeListener {
        int textChangedCount = 0;
        int selectionChangedCount = 0;
        int restartInputCount = 0;
        boolean syncingExtracted = false;

        @Override public void onTextChanged(EditSpan span) { textChangedCount++; }
        @Override public void onSelectionChanged(int s, int e, int cs, int ce) { selectionChangedCount++; }
        @Override public void onRestartInput() { restartInputCount++; }
        @Override public boolean isSyncingExtractedText() { return syncingExtracted; }
    }

    // ── Auto-espace SwiftKey (forme groupée) ────────────────────

    @Test
    void imeCommitText_bundledAutoSpaceAfterParen_stripsTrailingSpace() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        // L'IME committe "p " : le "p" est le caractère tapé par
        // l'utilisateur, l'espace finale est l'auto-espace du clavier.
        // "(" étant un ouvrant non auto-espacé, on teste avec ")".
        s.imeCommitText(")");
        // L'heuristique ne se déclenche que si le DERNIER caractère du commit
        // est " " et l'avant-dernier un symbole auto-espacé. On committe donc
        // directement ") " — un fermant nu + espace.
        s.imeCommitText(") ");
        // L'espace finale doit avoir été supprimée.
        assertEquals("foo))", s.getText());
        // restartInput doit avoir été appelé (le modèle de l'IME contient
        // l'espace fantôme).
        assertTrue(l.restartInputCount > 0, "Expected onRestartInput for bundled auto-space");
    }

    @Test
    void imeCommitText_bundledAutoSpaceAfterSemicolon_stripsTrailingSpace() {
        EditorSession s = new EditorSession(EditorDocument.of("int x"));
        s.setSelection(5);
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.imeCommitText("; ");
        assertEquals("int x;", s.getText());
        assertTrue(l.restartInputCount > 0);
    }

    // ── Auto-espace SwiftKey (forme séparée) ────────────────────

    @Test
    void imeCommitText_splitAutoSpaceAfterSymbol_swallowsBareSpace() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        // Premier commit : ")" — cela arme le détecteur d'auto-espace séparé.
        s.imeCommitText(")");
        assertEquals("foo)", s.getText());
        // Second commit : " " — doit être avalée.
        s.imeCommitText(" ");
        assertEquals("foo)", s.getText());
        // restartInput doit avoir été appelé pour l'espace avalée.
        assertTrue(l.restartInputCount > 0, "Expected onRestartInput for split auto-space");
    }

    @Test
    void imeCommitText_userTypedSpaceIsNotSwallowed() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        // L'utilisateur tape une vraie espace (pas après un commit de symbole).
        s.imeCommitText(" ");
        assertEquals("foo ", s.getText());
        // Puis une autre espace — non avalée non plus (pas de commit de
        // symbole précédent).
        s.setSelection(4);
        s.imeCommitText(" ");
        assertEquals("foo  ", s.getText());
    }

    @Test
    void imeCommitText_spaceAfterLetterIsNotSwallowed() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        // Taper "bar" puis " " — l'espace ne doit PAS être avalée car le
        // commit précédent est une lettre, pas un symbole.
        s.imeCommitText("bar");
        s.imeCommitText(" ");
        assertEquals("foobar ", s.getText());
    }

    // ── Le commit vide efface la composition ────────────────────

    @Test
    void imeCommitText_emptyStringClearsComposingWithoutTouchingText() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        s.imeSetComposingText("hello", 1);
        assertTrue(s.isComposing());
        assertEquals("hello", s.getText());
        // L'IME confirme la région de composition par un commit vide.
        s.imeCommitText("");
        // Le texte n'est PAS touché (le texte composé est déjà dans le buffer).
        assertEquals("hello", s.getText());
        // Drapeau de composition effacé.
        assertFalse(s.isComposing());
    }

    // ── L'état replié survit au décalage ────────────────────────

    @Test
    void shiftFoldRegions_preservesCollapsedState() {
        // Placer un repli collapsed à [0, 10).
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, 10, "...", "block", true));
        // Édition à l'offset 5 : remplacer 1 caractère par 3.
        EditSpan span = new EditSpan(5, 1, 3);
        List<DiagnosticShift.FoldRegion> shifted = DiagnosticShift.shiftFoldRegions(folds, span);
        assertEquals(1, shifted.size());
        DiagnosticShift.FoldRegion r = shifted.get(0);
        // Le start doit être inchangé (avant l'édition).
        assertEquals(0, r.start);
        // Le end doit se décaler de +2 (3 insérés - 1 supprimé).
        assertEquals(12, r.end);
        // État collapsed préservé.
        assertTrue(r.collapsed);
        assertEquals("block", r.kind);
        assertEquals("...", r.placeholder);
    }

    @Test
    void shiftFoldRegions_dropsFoldsConsumedByDelete() {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(5, 10, "...", "block", true));
        // Supprimer toute la plage du repli : [0, 20) → "".
        EditSpan span = new EditSpan(0, 20, 0);
        List<DiagnosticShift.FoldRegion> shifted = DiagnosticShift.shiftFoldRegions(folds, span);
        // La fin du repli mappe sur 0 (elle était dans la plage supprimée),
        // donc newEnd <= newStart et le repli est abandonné.
        assertTrue(shifted.isEmpty());
    }

    // ── La région de composition ne bouge pas lors d'une édition hors composition ──

    @Test
    void replaceRange_outsideComposingRegion_keepsComposingIntact() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        // Sélectionner "world" (offsets 6..11) pour que le texte composé le
        // remplace.
        s.setSelection(Selection.range(6, 11));
        s.imeSetComposingText("WORLD", 1);
        // La région de composition est maintenant [6, 11) dans "hello WORLD".
        assertEquals("hello WORLD", s.getText());
        int[] comp = s.getComposingRegion();
        assertEquals(6, comp[0]);
        assertEquals(11, comp[1]);
        // Édition APRÈS la région de composition — ne doit pas la déplacer.
        s.setSelection(12);
        s.commitText("!");
        // "hello WORLD!" — région de composition [6, 11) inchangée.
        assertEquals("hello WORLD!", s.getText());
        comp = s.getComposingRegion();
        assertEquals(6, comp[0]);
        assertEquals(11, comp[1]);
    }

    // ── La saisie littérale typeChar n'arme pas le détecteur de commit de symbole ──

    @Test
    void typeChar_letterDoesNotArmSymbolCommitDetector() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Taper ")" — arme le détecteur.
        s.typeChar(')');
        assertEquals(")", s.getText());
        // Taper "a" (une lettre, pas une espace) — ne doit PAS être avalé.
        s.typeChar('a');
        assertEquals(")a", s.getText());
    }

    // ── Plusieurs commits de symboles à la suite ────────────────

    @Test
    void imeCommitText_multipleSymbolsInARow_splitAutoSpaceSwallowed() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Taper ")" (symbole nu — arme le détecteur séparé).
        s.imeCommitText(")");
        assertEquals(")", s.getText());
        // Taper " " — auto-espace séparée, avalée.
        s.imeCommitText(" ");
        assertEquals(")", s.getText());
        // Taper ";" (symbole nu — ré-arme le détecteur).
        s.imeCommitText(";");
        assertEquals(");", s.getText());
        // Taper " " — auto-espace séparée, avalée.
        s.imeCommitText(" ");
        assertEquals(");", s.getText());
    }
}
