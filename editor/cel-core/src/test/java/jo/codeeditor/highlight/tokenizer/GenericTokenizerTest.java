package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (chantier C2, étape 1 — fondation) — le tokéniseur
 * GÉNÉRIQUE piloté par un {@link TokenizerTemplate} déclaratif : la
 * grammaire d'une famille cesse d'être du code Java dédié, elle
 * devient une table de règles (préfixes de commentaire, séparateurs
 * clé/valeur, types, échappements). Première migration du cliquet :
 * Properties (voir {@code PropertiesTokenizer}) ; les familles
 * suivantes migreront tokéniseur par tokéniseur, chacune gardée
 * verte par sa suite existante.
 *
 * <p>Ce test démontre le critère C2 sur un template MAISON (un
 * format ini-like n'ayant jamais eu de tokéniseur dédié) : une
 * grammaire clé=valeur complète sans une ligne de moteur.</p>
 */
class GenericTokenizerTest {

    /** Template maison : ini-like à points-virgules. */
    private static final TokenizerTemplate INI = TokenizerTemplate.builder()
            .lineComments(";", "#")
            .keyValueSeparators('=', ':')
            .keyType(TokenType.TYPE)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.RAW_STRING)
            .escapesInValue(true)
            .build();

    /** Le template Properties intégré (celui de la migration du cliquet). */
    private static final TokenizerTemplate PROPERTIES =
            TokenizerTemplate.PROPERTIES;

    private static TokenType typeAt(StyledLine l, int col) {
        return l.spans.stream().filter(s -> col >= s.startCol && col < s.endCol)
                .map(s -> s.type).findFirst()
                .orElseThrow(() -> new AssertionError("aucun span en col " + col + " : " + l.spans));
    }

    // ── Template maison : grammaire complète sans Java dédié ────

    @Test
    void iniMaison_commentairePointVirgule() {
        StyledLine l = GenericTokenizer.styleLine(INI, "; a comment", LexState.NORMAL);
        assertEquals(1, l.spans.size());
        assertEquals(TokenType.COMMENT, l.spans.get(0).type);
    }

    @Test
    void iniMaison_cléValeurAvecTypesDéclarés() {
        StyledLine l = GenericTokenizer.styleLine(INI, "section=value", LexState.NORMAL);
        assertEquals(TokenType.TYPE, typeAt(l, 0), "clé → type déclaré (TYPE)");
        assertEquals(TokenType.OPERATOR, typeAt(l, 7), "séparateur → OPERATOR");
        assertEquals(TokenType.RAW_STRING, typeAt(l, 8), "valeur → type déclaré (RAW_STRING)");
    }

    @Test
    void iniMaison_échappementEnValeur() {
        StyledLine l = GenericTokenizer.styleLine(INI, "k=a\\u0041b", LexState.NORMAL);
        assertEquals(TokenType.ESCAPE, typeAt(l, 3), "backslash-uXXXX → ESCAPE");
        assertEquals(TokenType.RAW_STRING, typeAt(l, 9), "après l'échappement, la valeur continue");
    }

    @Test
    void iniMaison_pasDeCommentaireAprèsLeSéparateur() {
        // Le # en VALEUR n'est pas un commentaire (comportement
        // Properties historique, paramétré).
        StyledLine l = GenericTokenizer.styleLine(INI, "k=a # b", LexState.NORMAL);
        assertEquals(TokenType.RAW_STRING, typeAt(l, 3));
    }

    // ── Template Properties : la migration du cliquet est exacte ──

    @Test
    void properties_commentaireDièseEtPointExclamation() {
        StyledLine l1 = GenericTokenizer.styleLine(PROPERTIES, "# a comment", LexState.NORMAL);
        assertEquals(TokenType.COMMENT, l1.spans.get(0).type);
        StyledLine l2 = GenericTokenizer.styleLine(PROPERTIES, "! autre", LexState.NORMAL);
        assertEquals(TokenType.COMMENT, l2.spans.get(0).type);
    }

    @Test
    void properties_cléValeur() {
        StyledLine l = GenericTokenizer.styleLine(PROPERTIES, "foo.bar=value", LexState.NORMAL);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0));
        assertEquals(TokenType.OPERATOR, typeAt(l, 7));
        assertEquals(TokenType.STRING, typeAt(l, 8));
    }

    @Test
    void properties_cléAvecDeuxPoints() {
        StyledLine l = GenericTokenizer.styleLine(PROPERTIES, "key:val", LexState.NORMAL);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0));
        assertEquals(TokenType.STRING, typeAt(l, 4));
    }

    @Test
    void properties_étatDeSortieToujoursNormal() {
        StyledLine l = GenericTokenizer.styleLine(PROPERTIES, "a=b", 0);
        assertEquals(LexState.NORMAL, l.exitState, "la grammaire properties n'a pas d'état inter-lignes");
        assertTrue(l.entryState == 0);
    }
}
