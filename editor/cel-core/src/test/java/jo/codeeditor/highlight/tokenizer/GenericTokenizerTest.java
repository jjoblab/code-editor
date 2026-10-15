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

    /**
     * Template maison (C2-é2) : format de configuration à sections
     * [table] et valeurs à jetons (chaînes quotées, nombres, mots-clés),
     * commentaires # partout — grammaire de la famille TOML sans une
     * ligne de moteur dédié.
     */
    private static final TokenizerTemplate CONF = TokenizerTemplate.builder()
            .lineComments("#")
            .commentsAnywhere(true)
            .sections('[', ']', TokenType.TYPE, TokenType.PUNCT)
            .keyValueSeparators('=')
            .keyType(TokenType.PROPERTY)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.STRING)
            .valueQuotes('"', '\'')
            .valueNumberChars('.', '-', ':', 'T', 'Z')
            .valueKeywords(java.util.Set.of("oui", "non"))
            .valueKeywordType(TokenType.CONSTANT)
            .build();

    /** Le template Properties intégré (celui de la migration du cliquet). */
    private static final TokenizerTemplate PROPERTIES =
            TokenizerTemplate.PROPERTIES;

    /** Le template TOML intégré (deuxième migration du cliquet). */
    private static final TokenizerTemplate TOML =
            TokenizerTemplate.TOML;

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

    // ── C2-é2 : sections, chaînes, nombres, mots-clés de valeur ────

    @Test
    void confMaison_sectionEntreCrochets() {
        StyledLine l = GenericTokenizer.styleLine(CONF, "[table]", LexState.NORMAL);
        assertEquals(TokenType.TYPE, typeAt(l, 1), "nom de section → type déclaré");
        assertEquals(TokenType.PUNCT, typeAt(l, 6), "crochet fermant → PUNCT");
        assertEquals(2, l.spans.size(), "un nom + un crochet fermant, pas plus");
    }

    @Test
    void confMaison_sectionDoubleCrochet() {
        StyledLine l = GenericTokenizer.styleLine(CONF, "[[arr]]", LexState.NORMAL);
        assertEquals(TokenType.TYPE, typeAt(l, 2));
        assertEquals(TokenType.PUNCT, typeAt(l, 5));
        assertEquals(TokenType.PUNCT, typeAt(l, 6), "les DEUX crochets fermants, en un span");
    }

    @Test
    void confMaison_cléAvantLeSéparateurMêmeAvecCrochetsOuverts() {
        // Un [ APRÈS le séparateur n'est plus une section : PUNCT.
        StyledLine l = GenericTokenizer.styleLine(CONF, "k = [ a ]", LexState.NORMAL);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0));
        assertEquals(TokenType.PUNCT, typeAt(l, 4), "crochet en zone valeur → PUNCT");
    }

    @Test
    void confMaison_chaîneQuotéeAvecÉchappement() {
        StyledLine l = GenericTokenizer.styleLine(CONF, "k=\"a\\nb\"", LexState.NORMAL);
        assertEquals(TokenType.STRING, typeAt(l, 2), "ouverture de chaîne");
        assertEquals(TokenType.ESCAPE, typeAt(l, 4), "\\n interne → ESCAPE");
        assertEquals(TokenType.STRING, typeAt(l, 6), "queue de chaîne");
    }

    @Test
    void confMaison_chaîneNonFermée() {
        // Comportement historique repris : guillemet orphelin → PUNCT,
        // le contenu tombe dans la règle de mot.
        StyledLine l = GenericTokenizer.styleLine(CONF, "k = \"abc", LexState.NORMAL);
        assertEquals(TokenType.PUNCT, typeAt(l, 4));
        assertEquals(TokenType.STRING, typeAt(l, 5), "contenu non fermé → mot (STRING)");
    }

    @Test
    void confMaison_nombreAvecJeuDeCaractèresÉtendu() {
        StyledLine l = GenericTokenizer.styleLine(CONF, "d = 1979-05-27T07:32:00Z", LexState.NORMAL);
        assertEquals(TokenType.NUMBER, typeAt(l, 4), "date RFC 3339 entière en un jeton NUMBER");
        assertEquals(1, l.spans.stream().filter(s -> s.type == TokenType.NUMBER).count());
    }

    @Test
    void confMaison_nombreNégatif() {
        StyledLine l = GenericTokenizer.styleLine(CONF, "k = -12", LexState.NORMAL);
        assertEquals(TokenType.NUMBER, typeAt(l, 4), "- suivi d'un chiffre → NUMBER");
    }

    @Test
    void confMaison_motCléDeValeur() {
        StyledLine l = GenericTokenizer.styleLine(CONF, "ok = oui", LexState.NORMAL);
        assertEquals(TokenType.CONSTANT, typeAt(l, 5), "mot déclaré → type de mot-clé (CONSTANT)");
        StyledLine l2 = GenericTokenizer.styleLine(CONF, "ok = autre", LexState.NORMAL);
        assertEquals(TokenType.STRING, typeAt(l2, 5), "mot non déclaré → type de valeur");
    }

    @Test
    void confMaison_commentaireEnZoneDeValeur() {
        // commentsAnywhere : le # commente même après le séparateur
        // (TOML) — contrairement au repli Properties.
        StyledLine l = GenericTokenizer.styleLine(CONF, "k = v # note", LexState.NORMAL);
        assertEquals(TokenType.COMMENT, typeAt(l, 6));
    }

    @Test
    void confMaison_valeurMultiJetons() {
        StyledLine l = GenericTokenizer.styleLine(CONF, "t = [ \"a\", 1 ]", LexState.NORMAL);
        assertEquals(TokenType.PUNCT, typeAt(l, 4));
        assertEquals(TokenType.STRING, typeAt(l, 6));
        assertEquals(TokenType.PUNCT, typeAt(l, 9));
        assertEquals(TokenType.NUMBER, typeAt(l, 11));
        assertEquals(TokenType.PUNCT, typeAt(l, 13));
    }

    // ── Template TOML : la deuxième migration est exacte ───────────

    @Test
    void toml_section() {
        StyledLine l = GenericTokenizer.styleLine(TOML, "[dependencies]", LexState.NORMAL);
        assertEquals(TokenType.TYPE, typeAt(l, 1));
        assertEquals(TokenType.PUNCT, typeAt(l, 13));
    }

    @Test
    void toml_booléenEnConstante() {
        StyledLine l = GenericTokenizer.styleLine(TOML, "enabled = true", LexState.NORMAL);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0));
        assertEquals(TokenType.CONSTANT, typeAt(l, 10), "true est un mot-clé TOML → CONSTANT");
    }

    @Test
    void toml_commentaireAprèsLaValeur() {
        StyledLine l = GenericTokenizer.styleLine(TOML, "key = value # note", LexState.NORMAL);
        assertEquals(TokenType.COMMENT, typeAt(l, 12), "le # commente n'importe où (commentsAnywhere)");
    }
}
