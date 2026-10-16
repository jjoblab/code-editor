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
 * Caractérisation du tokéniseur YAML (ADR-002, chantier C2 — cliquet
 * de démantèlement) : verrouille le comportement EXACT (bornes et
 * types de chaque span) de {@code YamlTokenizer.styleYaml} sur une
 * batterie de lignes représentatives, AVANT puis APRÈS la migration
 * sur le moteur générique {@link GenericTokenizer}. La migration doit
 * garder cette suite verte sans toucher à ses assertions — c'est le
 * critère « octet pour octet » du chantier C2.
 */
class YamlTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return YamlTokenizer.styleYaml(line, LexState.NORMAL).spans;
    }

    // ── Clés et valeurs ─────────────────────────────────────────────

    @Test
    void clé_identifiéeParLookaheadDeuxPoints() {
        // La clé n'est PROPERTY que si un ':' la suit (après blancs).
        assertEquals(List.of(
                new LineSpan(0, 4, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 10, STRING)),
                spans("name: John"));
    }

    @Test
    void clé_sansDeuxPointsScalaireBrut() {
        // Pas de ':' derrière : candidat clé retombe en scalaire STRING
        // et la suite est traitée en zone valeur.
        assertEquals(List.of(
                new LineSpan(0, 4, STRING),
                new LineSpan(5, 11, STRING)),
                spans("bare scalar"));
    }

    @Test
    void clé_interrompueParDièsePuisCommentaire() {
        // La règle de clé s'arrête au '#' ; le lookahead échoue (le '#'
        // n'est pas ':') → scalaire, puis le '#' ouvre un commentaire.
        assertEquals(List.of(
                new LineSpan(0, 3, STRING),
                new LineSpan(3, 10, COMMENT)),
                spans("foo#bar: x"));
    }

    @Test
    void valeur_booléensEnConstante() {
        assertEquals(List.of(
                new LineSpan(0, 7, PROPERTY),
                new LineSpan(7, 8, OPERATOR),
                new LineSpan(9, 13, CONSTANT)),
                spans("enabled: true"));
        assertEquals(List.of(
                new LineSpan(0, 2, PROPERTY),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 7, CONSTANT)),
                spans("ok: yes"));
    }

    @Test
    void valeur_nombre() {
        assertEquals(List.of(
                new LineSpan(0, 4, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 10, NUMBER)),
                spans("port: 8080"));
    }

    @Test
    void valeur_tildeEnScalaireBrut() {
        // '~' est dans la table des constantes mais ne peut pas DÉMARRER
        // un mot (ensemble de départ) : il tombe dans le scalaire brut.
        assertEquals(List.of(
                new LineSpan(0, 1, PROPERTY),
                new LineSpan(1, 2, OPERATOR),
                new LineSpan(3, 4, STRING)),
                spans("k: ~"));
    }

    @Test
    void valeur_urlAvecDeuxPointsEnUnJeton() {
        // Le ':' fait partie du jeu de caractères du mot de valeur :
        // une URL reste UN jeton (classée scalaire, pas nombre).
        assertEquals(List.of(
                new LineSpan(0, 1, PROPERTY),
                new LineSpan(1, 2, OPERATOR),
                new LineSpan(3, 22, STRING)),
                spans("u: https://example.org"));
    }

    // ── Chaînes quotées ─────────────────────────────────────────────

    @Test
    void chaîne_guillemetsDoublesEnValeur() {
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 19, STRING)),
                spans("key: \"quoted value\""));
    }

    @Test
    void chaîne_apostrophesEnValeur() {
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 13, STRING)),
                spans("key: 'single'"));
    }

    @Test
    void chaîne_nonFerméeEnValeurScalaireBrut() {
        // findStringEnd échoue → retombée : le reste (guillemet compris)
        // devient un scalaire brut STRING.
        assertEquals(List.of(
                new LineSpan(0, 1, PROPERTY),
                new LineSpan(1, 2, OPERATOR),
                new LineSpan(3, 7, STRING)),
                spans("k: \"abc"));
    }

    @Test
    void chaîne_réouvreLaZoneClé() {
        // Comportement historique caractéristique : une chaîne quotée
        // RÉINITIALISE afterColon — le 42 suivant retombe en scalaire
        // brut STRING (pas en nombre de valeur).
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 8, STRING),
                new LineSpan(9, 11, STRING)),
                spans("key: \"x\" 42"));
    }

    @Test
    void chaîne_puisDeuxPointsRetrouveLeSéparateur() {
        // Après la réinitialisation, le ':' redevient un séparateur.
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 8, STRING),
                new LineSpan(8, 9, OPERATOR),
                new LineSpan(10, 11, STRING)),
                spans("key: \"x\": y"));
    }

    // ── Marqueurs de document, ancres, indicateurs ─────────────────

    @Test
    void séparateurDeDocument() {
        assertEquals(List.of(
                new LineSpan(0, 3, KEYWORD)),
                spans("---"));
    }

    @Test
    void séparateurDeDocument_enPleinMilieu() {
        assertEquals(List.of(
                new LineSpan(0, 1, STRING),
                new LineSpan(2, 5, KEYWORD),
                new LineSpan(6, 7, STRING)),
                spans("a --- b"));
    }

    @Test
    void ancresAliasEtTags() {
        assertEquals(List.of(
                new LineSpan(0, 6, ANNOTATION)),
                spans("*alias"));
        assertEquals(List.of(
                new LineSpan(0, 7, ANNOTATION),
                new LineSpan(8, 13, PROPERTY),
                new LineSpan(13, 14, OPERATOR),
                new LineSpan(15, 16, NUMBER)),
                spans("&anchor value: 1"));
    }

    @Test
    void indicateurDeBlocPipeEtChevron() {
        assertEquals(List.of(
                new LineSpan(0, 1, KEYWORD)),
                spans("|"));
        assertEquals(List.of(
                new LineSpan(0, 1, KEYWORD)),
                spans(">"));
        // En zone valeur, le '|' n'est plus un indicateur : scalaire brut.
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 6, STRING)),
                spans("key: |"));
    }

    // ── Séquences et flux ───────────────────────────────────────────

    @Test
    void tiretDeListeScalaireBrut() {
        // Le '-' suivi d'un blanc : candidat clé sans ':' derrière →
        // scalaire STRING, la suite en zone valeur.
        assertEquals(List.of(
                new LineSpan(0, 1, STRING),
                new LineSpan(2, 6, STRING)),
                spans("- item"));
    }

    @Test
    void flux_crochetsEtMots() {
        assertEquals(List.of(
                new LineSpan(0, 4, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 7, PUNCT),
                new LineSpan(7, 8, STRING),
                new LineSpan(8, 9, STRING),
                new LineSpan(10, 11, STRING),
                new LineSpan(11, 12, PUNCT)),
                spans("list: [a, b]"));
    }

    @Test
    void flux_mappingOuvrantPuisClé() {
        // '{' ouvre le flux (PUNCT) ; « a » reste une clé avec son ':'.
        assertEquals(List.of(
                new LineSpan(0, 1, PUNCT),
                new LineSpan(1, 2, PROPERTY),
                new LineSpan(2, 3, OPERATOR)),
                spans("{a: "));
    }

    // ── Commentaires ────────────────────────────────────────────────

    @Test
    void commentaire_aprèsLaValeur() {
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(3, 4, OPERATOR),
                new LineSpan(5, 10, STRING),
                new LineSpan(11, 20, COMMENT)),
                spans("key: value # comment"));
    }

    @Test
    void commentaire_ligneEntière() {
        assertEquals(List.of(
                new LineSpan(0, 11, COMMENT)),
                spans("# a comment"));
    }

    // ── État de sortie ──────────────────────────────────────────────

    @Test
    void sortie_toujoursNormalEtLigneVide() {
        StyledLine l = YamlTokenizer.styleYaml("a: b", LexState.NORMAL);
        assertEquals(LexState.NORMAL, l.exitState, "YAML (cette impl) n'a pas d'état inter-lignes");
        assertEquals(List.of(), spans(""));
    }
}
