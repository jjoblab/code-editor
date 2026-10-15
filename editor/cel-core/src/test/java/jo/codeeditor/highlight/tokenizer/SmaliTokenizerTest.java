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
 * Caractérisation du tokéniseur Smali (ADR-002, chantier C2 — cliquet
 * de démantèlement) : verrouille le comportement EXACT (bornes et types
 * de chaque span) de {@code SmaliTokenizer.styleSmali} sur une batterie
 * de lignes représentatives, AVANT puis APRÈS la migration sur le moteur
 * générique {@link GenericTokenizer}. La migration doit garder cette
 * suite verte sans toucher à ses assertions — c'est le critère « octet
 * pour octet » du chantier C2.
 */
class SmaliTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return SmaliTokenizer.styleSmali(line, LexState.NORMAL).spans;
    }

    // ── Directives ───────────────────────────────────────────────────

    @Test
    void directive_connue() {
        assertEquals(List.of(
                new LineSpan(0, 6, KEYWORD)),
                spans(".class"));
    }

    @Test
    void directive_inconnue() {
        // .end seul n'est pas dans la table (« .end method » l'est) →
        // ANNOTATION.
        assertEquals(List.of(
                new LineSpan(0, 4, ANNOTATION)),
                spans(".end"));
    }

    @Test
    void directive_avecDescripteur() {
        assertEquals(List.of(
                new LineSpan(0, 6, KEYWORD),
                new LineSpan(7, 13, PLAIN),
                new LineSpan(14, 40, TYPE)),
                spans(".class public Lcom/example/MainActivity;"));
    }

    // ── Commentaires ─────────────────────────────────────────────────

    @Test
    void commentaire_dièse() {
        assertEquals(List.of(
                new LineSpan(0, 9, COMMENT)),
                spans("# comment"));
    }

    // ── Chaînes ──────────────────────────────────────────────────────

    @Test
    void chaîne_avecÉchappement() {
        // const-string v0, "a\"b" — l'échappement reste apparent.
        assertEquals(List.of(
                new LineSpan(0, 12, FUNC),
                new LineSpan(13, 15, VARIABLE),
                new LineSpan(15, 16, PUNCT),
                new LineSpan(17, 19, STRING),
                new LineSpan(19, 21, ESCAPE),
                new LineSpan(21, 23, STRING)),
                spans("const-string v0, \"a\\\"b\""));
    }

    @Test
    void chaîneOrpheline_tombeDansLesRèglesSuivantes() {
        // Garde historique : une chaîne non fermée n'est PAS consommée
        // jusqu'en fin de ligne — le guillemet devient PUNCT et le
        // reste de la ligne est re-lexé comme du code.
        assertEquals(List.of(
                new LineSpan(0, 5, PLAIN),
                new LineSpan(6, 8, VARIABLE),
                new LineSpan(8, 9, PUNCT),
                new LineSpan(10, 11, PUNCT),
                new LineSpan(11, 15, PLAIN)),
                spans("const v0, \"oops"));
    }

    // ── Descripteurs de type ─────────────────────────────────────────

    @Test
    void descripteur_objet() {
        assertEquals(List.of(
                new LineSpan(0, 18, TYPE)),
                spans("Ljava/lang/String;"));
    }

    @Test
    void descripteur_tableau() {
        assertEquals(List.of(
                new LineSpan(0, 19, TYPE)),
                spans("[Ljava/lang/String;"));
    }

    @Test
    void descripteur_crochetSimpleNestPasUnDescripteur() {
        // [I : '[' non suivi de L → PUNCT, puis mot (majuscule → TYPE).
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 2, TYPE)),
                spans("[I"));
    }

    @Test
    void descripteur_sansFermante() {
        // Pas de « ; » sur la ligne : « L… » retombe dans la règle de
        // mot (ici majuscule initiale → TYPE).
        assertEquals(List.of(
                new LineSpan(0, 4, TYPE),
                new LineSpan(5, 9, PLAIN)),
                spans("Long text"));
    }

    @Test
    void descripteur_fermanteLointaineQuandMêmePrise() {
        // Garde historique : la fermante peut être à distance (moins de
        // 200 caractères) — « Long text; » devient UN SEUL TYPE.
        assertEquals(List.of(
                new LineSpan(0, 10, TYPE),
                new LineSpan(11, 15, PLAIN)),
                spans("Long text; here"));
    }

    // ── Nombres ──────────────────────────────────────────────────────

    @Test
    void nombre_hexadécimal() {
        assertEquals(List.of(
                new LineSpan(0, 4, NUMBER)),
                spans("0x1A"));
    }

    @Test
    void nombre_suffixesTLong() {
        assertEquals(List.of(
                new LineSpan(0, 2, NUMBER)),
                spans("5t"));
    }

    @Test
    void nombre_ouvertAuSigne() {
        // Garde historique Smali : « - » et « + » ouvrent TOUJOURS un
        // nombre, même seuls.
        assertEquals(List.of(
                new LineSpan(0, 2, NUMBER)),
                spans("-1"));
        assertEquals(List.of(
                new LineSpan(0, 1, NUMBER)),
                spans("-"));
        assertEquals(List.of(
                new LineSpan(0, 2, NUMBER)),
                spans("+5"));
    }

    // ── Mots : registres, opcodes ────────────────────────────────────

    @Test
    void mot_registre() {
        assertEquals(List.of(
                new LineSpan(0, 2, VARIABLE)),
                spans("v0"));
        assertEquals(List.of(
                new LineSpan(0, 2, VARIABLE)),
                spans("p1"));
    }

    @Test
    void mot_opcodeAvecTiret() {
        assertEquals(List.of(
                new LineSpan(0, 13, FUNC)),
                spans("invoke-direct"));
    }

    @Test
    void mot_cheminSeTerminantParUnSlash() {
        assertEquals(List.of(
                new LineSpan(0, 6, FUNC)),
                spans("array/"));
    }

    @Test
    void mot_majusculeInitiale() {
        assertEquals(List.of(
                new LineSpan(0, 4, TYPE)),
                spans("View"));
    }

    @Test
    void ligne_appelComplet() {
        // invoke-virtual {p0, v1}, Ljava/lang/String;->length()I
        // (version courte pour des bornes lisibles)
        assertEquals(List.of(
                new LineSpan(0, 14, FUNC),
                new LineSpan(15, 16, PUNCT),
                new LineSpan(16, 18, VARIABLE),
                new LineSpan(18, 19, PUNCT),
                new LineSpan(20, 24, TYPE)),
                spans("invoke-virtual {p0, LAb;"));
    }

    // ── Opérateurs et ponctuation ────────────────────────────────────

    @Test
    void opérateur_simple() {
        // Les opérateurs Smali ne fusionnent PAS en course (et « - »
        // est avalé par la règle de nombre de toute façon).
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(2, 3, OPERATOR)),
                spans(", ="));
    }

    @Test
    void flèche_faitsDeuxJetons() {
        // « -> » : « - » ouvre un nombre (garde historique), « > » est
        // un opérateur.
        assertEquals(List.of(
                new LineSpan(0, 1, NUMBER),
                new LineSpan(1, 2, OPERATOR)),
                spans("->"));
    }

    @Test
    void étatDeSortie_toujoursNormal() {
        StyledLine line = SmaliTokenizer.styleSmali("\"open", LexState.NORMAL);
        assertEquals(LexState.NORMAL, line.exitState, "aucun état inter-lignes en Smali");
    }
}
