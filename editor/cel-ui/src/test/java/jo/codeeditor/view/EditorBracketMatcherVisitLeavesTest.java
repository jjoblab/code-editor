package jo.codeeditor.view;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import jo.codeeditor.rope.Rope;

/**
 * Équivalence du port feuille par feuille de
 * {@link EditorBracketMatcher#matchingBracket} (amont c4bec0cf7) : les
 * scans AVANT et ARRIÈRE sur une Rope (visite des feuilles, accès direct
 * au String de chaque feuille, arrêt au 1er appariement) donnent
 * EXACTEMENT les mêmes paires que le scan CharSequence générique
 * (charAt par caractère).
 */
public class EditorBracketMatcherVisitLeavesTest {

    @Test
    public void leScanAvantSurRopeEgaleLeScanString() {
        // Appariement avant : caret sur la fermante de l'accolade du bloc.
        String text = "class A { void f() { if (x[0]) { g(); } } }";
        Rope rope = Rope.fromString(text);
        int closeIdx = text.lastIndexOf('}');
        assertArrayEquals("Paire '}' : rope == String",
                EditorBracketMatcher.matchingBracket(text, closeIdx),
                EditorBracketMatcher.matchingBracket(rope, closeIdx));
        int parenIdx = text.indexOf('(');
        int parenClose = text.indexOf(')', parenIdx);
        assertArrayEquals("Paire '(' : rope == String",
                EditorBracketMatcher.matchingBracket(text, parenClose),
                EditorBracketMatcher.matchingBracket(rope, parenClose));
    }

    @Test
    public void leScanArriereSurRopeEgaleLeScanString() {
        // Appariement arrière : caret SUR l'ouvrante → fermante correspondante.
        String text = "a = f(g(h(1) + 2), 3) + b[ (c) ];";
        Rope rope = Rope.fromString(text);
        for (int probe = 0; probe < text.length(); probe++) {
            char ch = text.charAt(probe);
            if (ch != '(' && ch != '[' && ch != ')' && ch != ']') continue;
            assertArrayEquals("Sonde " + probe + " ('" + ch + "') : rope == String",
                    EditorBracketMatcher.matchingBracket(text, probe),
                    EditorBracketMatcher.matchingBracket(rope, probe));
        }
    }

    @Test
    public void lesFeuillesMultiplesNeCassentPasLAppariement() {
        // Texte > 512 chars (plusieurs feuilles) avec des paires à cheval.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) sb.append("x = compute(arr[(").append(i).append(")]);\n");
        String text = sb.toString();
        Rope rope = Rope.fromString(text);
        assertTrue("La rope est bien ramifiée", text.length() > Rope.MAX_LEAF);
        int lastClose = text.lastIndexOf(')');
        int[] expected = EditorBracketMatcher.matchingBracket(text, lastClose);
        int[] actual = EditorBracketMatcher.matchingBracket(rope, lastClose);
        assertNotNull("La paire doit être trouvée dans la rope", actual);
        assertArrayEquals("Paire à cheval sur feuilles : rope == String",
                expected, actual);
        int opener = text.indexOf('(');
        int[] backExpected = EditorBracketMatcher.matchingBracket(text, opener);
        int[] backActual = EditorBracketMatcher.matchingBracket(rope, opener);
        assertNotNull("L'appariement arrière doit trouver la fermante", backActual);
        assertArrayEquals("Appariement arrière à cheval : rope == String",
                backExpected, backActual);
    }

    @Test
    public void aucunAppariementRendNullDansLesDeuxCas() {
        String text = "x = ((1 + 2);";
        Rope rope = Rope.fromString(text);
        // La parenthèse INTÉRIEURE (5–11) est appariée : le matcher la trouve.
        int innerClose = text.indexOf(')');
        assertArrayEquals("Paire intérieure trouvée identiquement",
                EditorBracketMatcher.matchingBracket(text, innerClose),
                EditorBracketMatcher.matchingBracket(rope, innerClose));
        // L'ouvrante EXTÉRIEURE (index 4 = début de "((") n'a PAS de fermante.
        int unmatched = text.indexOf("(("); // probe sur '(' extérieur
        assertNull("Ouvrante non appariée → null",
                EditorBracketMatcher.matchingBracket(rope, unmatched));
        assertEquals("Null identique rope/String",
                EditorBracketMatcher.matchingBracket(text, unmatched),
                EditorBracketMatcher.matchingBracket(rope, unmatched));
        // Sonde hors crochets (caret 0 = 'x') : null.
        assertNull("Pas de crochet sondé → null",
                EditorBracketMatcher.matchingBracket(rope, 0));
        assertEquals("Rope sans paire == String sans paire",
                EditorBracketMatcher.matchingBracket(text, 0),
                EditorBracketMatcher.matchingBracket(rope, 0));
        // Texte sans crochets du tout.
        String plain = "juste du texte sans aucune parenthese";
        assertNull("Pas de crochet sondé → null",
                EditorBracketMatcher.matchingBracket(Rope.fromString(plain), 5));
        assertEquals("Rope sans paire == String sans paire",
                EditorBracketMatcher.matchingBracket(plain, 5),
                EditorBracketMatcher.matchingBracket(Rope.fromString(plain), 5));
    }

    @Test
    public void laSondeBorneeSArreteAuPlafondDeScan() {
        // Une parenthèse non appariée au-delà du plafond ne fait pas
        // déborder le scan : même résultat borné rope/String.
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < 3000; i++) sb.append('x');
        String text = sb.toString();
        Rope rope = Rope.fromString(text);
        assertEquals("Aucun fermant dans la fenêtre → null (rope == String)",
                EditorBracketMatcher.matchingBracket(text, 0),
                EditorBracketMatcher.matchingBracket(rope, 0));
        assertNull("Null dans les deux représentations",
                EditorBracketMatcher.matchingBracket(rope, 0));
    }
}
