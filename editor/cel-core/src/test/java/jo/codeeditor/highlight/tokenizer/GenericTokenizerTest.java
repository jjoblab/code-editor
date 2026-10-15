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

    /**
     * Template maison (C2-é3) : mini-langage de script en flux libre
     * (pas de zones clé=valeur) — mots-clés, $variables, chaînes
     * doubles et littérales à état inter-lignes, substitution par
     * backtick, courses d'opérateurs, commentaires ancrés : la
     * grammaire de la famille SHELL sans une ligne de moteur.
     */
    private static final TokenizerTemplate FLUX = TokenizerTemplate.builder()
            .lineComments("#")
            .commentRequiresBoundary(true)
            .commentAnchors(';')
            .freeForm()
            .variablePrefixes('$')
            .variableType(TokenType.VARIABLE)
            .plainStringQuotes('"')
            .interLineString('\'', 42)
            .commandQuotes('`')
            .valueType(TokenType.STRING)
            .valueKeywords(java.util.Set.of("si", "alors"))
            .valueKeywordType(TokenType.KEYWORD)
            .wordExtraChars('_', '-')
            .wordFuncFollowChars('(', '[')
            .wordUpperCaseType(TokenType.CONSTANT)
            .wordFallbackType(TokenType.PLAIN)
            .operatorChars('|', '&', ';', '<', '>', '(', ')')
            .build();

    /** Le template Shell intégré (troisième migration du cliquet). */
    private static final TokenizerTemplate SHELL =
            TokenizerTemplate.SHELL;

    /**
     * Template maison (C2-é4) : format d'inventaire strict façon YAML
     * — clés identifiées par lookahead de ':', marqueur de document,
     * ancres, indicateurs de bloc, mots de valeur classés
     * (nombre/constant/scalaire), scalaires bruts : la grammaire de la
     * famille YAML sans une ligne de moteur.
     */
    private static final TokenizerTemplate FICHIER = TokenizerTemplate.builder()
            .lineComments("#")
            .commentsAnywhere(true)
            .documentMarkers("---")
            .documentMarkerType(TokenType.KEYWORD)
            .annotationPrefixes('&', '*', '!')
            .keyValueSeparators(':')
            .keyType(TokenType.PROPERTY)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.STRING)
            .keyStartExtras('"', '\'', '-', '.', '/', '_')
            .keyStopChars('#')
            .keyLookahead(true)
            .stringsInKeyZone(true)
            .stringReopensKeyZone(true)
            .valueQuotes('"', '\'')
            .blockIndicators('|', '>')
            .valueWordStartExtras('-', '.')
            .valueWordExtras('.', '_', '-', ':', '/')
            .valueWordConsumesDigits(true)
            .valueWordNumbers(true)
            .valueKeywords(java.util.Set.of("vrai", "faux"))
            .valueKeywordType(TokenType.CONSTANT)
            .flowPunct('[', ']', '{', '}')
            .scalarFallback(true)
            .scalarStopChars('#')
            .build();

    /** Le template YAML intégré (quatrième migration du cliquet). */
    private static final TokenizerTemplate YAML =
            TokenizerTemplate.YAML;

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

    // ── C2-é3 : flux libre, variables, états inter-lignes ──────────

    @Test
    void fluxMaison_motCléEtVariable() {
        StyledLine l = GenericTokenizer.styleLine(FLUX, "si $X", 0);
        assertEquals(TokenType.KEYWORD, typeAt(l, 0));
        assertEquals(TokenType.VARIABLE, typeAt(l, 3));
    }

    @Test
    void fluxMaison_variableEntreAccoladesEtCommentaireAncré() {
        StyledLine l = GenericTokenizer.styleLine(FLUX, "${x} # c", 0);
        assertEquals(TokenType.VARIABLE, typeAt(l, 0));
        assertEquals(TokenType.COMMENT, typeAt(l, 5), "le # suit un blanc → commentaire");
        StyledLine l2 = GenericTokenizer.styleLine(FLUX, "x#y", 0);
        assertEquals(TokenType.PUNCT, typeAt(l2, 1), "le # dans un mot n'est PAS un commentaire");
    }

    @Test
    void fluxMaison_chaîneDoublesÉchappementSauté() {
        StyledLine l = GenericTokenizer.styleLine(FLUX, "\"a\\\"b\"", 0);
        assertEquals(1, l.spans.size(), "un seul span, l'échappement sauté");
        assertEquals(TokenType.STRING, l.spans.get(0).type);
    }

    @Test
    void fluxMaison_littéralÀÉtatInterLignes() {
        // L'état est PARAMÉTRIQUE : 42 pour ce template maison.
        StyledLine l = GenericTokenizer.styleLine(FLUX, "x 'abc", 0);
        assertEquals(42, l.exitState, "apostrophe non fermée → état déclaré");
        StyledLine l2 = GenericTokenizer.styleLine(FLUX, "d' e", 42);
        assertEquals(TokenType.STRING, typeAt(l2, 0), "le littéral se clôt à la ligne suivante");
        assertEquals(0, l2.exitState, "retour à NORMAL");
        StyledLine l3 = GenericTokenizer.styleLine(FLUX, "zz", 42);
        assertEquals(42, l3.exitState, "sans fermante, l'état se propage");
    }

    @Test
    void fluxMaison_substitutionParBacktick() {
        StyledLine l = GenericTokenizer.styleLine(FLUX, "x=`f`", 0);
        assertEquals(TokenType.ANNOTATION, typeAt(l, 2));
    }

    @Test
    void fluxMaison_majusculeFonctionEtRepli() {
        StyledLine l = GenericTokenizer.styleLine(FLUX, "TOTO foo(1)", 0);
        assertEquals(TokenType.CONSTANT, typeAt(l, 0), "initiale majuscule → CONSTANT");
        assertEquals(TokenType.FUNC, typeAt(l, 5), "mot suivi de ( → FUNC");
        StyledLine l2 = GenericTokenizer.styleLine(FLUX, "bar", 0);
        assertEquals(TokenType.PLAIN, typeAt(l2, 0), "mot de repli → PLAIN");
    }

    @Test
    void fluxMaison_opérateursEnCourseEtNombres() {
        StyledLine l = GenericTokenizer.styleLine(FLUX, "a |& 2", 0);
        assertEquals(TokenType.OPERATOR, typeAt(l, 2));
        assertEquals(TokenType.OPERATOR, typeAt(l, 3), "course : |& en un seul jeton");
        assertEquals(TokenType.NUMBER, typeAt(l, 5));
    }

    // ── Template Shell : la troisième migration est exacte ─────────

    @Test
    void shell_variable() {
        StyledLine l = GenericTokenizer.styleLine(SHELL, "echo $HOME", LexState.NORMAL);
        assertEquals(TokenType.VARIABLE, typeAt(l, 5));
        assertEquals(TokenType.KEYWORD, typeAt(l, 0), "echo est un mot-clé du registre");
    }

    @Test
    void shell_étatInterLignes() {
        StyledLine l = GenericTokenizer.styleLine(SHELL, "echo 'abc", LexState.NORMAL);
        assertEquals(LexState.SHELL_SINGLE_QUOTE, l.exitState);
    }

    // ── C2-é4 : lookahead de clé, marqueurs, ancres, scalaires ─────

    @Test
    void fichierMaison_cléParLookaheadOuScalaire() {
        StyledLine l = GenericTokenizer.styleLine(FICHIER, "nom: John", 0);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0), "suivi de ':' → clé");
        assertEquals(TokenType.OPERATOR, typeAt(l, 3));
        assertEquals(TokenType.STRING, typeAt(l, 5));
        StyledLine l2 = GenericTokenizer.styleLine(FICHIER, "scalaire nu", 0);
        assertEquals(TokenType.STRING, typeAt(l2, 0), "sans ':' → scalaire brut");
    }

    @Test
    void fichierMaison_marqueurDeDocumentEtAncres() {
        StyledLine l = GenericTokenizer.styleLine(FICHIER, "---", 0);
        assertEquals(TokenType.KEYWORD, typeAt(l, 0));
        StyledLine l2 = GenericTokenizer.styleLine(FICHIER, "&ancre x: 1", 0);
        assertEquals(TokenType.ANNOTATION, typeAt(l2, 0));
        assertEquals(TokenType.PROPERTY, typeAt(l2, 7));
        assertEquals(TokenType.NUMBER, typeAt(l2, 10));
    }

    @Test
    void fichierMaison_indicateurDeBlocEnZoneClé() {
        StyledLine l = GenericTokenizer.styleLine(FICHIER, "|", 0);
        assertEquals(TokenType.KEYWORD, typeAt(l, 0));
    }

    @Test
    void fichierMaison_motsDeValeurClassés() {
        StyledLine l = GenericTokenizer.styleLine(FICHIER, "p: 8080", 0);
        assertEquals(TokenType.NUMBER, typeAt(l, 3), "chiffre en tête → nombre");
        StyledLine l2 = GenericTokenizer.styleLine(FICHIER, "v: vrai", 0);
        assertEquals(TokenType.CONSTANT, typeAt(l2, 3), "mot déclaré → constant");
        StyledLine l3 = GenericTokenizer.styleLine(FICHIER, "u: https://x.org", 0);
        assertEquals(TokenType.STRING, typeAt(l3, 3), "URL avec ':' → un seul jeton scalaire");
    }

    @Test
    void fichierMaison_chaîneRéouvreLaZoneClé() {
        // Comportement YAML historique : après une chaîne quotée, le
        // nombre suivant retombe en scalaire brut (pas en nombre).
        StyledLine l = GenericTokenizer.styleLine(FICHIER, "k: \"x\" 42", 0);
        assertEquals(TokenType.STRING, typeAt(l, 3));
        assertEquals(TokenType.STRING, typeAt(l, 7), "42 après chaîne → scalaire STRING");
    }

    @Test
    void fichierMaison_scalaireBrutEtFlux() {
        StyledLine l = GenericTokenizer.styleLine(FICHIER, "l: [a, b]", 0);
        assertEquals(TokenType.PUNCT, typeAt(l, 3), "crochet de flux → PUNCT");
        assertEquals(TokenType.STRING, typeAt(l, 5), "mot du flux");
        StyledLine l2 = GenericTokenizer.styleLine(FICHIER, "- item", 0);
        assertEquals(TokenType.STRING, typeAt(l2, 0), "tiret de liste → scalaire");
    }

    // ── Template YAML : la quatrième migration est exacte ──────────

    @Test
    void yaml_cléEtValeur() {
        StyledLine l = GenericTokenizer.styleLine(YAML, "name: John", LexState.NORMAL);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0));
        assertEquals(TokenType.STRING, typeAt(l, 6));
    }

    @Test
    void yaml_séparateurDeDocument() {
        StyledLine l = GenericTokenizer.styleLine(YAML, "---", LexState.NORMAL);
        assertEquals(TokenType.KEYWORD, typeAt(l, 0));
    }

    @Test
    void yaml_booléen() {
        StyledLine l = GenericTokenizer.styleLine(YAML, "enabled: true", LexState.NORMAL);
        assertEquals(TokenType.CONSTANT, typeAt(l, 9));
    }

    // ── C2-é5 : chaînes quotées, clés par lookahead, flux libre ────

    /**
     * Template maison (C2-é5) : format de fiches balisées façon JSON —
     * chaînes quotées à échappements apparents, clé requalifiée par
     * lookahead de ':' (guillemets fermants), mots-clés, nombres à
     * signe collés aux mots, opérateurs mono-caractère, chaîne
     * orpheline à état inter-lignes : la grammaire de la famille JSON
     * sans une ligne de moteur.
     */
    private static final TokenizerTemplate FICHE = TokenizerTemplate.builder()
            .freeForm()
            .valueQuotes('"')
            .stringKeyLookahead(true)
            .keyValueSeparators(':')
            .keyType(TokenType.PROPERTY)
            .valueType(TokenType.STRING)
            .interLineString('"', 42)
            .valueNumberChars('.', 'e', '+')
            .numberLeadingMinus(true)
            .numberRequiresBoundary(false)
            .wordConsumesDigits(false)
            .valueKeywords(java.util.Set.of("vrai", "faux"))
            .valueKeywordType(TokenType.KEYWORD)
            .wordFallbackType(TokenType.PLAIN)
            .operatorSingleChars(':', ',')
            .build();

    /** Le template JSON intégré (cinquième migration du cliquet). */
    private static final TokenizerTemplate JSON =
            TokenizerTemplate.JSON;

    @Test
    void ficheMaison_cléChaîneParLookahead() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, "\"nom\": \"valeur\"", 0);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0), "chaîne suivie de ':' → clé");
        assertEquals(TokenType.OPERATOR, typeAt(l, 5), "le ':' lui-même");
        assertEquals(TokenType.STRING, typeAt(l, 7), "chaîne sans ':' → valeur");
    }

    @Test
    void ficheMaison_lookaheadSauteLesBlancs() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, "\"a\"  : vrai", 0);
        assertEquals(TokenType.PROPERTY, typeAt(l, 0), "blancs sautés avant le ':'");
        assertEquals(TokenType.KEYWORD, typeAt(l, 8), "mot-clé de valeur");
    }

    @Test
    void ficheMaison_chaîneNonSuivieDeSéparateur() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, "\"a\" , \"b\"", 0);
        assertEquals(TokenType.STRING, typeAt(l, 0), "suivie de ',' → pas une clé");
        assertEquals(TokenType.OPERATOR, typeAt(l, 4), "',' mono-caractère");
        assertEquals(TokenType.STRING, typeAt(l, 7));
    }

    @Test
    void ficheMaison_échappementsApparentsDansLaChaîne() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, "\"a\\nb\"", 0);
        assertEquals(TokenType.STRING, typeAt(l, 0));
        assertEquals(TokenType.ESCAPE, typeAt(l, 2), "échappement apparent en flux libre");
        assertEquals(TokenType.STRING, typeAt(l, 4));
    }

    @Test
    void ficheMaison_chaîneOrpheline_étatInterLignes() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, "\"abc", 0);
        assertEquals(42, l.exitState, "chaîne non fermée → état déclaré");
        StyledLine l2 = GenericTokenizer.styleLine(FICHE, "x\" suite", 42);
        assertEquals(TokenType.STRING, typeAt(l2, 0), "continuation consommée jusqu'à la fermante");
        assertEquals(LexState.NORMAL, l2.exitState);
        assertEquals(TokenType.PLAIN, typeAt(l2, 4), "la suite de la ligne reprend le flux");
    }

    @Test
    void ficheMaison_motsLettresPurs_etNombresSansFrontière() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, "abc123", 0);
        assertEquals(TokenType.PLAIN, typeAt(l, 0), "mot de lettres pures");
        assertEquals(TokenType.NUMBER, typeAt(l, 3), "chiffre collé au mot → nombre (pas de frontière)");
    }

    @Test
    void ficheMaison_moinsAncréÀUnChiffre() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, "-4.2", 0);
        assertEquals(TokenType.NUMBER, typeAt(l, 0), "signe moins ancré à un chiffre");
        assertEquals(TokenType.NUMBER, typeAt(l, 3));
    }

    @Test
    void ficheMaison_opérateursMonoCaractèreJamaisFusionnés() {
        StyledLine l = GenericTokenizer.styleLine(FICHE, ":,", 0);
        assertEquals(2, l.spans.size(), "deux opérateurs mono-caractère, pas une course");
        assertEquals(TokenType.OPERATOR, l.spans.get(0).type);
        assertEquals(TokenType.OPERATOR, l.spans.get(1).type);
    }

    // ── Template JSON : la cinquième migration est exacte ──────────

    @Test
    void json_cléValeurEtNombres() {
        StyledLine l = GenericTokenizer.styleLine(JSON, "{\"a\": -2.5e3}", 0);
        assertEquals(TokenType.PUNCT, typeAt(l, 0));
        assertEquals(TokenType.PROPERTY, typeAt(l, 1), "clé par lookahead");
        assertEquals(TokenType.OPERATOR, typeAt(l, 4));
        assertEquals(TokenType.NUMBER, typeAt(l, 6));
        assertEquals(TokenType.PUNCT, typeAt(l, 12));
    }

    @Test
    void json_booléensNullEnMotsClés() {
        StyledLine l = GenericTokenizer.styleLine(JSON, "[true, null]", 0);
        assertEquals(TokenType.KEYWORD, typeAt(l, 1));
        assertEquals(TokenType.OPERATOR, typeAt(l, 5), "virgule mono-caractère");
        assertEquals(TokenType.KEYWORD, typeAt(l, 7));
    }

    @Test
    void json_chaîneOrphine_étatXMLString() {
        StyledLine l = GenericTokenizer.styleLine(JSON, "\"x", 0);
        assertEquals(LexState.XML_STRING, l.exitState);
        StyledLine l2 = GenericTokenizer.styleLine(JSON, "y\"", LexState.XML_STRING);
        assertEquals(LexState.NORMAL, l2.exitState);
        assertEquals(TokenType.STRING, typeAt(l2, 0));
    }

    // ── C2-é6 : littéraux à délimiteurs, raffinement des mots ──────

    /**
     * Template maison (C2-é6) : mini-langage à syntaxe C francisé —
     * commentaires de bloc et de documentation à délimiteurs, chaîne
     * brute à triples guillemets réservée à « kotlin », littéraux de
     * caractère, template literals JS, annotations, couleurs hexa,
     * raffinement des mots (contrôle/modificateur/type/constant/
     * propriété) : la grammaire de la famille C_LIKE sans une ligne
     * de moteur.
     */
    private static final TokenizerTemplate PAGES = TokenizerTemplate.builder()
            .freeForm()
            .lineComments("//")
            .blockLiteral("/**", "*/", 2, 7, TokenType.DOC_COMMENT)
            .blockLiteral("/*", "*/", 2, 1, TokenType.COMMENT)
            .blockLiteral("\"\"\"", "\"\"\"", 3, 3, TokenType.RAW_STRING, "kotlin")
            .plainStringQuotes('"')
            .charQuotes('\'')
            .templateLiteralLanguages("javascript", "js")
            .annotationPrefixes('@')
            .colorLiteral('#', TokenType.NUMBER)
            .valueType(TokenType.STRING)
            .valueNumberChars('.', '_', 'x', 'a', 'b', 'c', 'd', 'e', 'f', 'L')
            .valueKeywords(java.util.Set.of("si", "sinon", "mut"))
            .valueKeywordType(TokenType.KEYWORD)
            .wordControlKeywords(java.util.Set.of("si"))
            .wordControlType(TokenType.KEYWORD_CONTROL)
            .wordModifierKeywords(java.util.Set.of("mut"))
            .wordModifierType(TokenType.KEYWORD_MODIFIER)
            .wordTypeKeywords(java.util.Set.of("Entier"))
            .wordTypeKeywordType(TokenType.TYPE)
            .wordAllCapsType(TokenType.CONSTANT)
            .wordAfterDotType(TokenType.PROPERTY)
            .wordUpperCaseType(TokenType.TYPE)
            .wordFuncFollowChars('(')
            .wordExtraChars('_')
            .wordFallbackType(TokenType.PLAIN)
            .operatorChars('+', '-', '=', '<', '>')
            .build();

    /**
     * Template maison (C2-é6) : mots-clés pilotés par le REGISTRE des
     * langages (voie déclarée de la famille C) — les raffinements
     * s'appliquent aux mots-clés du langage demandé.
     */
    private static final TokenizerTemplate REGISTRE = TokenizerTemplate.builder()
            .freeForm()
            .registryKeywords(true)
            .valueKeywordType(TokenType.KEYWORD)
            .wordControlKeywords(KeywordTables.CONTROL_KEYWORDS)
            .wordModifierKeywords(KeywordTables.MODIFIER_KEYWORDS)
            .wordTypeKeywords(KeywordTables.JAVA_TYPES)
            .wordFallbackType(TokenType.PLAIN)
            .build();

    @Test
    void pagesMaison_commentairesDeBlocEtDoc() {
        StyledLine l = GenericTokenizer.styleLine(PAGES, "/* x */ si", 0);
        assertEquals(TokenType.COMMENT, typeAt(l, 0), "bloc fermé → COMMENT");
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l, 8), "« si » raffiné en contrôle");
        // /**/ : fermante chevauchant l'ouverture, en DOC.
        StyledLine l2 = GenericTokenizer.styleLine(PAGES, "/**/", 0);
        assertEquals(TokenType.DOC_COMMENT, typeAt(l2, 0));
        assertEquals(LexState.NORMAL, l2.exitState);
        // Bloc ouvert → état inter-lignes déclaré, propagation.
        StyledLine l3 = GenericTokenizer.styleLine(PAGES, "/* open", 0);
        assertEquals(1, l3.exitState);
        StyledLine l4 = GenericTokenizer.styleLine(PAGES, "encore", 1);
        assertEquals(1, l4.exitState, "pas de fermante : l'état se propage");
    }

    @Test
    void pagesMaison_chaîneBrutePortéeAuLangageKotlin() {
        // kotlin exact : « """ » ouvre la chaîne brute (état 3).
        StyledLine l = GenericTokenizer.styleLine(PAGES, "s = \"\"\"raw", 0, "kotlin");
        assertEquals(TokenType.RAW_STRING, typeAt(l, 4));
        assertEquals(3, l.exitState);
        StyledLine l2 = GenericTokenizer.styleLine(PAGES, "suite\"\"\" x", 3, "kotlin");
        assertEquals(TokenType.RAW_STRING, typeAt(l2, 0));
        assertEquals(LexState.NORMAL, l2.exitState);
        // Autre langage : retombe dans la règle de chaîne « normale ».
        StyledLine l3 = GenericTokenizer.styleLine(PAGES, "s = \"\"\"raw", 0, "java");
        assertEquals(TokenType.STRING, typeAt(l3, 4));
        assertEquals(TokenType.STRING, typeAt(l3, 6), "deux chaînes « normales »");
        assertEquals(LexState.NORMAL, l3.exitState);
    }

    @Test
    void pagesMaison_littéralDeCaractère() {
        StyledLine l = GenericTokenizer.styleLine(PAGES, "c = 'a';", 0);
        assertEquals(TokenType.CHAR, typeAt(l, 4), "'a' → CHAR");
        StyledLine l2 = GenericTokenizer.styleLine(PAGES, "'x", 0);
        assertEquals(TokenType.CHAR, typeAt(l2, 0));
        assertEquals(LexState.NORMAL, l2.exitState, "orphelin sans état inter-lignes");
    }

    @Test
    void pagesMaison_templateLiteralsPortésAuxLangagesJs() {
        StyledLine l = GenericTokenizer.styleLine(PAGES, "`x ${a}`", 0, "javascript");
        assertEquals(TokenType.STRING, typeAt(l, 0));
        assertEquals(TokenType.VARIABLE, typeAt(l, 3), "interpolation ${a} → VARIABLE");
        assertEquals(TokenType.STRING, typeAt(l, 7), "backtick fermant");
        // Hors des langages déclarés : le backtick n'est rien.
        StyledLine l2 = GenericTokenizer.styleLine(PAGES, "`x`", 0, "java");
        assertEquals(TokenType.PUNCT, typeAt(l2, 0));
    }

    @Test
    void pagesMaison_annotation() {
        StyledLine l = GenericTokenizer.styleLine(PAGES, "@Ann x", 0);
        assertEquals(TokenType.ANNOTATION, typeAt(l, 0), "@ + identifiant en un jeton");
        assertEquals(TokenType.PLAIN, typeAt(l, 5));
    }

    @Test
    void pagesMaison_couleurHexadécimale() {
        StyledLine l = GenericTokenizer.styleLine(PAGES, "#FF00AA", 0);
        assertEquals(TokenType.NUMBER, typeAt(l, 0), "6 hex → couleur");
        // Longueur invalide : repli PUNCT + mot.
        StyledLine l2 = GenericTokenizer.styleLine(PAGES, "#GG", 0);
        assertEquals(TokenType.PUNCT, typeAt(l2, 0));
        assertEquals(TokenType.CONSTANT, typeAt(l2, 1), "GG tout-en-majuscules");
    }

    @Test
    void pagesMaison_raffinementDesMots() {
        StyledLine l = GenericTokenizer.styleLine(PAGES, "mut si sinon Entier", 0);
        assertEquals(TokenType.KEYWORD_MODIFIER, typeAt(l, 0), "« mut » → modificateur");
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l, 4), "« si » → contrôle");
        assertEquals(TokenType.KEYWORD, typeAt(l, 7), "« sinon » → mot-clé simple");
        assertEquals(TokenType.TYPE, typeAt(l, 13), "« Entier » → type déclaré");
        // ALL_CAPS → CONSTANT, appel → FUNC, après point → PROPERTY.
        StyledLine l2 = GenericTokenizer.styleLine(PAGES, "MAX Fonc( obj.champ", 0);
        assertEquals(TokenType.CONSTANT, typeAt(l2, 0));
        assertEquals(TokenType.FUNC, typeAt(l2, 4));
        assertEquals(TokenType.PROPERTY, typeAt(l2, 14), "champ suit le point");
    }

    @Test
    void registreMaison_motsClésDuRegistreParLangage() {
        // Langage enregistré : les mots-clés viennent du profil.
        StyledLine l = GenericTokenizer.styleLine(REGISTRE, "public return String", 0, "java");
        assertEquals(TokenType.KEYWORD_MODIFIER, typeAt(l, 0), "public via MODIFIER_KEYWORDS");
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l, 7), "return via CONTROL_KEYWORDS");
        assertEquals(TokenType.TYPE, typeAt(l, 14), "String via JAVA_TYPES");
        // Langage null : repli mots-clés Java (garde historique).
        StyledLine l2 = GenericTokenizer.styleLine(REGISTRE, "while", 0, null);
        assertEquals(TokenType.KEYWORD_CONTROL, typeAt(l2, 0));
    }

    // ── C2-é7 : littéraux à délimiteurs AVANT les commentaires ────

    /**
     * Template maison (C2-é7) : mini-langage façon Lua — commentaire de
     * bloc « --[[ ]] » PARTAGEANT son préfixe avec le commentaire de
     * ligne « -- », chaîne longue « [[ ]] » réutilisant le même état
     * inter-lignes : la grammaire de la famille LUA sans une ligne de
     * moteur.
     */
    private static final TokenizerTemplate BLOC = TokenizerTemplate.builder()
            .freeForm()
            .lineComments("--")
            .blockLiteral("--[[", "]]", 4, 7, TokenType.COMMENT)
            .blockLiteral("[[", "]]", 2, 7, TokenType.STRING)
            .valueQuotes('"', '\'')
            .valueType(TokenType.STRING)
            .wordExtraChars('_')
            .wordFallbackType(TokenType.PLAIN)
            .build();

    @Test
    void blocMaison_littéralÀDélimiteursAvantCommentaireDeLigne() {
        // « --[[ x ]] y » : le commentaire de bloc ferme en colonne 9 et
        // le code reprend — preuve que le littéral est évalué AVANT le
        // commentaire de ligne « -- » (sinon toute la ligne serait un
        // commentaire).
        StyledLine l = GenericTokenizer.styleLine(BLOC, "--[[ x ]] y", 0);
        assertEquals(TokenType.COMMENT, typeAt(l, 0));
        assertEquals(TokenType.COMMENT, typeAt(l, 8), "la fermante ]] appartient au bloc");
        assertEquals(TokenType.PLAIN, typeAt(l, 10), "le code reprend après le bloc");
        assertEquals(LexState.NORMAL, l.exitState);
    }

    @Test
    void blocMaison_commentaireDeLigneSeulQuandLeBlocNeOuvrePas() {
        // « -- x » : pas de « --[[ » → commentaire de ligne jusqu'au bout.
        StyledLine l = GenericTokenizer.styleLine(BLOC, "-- x ]]", 0);
        assertEquals(1, l.spans.size());
        assertEquals(TokenType.COMMENT, l.spans.get(0).type);
    }

    @Test
    void blocMaison_chaîneLongueRéutilisantLEtat() {
        // « [[ ... ]] » ouvre une chaîne longue ; non fermée, elle porte
        // l'état du commentaire de bloc (règle historique Lua).
        StyledLine l = GenericTokenizer.styleLine(BLOC, "s = [[texte", 0);
        assertEquals(TokenType.STRING, typeAt(l, 4));
        assertEquals(7, l.exitState, "la chaîne longue ouverte porte l'état déclaré");
    }

    @Test
    void blocMaison_continuationColoréeParLePremierLittéral() {
        // L'état étant partagé, la continuation reçoit le type du
        // PREMIER littéral déclarant cet état (COMMENT) — contrat de la
        // machine, calqué sur le comportement historique Lua.
        StyledLine l = GenericTokenizer.styleLine(BLOC, "suite ]]", 7);
        assertEquals(TokenType.COMMENT, typeAt(l, 0));
        assertEquals(LexState.NORMAL, l.exitState);
    }
}
