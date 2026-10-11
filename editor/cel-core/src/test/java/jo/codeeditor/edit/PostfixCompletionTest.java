package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests pour la complétion postfix (`.if`, `.for`, `.notnull`,
 * `.return`, `.var`, `.else`, `.while`, `.try`).
 *
 * <p>Inspiré d'les IDE de bureau : l'utilisateur tape `obj.if` puis
 * Entrée, et l'éditeur génère `if (obj) {\n    \n}`.</p>
 */
class PostfixCompletionTest {

    @Test
    void postfix_if_generatesIfStatement() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "list.isEmpty().if", 17, "java");
        assertNotNull(r);
        assertTrue(r.insertText.contains("if (list.isEmpty())"));
        assertTrue(r.insertText.contains("{"));
        assertTrue(r.insertText.contains("}"));
    }

    @Test
    void postfix_for_generatesForLoop() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "items.for", 9, "java");
        assertNotNull(r);
        assertTrue(r.insertText.contains("for ("));
        assertTrue(r.insertText.contains("items"));
    }

    @Test
    void postfix_notnull_generatesNullCheck() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "obj.notnull", 11, "java");
        assertNotNull(r);
        assertTrue(r.insertText.contains("if (obj != null)"));
    }

    @Test
    void postfix_return_generatesReturn() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "value.return", 12, "java");
        assertNotNull(r);
        assertEquals("return value;", r.insertText.trim());
    }

    @Test
    void postfix_var_generatesVariableDeclaration() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "getResult().var", 15, "java");
        assertNotNull(r);
        assertTrue(r.insertText.contains("var"));
        assertTrue(r.insertText.contains("getResult()"));
    }

    @Test
    void postfix_while_generatesWhileLoop() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "condition.while", 15, "java");
        assertNotNull(r);
        assertTrue(r.insertText.contains("while (condition)"));
    }

    @Test
    void postfix_try_generatesTryCatch() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "risky().try", 11, "java");
        assertNotNull(r);
        assertTrue(r.insertText.contains("try {"));
        assertTrue(r.insertText.contains("} catch"));
    }

    @Test
    void noPostfix_dotWithoutKeyword_returnsNull() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "obj.foo", 7, "java");
        assertNull(r);
    }

    @Test
    void noPostfix_regularCode_returnsNull() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "int x = 1;", 10, "java");
        assertNull(r);
    }

    @Test
    void postfix_kotlin_if_works() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "list.isEmpty().if", 17, "kotlin");
        assertNotNull(r);
        assertTrue(r.insertText.contains("if (list.isEmpty())"));
    }

    @Test
    void postfix_kotlin_for_works() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "items.for", 9, "kotlin");
        assertNotNull(r);
        assertTrue(r.insertText.contains("for ("));
    }

    @Test
    void postfix_python_if_works() {
        PostfixCompletion.Result r = PostfixCompletion.tryComplete(
                "x.if", 4, "python");
        assertNotNull(r);
        assertTrue(r.insertText.contains("if x"));
    }
}
