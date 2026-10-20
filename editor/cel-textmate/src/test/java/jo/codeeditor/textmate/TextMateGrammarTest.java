package jo.codeeditor.textmate;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (C3) — le chargement des grammaires : format, repository,
 * compilations des regex (échec au CHARGEMENT, jamais en coloration).
 */
class TextMateGrammarTest {

    /** Grammaire C# embarquée réelle (classpath du module). */
    private static String embeddedCSharp() {
        try (InputStream in = TextMateGrammarTest.class.getClassLoader()
                .getResourceAsStream("textmate/csharp.tmg.json");
                Scanner sc = new Scanner(in, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void grammaireCSharpeEmbarquée_parse() {
        String source = embeddedCSharp();
        assertNotNull(source, "la grammaire csharp.tmg.json doit être sur le classpath");
        TextMateGrammar g = TextMateGrammar.parse(source);
        assertEquals("source.csharp", g.scopeName);
        assertEquals("C#", g.name);
        assertEquals(11, g.patterns.size());
        assertNotNull(g.repository.get("strings"));
        assertNotNull(g.repository.get("keywords"));
        assertNotNull(g.repository.get("doc-comments"));
        // Les includes racine référencent le repository.
        assertTrue(g.patterns.get(0).include.startsWith("#"));
    }

    @Test
    void règlesBeginEnd_avecCapturesEtSousRègles() {
        // Grammaire de test en ressource (mêmes niveaux d'échappement
        // que les grammaires réelles : JSON → regex).
        String source = readResource("textmate/captures-test.tmg.json");
        TextMateGrammar g = TextMateGrammar.parse(source);
        TextMateGrammar.Rule r = g.patterns.get(0);
        assertNull(r.match);
        assertNotNull(r.begin);
        assertEquals("\"", r.end);
        assertEquals(1, r.patterns.size());
        assertEquals("string.quoted.double.capt", r.contentName);
        assertEquals("punctuation.definition.string.begin.capt", r.beginCaptures.get(0));
        assertEquals("punctuation.definition.string.end.capt", r.endCaptures.get(0));
    }

    /** Lit une ressource du classpath de test. */
    private static String readResource(String path) {
        try (InputStream in = TextMateGrammarTest.class.getClassLoader()
                .getResourceAsStream(path);
                Scanner sc = new Scanner(in, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void échecs_Syntaxe_Regex_EtInclusionExterne() {
        // scopeName manquant.
        assertThrows(TextMateGrammar.GrammarException.class,
                () -> TextMateGrammar.parse("{\"patterns\": []}"));
        // Regex incompilable : signalée au chargement avec la règle.
        TextMateGrammar.GrammarException e = assertThrows(
                TextMateGrammar.GrammarException.class,
                () -> TextMateGrammar.parse(
                        "{\"scopeName\": \"s\", \"repository\": "
                                + "{\"r\": {\"match\": \"(unclosed\"}}}"));
        assertTrue(e.getMessage().contains("r"));
        // JSON illisible.
        assertThrows(TextMateGrammar.GrammarException.class,
                () -> TextMateGrammar.parse("{pas du json"));
        // Plist XML illisible (racine non plist).
        assertThrows(TextMateGrammar.GrammarException.class,
                () -> TextMateGrammar.parse("<plist><array><string>x</string></array></plist>"));
    }

    // ── Compatibilité grammaires tierces (C3-bis) ────────────────────

    @Test
    void sourcePlist_parseIdentiqueAuJson() {
        // Les grammaires TextMate historiques (kotlin, plist XML)
        // sont des plist XML — même modèle après parse.
        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<plist version=\"1.0\">\n"
                + "  <dict>\n"
                + "    <key>scopeName</key><string>source.ex</string>\n"
                + "    <key>name</key><string>Exemple</string>\n"
                + "    <key>patterns</key>\n"
                + "    <array>\n"
                + "      <dict>\n"
                + "        <key>match</key><string>\\bfoo\\b</string>\n"
                + "        <key>name</key><string>keyword.control.ex</string>\n"
                + "      </dict>\n"
                + "    </array>\n"
                + "    <key>repository</key>\n"
                + "    <dict>\n"
                + "      <key>strings</key>\n"
                + "      <dict>\n"
                + "        <key>begin</key><string>\"</string>\n"
                + "        <key>end</key><string>\"</string>\n"
                + "        <key>name</key><string>string.quoted.ex</string>\n"
                + "      </dict>\n"
                + "    </dict>\n"
                + "  </dict>\n"
                + "</plist>\n";
        TextMateGrammar g = TextMateGrammar.parse(plist);
        assertEquals("source.ex", g.scopeName);
        assertEquals("Exemple", g.name);
        assertEquals(1, g.patterns.size());
        assertNotNull(g.patterns.get(0).match);
        assertEquals("string.quoted.ex", g.repository.get("strings").name);
        assertNotNull(g.repository.get("strings").begin);
    }

    @Test
    void clauseWhile_acceptéeCommeEnd() {
        // Les grammaires modernes (markdown tierce) utilisent
        // begin/while pour les blocs continus — la clause while est
        // conservée SÉPARÉMENT (C3-quinquies) : c'est une garde de
        // continuation de ligne (sémantique TextMate), PAS une
        // fermante en ligne — le while des blocs de code markdown est
        // un lookahead négatif qui oscillerait en fermante. Une règle
        // begin+while sans end est valide.
        String source = "{\"scopeName\": \"s\", \"patterns\": ["
                + "{\"begin\": \"^>\", \"while\": \"^>\", "
                + "\"name\": \"markup.quote\"}]}";
        TextMateGrammar g = TextMateGrammar.parse(source);
        assertEquals("^>", g.patterns.get(0).whileSrc, "while conservé comme garde");
        assertEquals(null, g.patterns.get(0).end, "pas de fermante en ligne");
    }

    @Test
    void includeExterne_acceptéEtCollecté() {
        // Un include source.xxx (html → source.css, markdown → ses
        // blocs de code…) ne fait plus ÉCHOUER le chargement : la règle
        // est conservée, la référence collectée — la résolution a lieu
        // à la tokénisation (registre de grammaires de l'hôte, repli
        // dégradé documenté si absente).
        String source = "{\"scopeName\": \"source.html\", \"patterns\": ["
                + "{\"include\": \"source.css\"}, "
                + "{\"include\": \"#local\"}], "
                + "\"repository\": {\"local\": {\"match\": \"x\"}}}";
        TextMateGrammar g = TextMateGrammar.parse(source);
        assertEquals("source.css", g.patterns.get(0).include);
        assertTrue(g.externalRefs.contains("source.css"),
                "la référence externe est collectée pour le registre");
    }

    @Test
    void regexOniguruma_commentairesEtAccolades() {
        // Constructions du dialecte auparavant TRADUITES (accolade seule
        // échappée, (?# …) retiré) : natives dans le moteur embarqué
        // (plan 10) — la traduction a disparu avec le traducteur, ces
        // tests verrouillent désormais le comportement NATIF.
        TextMateGrammar braces = TextMateGrammar.parse(
                "{\"scopeName\": \"s\", \"patterns\": "
                        + "[{\"match\": \"\\\\{\", \"name\": \"punct\"}]}");
        assertTrue(matchSur(braces.patterns.get(0).match, "{"),
                "accolade littérale");
        TextMateGrammar comment = TextMateGrammar.parse(
                "{\"scopeName\": \"s\", \"patterns\": "
                        + "[{\"match\": \"a(?# comment)b\", \"name\": \"kw\"}]}");
        assertTrue(matchSur(comment.patterns.get(0).match, "ab"),
                "le commentaire inline est natif");
        // {3} reste un quantificateur valide — natif, jamais échappé.
        TextMateGrammar quant = TextMateGrammar.parse(
                "{\"scopeName\": \"s\", \"patterns\": "
                        + "[{\"match\": \"a{3}\", \"name\": \"kw\"}]}");
        assertTrue(matchSur(quant.patterns.get(0).match, "aaa"));
    }

    /** Essai ancré du motif compilé sur un texte nu (via la couture). */
    private static boolean matchSur(jo.codeeditor.textmate.regex.CompiledRegex motif,
            String texte) {
        jo.codeeditor.textmate.regex.EncodedLine ligne =
                jo.codeeditor.textmate.regex.EncodedLine.of(texte);
        return motif.matcher(ligne).matchAt(0);
    }
}
