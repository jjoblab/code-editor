package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de régression pour des bugs corrigés.
 *
 * <p>Chaque test vérifie un comportement précis auparavant cassé :
 *
 * <ul>
 *   <li>{@code typeChar} skip-over ne doit PAS pousser d'étape d'undo.</li>
 *   <li>{@code toggleLineComment} ne doit pas traiter les lignes vides comme « allCommented ».</li>
 *   <li>{@code toggleBlockComment} doit être une seule étape d'undo.</li>
 *   <li>{@code indent}/{@code dedent} ne doivent pas inclure la ligne suivante quand la
 *       sélection finit exactement au début d'une ligne.</li>
 *   <li>le caret de {@code typeChar} doit atterrir à la bonne position après l'expansion
 *       intelligente du saut de ligne.</li>
 * </ul>
 */
class RegressionFixTest {

    // ── typeChar skip-over ne doit pas polluer l'undo ───────────

    @Test
    void typeChar_skipOverCloser_doesNotRecordUndo() {
        EditorSession s = new EditorSession(EditorDocument.of("()"));
        s.setSelection(1);                  // caret entre '(' et ')'
        s.typeChar(')');                    // skip-over : le caret passe à 2

        assertEquals("()", s.getText(), "Skip-over must not insert anything");
        assertEquals(2, s.getSelection().start, "Caret must move past the closer");

        // La pile d'undo doit être vide — il n'y a rien à annuler.
        assertFalse(s.undo(), "Skip-over must not produce an undo step");
    }

    @Test
    void typeChar_skipOverQuote_doesNotRecordUndo() {
        EditorSession s = new EditorSession(EditorDocument.of("\"\""));
        s.setSelection(1);
        s.typeChar('"');

        assertEquals("\"\"", s.getText());
        assertEquals(2, s.getSelection().start);
        assertFalse(s.undo());
    }

    // ── typeChar avec auto-close doit positionner le caret correctement ─

    @Test
    void typeChar_autoCloseParen_setsCaretBetweenPair() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(5);
        s.typeChar('(');

