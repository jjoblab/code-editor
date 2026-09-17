package jo.codeeditor.integration;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'intégration : saisir une méthode complète, tout annuler, tout
 * rétablir, vérifier que le texte correspond.
 */
class EditorIntegrationTest {

    @Test
    void typeMethod_undoAll_redoAll() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setLanguage("java");

        // Saisir une méthode simple
        String[] keystrokes = {
            "p", "u", "b", "l", "i", "c", " ", "v", "o", "i", "d", " ",
            "m", "a", "i", "n", "(", ")", " ", "{", "\n",
            " ", " ", " ", " ", "S", "y", "s", "t", "e", "m", ".",
            "o", "u", "t", ".", "p", "r", "i", "n", "t", "l", "n",
            "(", "\"", "H", "e", "l", "l", "o", "\"", ")", ";", "\n",
            "}"
        };

        for (String k : keystrokes) {
            if ("\n".equals(k)) {
                s.commitText("\n");
            } else {
                s.typeChar(k.charAt(0));
            }
        }

        String typed = s.getText();
        assertTrue(typed.contains("public void main()"));
        assertTrue(typed.contains("System.out.println"));

        // Tout annuler
        int undoCount = 0;
        while (s.undo()) {
            undoCount++;
            if (undoCount > 200) break; // garde-fou
        }
        assertTrue(undoCount > 0, "Should have undone at least one step");

        // Tout rétablir
        int redoCount = 0;
        while (s.redo()) {
            redoCount++;
            if (redoCount > 200) break;
        }

        assertEquals(typed, s.getText(), "Redo should restore the original text");
    }

    @Test
    void editMultiLineFile_maintainsConsistency() {
        String initial = "line1\nline2\nline3\nline4\nline5";
        EditorSession s = new EditorSession(EditorDocument.of(initial));
        s.setLanguage("java");

        // Commenter les lignes 2 à 4
        s.setSelection(Selection.range(
            s.getDocument().lineStart(1),
            s.getDocument().lineEnd(3)
        ));
        s.toggleLineComment();
        String commented = s.getText();
        assertTrue(commented.contains("// line2"));
        assertTrue(commented.contains("// line3"));
        assertTrue(commented.contains("// line4"));

        // Décommenter
        s.setSelection(Selection.range(
            s.getDocument().lineStart(1),
            s.getDocument().lineEnd(3)
        ));
        s.toggleLineComment();
        assertEquals(initial, s.getText());
    }

    @Test
    void indentDedent_roundTrip() {
        String initial = "aaa\nbbb\nccc";
        EditorSession s = new EditorSession(EditorDocument.of(initial));

        s.setSelection(Selection.range(0, 11));
        s.indent();
        assertEquals("    aaa\n    bbb\n    ccc", s.getText());

        // Réinitialiser la sélection pour couvrir toutes les lignes après
        // l'indentation (le curseur a bougé en fin de document)
        s.setSelection(Selection.range(0, s.getDocument().length()));
        s.dedent();
        assertEquals(initial, s.getText());
    }

    @Test
    void deleteAndUndo_restoresOriginal() {
        String initial = "Hello, World!";
        EditorSession s = new EditorSession(EditorDocument.of(initial));
        s.setSelection(Selection.range(0, 5));
        s.backspace();
        assertEquals(", World!", s.getText());

        s.undo();
        assertEquals(initial, s.getText());
    }

    @Test
    void moveLines_roundTrip() {
        String initial = "aaa\nbbb\nccc";
        EditorSession s = new EditorSession(EditorDocument.of(initial));

        // Déplacer la première ligne vers le bas
        s.setSelection(0);
        s.moveLineDown();
        assertEquals("bbb\naaa\nccc", s.getText());

        // La remonter (elle est désormais en ligne 1)
        s.setSelection(4);
        s.moveLineUp();
        assertEquals(initial, s.getText());
    }

    @Test
    void documentRevision_tracksChanges() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        assertEquals(0, s.getDocument().getRevision());

        s.setSelection(5);
        s.commitText(" world");
        assertTrue(s.getDocument().getRevision() > 0);
    }

    @Test
    void highlighter_survivesMultiLineEdit() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setLanguage("java");

        s.setSelection(0);
        s.commitText("/* start\nmiddle\nend */\nint x = 5;");

        // La dernière ligne doit contenir des tokens KEYWORD et NUMBER
        var lastLine = s.getStyledLines().get(3);
        assertTrue(lastLine.spans.stream().anyMatch(sp -> sp.type == jo.codeeditor.highlight.TokenType.KEYWORD));
        assertTrue(lastLine.spans.stream().anyMatch(sp -> sp.type == jo.codeeditor.highlight.TokenType.NUMBER));
    }

    @Test
    void largeFile_editPerformance() {
        // Construire un fichier de 1000 lignes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append('\n');
            sb.append("line ").append(i);
        }
        EditorSession s = new EditorSession(EditorDocument.of(sb.toString()));
        s.setLanguage("java");

        // Éditer au milieu
        int midLine = 500;
        s.setSelection(s.getDocument().lineStart(midLine));
        s.commitText("// edited ");

        assertTrue(s.getText().contains("// edited line 500"));
        assertEquals(1000, s.getDocument().lineCount());

        // Annuler
        s.undo();
        assertTrue(s.getText().contains("line 500"));
        assertFalse(s.getText().contains("// edited line 500"));
    }
}
