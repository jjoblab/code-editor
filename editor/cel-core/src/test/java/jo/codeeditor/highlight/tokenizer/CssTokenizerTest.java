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
 * Caractérisation du tokéniseur CSS/SCSS/LESS (ADR-002, chantier C2 —
 * cliquet de démantèlement) : verrouille le comportement EXACT (bornes
 * et types de chaque span, état de sortie inter-lignes) de
 * {@code CssTokenizer.styleCss} sur une batterie de lignes
 * représentatives, AVANT puis APRÈS la migration sur le moteur générique
 * {@link GenericTokenizer}. La migration doit garder cette suite verte
 * sans toucher à ses assertions — c'est le critère « octet pour octet »
 * du chantier C2.
 */
class CssTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return CssTokenizer.styleCss(line, LexState.NORMAL).spans;
    }

    // ── Règles et déclarations ───────────────────────────────────────

    @Test
    void déclaration_simple() {
        assertEquals(List.of(
                new LineSpan(0, 6, PLAIN),
                new LineSpan(7, 8, OPERATOR),
                new LineSpan(9, 14, PROPERTY),
                new LineSpan(14, 15, OPERATOR),
                new LineSpan(16, 19, PLAIN),
                new LineSpan(19, 20, OPERATOR),
                new LineSpan(21, 22, OPERATOR)),
                spans(".class { color: red; }"));
    }

    @Test
    void règle_at() {
        // @media → KEYWORD via CSS_AT_RULES ; max-width → PROPERTY ;
        // 600px → NUMBER avec unité ; parenthèses → PUNCT (pas des
        // opérateurs CSS).
        assertEquals(List.of(
                new LineSpan(0, 6, KEYWORD),
                new LineSpan(7, 13, PLAIN),
                new LineSpan(14, 17, PLAIN),
                new LineSpan(18, 19, PUNCT),
                new LineSpan(19, 28, PROPERTY),
                new LineSpan(28, 29, OPERATOR),
                new LineSpan(30, 35, NUMBER),
                new LineSpan(35, 36, PUNCT)),
                spans("@media screen and (max-width: 600px)"));
    }

    @Test
    void règle_atInconnueEstUneVariable() {
        // @var-x : pas dans CSS_AT_RULES → VARIABLE (interpolation).
        assertEquals(List.of(
                new LineSpan(0, 6, VARIABLE)),
                spans("@var-x"));
    }

    @Test
    void variable_scss() {
        // $primary-color : le tiret reste dans la variable.
        assertEquals(List.of(
                new LineSpan(0, 14, VARIABLE),
                new LineSpan(14, 15, OPERATOR),
                new LineSpan(16, 20, NUMBER),
                new LineSpan(20, 21, OPERATOR)),
                spans("$primary-color: #333;"));
    }

    @Test
    void chaîne_valeur() {
        assertEquals(List.of(
                new LineSpan(0, 11, PROPERTY),
                new LineSpan(11, 12, OPERATOR),
                new LineSpan(13, 24, STRING),
                new LineSpan(24, 25, OPERATOR),
                new LineSpan(26, 36, PLAIN),
                new LineSpan(36, 37, OPERATOR)),
                spans("font-family: \"Helvetica\", sans-serif;"));
    }

    // ── Commentaires ─────────────────────────────────────────────────

    @Test
    void commentaire_blocUneLigne() {
        assertEquals(List.of(
                new LineSpan(0, 13, COMMENT),
                new LineSpan(14, 18, PLAIN)),
                spans("/* comment */ body"));
    }

    @Test
    void commentaire_blocInterLignes() {
        StyledLine line = CssTokenizer.styleCss("/* open", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 7, COMMENT)), line.spans);
        assertEquals(LexState.CSS_COMMENT, line.exitState);
    }

    @Test
    void commentaire_continuationFermée() {
        StyledLine line = CssTokenizer.styleCss("still */ end", LexState.CSS_COMMENT);
        assertEquals(List.of(
                new LineSpan(0, 8, COMMENT),
                new LineSpan(9, 12, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void commentaire_ligneSCSS() {
        assertEquals(List.of(
                new LineSpan(0, 15, COMMENT)),
                spans("// line comment"));
    }

    // ── Chaîne inter-lignes ──────────────────────────────────────────

    @Test
    void chaîne_nonFermée_propageLEtat() {
        StyledLine line = CssTokenizer.styleCss("content: \"unclosed", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 7, PROPERTY),
                new LineSpan(7, 8, OPERATOR),
                new LineSpan(9, 18, STRING)), line.spans);
        assertEquals(LexState.CSS_STRING, line.exitState);
    }

    @Test
    void chaîne_continuationAuGuillemet() {
        StyledLine line = CssTokenizer.styleCss("still \" end", LexState.CSS_STRING);
        assertEquals(List.of(
                new LineSpan(0, 7, STRING),
                new LineSpan(8, 11, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void chaîne_continuationÀApostropheSeulement() {
        // Pas de guillemet sur la ligne : la continuation ferme à
        // l'apostrophe (ordre de recherche historique).
        StyledLine line = CssTokenizer.styleCss("it's fine", LexState.CSS_STRING);
        assertEquals(List.of(
                new LineSpan(0, 3, STRING),
                new LineSpan(3, 4, PLAIN),
                new LineSpan(5, 9, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── Couleurs et nombres ──────────────────────────────────────────

    @Test
    void couleur_hexadécimale() {
        assertEquals(List.of(
                new LineSpan(0, 4, NUMBER)),
                spans("#333"));
        assertEquals(List.of(
                new LineSpan(0, 7, NUMBER)),
                spans("#aabbcc"));
    }

    @Test
    void couleur_mauvaiseLongueur() {
        // #GG : pas une couleur → PUNCT + mot (majuscule → TYPE).
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 3, TYPE)),
                spans("#GG"));
    }

    @Test
    void nombre_avecUnité() {
        assertEquals(List.of(
                new LineSpan(0, 4, NUMBER),
                new LineSpan(5, 10, NUMBER),
                new LineSpan(11, 15, NUMBER)),
                spans("12px 1.5em 100%"));
    }

    @Test
    void nombre_pointInitial() {
        assertEquals(List.of(
                new LineSpan(0, 2, NUMBER),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 9, NUMBER)),
                spans(".5 + 10px"));
    }

    // ── Mots ─────────────────────────────────────────────────────────

    @Test
    void mot_sélecteurMajuscule() {
        // Initiale majuscule → TYPE (sélecteur de classe).
        assertEquals(List.of(
                new LineSpan(0, 1, TYPE),
                new LineSpan(1, 2, OPERATOR),
                new LineSpan(2, 7, PLAIN)),
                spans("A:hover"));
    }

    @Test
    void mot_etchCommercialOuvreUnMot() {
        // Garde historique SCSS : « & » OUVRE un mot mais ne le
        // CONTINUE pas — a&b est DEUX mots.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 3, PLAIN)),
                spans("a&b"));
    }

    @Test
    void important_pointExclamation() {
        assertEquals(List.of(
                new LineSpan(0, 1, OPERATOR),
                new LineSpan(1, 10, PLAIN)),
                spans("!important"));
    }

    // ── Opérateurs ───────────────────────────────────────────────────

    @Test
    void opérateurs_jamaisFusionnés() {
        // Les opérateurs CSS sont mono-caractère — pas de course.
        assertEquals(List.of(
                new LineSpan(0, 1, OPERATOR),
                new LineSpan(2, 3, OPERATOR)),
                spans(": ,"));
    }
}
