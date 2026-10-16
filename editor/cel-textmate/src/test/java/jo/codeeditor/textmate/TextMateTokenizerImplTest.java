package jo.codeeditor.textmate;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (C3) — l'implémentation du contrat : grammaires embarquées
 * (chargement classpath), enregistrement hôte, intégration au crochet
 * {@code SyntaxHighlighter.setTextMateTokenizer} — le critère (ii) de
 * l'ADR : un langage « grammaire-only », coloré sans tokéniseur dédié.
 */
class TextMateTokenizerImplTest {

    @AfterEach
    void tearDown() {
        // Le crochet est un statique global : ne jamais le fuir vers
        // les autres tests.
        SyntaxHighlighter.setTextMateTokenizer(null);
    }

    @Test
    void grammairesEmbarquées_disponibles() {
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        assertTrue(impl.isAvailable("csharp"));
        assertTrue(impl.isAvailable("CSharp"), "la clé est normalisée (casse)");
        // Les grammaires tierces (java, python…) sont EMBARQUÉES mais
        // inactives : opt-in explicite par loadEmbeddedGrammar (le
        // rendu d'un hôte qui croche le module sans rien demander ne
        // change pas — contrat « opt-in par langage »).
        assertFalse(impl.isAvailable("java"),
                "java reste au tokéniseur intégré tant que non activé");
        assertFalse(impl.isAvailable(null));
    }

    // ── Grammaires tierces embarquées (C3-quater) ───────────────────

    @Test
    void grammaireTierce_inactiveParDéfaut_activeParAppel() {
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        assertFalse(impl.isAvailable("java"), "inactive après create()");
        impl.loadEmbeddedGrammar("Java"); // clé normalisée
        assertTrue(impl.isAvailable("java"));
        String line = "  // ligne de commentaire";
        StyledLine l = impl.tokenize(line, 0, "java");
        assertEquals(TokenType.COMMENT, typeAt(l, line.indexOf("//")));
        assertEquals(0, l.exitState, "le wrapper (?!\\G) se referme en fin de ligne");
        // Idempotent : réactiver remplace sans erreur.
        impl.loadEmbeddedGrammar("java");
        assertTrue(impl.isAvailable("java"));
    }

    @Test
    void grammaireTierce_python_tokéniseRéellement() {
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("python");
        String line = "def ma_fonction(x):  # commentaire";
        StyledLine l = impl.tokenize(line, 0, "python");
        assertEquals(TokenType.TYPE, typeAt(l, line.indexOf("def")));
        assertEquals(TokenType.COMMENT, typeAt(l, line.indexOf("#")));
    }

    @Test
    void grammaireTierce_inconnu_leveUneErreurClaire() {
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        TextMateGrammar.GrammarException e = assertThrows(
                TextMateGrammar.GrammarException.class,
                () -> impl.loadEmbeddedGrammar("brainfuck"));
        assertTrue(e.getMessage().contains("brainfuck"));
        assertTrue(e.getMessage().contains("inventaire"),
                "le message liste l'inventaire : " + e.getMessage());
    }

    @Test
    void grammaireTierce_inventaireCompletChargeEtTokénise() {
        // Les 7 grammaires tierces MIT embarquées chargent (parse
        // indulgent C3-bis) et tokénisent sans exception — l'échantillon
        // typique de chaque langage rend un span non vide.
        String[] langs = {"html", "java", "javascript", "lua",
                "markdown", "python", "xml"};
        String[] samples = {
                "<div class=\"x\">texte</div>",
                "public class Foo { /* x */ }",
                "const f = (a) => { return `tpl ${a}`; };",
                "local function f(x) return x * 2 end",
                "# Titre avec `code`",
                "def ma_fonction(x):  # commentaire",
                "<TextView android:text=\"salut\" />"};
        for (int i = 0; i < langs.length; i++) {
            TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
            impl.loadEmbeddedGrammar(langs[i]);
            assertTrue(impl.isAvailable(langs[i]), "chargée : " + langs[i]);
            StyledLine l = impl.tokenize(samples[i], 0, langs[i]);
            assertFalse(l.spans.isEmpty(), "spans non vides : " + langs[i]);
        }
    }

    @Test
    void blocDeCodeMarkdown_coloréParLaGrammaireIncluse() {
        // Imbrication des grammaires embarquées (C3-quinquies) : le
        // bloc ```java d'un document Markdown est coloré par la
        // grammaire JAVA — la clause while du bloc embarqué (garde de
        // continuation de ligne) et la résolution des #ref de
        // L'INVITÉE dans SON repository (owner) font toute la chaîne
        // travailler : int TYPE, x VARIABLE, = OPERATOR, 1 NUMBER.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("markdown");
        impl.loadEmbeddedGrammar("java");
        StyledLine l1 = impl.tokenize("```java", 0, "markdown");
        assertTrue(l1.exitState != 0, "le bloc de code est ouvert");
        String line = "int x = 1;";
        StyledLine l2 = impl.tokenize(line, l1.exitState, "markdown");
        assertEquals(TokenType.TYPE, typeAt(l2, line.indexOf("int")));
        assertEquals(TokenType.VARIABLE, typeAt(l2, line.indexOf("x")));
        assertEquals(TokenType.OPERATOR, typeAt(l2, line.indexOf("=")));
        assertEquals(TokenType.NUMBER, typeAt(l2, line.indexOf("1")));
        // La clôture referme le bloc (le while échoue sur la ligne de
        // clôture, la fermante du bloc parent matche).
        StyledLine l3 = impl.tokenize("```", l2.exitState, "markdown");
        assertEquals(0, l3.exitState, "le bloc se referme");
    }

