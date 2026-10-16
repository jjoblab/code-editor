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
        // Calibrage C4 : « def » (storage.type.function.python) est un
        // MOT-CLÉ côté voie intégrée — pas un TYPE.
        assertEquals(TokenType.KEYWORD, typeAt(l, line.indexOf("def")));
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
        // Les 9 grammaires tierces embarquées chargent (parse
        // indulgent C3-bis, plist XML inclus pour kotlin) et
        // tokénisent sans exception — l'échantillon typique de chaque
        // langage rend un span non vide.
        String[] langs = {"css", "html", "java", "javascript", "kotlin",
                "lua", "markdown", "python", "xml"};
        String[] samples = {
                "body { color: red; }",
                "<div class=\"x\">texte</div>",
                "public class Foo { /* x */ }",
                "const f = (a) => { return `tpl ${a}`; };",
                "fun main() { println(\"salut\") } // note",
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
        // travailler : int KEYWORD (primitif — calibrage C4), x
        // VARIABLE, = OPERATOR, 1 NUMBER.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("markdown");
        impl.loadEmbeddedGrammar("java");
        StyledLine l1 = impl.tokenize("```java", 0, "markdown");
        assertTrue(l1.exitState != 0, "le bloc de code est ouvert");
        String line = "int x = 1;";
        StyledLine l2 = impl.tokenize(line, l1.exitState, "markdown");
        assertEquals(TokenType.KEYWORD, typeAt(l2, line.indexOf("int")),
                "primitif = mot-clé (convergence voie intégrée)");
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

    // ── C3-sexties : imbrication html → grammaire invitée ─────────

    @Test
    void grammaireCss_activeParAppel_tokéniseUnVraiStyle() {
        // CSS embarquée (grammaire tierce, MIT) : opt-in par
        // loadEmbeddedGrammar, sélecteur en
        // TYPE (entity.name.tag), propriété en TYPE (support.type via
        // la liste de portées), valeur de couleur en CONSTANT.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        assertFalse(impl.isAvailable("css"), "inactive après create()");
        impl.loadEmbeddedGrammar("css");
        assertTrue(impl.isAvailable("css"));
        String line = "body { color: red; }";
        StyledLine l = impl.tokenize(line, 0, "css");
        assertEquals(TokenType.TYPE, typeAt(l, line.indexOf("body")));
        assertEquals(TokenType.TYPE, typeAt(l, line.indexOf("color")));
        assertEquals(TokenType.CONSTANT, typeAt(l, line.indexOf("red")));
    }

    @Test
    void blocStyleHtml_coloréParLaGrammaireCss() {
        // L'imbrication de l'option (c) : le bloc <style> d'un document
        // html est délégué à la grammaire CSS incluse (source.css) —
        // le sélecteur body en TYPE (entity.name.tag), la propriété en
        // TYPE (support.type), pas en attribut html (PROPERTY). Avant
        // les correctifs C3-sexties (idiome \G en tête de begin), le
        // cadre du contenu ne s'ouvrait pas et le CSS tombait en
        // attributs ; sans la grammaire css enregistrée, il restait PLAIN.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("html");
        impl.loadEmbeddedGrammar("css");
        StyledLine l1 = impl.tokenize("<style>", 0, "html");
        assertTrue(l1.exitState != 0, "le bloc style est ouvert");
        String line = "body { color: red; }";
        StyledLine l2 = impl.tokenize(line, l1.exitState, "html");
        assertEquals(TokenType.TYPE, typeAt(l2, line.indexOf("body")),
                "body est un sélecteur CSS (entity.name.tag), pas un attribut");
        assertEquals(TokenType.TYPE, typeAt(l2, line.indexOf("color")),
                "color est un nom de propriété CSS");
        assertEquals(TokenType.CONSTANT, typeAt(l2, line.indexOf("red")));
        StyledLine l3 = impl.tokenize("</style>", l2.exitState, "html");
        assertEquals(0, l3.exitState, "la clôture referme le bloc style");
    }

    @Test
    void grammaireKotlinPlist_activeParAppel_tokénise() {
        // Option (b) : la grammaire KOTLIN (format plist XML, le
        // TextMate historique) rejoint l'inventaire embarqué. La
        // copie est l'originale du dépôt d'origine SANS le prolog
        // XML plist (DOCTYPE refusé par le parseur du module,
        // protection XXE) — UUID et contenu identiques à l'amont.
        // fun → keyword.other → KEYWORD, chaîne → STRING ; val →
        // storage.type → TYPE, littéral → NUMBER, commentaire de
        // ligne → COMMENT.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        assertFalse(impl.isAvailable("kotlin"), "inactive après create()");
        impl.loadEmbeddedGrammar("Kotlin"); // clé normalisée
        assertTrue(impl.isAvailable("kotlin"));
        String l1 = "fun main() { println(\"salut\") }";
        StyledLine s1 = impl.tokenize(l1, 0, "kotlin");
        assertEquals(TokenType.KEYWORD, typeAt(s1, l1.indexOf("fun")));
        assertEquals(TokenType.STRING, typeAt(s1, l1.indexOf("\"salut\"")));
        String l2 = "val reponse = 42 // réponse";
        StyledLine s2 = impl.tokenize(l2, 0, "kotlin");
        assertEquals(TokenType.TYPE, typeAt(s2, l2.indexOf("val")));
        assertEquals(TokenType.NUMBER, typeAt(s2, l2.indexOf("42")));
        assertEquals(TokenType.COMMENT, typeAt(s2, l2.indexOf("//")));
    }

    @Test
    void blocScriptHtml_coloréParLaGrammaireJavaScript() {
        // La paire d'idiomes {begin: \G}/{begin: (?!\G)} du wrapper
        // <script> de la grammaire html : le contenu du bloc doit être
        // coloré par la grammaire JAVASCRIPT incluse (source.js), pas
        // par les règles d'attributs de la tête de balise. Avant le
        // correctif moteur, la tête se ré-empilait après « > » et
        // avalait le script en attributs.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("html");
        impl.loadEmbeddedGrammar("javascript");
        StyledLine l1 = impl.tokenize("<script>", 0, "html");
        assertTrue(l1.exitState != 0, "le bloc script est ouvert");
        String line = "var x = 1;";
        StyledLine l2 = impl.tokenize(line, l1.exitState, "html");
        // var → storage.type.js → TYPE (la voie attributs donnait
        // entity.other.attribute-name → PROPERTY).
        assertEquals(TokenType.TYPE, typeAt(l2, line.indexOf("var")),
                "var est un mot-clé de stockage JS, pas un attribut html");
        assertEquals(TokenType.NUMBER, typeAt(l2, line.indexOf("1")),
                "le littéral 1 est un nombre JS");
    }

    private static TokenType typeAt(StyledLine l, int col) {
        for (LineSpan s : l.spans) {
            if (col >= s.startCol && col < s.endCol) return s.type;
        }
        throw new AssertionError("aucun span en colonne " + col + " : " + l.spans);
    }

    // ── Régressions retour terrain 3.72.0 : idiomes des grammaires
    // réelles (bugs vus dans la démo après activation par langage) ──

    @Test
    void grammaireJavascript_nomDeFonctionComplet() {
        // [_$[:alpha:]] du javascript réel : sans traduction POSIX, la
        // classe devenait une union littérale et le nom de fonction
        // était coupé (« ajouter » → « a » en FUNC). Le nom doit être
        // couvert ENTIÈREMENT.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("javascript");
        String line = "function ajouter(a, b) {";
        StyledLine l = impl.tokenize(line, 0, "javascript");
        int col = line.indexOf("ajouter");
        LineSpan span = null;
        for (LineSpan s : l.spans) {
            if (s.startCol == col) span = s;
        }
        assertNotNull(span, "un span commence sur « ajouter » : " + l.spans);
        assertEquals(TokenType.FUNC, span.type);
        assertEquals(col + "ajouter".length(), span.endCol,
                "le nom de fonction est couvert ENTIÈREMENT : " + l.spans);
        // Aucun span ERROR fantôme (captures vides gonflées).
        for (LineSpan s : l.spans) {
            assertTrue(s.type != TokenType.ERROR, "pas de span ERROR : " + l.spans);
        }
    }

    @Test
    void grammaireCss_pseudoClasseRootSansErreur() {
        // (:)(:*) du CSS réel : la capture vide gonflée à un caractère
        // colorait le « r » de :root en invalid.illegal (ERROR rouge).
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("css");
        String line = ":root {";
        StyledLine l = impl.tokenize(line, 0, "css");
        for (LineSpan s : l.spans) {
            assertTrue(s.type != TokenType.ERROR,
                    "aucun span ERROR sur une pseudo-classe valide : " + l.spans);
        }
    }

    @Test
    void grammairePython_décorateurEtNomDeFonction() {
        // ((@)) du python réel : captures imbriquées de même plage —
        // un SEUL span pour « @ » ; le décorateur converge vers
        // ANNOTATION (voie intégrée) ; « def » est KEYWORD (calibrage).
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("python");
        String line = "@dataclass";
        StyledLine l = impl.tokenize(line, 0, "python");
        int spansSurArobase = 0;
        for (LineSpan s : l.spans) {
            if (s.startCol == 0) spansSurArobase++;
        }
        assertEquals(1, spansSurArobase, "un SEUL span sur « @ » : " + l.spans);
        assertEquals(TokenType.ANNOTATION, typeAt(l, 0), "le décorateur est ANNOTATION");
    }

    @Test
    void grammaireKotlin_cheminDImportEnProperty() {
        // La grammaire kotlin type le chemin d'import en
        // storage.modifier.import.java : le calibrage le ramène en
        // PROPERTY (convergence segment-après-point), pas un bloc
        // KEYWORD_MODIFIER sur tout le chemin.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("kotlin");
        String line = "import kotlinx.coroutines.launch";
        StyledLine l = impl.tokenize(line, 0, "kotlin");
        int col = line.indexOf("kotlinx");
        assertEquals(TokenType.PROPERTY, typeAt(l, col),
                "le chemin d'import est PROPERTY : " + l.spans);
    }

    @Test
    void grammaireJava_javadocEnDocComment() {
        // Le corps du javadoc java (comment.block.javadoc.java) doit
        // converger vers DOC_COMMENT comme la voie intégrée (/** */).
        // Les DÉLIMITEURS portent la portée de la grammaire tierce
        // punctuation.definition.comment (→ COMMENT, deux gris contre
        // le jade du corps) : choix de la grammaire tierce, documenté
        // dans les limites du module.
        TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
        impl.loadEmbeddedGrammar("java");
        String line = "/** Documentation de la classe. */";
        StyledLine l = impl.tokenize(line, 0, "java");
        assertEquals(TokenType.COMMENT, typeAt(l, line.indexOf("/**")));
        assertEquals(TokenType.DOC_COMMENT, typeAt(l, line.indexOf("Documentation")),
                "le CORPS du javadoc est DOC_COMMENT (javadoc calibré)");
        assertEquals(TokenType.COMMENT, typeAt(l, line.indexOf("*/")));
    }
}
