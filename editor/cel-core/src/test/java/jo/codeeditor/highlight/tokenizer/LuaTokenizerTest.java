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
 * Caractérisation du tokéniseur Lua (ADR-002, chantier C2 — cliquet de
 * démantèlement) : verrouille le comportement EXACT (bornes et types de
 * chaque span, état de sortie inter-lignes) de
 * {@code LuaTokenizer.styleLua} sur une batterie de lignes
 * représentatives, AVANT puis APRÈS la migration sur le moteur générique
 * {@link GenericTokenizer}. La migration doit garder cette suite verte
 * sans toucher à ses assertions — c'est le critère « octet pour octet »
 * du chantier C2.
 */
class LuaTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return LuaTokenizer.styleLua(line, LexState.NORMAL).spans;
    }

    private static List<LineSpan> spans(String line, int entryState) {
        return LuaTokenizer.styleLua(line, entryState).spans;
    }

    // ── Commentaires ────────────────────────────────────────────────

    @Test
    void commentaire_ligne() {
        assertEquals(List.of(
                new LineSpan(0, 17, COMMENT)),
                spans("-- simple comment"));
    }

    @Test
    void commentaire_ligneAprèsDuCode() {
        // a -- b : le « -- » en milieu de ligne ouvre aussi un commentaire.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 6, COMMENT)),
                spans("a -- b"));
    }

    @Test
    void commentaire_blocSurUneLigne() {
        // --[[ ... ]] fermé sur place : un seul span COMMENT.
        assertEquals(List.of(
                new LineSpan(0, 21, COMMENT)),
                spans("--[[ block comment ]]"));
    }

    @Test
    void commentaire_blocFermeAprèsDuCode() {
        assertEquals(List.of(
                new LineSpan(0, 21, COMMENT),
                new LineSpan(22, 27, KEYWORD),
                new LineSpan(28, 29, OPERATOR),
                new LineSpan(30, 31, NUMBER)),
                spans("--[[ block comment ]] local = 1"));
    }

    @Test
    void commentaire_blocVide() {
        // --[[]] : la fermante ]] est trouvée dès pos+4 → tout est COMMENT.
        assertEquals(List.of(
                new LineSpan(0, 6, COMMENT)),
                spans("--[[]]"));
    }

    @Test
    void commentaire_blocNonFermé_propageLEtat() {
        StyledLine line = LuaTokenizer.styleLua("--[[ never closed", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 17, COMMENT)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void commentaire_blocOuvertEnFinDeLigne() {
        // « --[[ » exactement : pos+3 existe → le bloc s'ouvre.
        StyledLine line = LuaTokenizer.styleLua("--[[", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 4, COMMENT)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void commentaire_deuxCrochetsSansBloc() {
        // --[ x : deux crochets absents → simple commentaire de ligne.
        assertEquals(List.of(
                new LineSpan(0, 5, COMMENT)),
                spans("--[ x"));
    }

    @Test
    void commentaire_continuationInterLignes() {
        // Ligne de continuation d'un --[[ ouvert : COMMENT jusqu'à ]].
        StyledLine line = LuaTokenizer.styleLua("still comment ]] and code",
                LexState.BLOCK_COMMENT);
        assertEquals(List.of(
                new LineSpan(0, 16, COMMENT),
                new LineSpan(17, 20, KEYWORD),
                new LineSpan(21, 25, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void commentaire_continuationSansFermante() {
        StyledLine line = LuaTokenizer.styleLua("just text", LexState.BLOCK_COMMENT);
        assertEquals(List.of(new LineSpan(0, 9, COMMENT)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    // ── Chaînes longues [[ ]] ───────────────────────────────────────

    @Test
    void chaîneLongue_surUneLigne() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 19, STRING)),
                spans("s = [[long string]]"));
    }

    @Test
    void chaîneLongue_nonFermée_propageLEtatRéutilisé() {
        // Une [[ non fermée porte le même état BLOCK_COMMENT qu'un --[[ :
        // garde historique de l'implémentation maison.
        StyledLine line = LuaTokenizer.styleLua("x = [[open", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 10, STRING)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void chaîneLongue_continuationEnCommentaire() {
        // Garde historique : la continuation d'une [[ non fermée est
        // colorée COMMENT (le gestionnaire d'état ne distingue pas les
        // deux ouvertures) — verrouillé tel quel.
        StyledLine line = LuaTokenizer.styleLua("more text]]", LexState.BLOCK_COMMENT);
        assertEquals(List.of(new LineSpan(0, 11, COMMENT)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── Chaînes quotées ─────────────────────────────────────────────

    @Test
    void chaîne_guillemetsDoubles() {
        assertEquals(List.of(
                new LineSpan(0, 3, PLAIN),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 13, STRING)),
                spans("msg = \"hello\""));
    }

    @Test
    void chaîne_échappementApparent() {
        // L'échappement \n reçoit ESCAPE au milieu de la chaîne.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 10, STRING),
                new LineSpan(10, 12, ESCAPE),
                new LineSpan(12, 18, STRING)),
                spans("s = \"hello\\nworld\""));
    }

    @Test
    void chaîne_apostrophes() {
        assertEquals(List.of(
                new LineSpan(0, 8, STRING)),
                spans("'single'"));
    }

    @Test
    void chaîne_nonFermée_sansEtat() {
        // Une quote orpheline s'étend jusqu'en fin de ligne SANS état
        // inter-lignes (contrairement au Shell).
        StyledLine line = LuaTokenizer.styleLua("s = \"open", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 9, STRING)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── Nombres ─────────────────────────────────────────────────────

    @Test
    void nombre_hexadécimal() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 8, NUMBER)),
                spans("x = 0xFF"));
    }

    @Test
    void nombre_décimal() {
        assertEquals(List.of(
                new LineSpan(0, 7, NUMBER)),
                spans("3.14159"));
    }

    // ── Mots ────────────────────────────────────────────────────────

    @Test
    void mot_clé() {
        assertEquals(List.of(
                new LineSpan(0, 5, KEYWORD)),
                spans("local"));
    }

    @Test
    void mot_fonctionIntégrée() {
        // Les builtins Lua reçoivent TYPE.
        assertEquals(List.of(
                new LineSpan(0, 5, TYPE),
                new LineSpan(5, 6, PUNCT),
                new LineSpan(6, 7, PLAIN),
                new LineSpan(7, 8, PUNCT)),
                spans("print(x)"));
    }

    @Test
    void mot_toutEnMajuscules() {
        assertEquals(List.of(
                new LineSpan(0, 9, CONSTANT),
                new LineSpan(10, 11, OPERATOR),
                new LineSpan(12, 14, NUMBER)),
                spans("MAX_VALUE = 10"));
    }

    @Test
    void mot_appelDeFonction() {
        assertEquals(List.of(
                new LineSpan(0, 3, FUNC),
                new LineSpan(3, 4, PUNCT),
                new LineSpan(4, 5, PLAIN),
                new LineSpan(5, 6, PUNCT)),
                spans("foo(x)"));
    }

    @Test
    void mot_repliPlain() {
        assertEquals(List.of(
                new LineSpan(0, 5, PLAIN)),
                spans("value"));
    }

    // ── Opérateurs et ponctuation ───────────────────────────────────

    @Test
    void opérateurs_course() {
        // ~= : les opérateurs consécutifs forment un seul span.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 5, PLAIN),
                new LineSpan(6, 8, OPERATOR),
                new LineSpan(9, 10, PLAIN)),
                spans("a val ~= b"));
    }

    @Test
    void ponctuation_repli() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 2, PUNCT),
                new LineSpan(2, 3, PLAIN)),
                spans("a.b"));
    }
}
