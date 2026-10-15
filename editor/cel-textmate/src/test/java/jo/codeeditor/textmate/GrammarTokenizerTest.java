package jo.codeeditor.textmate;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (C3) — le moteur d'exécution des grammaires : leftmost-first,
 * pile de contextes inter-lignes (exitState opaque STABLE), portée de
 * contenu, backreferences du end, fusion des spans.
 *
 * <p>Les grammaires de travail vivent en RESSOURCES de test
 * ({@code textmate/*.tmg.json}) — mêmes deux niveaux d'échappement
 * (JSON → regex) que les grammaires embarquées réelles, sans l'échot
 * d'un troisième niveau de text block Java.</p>
 */
class GrammarTokenizerTest {

    /** Grammaire de travail : commentaires, doc blocks, chaînes (échappements), mots-clés. */
    private static final TextMateGrammar GRAMMAR = load("textmate/grammar-test.tmg.json");

    private final GrammarTokenizer engine = new GrammarTokenizer();

    private static TextMateGrammar load(String path) {
        try (InputStream in = GrammarTokenizerTest.class.getClassLoader()
                .getResourceAsStream(path);
                Scanner sc = new Scanner(in, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            return TextMateGrammar.parse(sc.next());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ressource de test manquante : " + path, e);
        }
    }

    private StyledLine tok(String line, int state) {
        return engine.tokenize(GRAMMAR, line, state);
    }

    /** Type du jeton couvrant la première occurrence de needle dans la ligne. */
    private static TokenType type(StyledLine line, String lineText, String needle) {
        int col = lineText.indexOf(needle);
        assertTrue(col >= 0, "aiguille absente : " + needle);
        for (LineSpan s : line.spans) {
            if (col >= s.startCol && col < s.endCol) return s.type;
        }
        throw new AssertionError("aucun span ne couvre « " + needle + " » (col " + col
                + ") : " + line.spans);
    }

    // ── Matches simples : leftmost-first, portées mappées ────────

    @Test
    void motsClésEtNombres() {
        String line = "if x == 42 return";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.KEYWORD_CONTROL, type(l, line, "if"));
        assertEquals(TokenType.NUMBER, type(l, line, "42"));
        assertEquals(TokenType.KEYWORD_CONTROL, type(l, line, "return"));
        assertEquals(TokenType.PLAIN, type(l, line, "x"));
    }

    @Test
    void commentaireDeLigne_puisRien() {
        StyledLine l = tok("// note entière", 0);
        assertEquals(1, l.spans.size());
        assertEquals(TokenType.COMMENT, l.spans.get(0).type);
        assertEquals(0, l.exitState);
    }

    @Test
    void appelDeFonction_entityNameFunction() {
        String line = "foo(1)";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.FUNC, type(l, line, "foo"));
        assertEquals(TokenType.NUMBER, type(l, line, "1"));
    }

    // ── Chaînes : même ligne, échappements ───────────────────────

    @Test
    void chaîneMonoLigne_avecÉchappement() {
        String line = "s = \"a\\nb\"";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.STRING, type(l, line, "\"a"));
        assertEquals(TokenType.ESCAPE, type(l, line, "\\n"));
        assertEquals(0, l.exitState, "la chaîne fermée ne laisse rien en pile");
    }

    @Test
    void guillemetÉchappé_neFermePasLaChaîne() {
        // "a\"b" — l'échappement \" est consommé, la chaîne ferme à la
        // fin, l'état de sortie est vide.
        String line = "\"a\\\"b\"";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.ESCAPE, type(l, line, "\\\""));
        assertEquals(0, l.exitState);
    }

    // ── Chaînes multilignes : pile d'états, stabilité ────────────

    @Test
    void chaîneMultiligne_étatEtPortéeDeContenu() {
        String line1 = "s = \"abc";
        StyledLine l1 = tok(line1, 0);
        assertTrue(l1.exitState != 0, "une chaîne non fermée empile un contexte");
        assertEquals(TokenType.STRING, type(l1, line1, "abc"));

        String line2 = "suite de chaîne";
        StyledLine l2 = tok(line2, l1.exitState);
        assertEquals(TokenType.STRING, l2.spans.get(0).type,
                "toute la ligne dans une chaîne non fermée est STRING");
        assertTrue(l2.exitState != 0);

        String line3 = "fin\" + x";
        StyledLine l3 = tok(line3, l2.exitState);
        assertEquals(TokenType.STRING, type(l3, line3, "fin\""));
        assertEquals(TokenType.PLAIN, type(l3, line3, "x"));
        assertEquals(0, l3.exitState, "la fermeture dépile tout");
    }

    @Test
    void exitState_stable_etInterné() {
        StyledLine l1 = tok("s = \"abc", 0);
        int state1 = l1.exitState;
        assertTrue(state1 != 0, "une pile non vide a un identifiant non nul");
        // Contrat : re-tokéniser la même ligne → même exitState.
        StyledLine again = tok("s = \"abc", 0);
        assertEquals(state1, again.exitState);

        // Interning : une pile de même contenu (même règle encore
        // ouverte) réutilise le même identifiant — c'est ce qui borne
        // la table d'états et garantit la stabilité.
        StyledLine l2 = tok("suite", state1);
        assertEquals(state1, l2.exitState, "pile inchangée → même identifiant");

        // La fermeture ramène à 0 (pile vide).
        StyledLine l3 = tok("fin\"", state1);
        assertEquals(0, l3.exitState);
    }

    @Test
    void sousRèglesActivesDansLeContexte() {
        // L'échappement n'est reconnu QUE dans la chaîne (sous-règle).
        String dans = "x = \"a\\tb";
        StyledLine lDans = tok(dans, 0);
        assertEquals(TokenType.ESCAPE, type(lDans, dans, "\\t"));
        String hors = "a\\tb";
        StyledLine lHors = tok(hors, 0);
        assertEquals(TokenType.PLAIN, type(lHors, hors, "\\t"),
                "hors chaîne, l'échappement n'est pas une règle active");
    }

    // ── Doc block : begin/end multi-lignes ───────────────────────

    @Test
    void docComment_multiligne() {
        StyledLine l1 = tok("/** doc", 0);
        assertEquals(TokenType.DOC_COMMENT, l1.spans.get(0).type);
        assertTrue(l1.exitState != 0);
        StyledLine l2 = tok("corps", l1.exitState);
        assertEquals(TokenType.DOC_COMMENT, l2.spans.get(0).type,
                "le contenu d'un doc block reste DOC_COMMENT");
        String line3 = "fin */ code";
        StyledLine l3 = tok(line3, l1.exitState);
        assertEquals(TokenType.DOC_COMMENT, type(l3, line3, "fin "));
        assertEquals(TokenType.PLAIN, type(l3, line3, "code"));
        assertEquals(0, l3.exitState);
    }

    // ── Backreferences du end ────────────────────────────────────

    @Test
    void backréférenceDuEnd_résolueAvecBegin() {
        // Bloc délimité par un triple guillemet identique — le end \1
        // reprend le délimiteur capturé par le begin.
        TextMateGrammar g = load("textmate/backref-test.tmg.json");
        GrammarTokenizer engine = new GrammarTokenizer();
        StyledLine l1 = engine.tokenize(g, "x = \"\"\"abc", 0);
        assertTrue(l1.exitState != 0);
        // La clôture doit reconnaître """ (pas ''').
        StyledLine l2 = engine.tokenize(g, "suite\"\"\"", l1.exitState);
        assertEquals(0, l2.exitState, "le \\1 du end doit matcher le délimiteur du begin");
        // Un délimiteur différent ne ferme PAS.
        StyledLine l2b = engine.tokenize(g, "suite'''", l1.exitState);
        assertTrue(l2b.exitState != 0);
    }

    // ── Fusion des spans adjacents ───────────────────────────────

    @Test
    void spansAdjacents_deMêmeTypeFusionnés() {
        // "abc" : quotes + contenu = un seul span STRING.
        StyledLine l = tok("\"abc\"", 0);
        long stringSpans = l.spans.stream().filter(s -> s.type == TokenType.STRING).count();
        assertEquals(1, stringSpans);
    }

    // ── Leftmost-first : l'ordre déclare les priorités ───────────

    @Test
    void leftmost_gagne_surLordreDeDéclaration() {
        // Chaque règle s'applique à SA position : le moteur prend le
        // match le plus à GAUCHE d'abord, puis continue.
        String line = "return foo(1)";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.KEYWORD_CONTROL, type(l, line, "return"));
        assertEquals(TokenType.FUNC, type(l, line, "foo"));
        assertEquals(TokenType.NUMBER, type(l, line, "1"));
    }
}
