package jo.codeeditor.session;

import org.junit.jupiter.api.Test;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R2 (plan 09, E2) — verrouille la borne IPC de la sélection exposée à
 * l'IME : {@link EditorSession#imeSelectedText()} ne renvoie JAMAIS plus
 * de 4 096 caractères (le plafond Binder maison, symétrique à
 * {@code imeTextBeforeCursor}), quand bien même la sélection couvre un
 * document géant — une fausse IME curieuse (recherche, dictionnaire,
 * réécriture) ne peut plus faire lever une TransactionTooLargeException.
 *
 * <p>La sélection INTERNE ({@code selectedText}) reste intacte : la
 * borne protège le pont Binder, pas les opérations d'éditeur.</p>
 *
 * @author jo@Dev
 */
public class ImeSelectedTextBoundTest {

    private static final int MAX_IPC_TEXT = 4096;

    private static EditorSession sessionOf(String text) {
        return new EditorSession(EditorDocument.of(text));
    }

    private static String big(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) ('a' + (i % 26)));
        return sb.toString();
    }

    @Test
    public void sélectionGéante_bornéeAuPlafondIPC() {
        // Document de 500 000 caractères, tout sélectionné.
        String doc = big(500_000);
        EditorSession s = sessionOf(doc);
        s.setSelection(Selection.range(0, 500_000));

        assertEquals(MAX_IPC_TEXT, s.imeSelectedText().length(),
                "la sélection IME est bornée au plafond Binder");
        assertEquals(doc.substring(0, 4), s.imeSelectedText().substring(0, 4),
                "les premiers caractères sont les bons (début de sélection)");
    }

    @Test
    public void sélectionCourte_renvoyéeIntégralement() {
        EditorSession s = sessionOf("court mais entier"); // 17 caractères
        s.setSelection(Selection.range(0, 17));
        assertEquals("court mais entier", s.imeSelectedText());
    }

    @Test
    public void sélectionHorsDocument_bornéeSansException() {
        // Une sélection dont la fin dépasse le document (état transitoire
        // d'un lot IME) : la borne document protège le substring.
        EditorSession s = sessionOf("abc");
        s.setSelection(Selection.range(0, 99));
        assertEquals("abc", s.imeSelectedText());
    }

    @Test
    public void sélectionAuMilieu_borneRelativeAuDébut() {
        // Sélection géante DÉCALÉE : la borne garde le DÉBUT de la
        // sélection (ce que l'IME voit autour du curseur), pas un
        // milieu arbitraire.
        String doc = big(500_000);
        EditorSession s = sessionOf(doc);
        s.setSelection(Selection.range(100_000, 500_000));
        String out = s.imeSelectedText();
        assertEquals(MAX_IPC_TEXT, out.length());
        assertEquals(doc.substring(100_000, 100_004), out.substring(0, 4));
    }

    @Test
    public void caretSimple_chaîneVidePasNull() {
        EditorSession s = sessionOf("abc");
        s.setSelection(1);
        assertEquals("", s.imeSelectedText());
    }

    @Test
    public void sélectionJusteAuPlafond_passante() {
        String doc = big(MAX_IPC_TEXT + 10);
        EditorSession s = sessionOf(doc);
        s.setSelection(Selection.range(0, MAX_IPC_TEXT));
        assertEquals(MAX_IPC_TEXT, s.imeSelectedText().length(),
                "une sélection d'exactement le plafond passe entière");
    }
}
