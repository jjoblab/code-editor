package jo.codeeditor.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import jo.codeeditor.rope.Rope;

/**
 * Équivalence du port feuille par feuille de {@link BracketPairs#docBalance}
 * (amont c4bec0cf7) : le comptage via Rope (visite des feuilles, accès
 * direct au String de chaque feuille) donne EXACTEMENT le résultat du
 * scan CharSequence générique (charAt par caractère).
 */
public class BracketPairsVisitLeavesTest {

    private static final String TEXT = buildText();

    private static String buildText() {
        StringBuilder sb = new StringBuilder();
        // 12 000 caractères : crochets équilibrés + imbriqués, plusieurs feuilles.
        for (int i = 0; i < 500; i++) {
            sb.append("if (a[i] < b[(j) + 1]) { xs.add(v); } // ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void leComptageRopeEgaleLeComptageString() {
        Rope rope = Rope.fromString(TEXT);
        // Parenthèses.
        assertEquals(BracketPairs.docBalance(TEXT, '(', ')'),
                BracketPairs.docBalance(rope, '(', ')'),
                "Balance '(' : rope == String");
        // Crochets.
        assertEquals(BracketPairs.docBalance(TEXT, '[', ']'),
                BracketPairs.docBalance(rope, '[', ']'),
                "Balance '[' : rope == String");
        // Accolades.
        assertEquals(BracketPairs.docBalance(TEXT, '{', '}'),
                BracketPairs.docBalance(rope, '{', '}'),
                "Balance '{' : rope == String");
    }

    @Test
    public void leComptageResteExactSurTexteDesEquilibre() {
        // 500 '(' sans fermeurs : le déséquilibre doit être identique.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append("f(g(h(");
        String unbalanced = sb.toString();
        Rope rope = Rope.fromString(unbalanced);
        assertEquals(BracketPairs.docBalance(unbalanced, '(', ')'),
                BracketPairs.docBalance(rope, '(', ')'),
                "Déséquilibre '(' identique rope/String");
        assertEquals(1500, BracketPairs.docBalance(rope, '(', ')'),
                "500 ouvreurs sans fermeurs");
    }
}
