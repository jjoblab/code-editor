package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static jo.codeeditor.highlight.LineSpan.*;
import static jo.codeeditor.highlight.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Caractérisation du tokéniseur Shell/Bash (ADR-002, chantier C2 —
 * cliquet de démantèlement) : verrouille le comportement EXACT (bornes
 * et types de chaque span, état de sortie inter-lignes) de
 * {@code ShellTokenizer.styleShell} sur une batterie de lignes
 * représentatives, AVANT puis APRÈS la migration sur le moteur
 * générique {@link GenericTokenizer}. La migration doit garder cette
 * suite verte sans toucher à ses assertions — c'est le critère « octet
 * pour octet » du chantier C2.
 */
class ShellTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return ShellTokenizer.styleShell(line, LexState.NORMAL).spans;
    }

    // ── Commentaires ────────────────────────────────────────────────

    @Test
    void commentaire_ligneEntière() {
        assertEquals(List.of(
                new LineSpan(0, 19, COMMENT)),
                spans("# this is a comment"));
    }

    @Test
    void commentaire_ancréAprèsUnBlancPasDansUnMot() {
        // Le # doit être en début, après un blanc ou après ';' : pas au
        // milieu d'un mot (foo#bar n'est pas un commentaire).
        assertEquals(List.of(
                new LineSpan(0, 4, KEYWORD),
                new LineSpan(5, 10, COMMENT)),
                spans("echo # bar"));
        assertEquals(List.of(
                new LineSpan(0, 4, KEYWORD),
                new LineSpan(5, 8, PLAIN),
                new LineSpan(8, 9, PUNCT),
                new LineSpan(9, 12, PLAIN)),
                spans("echo foo#bar"));
    }

    // ── Variables ───────────────────────────────────────────────────

    @Test
    void variable_nuAprèsUnBlanc() {
        assertEquals(List.of(
                new LineSpan(0, 4, KEYWORD),
                new LineSpan(5, 10, VARIABLE)),
                spans("echo $HOME"));
    }

    @Test
    void variable_formeAccolades() {
        assertEquals(List.of(
                new LineSpan(0, 6, VARIABLE),
                new LineSpan(7, 8, PLAIN)),
                spans("${VAR} x"));
    }

    @Test
    void variable_dièseDansLesAccolades() {
        // ${#var} : le # est consommé par la forme ${...} — jamais un
        // commentaire (garde historique).
        assertEquals(List.of(
                new LineSpan(0, 7, VARIABLE)),
                spans("${#var}"));
    }

    @Test
    void variable_spécialEtPositionnel() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 2, PUNCT),
                new LineSpan(2, 4, VARIABLE)),
                spans("x=$?"));
        assertEquals(List.of(
                new LineSpan(0, 2, VARIABLE)),
                spans("$1"));
    }

    @Test
    void dollarSeul_enPonctuation() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT)),
                spans("$"));
    }

    // ── Mots ────────────────────────────────────────────────────────

    @Test
    void mot_cléDeContrôle() {
        assertEquals(List.of(
                new LineSpan(0, 2, KEYWORD)),
                spans("if"));
    }

    @Test
    void mot_majusculeInitialeEnConstante() {
        assertEquals(List.of(
                new LineSpan(0, 5, CONSTANT),
                new LineSpan(5, 6, PUNCT),
                new LineSpan(6, 7, NUMBER)),
                spans("COUNT=1"));
    }

    @Test
    void mot_suiviDeParenthèseEnFonction() {
        assertEquals(List.of(
                new LineSpan(0, 3, FUNC),
                new LineSpan(3, 5, OPERATOR),
                new LineSpan(5, 6, PUNCT),
                new LineSpan(7, 10, PLAIN),
                new LineSpan(10, 11, OPERATOR),
                new LineSpan(12, 13, PUNCT)),
                spans("foo(){ bar; }"));
    }

    @Test
    void mot_avecTiretEtOption() {
        assertEquals(List.of(
                new LineSpan(0, 2, PLAIN),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 9, PLAIN),
                new LineSpan(10, 12, PLAIN),
                new LineSpan(13, 16, PLAIN)),
                spans("ls | grep -i foo"));
    }

    // ── Chaînes ─────────────────────────────────────────────────────

    @Test
    void chaîne_guillemetsDoubles() {
        assertEquals(List.of(
                new LineSpan(0, 4, KEYWORD),
                new LineSpan(5, 18, STRING)),
                spans("echo \"hello world\""));
    }

    @Test
    void chaîne_apostrophesLittéralesSansInterpolation() {
        // $var entre apostrophes reste du texte STRING.
        assertEquals(List.of(
                new LineSpan(0, 4, KEYWORD),
                new LineSpan(5, 19, STRING)),
                spans("echo 'literal $var'"));
    }

    @Test
    void chaîne_nonFermée_étatInterLignes() {
        // Bash autorise une apostrophe non fermée à cheval sur deux
        // lignes : l'état de sortie la porte.
        StyledLine l = ShellTokenizer.styleShell("echo 'abc", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 4, KEYWORD),
                new LineSpan(5, 9, STRING)), l.spans);
        assertEquals(LexState.SHELL_SINGLE_QUOTE, l.exitState);

        // Ligne suivante : la fermante clôt le littéral, l'état
        // redevient NORMAL.
        StyledLine l2 = ShellTokenizer.styleShell("def' more",
                LexState.SHELL_SINGLE_QUOTE);
        assertEquals(List.of(
                new LineSpan(0, 4, STRING),
                new LineSpan(5, 9, PLAIN)), l2.spans);
        assertEquals(LexState.NORMAL, l2.exitState);

        // Toujours pas de fermante : l'état se propage.
        StyledLine l3 = ShellTokenizer.styleShell("xyz",
                LexState.SHELL_SINGLE_QUOTE);
        assertEquals(List.of(
                new LineSpan(0, 3, STRING)), l3.spans);
        assertEquals(LexState.SHELL_SINGLE_QUOTE, l3.exitState);
    }

    @Test
    void chaîne_échappementSautéDansLesDoubles() {
        // \" à l'intérieur : l'échappement est sauté, la chaîne
        // continue — un seul span STRING, fermante incluse.
        assertEquals(List.of(
                new LineSpan(0, 8, STRING)),
                spans("\"a\\\"b c\""));
    }

    // ── Substitution de commande ────────────────────────────────────

    @Test
    void backticks_enAnnotation() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 2, PUNCT),
                new LineSpan(2, 7, ANNOTATION)),
                spans("x=`cmd`"));
    }

    @Test
    void backtickSeul_orphelinEnPonctuation() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT)),
                spans("`"));
    }

    // ── Ligne composite ─────────────────────────────────────────────

    @Test
    void ligne_ifCrochetsGuillemetsPointVirgule() {
        assertEquals(List.of(
                new LineSpan(0, 2, KEYWORD),
                new LineSpan(3, 4, PUNCT),
                new LineSpan(5, 7, PLAIN),
                new LineSpan(8, 12, STRING),
                new LineSpan(13, 14, PUNCT),
                new LineSpan(14, 15, OPERATOR),
                new LineSpan(16, 20, KEYWORD)),
                spans("if [ -z \"$x\" ]; then"));
    }

    @Test
    void ligneVide_aucunSpanSortieNormal() {
        StyledLine l = ShellTokenizer.styleShell("", LexState.NORMAL);
        assertEquals(List.of(), l.spans);
        assertEquals(LexState.NORMAL, l.exitState);
    }
}
