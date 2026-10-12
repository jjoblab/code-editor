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
 * Tests des fonctionnalités IME et des replis :
 *
 * <ul>
 *   <li>Remplacement (et non ajout) de la région de composition (taper "hello" puis
 *       backspace ne doit PAS produire "hellohell").</li>
 *   <li>{@code imeSetComposingText} respecte le contrat {@code newCursorPosition}
 *       (positif = relatif à la fin, négatif = relatif au début).</li>
 *   <li>{@code imeSetSelection} / {@code imeSetComposingRegion} sont bornés aux
 *       limites du document.</li>
 *   <li>{@code imeDeleteSurrounding} supprime une plage littérale de caractères (pas de
 *       règles de backspace intelligent — l'échange de ponctuation de SwiftKey est
 *       identique octet par octet à un backspace utilisateur).</li>
 *   <li>{@code imeReplaceText} (API 34) passe par replaceRangeWithCaret.</li>
 *   <li>{@code imeTextBeforeCursor} / {@code imeTextAfterCursor} renvoient la
 *       bonne tranche et sont fenêtrés à MAX_IPC_TEXT.</li>
 *   <li>{@code toggleFoldAtLine} / {@code isLineFolded} / {@code expandFoldAt}
 *       se comportent comme attendu.</li>
 *   <li>{@code EditorSession.ImeListener} reçoit {@code onTextChanged},
 *       {@code onSelectionChanged}, {@code onRestartInput} sur les bons
 *       événements.</li>
 * </ul>
 */
class V105ImeAndFoldTest {

    // ── Double de test ImeListener ──────────────────────────────

    private static final class RecordingImeListener implements EditorSession.ImeListener {
        int textChangedCount = 0;
        int selectionChangedCount = 0;
        int restartInputCount = 0;
        EditSpan lastSpan = null;
        int lastSelStart = -1, lastSelEnd = -1, lastCompStart = -2, lastCompEnd = -2;
        boolean syncingExtracted = false;

        @Override public void onTextChanged(EditSpan span) {
            textChangedCount++;
            lastSpan = span;
        }
        @Override public void onSelectionChanged(int selStart, int selEnd,
                                                  int composingStart, int composingEnd) {
            selectionChangedCount++;
            lastSelStart = selStart;
            lastSelEnd = selEnd;
            lastCompStart = composingStart;
            lastCompEnd = composingEnd;
        }
        @Override public void onRestartInput() {
            restartInputCount++;
        }
        @Override public boolean isSyncingExtractedText() {
            return syncingExtracted;
        }
    }

    // ── Région de composition : remplacer, pas ajouter ──────────

