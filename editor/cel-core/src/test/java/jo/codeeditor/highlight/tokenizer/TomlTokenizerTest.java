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
 * Caractérisation du tokéniseur TOML (ADR-002, chantier C2 — cliquet
 * de démantèlement) : verrouille le comportement EXACT (bornes et types
 * de chaque span) de {@code TomlTokenizer.styleToml} sur une batterie
 * de lignes représentatives, AVANT puis APRÈS la migration sur le
 * moteur générique {@link GenericTokenizer}. La migration doit garder
 * cette suite verte sans toucher à ses assertions — c'est le critère
 * « octet pour octet » du chantier C2 (comportement repris tel quel,
 * seule l'implémentation change).
 */
class TomlTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return TomlTokenizer.styleToml(line, LexState.NORMAL).spans;
    }

    // ── En-têtes de section ──────────────────────────────────────────

    @Test
    void section_simple_typeEtCrochetFermant() {
        assertEquals(List.of(
                new LineSpan(1, 13, TYPE),
                new LineSpan(13, 14, PUNCT)),
                spans("[dependencies]"));
    }

    @Test
    void section_doubleCrochet_tableauDeTableaux() {
        assertEquals(List.of(
                new LineSpan(2, 17, TYPE),
                new LineSpan(17, 19, PUNCT)),
                spans("[[array-of-tables]]"));
    }

    @Test
    void section_nonFermée_retombeEnClé() {
        // Comportement historique : sans ']' de fermeture, les crochets
        // ouvrants sont consommés et le reste devient une clé.
        assertEquals(List.of(
                new LineSpan(1, 9, PROPERTY)),
                spans("[unclosed"));
    }

    // ── Paires clé = valeur ──────────────────────────────────────────

    @Test
    void cléValeur_avantLeSéparateur() {
        assertEquals(List.of(
                new LineSpan(0, 7, PROPERTY),
                new LineSpan(8, 9, OPERATOR)),
                spans("version ="));
    }

    @Test
    void valeur_chaîneBasiqueAvecCrochetsQuotés() {
        assertEquals(List.of(
                new LineSpan(0, 7, PROPERTY),
                new LineSpan(8, 9, OPERATOR),
                new LineSpan(10, 15, STRING)),
                spans("version = \"1.0\""));
    }

    @Test
    void valeur_chaîneLittéraleEntreApostrophes() {
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 15, STRING)),
                spans("key = 'literal'"));
    }

    @Test
    void valeur_échappementDansLaChaîne() {
        // \"a\\nb\" → STRING("a), ESCAPE(\\n), STRING(b")
        assertEquals(List.of(
                new LineSpan(0, 1, PROPERTY),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 6, STRING),
                new LineSpan(6, 8, ESCAPE),
                new LineSpan(8, 10, STRING)),
                spans("s = \"a\\nb\""));
    }

    @Test
    void valeur_chaîneNonFermée_guillemetEnPonctuationPuisMot() {
        // Comportement historique : sans fermante, le guillemet
        // ouvrant devient PUNCT et le contenu est tokénisé mot à mot.
        assertEquals(List.of(
                new LineSpan(0, 1, PROPERTY),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 5, PUNCT),
                new LineSpan(5, 8, STRING)),
                spans("k = \"abc"));
    }

    @Test
    void valeur_booléenEnConstante() {
        assertEquals(List.of(
                new LineSpan(0, 7, PROPERTY),
                new LineSpan(8, 9, OPERATOR),
                new LineSpan(10, 14, CONSTANT)),
                spans("enabled = true"));
    }

    @Test
    void valeur_motNonCléEnChaîne() {
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 11, STRING)),
                spans("key = value"));
    }

    @Test
    void valeur_nombreEntier() {
        assertEquals(List.of(
                new LineSpan(0, 4, PROPERTY),
                new LineSpan(5, 6, OPERATOR),
                new LineSpan(7, 11, NUMBER)),
                spans("port = 8080"));
    }

    @Test
    void valeur_dateHeureRFC3339EnNombre() {
        // Le jeu de caractères numérique historique couvre
        // . - : T Z — une date complète reste UN jeton NUMBER.
        assertEquals(List.of(
                new LineSpan(0, 4, PROPERTY),
                new LineSpan(5, 6, OPERATOR),
                new LineSpan(7, 27, NUMBER)),
                spans("date = 1979-05-27T07:32:00Z"));
    }

    @Test
    void valeur_négatifAncréAuChiffre() {
        // '-12' commence par un chiffre (garde '-'+chiffre) → NUMBER ;
        // '-x' non (retombe en mot) — le garde est caractéristique.
        assertEquals(List.of(
                new LineSpan(0, 1, PROPERTY),
                new LineSpan(2, 3, OPERATOR),
                new LineSpan(4, 7, NUMBER)),
                spans("k = -12"));
    }

    @Test
    void valeur_tableauFluxJetonParJeton() {
        assertEquals(List.of(
                new LineSpan(0, 4, PROPERTY),
                new LineSpan(5, 6, OPERATOR),
                new LineSpan(7, 8, PUNCT),
                new LineSpan(9, 12, STRING),
                new LineSpan(12, 13, PUNCT),
                new LineSpan(14, 17, STRING),
                new LineSpan(18, 19, PUNCT)),
                spans("tags = [ \"a\", \"b\" ]"));
    }

    // ── Commentaires ────────────────────────────────────────────────

    @Test
    void commentaire_ligneEntière() {
        assertEquals(List.of(
                new LineSpan(0, 11, COMMENT)),
                spans("# a comment"));
    }

    @Test
    void commentaire_aprèsLaValeur() {
        // Spécificité TOML (vs Properties) : le # commente
        // N'IMPORTE OÙ, y compris après le séparateur.
        assertEquals(List.of(
                new LineSpan(0, 3, PROPERTY),
                new LineSpan(4, 5, OPERATOR),
                new LineSpan(6, 11, STRING),
                new LineSpan(12, 18, COMMENT)),
                spans("key = value # note"));
    }

    // ── État inter-lignes ────────────────────────────────────────────

    @Test
    void sortie_toujoursNormal() {
        StyledLine l = TomlTokenizer.styleToml("[a] # x", LexState.NORMAL);
        assertEquals(LexState.NORMAL, l.exitState, "TOML n'a pas d'état inter-lignes");
        assertEquals(LexState.NORMAL, l.entryState);
    }

    @Test
    void ligneVide_aucunSpan() {
        assertEquals(List.of(), spans(""));
    }
}