    @Test
    void tokénise_csharp_codeRéel() {
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        String line = "public class Foo { // note";
        StyledLine l = impl.tokenize(line, 0, "csharp");
        TokenType pub = typeAt(l, line.indexOf("public"));
        TokenType klass = typeAt(l, line.indexOf("class"));
        // public = storage.modifier ; class = keyword.other (les deux
        // mots-clés) ; le commentaire de fin est COMMENT.
        assertEquals(TokenType.KEYWORD_MODIFIER, pub);
        assertEquals(TokenType.KEYWORD, klass);
        assertEquals(TokenType.COMMENT, typeAt(l, line.indexOf("//")));
    }

    @Test
    void chaîneCSharp_multiligne_parLaGrammaire() {
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        String l1 = "string s = \"abc";
        StyledLine s1 = impl.tokenize(l1, 0, "csharp");
        assertTrue(s1.exitState != 0, "la chaîne non fermée empile un contexte");
        String l2 = "suite";
        StyledLine s2 = impl.tokenize(l2, s1.exitState, "csharp");
        assertEquals(TokenType.STRING, s2.spans.get(0).type);
        String l3 = "fin\";";
        StyledLine s3 = impl.tokenize(l3, s1.exitState, "csharp");
        assertEquals(0, s3.exitState);
    }

    @Test
    void grammaireMaison_enregistrementHôte() {
        TextMateTokenizerImpl impl = new TextMateTokenizerImpl();
        impl.registerGrammar("mylang", """
                {
                  "scopeName": "source.mylang",
                  "patterns": [
                    { "match": "#[^\\\\n]*", "name": "comment.line.mylang" }
                  ]
                }
                """);
        assertTrue(impl.isAvailable("mylang"));
        String line = "# un commentaire";
        StyledLine l = impl.tokenize(line, 0, "mylang");
        assertEquals(TokenType.COMMENT, l.spans.get(0).type);
        // Retrait : retour à l'indisponibilité.
        impl.unregisterGrammar("mylang");
        assertFalse(impl.isAvailable("mylang"));
    }

    @Test
    void enregistrementInvalide_échoueAuChargement() {
        TextMateTokenizerImpl impl = new TextMateTokenizerImpl();
        assertThrows(TextMateGrammar.GrammarException.class,
                () -> impl.registerGrammar("bad", "{pas du json"));
        assertThrows(IllegalArgumentException.class,
                () -> impl.registerGrammar("  ", "{}"));
        assertFalse(impl.isAvailable("bad"));
    }

    // ── Intégration : le crochet SyntaxHighlighter délègue ───────

    @Test
    void crochageSyntaxHighlighter_délègueEtRetombe() {
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        SyntaxHighlighter highlighter = new SyntaxHighlighter();
        // Ligne riche (modificateur, type, ponctuation, commentaire) :
        // le repli intégré C_LIKE et la grammaire ne tokénisent PAS de
        // la même façon ({ devient PUNCT côté intégré, les portées
        // fines diffèrent) — la comparaison des spans prouve la
        // délégation sans dépendre d'un type précis.
        String line = "public sealed class ItemRegistry { // note";

        // AVANT crochage : csharp retombe sur le tokéniseur intégré
        // (C_LIKE générique avec les mots-clés C# du profil — D5).
        StyledLine integrated = highlighter.styleLine(line, 0, "csharp");

        // Crochage : la grammaire prend le pas — la tokénisation change.
        SyntaxHighlighter.setTextMateTokenizer(impl);
        StyledLine viaGrammar = highlighter.styleLine(line, 0, "csharp");
        assertNotEquals(integrated.spans, viaGrammar.spans,
                "le crochage doit changer la coloration (voie grammaires)");

        // Décrochage : retour exact au repli intégré (chemin additif,
        // déterministe).
        SyntaxHighlighter.setTextMateTokenizer(null);
        StyledLine backToIntegrated = highlighter.styleLine(line, 0, "csharp");
        assertEquals(integrated.spans, backToIntegrated.spans,
                "le décrochage restaure le repli intégré octet pour octet");
    }

    @Test
    void tokénisationLigneNull_repliDéfensif() {
        TextMateTokenizerImpl impl = new TextMateTokenizerImpl();
        impl.registerGrammar("x", "{\"scopeName\": \"s.x\", \"patterns\": []}");
        StyledLine l = impl.tokenize(null, 3, "x");
        assertEquals(0, l.spans.size());
        assertEquals(0, l.exitState);
    }

    private static TokenType typeAt(StyledLine l, int col) {
        for (LineSpan s : l.spans) {
            if (col >= s.startCol && col < s.endCol) return s.type;
        }
        throw new AssertionError("aucun span en colonne " + col + " : " + l.spans);
    }
}
