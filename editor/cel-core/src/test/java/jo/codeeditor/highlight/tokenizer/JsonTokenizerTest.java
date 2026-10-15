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
 * Caractérisation du tokéniseur JSON (ADR-002, chantier C2 — cliquet
 * de démantèlement) : verrouille le comportement EXACT (bornes et
 * types de chaque span, état de sortie inter-lignes) de
 * {@code JsonTokenizer.styleJson} sur une batterie de lignes
 * représentatives, AVANT puis APRÈS la migration sur le moteur
 * générique {@link GenericTokenizer}. La migration doit garder cette
 * suite verte sans toucher à ses assertions — c'est le critère
 * « octet pour octet » du chantier C2.
 */
class JsonTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return JsonTokenizer.styleJson(line, LexState.NORMAL).spans;
    }

    // ── Clés vs valeurs ────────────────────────────────────────────

    @Test
    void objet_simple_cléPuisValeur() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 7, PROPERTY),
                new LineSpan(7, 8, OPERATOR),
                new LineSpan(9, 16, STRING),
                new LineSpan(16, 17, PUNCT)),
                spans("{\"name\": \"value\"}"));
    }

    @Test
    void clé_espaceAvantLeDeuxPoints() {
        // Le lookahead de clé saute les blancs : "a" : reste une clé.
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 7, NUMBER)),
                spans("\"a\" : 1"));
    }

    @Test
    void chaîneDeValeur_pasUneClé() {
        // Une chaîne suivie d'une virgule (et non d'un deux-points)
        // reste une STRING.
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 4, STRING),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 9, STRING),
                new LineSpan(9, 10, PUNCT)),
                spans("[\"a\", \"b\"]"));
    }

    @Test
    void objet_imbriqué() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 4, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 7, PUNCT),
                new LineSpan(7, 10, PROPERTY),
                new LineSpan(10, 11, OPERATOR),
                new LineSpan(12, 13, PUNCT),
                new LineSpan(13, 14, NUMBER),
                new LineSpan(14, 15, OPERATOR),
                new LineSpan(16, 19, STRING),
                new LineSpan(19, 20, PUNCT),
                new LineSpan(20, 21, PUNCT),
                new LineSpan(21, 22, PUNCT)),
                spans("{\"a\": {\"b\": [1, \"c\"]}}"));
    }

    // ── Échappements ───────────────────────────────────────────────

    @Test
    void échappementSimple_dansUneValeur() {
        assertEquals(List.of(
                new LineSpan(0, 2, STRING),
                new LineSpan(2, 4, ESCAPE),
                new LineSpan(4, 6, STRING)),
                spans("\"a\\nb\""));
    }

    @Test
    void échappementUnicode_dansUneClé() {
        // La séquence unicode fait 6 caractères ; le lookahead de clé
        // ignore ce qui est DANS la chaîne, seul le caractère après la
        // fermante décide clé vs valeur.
        assertEquals(List.of(
                new LineSpan(0, 2, PROPERTY),
                new LineSpan(2, 8, ESCAPE),
                new LineSpan(8, 9, PROPERTY),
                new LineSpan(9, 10, OPERATOR),
                new LineSpan(11, 12, NUMBER)),
                spans("\"a\\u00e9\": 1"));
    }

    @Test
    void guillemetÉchappé_laChaîneContinue() {
        // \" à l'intérieur : la fermante recherchée saute les
        // échappements — la clé est [0,6) malgré le guillemet interne.
        assertEquals(List.of(
                new LineSpan(0, 2, PROPERTY),
                new LineSpan(2, 4, ESCAPE),
                new LineSpan(4, 6, PROPERTY),
                new LineSpan(6, 7, OPERATOR),
                new LineSpan(8, 9, NUMBER)),
                spans("\"a\\\"b\": 1"));
    }

    // ── Chaîne non fermée (état inter-lignes) ──────────────────────

    @Test
    void chaîneNonFermée_étatInterLignes() {
        // Sécurité historique : une chaîne non fermée se propage.
        StyledLine l = JsonTokenizer.styleJson("\"abc", LexState.NORMAL);
        assertEquals(List.of(
                new LineSpan(0, 4, STRING)), l.spans);
        assertEquals(LexState.XML_STRING, l.exitState);

        // Ligne suivante : la fermante clôt le littéral.
        StyledLine l2 = JsonTokenizer.styleJson("def\"", LexState.XML_STRING);
        assertEquals(List.of(
                new LineSpan(0, 4, STRING)), l2.spans);
        assertEquals(LexState.NORMAL, l2.exitState);

        // Toujours pas de fermante : l'état se propage.
        StyledLine l3 = JsonTokenizer.styleJson("ghi", LexState.XML_STRING);
        assertEquals(List.of(
                new LineSpan(0, 3, STRING)), l3.spans);
        assertEquals(LexState.XML_STRING, l3.exitState);

        // Ligne vide : le span vide historique est conservé tel quel.
        StyledLine l4 = JsonTokenizer.styleJson("", LexState.XML_STRING);
        assertEquals(List.of(
                new LineSpan(0, 0, STRING)), l4.spans);
        assertEquals(LexState.XML_STRING, l4.exitState);
    }

    // ── Nombres ────────────────────────────────────────────────────

    @Test
    void nombres_décimauxExposantsEtSigne() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 2, NUMBER),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 8, NUMBER),
                new LineSpan(8, 9, OPERATOR),
                new LineSpan(10, 17, NUMBER),
                new LineSpan(17, 18, PUNCT)),
                spans("[1, -2.5, 6.02e23]"));
    }

    @Test
    void nombreColléÀUnMot() {
        // Garde historique : les mots de JSON sont des lettres pures
        // (un chiffre collé à des lettres ouvre un NOMBRE), et un
        // chiffre n'exige aucune frontière de mot.
        assertEquals(List.of(
                new LineSpan(0, 3, PLAIN),
                new LineSpan(3, 6, NUMBER)),
                spans("abc123"));
        assertEquals(List.of(
                new LineSpan(0, 3, NUMBER),
                new LineSpan(3, 6, PLAIN)),
                spans("123abc"));
    }

    @Test
    void moinsNonAncréÀUnChiffre_ponctuation() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 2, PLAIN)),
                spans("-x"));
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT)),
                spans("-"));
    }

    // ── Mots-clés ──────────────────────────────────────────────────

    @Test
    void booléensEtNull_motsClés() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 5, KEYWORD),
                new LineSpan(5, 6, OPERATOR),
                new LineSpan(7, 12, KEYWORD),
                new LineSpan(12, 13, OPERATOR),
                new LineSpan(14, 18, KEYWORD),
                new LineSpan(18, 19, PUNCT)),
                spans("[true, false, null]"));
    }

    @Test
    void motInconnu_plainSansRègleDeCasse() {
        // JSON n'a NI règle d'initiale majuscule NI règle ALL_CAPS :
        // tout mot hors true/false/null est PLAIN.
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 4, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 9, PLAIN),
                new LineSpan(9, 10, PUNCT)),
                spans("{\"a\": xyz}"));
        assertEquals(List.of(
                new LineSpan(0, 3, PLAIN)),
                spans("Xyz"));
        assertEquals(List.of(
                new LineSpan(0, 3, PLAIN)),
                spans("ABC"));
    }

    // ── Opérateurs et ponctuation ──────────────────────────────────

    @Test
    void deuxPointsEtVirgule_opérateursMonoCaractère() {
        // Garde historique : ':' et ',' sont des spans SÉPARÉS même
        // adjacents — jamais fusionnés en course.
        assertEquals(List.of(
                new LineSpan(0, 1, OPERATOR),
                new LineSpan(1, 2, OPERATOR)),
                spans(":,"));
    }

    @Test
    void ponctuation_crochetsEtAccolades() {
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 2, PUNCT)),
                spans("{}"));
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT)),
                spans("["));
    }

    @Test
    void ligneVide_aucunSpanSortieNormale() {
        StyledLine l = JsonTokenizer.styleJson("", LexState.NORMAL);
        assertEquals(List.of(), l.spans);
        assertEquals(LexState.NORMAL, l.exitState);
    }
}
