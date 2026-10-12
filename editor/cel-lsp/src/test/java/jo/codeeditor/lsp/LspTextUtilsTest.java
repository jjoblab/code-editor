package jo.codeeditor.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversion Position LSP → offset et application d'éditions sur un
 * contenu texte hors éditeur ({@link LspTextUtils}) — le cœur du chemin
 * « éditions de fichiers tiers ».
 *
 * <p>Sémantique LSP épingle : la colonne compte les unités de code
 * UTF-16 (un caractère hors BMP vaut DEUX colonnes), la fin de ligne se
 * mesure avant le {@code \n} (le {@code \r} d'une fin CRLF appartient à
 * la ligne), et les positions au-delà de la fin sont bornées.</p>
 */
class LspTextUtilsTest {

    @Test
    void positionToOffset_ligneSimple() {
        assertEquals(0, LspTextUtils.positionToOffset("abcdef", pos(0, 0)));
        assertEquals(3, LspTextUtils.positionToOffset("abcdef", pos(0, 3)));
        // Colonne au-delà de la fin → bornée à la fin de ligne.
        assertEquals(6, LspTextUtils.positionToOffset("abcdef", pos(0, 99)));
    }

    @Test
    void positionToOffset_multilignes() {
        String text = "ab\ncdef\ngh";
        assertEquals(3, LspTextUtils.positionToOffset(text, pos(1, 0)));
        assertEquals(5, LspTextUtils.positionToOffset(text, pos(1, 2)));
        // Dernière ligne sans \n final.
        assertEquals(9, LspTextUtils.positionToOffset(text, pos(2, 1)));
        // Ligne au-delà de la fin → -1 (position incohérente).
        assertEquals(-1, LspTextUtils.positionToOffset(text, pos(9, 0)));
    }

    @Test
    void positionToOffset_utf16CompteLesSurrogates() {
        // « 😀 » est hors BMP : 2 unités de code UTF-16 = 2 colonnes LSP.
        String text = "a😀b";
        assertEquals(1, LspTextUtils.positionToOffset(text, pos(0, 1)));
        // Après l'emoji : colonne 3 (1 + 2 unités de code).
        assertEquals(3, LspTextUtils.positionToOffset(text, pos(0, 3)));
    }

    @Test
    void positionToOffset_crlf() {
        String text = "un\r\ndeux\r\ntrois";
        // La fin de la ligne 0 est l'index du \n (le \r est DANS la ligne).
        assertEquals(3, LspTextUtils.positionToOffset(text, pos(0, 99)));
        assertEquals(4, LspTextUtils.positionToOffset(text, pos(1, 0)));
        assertEquals(9, LspTextUtils.positionToOffset(text, pos(1, 5)));
        assertEquals(10, LspTextUtils.positionToOffset(text, pos(2, 0)));
    }

    @Test
    void applyEdits_uneInsertion() {
        assertEquals("aXYZb",
                LspTextUtils.applyEdits("ab", edits(edit(1, 1, "XYZ"))));
    }

    @Test
    void applyEdits_plusieursEditsTrieesDecroissant() {
        String text = "abcdefghij";
        List<TextEdit> es = edits(
                edit(0, 1, "X"),     // remplace 'a'
                edit(3, 5, "YY"),    // remplace "de"
                edit(8, 9, "Z"));    // remplace 'i'
        assertEquals("XbcYYfghZj", LspTextUtils.applyEdits(text, es));
    }

    @Test
    void applyEdits_editionsChevauchantesRejetees() {
        // Le protocole LSP interdit les éditions qui se chevauchent :
        // refus net (null) plutôt qu'un résultat incohérent.
        List<TextEdit> es = edits(
                edit(0, 4, "X"),
                edit(2, 6, "Y"));
        assertNull(LspTextUtils.applyEdits("0123456789", es));
    }

    @Test
    void applyEdits_colonneAuDelaDeLaFinEstBornee() {
        // Spec LSP 3.17 : « If the character value is greater than the
        // line length it defaults back to the line length » — clamp,
        // pas de rejet.
        assertEquals("X", LspTextUtils.applyEdits("abc", edits(edit(0, 99, "X"))));
    }

    @Test
    void applyEdits_ligneInexistanteRejetee() {
        // Ligne au-delà de la fin du texte : position incohérente → rejet.
        List<TextEdit> es = edits(new TextEdit(
                new Range(pos(9, 0), pos(9, 1)), "X"));
        assertNull(LspTextUtils.applyEdits("abc", es));
    }

    @Test
    void applyEdits_finAvantDebutRejetee() {
        assertNull(LspTextUtils.applyEdits("abc", edits(edit(2, 1, "X"))));
    }

    @Test
    void applyEdits_nullVideEtListeSansTexteNull() {
        assertNull(LspTextUtils.applyEdits(null, edits(edit(0, 1, "X"))));
        assertEquals("abc", LspTextUtils.applyEdits("abc", null));
        assertEquals("abc", LspTextUtils.applyEdits("abc", new ArrayList<>()));
        // NB : LSP4J interdit un newText null à la construction ; la
        // garde « getNewText() == null → "" » de LspTextUtils reste
        // défensive pour les désérialisations exotiques.
    }

    // ── Fabriques ──────────────────────────────────────────────────────

    private static Position pos(int line, int character) {
        return new Position(line, character);
    }

    private static TextEdit edit(int start, int end, String newText) {
        return new TextEdit(new Range(pos(0, start), pos(0, end)), newText);
    }

    private static List<TextEdit> edits(TextEdit... es) {
        return new ArrayList<>(Arrays.asList(es));
    }
}