        assertEquals("hello()", s.getText());
        assertEquals(6, s.getSelection().start, "Caret must be between '(' and ')'");
    }

    // ── toggleLineComment avec lignes vides ─────────────────────

    @Test
    void toggleLineComment_ignoresBlankLinesForAllCommentedCheck() {
        // Ligne 0 = "// a", ligne 1 vide, ligne 2 = "// c".
        // allCommented doit être TRUE (les lignes vides sont ignorées), donc
        // l'appel doit DÉCOMMENTER et non re-commenter.
        EditorSession s = new EditorSession(EditorDocument.of("// a\n\n// c"));
        s.setSelection(Selection.range(0, 8));

        s.toggleLineComment();

        // Après décommentage, les deux lignes non vides perdent leur
        // préfixe, la ligne vide reste vide.
        assertEquals("a\n\nc", s.getText());
    }

    @Test
    void toggleLineComment_commentsBlockWithBlankLines() {
        // Aucune ligne n'est encore commentée — le toggle doit tout
        // commenter (y compris les vides).
        EditorSession s = new EditorSession(EditorDocument.of("a\n\nb"));
        s.setSelection(Selection.range(0, 4));

        s.toggleLineComment();

        assertEquals("// a\n// \n// b", s.getText());
    }

    @Test
    void toggleLineComment_undoRoundTrip() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        s.setSelection(Selection.range(0, 5));

        s.toggleLineComment();
        String commented = s.getText();
        assertNotEquals("a\nb\nc", commented);

        assertTrue(s.undo());
        assertEquals("a\nb\nc", s.getText());
    }

    // ── toggleBlockComment doit être une seule étape d'undo ─────

    @Test
    void toggleBlockComment_isSingleUndoStep() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(Selection.range(0, 5));      // sélectionner "hello"

        s.toggleBlockComment();                      // ajouter /* */ autour
        // Note : l'impl insère "/* " et " */" (avec espaces) autour de la
        // sélection, donc le résultat est "/* hello */ world".
        assertEquals("/* hello */ world", s.getText());

        int depthBeforeUndo = s.getUndoManager().undoDepth();
        assertTrue(depthBeforeUndo >= 1, "Should have at least one undo step, got " + depthBeforeUndo);

        boolean undone = s.undo();
        assertTrue(undone, "undo() should return true");
        assertEquals("hello world", s.getText(), "Single undo must remove both /* and */");
    }

    @Test
    void toggleBlockComment_unwrapWhenAlreadyWrapped() {
        // Utiliser "/*hello*/" (sans espaces) pour que start=2 tombe juste
        // après "/*" et end=7 juste avant "*/" — c'est ce que l'impl
        // détecte.
        EditorSession s = new EditorSession(EditorDocument.of("/*hello*/ world"));
        s.setSelection(Selection.range(2, 7));      // dans "hello"

        s.toggleBlockComment();
        assertEquals("hello world", s.getText());
    }

    // ── indent/dedent ne doivent pas inclure la ligne vide suivante ─

    @Test
    void indent_doesNotIncludeTrailingEmptyLineWhenSelectionEndsAtLineStart() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        // Sélectionner de l'offset 0 à l'offset 2 (qui est lineStart(1)).
        // La sélection couvre visuellement "a\n" — elle ne doit PAS
        // indenter la ligne "b".
        s.setSelection(Selection.range(0, 2));

        s.indent();

        assertEquals("    a\nb\nc", s.getText(), "Only line 0 should be indented");
    }

    @Test
    void indent_fullSelection_includesAllLines() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        s.setSelection(Selection.range(0, 5));      // couvre tout "a\nb\nc"

        s.indent();

        assertEquals("    a\n    b\n    c", s.getText());
    }

    @Test
    void indent_dedent_roundTrip() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        s.setSelection(Selection.range(0, 5));

        s.indent();
        s.setSelection(Selection.range(0, s.getDocument().length()));
        s.dedent();

        assertEquals("a\nb\nc", s.getText());
    }

    // ── backspace sur paire vide supprime les deux caractères ───

    @Test
    void backspace_emptyPair_deletesBoth() {
        EditorSession s = new EditorSession(EditorDocument.of("()"));
        s.setSelection(1);

        s.backspace();

        assertEquals("", s.getText());
        assertEquals(0, s.getSelection().start);
    }

    @Test
    void backspace_emptyBraces_deletesBoth() {
        EditorSession s = new EditorSession(EditorDocument.of("{}"));
        s.setSelection(1);

        s.backspace();

        assertEquals("", s.getText());
    }

    // ── isInString ignore les commentaires ─────────────────────

    @Test
    void smartEnter_insideLineComment_treatsAsCode() {
        // Le caret est juste après "// " dans un commentaire de ligne.
        // Le lexer ne doit pas croire qu'on est dans une chaîne (à cause
        // des guillemets plus loin sur la même ligne) : les commentaires
        // de ligne sont ignorés et l'indentation intelligente fonctionne
        // normalement.
        EditorSession s = new EditorSession(EditorDocument.of("// comment \"x\""));
        s.setLanguage("java");
        s.setSelection(s.getDocument().length());

        s.commitText("\n");

        // La nouvelle ligne doit juste hériter de l'indentation (aucune
        // ici), donc le résultat est la ligne d'origine + "\n" + ""
        // (pas d'indentation supplémentaire).
        assertEquals("// comment \"x\"\n", s.getText());
    }

    // ── replaceRangeWithCaret : caret explicite pour les opérations intelligentes ───────

    @Test
    void replaceRangeWithCaret_noOpDoesNotPushUndo() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(2);
        int undoDepthBefore = s.getUndoManager().undoDepth();

        // No-op : start == end, insertion vide.
        s.replaceRangeWithCaret(2, 2, "", 5);

        assertEquals(undoDepthBefore, s.getUndoManager().undoDepth(),
            "No-op replaceRangeWithCaret must not push undo");
        assertEquals(5, s.getSelection().start, "Caret must move to 5");
    }

    @Test
    void replaceRangeWithCaret_customCaretAfterInsert() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(0);

        // Insérer "WORLD" mais laisser le caret à l'offset 2 (pas à la fin).
        s.replaceRangeWithCaret(0, 0, "WORLD", 2);

        assertEquals("WORLDhello", s.getText());
        assertEquals(2, s.getSelection().start);
    }
}
