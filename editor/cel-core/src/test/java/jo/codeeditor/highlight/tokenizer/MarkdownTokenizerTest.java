package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static jo.codeeditor.highlight.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Caractérisation du tokéniseur Markdown (ADR-002, chantier C2 —
 * cliquet de démantèlement) : verrouille le comportement EXACT (bornes
 * et types de chaque span, bascule inter-lignes des blocs de code
 * clôturés, fusion des runs de texte) de
 * {@code MarkdownTokenizer.styleMarkdown} sur une batterie de lignes
 * représentatives, AVANT puis APRÈS la migration sur le moteur générique
 * {@link GenericTokenizer} (cinquième saveur : balisage en ligne). La
 * migration doit garder cette suite verte sans toucher à ses
 * assertions — c'est le critère « octet pour octet » du chantier C2.
 */
class MarkdownTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return MarkdownTokenizer.styleMarkdown(line, LexState.NORMAL).spans;
    }

    // ── Titres ───────────────────────────────────────────────────────

    @Test
    void titre_dièses() {
        assertEquals(List.of(
                new LineSpan(0, 1, KEYWORD),
                new LineSpan(1, 7, PLAIN)),
                spans("# Title"));
    }

    @Test
    void titre_dièsesDoubles() {
        assertEquals(List.of(
                new LineSpan(0, 2, KEYWORD),
                new LineSpan(2, 10, PLAIN)),
                spans("## Section"));
    }

    @Test
    void dièseMilieuDeLigne_pasUnTitre() {
        // Le # n'ouvre un titre qu'en colonne 0.
        assertEquals(List.of(
                new LineSpan(0, 2, PLAIN),
                new LineSpan(2, 10, PLAIN)),
                spans("  # pas un"));
    }

    // ── Blocs de code clôturés ───────────────────────────────────────

    @Test
    void clôture_ouverture() {
        StyledLine line = MarkdownTokenizer.styleMarkdown("```", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 3, KEYWORD)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void clôture_ouvertureAvecBlancsEtLangage() {
        StyledLine line = MarkdownTokenizer.styleMarkdown("  ```js", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 7, KEYWORD)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void clôture_corpsEnCommentaire() {
        StyledLine line = MarkdownTokenizer.styleMarkdown("int x = 5;",
                LexState.BLOCK_COMMENT);
        assertEquals(List.of(new LineSpan(0, 10, COMMENT)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void clôture_fermetureBasculeRetour() {
        // La clôture ne bascule que si la ligne COMMENCE par ``` — le
        // "```" en milieu de ligne reste du corps du bloc.
        StyledLine line = MarkdownTokenizer.styleMarkdown("```",
                LexState.BLOCK_COMMENT);
        assertEquals(List.of(new LineSpan(0, 3, KEYWORD)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
        StyledLine line2 = MarkdownTokenizer.styleMarkdown("fermeture ```",
                LexState.BLOCK_COMMENT);
        assertEquals(List.of(new LineSpan(0, 13, COMMENT)), line2.spans);
        assertEquals(LexState.BLOCK_COMMENT, line2.exitState);
    }

    // ── Emphase ──────────────────────────────────────────────────────

    @Test
    void emphase_gras() {
        assertEquals(List.of(
                new LineSpan(0, 13, EMPHASIS)),
                spans("**bold text**"));
    }

    @Test
    void emphase_italique() {
        assertEquals(List.of(
                new LineSpan(0, 8, EMPHASIS)),
                spans("*italic*"));
    }

    @Test
    void emphase_astérisqueIntraMot() {
        // « * » PEUT ouvrir à l'intérieur d'un mot (a*b*c) — « _ » non.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 4, EMPHASIS),
                new LineSpan(4, 5, PLAIN)),
                spans("a*b*c"));
    }

    @Test
    void emphase_soulignéIntraMotRefusé() {
        // a_b_c : snake_case reste du texte brut (règle historique).
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 3, PLAIN),
                new LineSpan(3, 5, PLAIN)),
                spans("a_b_c"));
    }

    @Test
    void emphase_soulignéEnDébutDeMot() {
        // _word_ : ouverture en frontière de mot → emphase.
        assertEquals(List.of(
                new LineSpan(0, 6, EMPHASIS)),
                spans("_word_"));
    }

    @Test
    void emphase_grasNonFerméRetombeEnTexte() {
        // ** sans fermante : ni gras ni emphase — le PREMIER astérisque
        // en colonne 0 est un marqueur de liste (PUNCT), le reste est
        // du texte brut (garde historique de l'ordre des règles).
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 8, PLAIN)),
                spans("**unbold"));
    }

    // ── Code en ligne et liens ───────────────────────────────────────

    @Test
    void code_enLigne() {
        assertEquals(List.of(
                new LineSpan(0, 6, STRING)),
                spans("`code`"));
    }

    @Test
    void code_backtickOrphelinFusionneLeTexte() {
        // « a` » : le run de texte d'UN caractère après un span PLAIN
        // l'ÉTEND — un seul span (garde historique du styleur).
        assertEquals(List.of(
                new LineSpan(0, 2, PLAIN)),
                spans("a`"));
    }

    @Test
    void lien_complet() {
        assertEquals(List.of(
                new LineSpan(0, 16, FUNC)),
                spans("[link](http://x)"));
    }

    // ── Listes et texte ──────────────────────────────────────────────

    @Test
    void liste_marqueur() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 6, PLAIN)),
                spans("- item"));
    }

    @Test
    void liste_marqueurAstérisque() {
        // * item : l'emphase échoue (pas de fermante) → marqueur de
        // liste en PUNCT.
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 6, PLAIN)),
                spans("* item"));
    }

    @Test
    void texte_astérisqueSansFermante() {
        // a * b : ni emphase ni liste — deux runs de texte.
        assertEquals(List.of(
                new LineSpan(0, 2, PLAIN),
                new LineSpan(2, 5, PLAIN)),
                spans("a * b"));
    }

    @Test
    void ligne_mixte() {
        assertEquals(List.of(
                new LineSpan(0, 10, PLAIN),
                new LineSpan(10, 18, EMPHASIS),
                new LineSpan(18, 23, PLAIN),
                new LineSpan(23, 29, STRING)),
                spans("text with **bold** and `code`"));
    }
}