    @Test
    void imeSetComposingText_replacesExistingComposingRegion() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Premier appel : insérer "hell" en composition au caret.
        s.imeSetComposingText("hell", 1);
        assertEquals("hell", s.getText());
        assertEquals(4, s.getSelection().start);
        // Second appel : remplacer la région de composition par "hello".
        s.imeSetComposingText("hello", 1);
        assertEquals("hello", s.getText());
        assertEquals(5, s.getSelection().start);
        // Troisième appel : remplacer "hello" par "help" — le "lo" doit
        // avoir DISPARU.
        s.imeSetComposingText("help", 1);
        assertEquals("help", s.getText());
        assertEquals(4, s.getSelection().start);
    }

    @Test
    void imeSetComposingText_backspaceDoesNotProduce_hellohell() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Taper "hello" en composition.
        s.imeSetComposingText("hello", 1);
        assertEquals("hello", s.getText());
        // L'IME envoie setComposingText("") ou finishComposingText +
        // deleteSurrounding. Cas courant : setComposingText("hell", 1) →
        // doit remplacer "hello" par "hell".
        s.imeSetComposingText("hell", 1);
        assertEquals("hell", s.getText());
        // Backspace à nouveau → "hel"
        s.imeSetComposingText("hel", 1);
        assertEquals("hel", s.getText());
        // Le bug historique : la composition était AJOUTÉE, produisant
        // "hellohell".
        assertNotEquals("hellohell", s.getText());
        assertNotEquals("hellohellhell", s.getText());
    }

    @Test
    void imeSetComposingText_firstCallOnSelectionReplacesSelection() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        // Sélectionner "hello".
        s.setSelection(Selection.range(0, 5));
        // L'IME démarre la composition — le mot composé doit REMPLACER la
        // sélection.
        s.imeSetComposingText("HELLO", 1);
        assertEquals("HELLO world", s.getText());
        assertEquals(5, s.getSelection().start);
    }

    // ── Contrat newCursorPosition ───────────────────────────────

    @Test
    void imeSetComposingText_newCursorPositionPositive_isRelativeToEnd() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // newCaretPos = 1 → caret juste après le texte inséré.
        s.imeSetComposingText("abc", 1);
        assertEquals(3, s.getSelection().start);
        // newCaretPos = 2 → caret un caractère après la fin de la
        // composition. Borné à doc.length() quand la composition est tout
        // le document.
        s.imeSetComposingText("abcde", 2);
        assertEquals(5, s.getSelection().start); // doc.length() = 5, borné
    }

    @Test
    void imeSetComposingText_newCursorPositionNegative_isRelativeToStart() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // newCaretPos = 0 → caret au début de la région de composition.
        s.imeSetComposingText("abc", 0);
        assertEquals(0, s.getSelection().start);
    }

    @Test
    void imeSetComposingText_newCursorPositionNegativeClampedToZero() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Un IME bogué envoie un newCaretPos très négatif — ne doit pas
        // crasher.
        s.imeSetComposingText("abc", -1000);
        assertEquals(0, s.getSelection().start);
    }

    // ── Bornage de imeSetSelection / imeSetComposingRegion ─────

    @Test
    void imeSetSelection_clampsToDocumentBounds() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.imeSetSelection(-10, 1000);
        assertEquals(0, s.getSelection().start);
        assertEquals(5, s.getSelection().end);
    }

    @Test
    void imeSetComposingRegion_emptyRangeClearsComposing() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.imeSetComposingRegion(1, 4);
        assertTrue(s.isComposing());
        // Une région vide efface la composition.
        s.imeSetComposingRegion(2, 2);
        assertFalse(s.isComposing());
    }

    @Test
    void imeFinishComposing_clearsStateWithoutTouchingText() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        s.imeSetComposingText("hello", 1);
        assertTrue(s.isComposing());
        assertEquals("hello", s.getText());
        // Finir la composition — le texte reste, le drapeau de composition
        // s'efface.
        s.imeFinishComposing();
        assertEquals("hello", s.getText());
        assertFalse(s.isComposing());
    }

    // ── imeDeleteSurrounding ─────────────────────────────────────

    @Test
    void imeDeleteSurrounding_deletesLiteralRange() {
        EditorSession s = new EditorSession(EditorDocument.of("abcdef"));
        s.setSelection(3); // entre 'c' et 'd'
        s.imeDeleteSurrounding(2, 1); // supprimer 2 avant + 1 après
        // abc|def → supprimer 2 avant (bc) et 1 après (d) → a|ef
        assertEquals("aef", s.getText());
    }

    @Test
    void imeDeleteSurrounding_doesNotApplySmartBackspaceRules() {
        // La règle de backspace intelligent sur la paire vide `()`
        // supprimerait les deux caractères. imeDeleteSurrounding(1, 0) sur
        // "()" ne doit supprimer qu'UN caractère.
        EditorSession s = new EditorSession(EditorDocument.of("()"));
        s.setSelection(1); // entre '(' et ')'
        s.imeDeleteSurrounding(1, 0);
        assertEquals(")", s.getText());
    }

    // ── imeReplaceText (API 34) ───────────────────────────────────

    @Test
    void imeReplaceText_replacesRangeAndSetsCaret() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.imeReplaceText(0, 5, "HELLO", 1);
        assertEquals("HELLO world", s.getText());
        // Caret juste après le texte inséré (newCaretPos=1 → fin+0).
        assertEquals(5, s.getSelection().start);
    }

    // ── imeTextBeforeCursor / imeTextAfterCursor ─────────────────

    @Test
    void imeTextBeforeCursor_returnsUpToNCharsBeforeCaret() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(7); // au second 'o' de "world" (offset 7 → après "hello w")
        assertEquals("hello w", s.imeTextBeforeCursor(7));
        // Un n plus grand renvoie tout le texte jusqu'au caret.
        assertEquals("hello w", s.imeTextBeforeCursor(100));
    }

    @Test
    void imeTextAfterCursor_returnsUpToNCharsAfterCaret() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(6); // sur 'w'
        assertEquals("world", s.imeTextAfterCursor(5));
        assertEquals("world", s.imeTextAfterCursor(100));
    }

    // ── Câblage ImeListener ────────────────────────────────────

    @Test
    void setSelection_notifiesImeListener() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.setSelection(3);
        assertEquals(1, l.selectionChangedCount);
        assertEquals(3, l.lastSelStart);
        assertEquals(3, l.lastSelEnd);
        assertEquals(-1, l.lastCompStart); // composingStart vaut -1 hors composition
    }

    @Test
    void replaceRange_notifiesImeListenerWithSpanAndSelection() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.replaceRange(0, 5, "HELLO");
        assertEquals(1, l.textChangedCount);
        assertNotNull(l.lastSpan);
        assertEquals(5, l.lastSpan.removed);
        assertEquals(5, l.lastSpan.added);
        assertEquals(5, l.lastSelStart);
        assertEquals(5, l.lastSelEnd);
    }

    @Test
    void typeChar_smartEditDivergenceTriggersRestartInput() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        // Taper '(' : l'insertion intelligente doit auto-fermer en '()'
        // avec le caret au milieu.
        s.typeChar('(');
        assertEquals("()", s.getText());
        assertEquals(1, s.getSelection().start);
        // L'édition intelligente a divergé de « taper littéralement ce
        // caractère » → restartInput.
        assertTrue(l.restartInputCount > 0, "Expected onRestartInput to be called for divergent smart-edit");
    }

    @Test
    void typeChar_literalTypingDoesNotTriggerRestartInput() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.setSelection(5); // à la fin de "hello"
        // Taper 'a' — doit être une insertion littérale, pas de divergence
        // d'édition intelligente.
        s.typeChar('a');
        assertEquals("helloa", s.getText());
        assertEquals(0, l.restartInputCount);
    }

    // ── Gestion des replis ─────────────────────────────────────

    @Test
    void toggleFoldAtLine_togglesCollapsedState() {
        EditorSession s = new EditorSession(EditorDocument.of("public class A {\n    int x;\n    int y;\n}\n"));
        // Placer une région de repli commençant à la ligne 0 (offset 0).
        int classEnd = s.getText().indexOf('}');
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, classEnd + 1, "{...}", "block", false));
        s.setFoldRegions(folds);
        // La ligne 0 n'est PAS repliée (la région commence À la ligne 0
        // mais n'est pas collapsed).
        assertFalse(s.isLineFolded(1));
        // Basculer le repli à la ligne 0 → se replie.
        assertTrue(s.toggleFoldAtLine(0));
        assertTrue(s.isLineFolded(1));
        assertTrue(s.isLineFolded(2));
        assertFalse(s.isLineFolded(0)); // la ligne de départ reste visible (composite)
        // Basculer à nouveau → se déplie.
        assertTrue(s.toggleFoldAtLine(0));
        assertFalse(s.isLineFolded(1));
    }

    @Test
    void toggleFoldAtLine_unknownLineReturnsFalse() {
        EditorSession s = new EditorSession(EditorDocument.of("abc\ndef\n"));
        assertFalse(s.toggleFoldAtLine(0));
    }

    @Test
    void expandFoldAt_expandsCollapsedFoldContainingOffset() {
        EditorSession s = new EditorSession(EditorDocument.of("public class A {\n    int x;\n    int y;\n}\n"));
        int classEnd = s.getText().indexOf('}');
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, classEnd + 1, "{...}", "block", true));
        s.setFoldRegions(folds);
        assertTrue(s.isLineFolded(1));
        // Le caret atterrit à l'offset 25 (dans le repli).
        s.expandFoldAt(25);
        assertFalse(s.isLineFolded(1));
    }

    @Test
    void getCollapsedFolds_returnsOnlyCollapsed() {
        EditorSession s = new EditorSession(EditorDocument.of("aaaa\n"));
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, 4, "...", "block", true));
        folds.add(new DiagnosticShift.FoldRegion(0, 4, "...", "block", false));
        s.setFoldRegions(folds);
        assertEquals(1, s.getCollapsedFolds().size());
    }

    // ── Décalage de la région de composition à l'édition ──────

    @Test
    void replaceRange_shiftsComposingRegion() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(2);
        s.imeSetComposingText("XYZ", 1);
        // Le buffer est maintenant "heXYZllo world", région de composition
        // [2,5].
        assertEquals("heXYZllo world", s.getText());
        int[] comp = s.getComposingRegion();
        assertNotNull(comp);
        assertEquals(2, comp[0]);
        assertEquals(5, comp[1]);
        // Insérer un caractère AVANT la région de composition — la région
        // doit se décaler.
        s.setSelection(0);
        s.commitText("A"); // l'insertion intelligente insère juste le caractère
        // Le buffer est maintenant "AheXYZllo world", la région de
        // composition doit être [3,6].
        comp = s.getComposingRegion();
        assertNotNull(comp);
        assertEquals(3, comp[0]);
        assertEquals(6, comp[1]);
    }
}
