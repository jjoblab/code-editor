package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests pour le code surround with (entourer une sélection avec
 * if/try/for/while).
 */
class SurroundWithTest {

    @Test
    void surround_if_wrapsSelection() {
        SurroundWith.Result r = SurroundWith.surround(
                "selected code", 0, 13, "if", "java", "    ");
        assertNotNull(r);
        assertTrue(r.insertText.contains("if ("));
        assertTrue(r.insertText.contains("selected code"));
        assertTrue(r.insertText.contains("}"));
    }

    @Test
    void surround_try_wrapsSelectionWithCatch() {
        SurroundWith.Result r = SurroundWith.surround(
                "risky()", 0, 7, "try", "java", "");
        assertNotNull(r);
        assertTrue(r.insertText.contains("try {"));
        assertTrue(r.insertText.contains("risky()"));
        assertTrue(r.insertText.contains("} catch"));
    }

    @Test
    void surround_for_wrapsSelection() {
        SurroundWith.Result r = SurroundWith.surround(
                "item", 0, 4, "for", "java", "");
        assertNotNull(r);
        assertTrue(r.insertText.contains("for ("));
    }

    @Test
    void surround_while_wrapsSelection() {
        SurroundWith.Result r = SurroundWith.surround(
                "condition", 0, 9, "while", "java", "");
        assertNotNull(r);
        assertTrue(r.insertText.contains("while ("));
    }

    @Test
    void surround_python_if_usesColonSyntax() {
        SurroundWith.Result r = SurroundWith.surround(
                "x = 1", 0, 4, "if", "python", "    ");
        assertNotNull(r);
        assertTrue(r.insertText.contains("if "));
        assertTrue(r.insertText.contains(":"));
    }

    @Test
    void surround_unknownType_returnsNull() {
        SurroundWith.Result r = SurroundWith.surround(
                "code", 0, 4, "unknown", "java", "");
        assertNull(r);
    }
}
