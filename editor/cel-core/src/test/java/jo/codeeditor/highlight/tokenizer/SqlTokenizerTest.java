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
 * Caractérisation du tokéniseur SQL (ADR-002, chantier C2 — cliquet de
 * démantèlement) : verrouille le comportement EXACT (bornes et types de
 * chaque span, état de sortie inter-lignes) de
 * {@code SqlTokenizer.styleSql} sur une batterie de lignes
 * représentatives, AVANT puis APRÈS la migration sur le moteur générique
 * {@link GenericTokenizer}. La migration doit garder cette suite verte
 * sans toucher à ses assertions — c'est le critère « octet pour octet »
 * du chantier C2.
 */
class SqlTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return SqlTokenizer.styleSql(line, LexState.NORMAL).spans;
    }

    // ── Requêtes et mots ────────────────────────────────────────────

    @Test
    void requête_filtrage() {
        assertEquals(List.of(
                new LineSpan(0, 6, KEYWORD),
                new LineSpan(7, 8, OPERATOR),
                new LineSpan(9, 13, KEYWORD),
                new LineSpan(14, 19, PLAIN)),
                spans("SELECT * FROM users"));
    }

    @Test
    void requête_motsClésMinuscules() {
        // La table SQL contient aussi les variantes minuscules.
        assertEquals(List.of(
                new LineSpan(0, 6, KEYWORD),
                new LineSpan(7, 8, OPERATOR),
                new LineSpan(9, 13, KEYWORD),
                new LineSpan(14, 15, PLAIN)),
                spans("select * from t"));
    }

    @Test
    void appel_fonction() {
        // COUNT n'est pas un mot-clé de la table : suivi de « ( » → FUNC.
        assertEquals(List.of(
                new LineSpan(0, 5, FUNC),
                new LineSpan(5, 6, PUNCT),
                new LineSpan(6, 7, OPERATOR),
                new LineSpan(7, 8, PUNCT)),
                spans("COUNT(*)"));
    }

    @Test
    void mot_majusculeInitialeEnConstante() {
        assertEquals(List.of(
                new LineSpan(0, 7, CONSTANT)),
                spans("MyTable"));
    }

    @Test
    void mot_repliPlain() {
        assertEquals(List.of(
                new LineSpan(0, 6, PLAIN)),
                spans("valeur"));
    }

    // ── Commentaires ────────────────────────────────────────────────

    @Test
    void commentaire_ligne() {
        assertEquals(List.of(
                new LineSpan(0, 14, COMMENT)),
                spans("-- commentaire"));
    }

    @Test
    void commentaire_ligneAprèsDuCode() {
        assertEquals(List.of(
                new LineSpan(0, 4, KEYWORD),
                new LineSpan(5, 16, COMMENT)),
                spans("DROP -- le reste"));
    }

    @Test
    void commentaire_blocUneLigne() {
        assertEquals(List.of(
                new LineSpan(0, 10, COMMENT),
                new LineSpan(11, 17, KEYWORD)),
                spans("/* note */ SELECT"));
    }

    @Test
    void commentaire_blocInterLignes_sortie() {
        StyledLine line = SqlTokenizer.styleSql("/* x", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 4, COMMENT)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void commentaire_continuationFermée() {
        StyledLine line = SqlTokenizer.styleSql("suite */ fin", LexState.BLOCK_COMMENT);
        assertEquals(List.of(
                new LineSpan(0, 8, COMMENT),
                new LineSpan(9, 12, PLAIN)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void commentaire_continuationNonFermée() {
        StyledLine line = SqlTokenizer.styleSql("suite sans fin", LexState.BLOCK_COMMENT);
        assertEquals(List.of(new LineSpan(0, 14, COMMENT)), line.spans);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    // ── Chaînes et identifiants quotés ──────────────────────────────

    @Test
    void chaîne_apostropheAvecÉchappementApparent() {
        // 'value\'s' : l'échappement reçoit ESCAPE.
        assertEquals(List.of(
                new LineSpan(0, 4, PLAIN),
                new LineSpan(5, 6, OPERATOR),
                new LineSpan(7, 13, STRING),
                new LineSpan(13, 15, ESCAPE),
                new LineSpan(15, 17, STRING)),
                spans("name = 'value\\'s'"));
    }

    @Test
    void chaîne_apostropheNonFermée() {
        // Pas d'état inter-lignes pour une quote orpheline en SQL.
        StyledLine line = SqlTokenizer.styleSql("x = 'ouvert", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 11, STRING)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void identifiant_guillemetsDouble() {
        // "colonne" : identifiant quoté → PROPERTY en UN SEUL span.
        assertEquals(List.of(
                new LineSpan(0, 9, PROPERTY)),
                spans("\"colonne\""));
    }

    @Test
    void identifiant_échappementAvalé() {
        // "a\"b" : l'échappement ne crée PAS de sous-span (sémantique
        // « chaîne simple ») — garde historique.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 10, PROPERTY)),
                spans("x = \"a\\\"b\""));
    }

    @Test
    void identifiant_guillemetNonFermé() {
        StyledLine line = SqlTokenizer.styleSql("\"ouvert", LexState.NORMAL);
        assertEquals(List.of(new LineSpan(0, 7, PROPERTY)), line.spans);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── Nombres ─────────────────────────────────────────────────────

    @Test
    void nombre_pointInitial() {
        // .5 : le point suivi d'un chiffre ouvre le nombre.
        assertEquals(List.of(
                new LineSpan(0, 2, NUMBER),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 7, NUMBER)),
                spans(".5 + 10"));
    }

    @Test
    void nombre_pointColléAprèsUnMot() {
        // a.5 : le point ouvre le nombre même collé au mot précédent
        // (pas de frontière en SQL).
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 3, NUMBER)),
                spans("a.5"));
    }

    @Test
    void nombre_entierEtDécimal() {
        assertEquals(List.of(
                new LineSpan(0, 3, NUMBER),
                new LineSpan(4, 8, NUMBER)),
                spans("150 2.75"));
    }

    // ── Opérateurs et ponctuation ───────────────────────────────────

    @Test
    void opérateurs_course() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 4, OPERATOR),
                new LineSpan(5, 6, PLAIN)),
                spans("a <> b"));
    }

    @Test
    void opérateur_signeMoinsSeul() {
        // Pas de moins initial en SQL : « - » est un opérateur.
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 5, NUMBER)),
                spans("x - 1"));
    }

    @Test
    void ponctuation_repli() {
        assertEquals(List.of(
                new LineSpan(0, 1, PLAIN),
                new LineSpan(1, 2, PUNCT),
                new LineSpan(3, 4, PLAIN)),
                spans("a, b"));
    }
}
