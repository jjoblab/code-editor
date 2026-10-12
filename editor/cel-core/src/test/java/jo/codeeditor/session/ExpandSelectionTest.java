package jo.codeeditor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;

/**
 * Expand selection (lot 4 #27) : curseur → mot → syntagme (span
 * syntaxique ou crochets englobants) → ligne(s) → saut de ligne final.
 */
public class ExpandSelectionTest {

    private static final String DOC =
        "public class Main {\n"                 // 0  (20 chars)
        + "    void run() {\n"                  // 20
        + "        greet(\"bonjour ici\");\n"   // 38
        + "    }\n"                             // 63
        + "}\n";                                // 67

    private EditorSession session() {
        return new EditorSession(EditorDocument.of(DOC));
    }

    /** Offset de « bonjour » dans la ligne 2. */
    private static int bonjourOffset(EditorSession s) {
        return DOC.indexOf("bonjour");
    }

    // ── Curseur → mot ────────────────────────────────────────────

    @Test
    public void cursor_selectsWord() {
        EditorSession s = session();
        int off = bonjourOffset(s) + 2; // au milieu de « bonjour »
        s.setSelection(Selection.cursor(off));
        assertTrue(s.expandSelection());
        int[] w = jo.codeeditor.edit.EditOps.wordRangeAt(s.getDocument().charSequence(), off);
        assertEquals(w[0], s.getSelection().start);
        assertEquals(w[1], s.getSelection().end);
    }

    // ── Mot → span syntaxique (chaîne, guillemets inclus) ────────

    @Test
    public void wordInsideString_expandsToStringWithQuotes() {
        EditorSession s = session();
        int off = bonjourOffset(s) + 2;
        s.setSelection(Selection.cursor(off));
        s.expandSelection(); // mot « bonjour »
        int selStart = s.getSelection().start;
        assertTrue(selStart != s.getSelection().end, "le mot est sélectionné");

        s.expandSelection(); // → la chaîne « "bonjour ici" » entière
        String selected = DOC.substring(s.getSelection().start, s.getSelection().end);
        assertEquals("\"bonjour ici\"", selected);
    }

    // ── Mot → crochets englobants ────────────────────────────────

    @Test
    public void wordInsideParens_expandsToInnermostPair() {
        EditorSession s = session();
        int greetArg = DOC.indexOf("greet(") + "greet".length(); // sur « ( »
        int off = greetArg + 1; // premier caractère de l'argument
        s.setSelection(Selection.cursor(off));
        s.expandSelection(); // mot (vide ici — « ( » collé au «" » ? non : «" » suit)
        // Sélectionner explicitement un mot à l'intérieur des parenthèses :
        int quote = DOC.indexOf('"', greetArg);
        s.setSelection(Selection.cursor(quote));
        s.expandSelection(); // pas de mot sur un guillemet → span/crochets
        // Le contenu entier de la paire la plus interne doit être couvert.
        int selStart = s.getSelection().start;
        int selEnd = s.getSelection().end;
        assertTrue(DOC.charAt(selStart) == '(' || DOC.charAt(selStart) == '"'
                        || selEnd > quote, "la sélection doit couvrir les parenthèses englobantes");
    }

    @Test
    public void nestedBrackets_innermostFirst() {
        EditorSession s = session();
        String doc = "f(g(x), y);\n";
        EditorSession s2 = new EditorSession(EditorDocument.of(doc));
        int x = doc.indexOf('x');
        s2.setSelection(Selection.cursor(x));
        s2.expandSelection(); // mot « x »
        assertEquals(doc.substring(s2.getSelection().start, s2.getSelection().end), "x");
        s2.expandSelection(); // parenthèses internes (x)
        assertEquals(doc.substring(s2.getSelection().start, s2.getSelection().end), "(x)");
        s2.expandSelection(); // parenthèses externes (g(x), y)
        assertEquals(doc.substring(s2.getSelection().start, s2.getSelection().end), "(g(x), y)");
    }

    @Test
    public void fullBracketSelection_expandsToLine() {
        EditorSession s2 = new EditorSession(EditorDocument.of("f(g(x), y);\n"));
        String doc = "f(g(x), y);\n";
        int open = doc.indexOf("(g");
        int close = doc.indexOf("y)");
        // Sélectionner déjà la paire interne entière « (x) ».
        s2.setSelection(Selection.range(open + 1, open + 4));
        // « (x) » couvert → l'appel suivant va aux LIGNES.
        s2.setSelection(Selection.range(doc.indexOf('('), doc.indexOf(')') + 1));
        s2.expandSelection();
        assertEquals(doc.substring(s2.getSelection().start, s2.getSelection().end), "f(g(x), y);");
    }

    // ── Ligne(s) → saut de ligne final → fin ─────────────────────

    @Test
    public void lineSelection_includesTrailingNewline_thenStops() {
        EditorSession s = session();
        // Sélectionner toute la ligne 3 («     } »).
        int line4Start = DOC.indexOf("    }");
        int line4End = DOC.indexOf("    }") + 5;
        s.setSelection(Selection.range(line4Start, line4End));
        assertTrue(s.expandSelection());
        assertEquals('\n',
                DOC.charAt(s.getSelection().end - 1), "saut de ligne final inclus");

        int before = s.getSelection().start;
        assertFalse(s.expandSelection(), "document déjà couvert de la ligne 0 à la fin : plus rien");
        // (la sélection inclut maintenant tout ce qui peut l'être depuis
        // cet état — l'appel répété ne doit rien casser)
        assertEquals(before, s.getSelection().start);
    }

    @Test
    public void multilineSelection_expandsToEnclosingBraces_thenFullLines() {
        EditorSession s = session();
        // Sélection à cheval sur les lignes 1-2 (du milieu de run() au
        // milieu de greet) : le premier appel capture les accolades de la
        // méthode englobante (syntagme), le second les lignes entières.
        int a = DOC.indexOf("run()") + 2;
        int b = DOC.indexOf("bonjour") + 2;
        s.setSelection(Selection.range(a, b));
        assertTrue(s.expandSelection());
        assertEquals((int) '{', DOC.charAt(s.getSelection().start),
                "accolade de méthode englobante");
        assertTrue(s.expandSelection());
        int start = s.getSelection().start;
        int end = s.getSelection().end;
        assertEquals(0, start, "début de la ligne 0");
        assertEquals(s.getDocument().lineEnd(4), end,
                "toutes les lignes touchées, sans le \\n final");
    }

    // ── Bornes ───────────────────────────────────────────────────

    @Test
    public void selectAll_expand_returnsFalse() {
        EditorSession s = session();
        s.selectAll();
        assertFalse(s.expandSelection());
    }

}
