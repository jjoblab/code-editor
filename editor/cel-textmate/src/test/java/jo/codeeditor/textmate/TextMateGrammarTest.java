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
        // Include externe (grammaire tierce) : non supporté, refusé
        // explicitement plutôt qu'ignoré en silence.
        assertThrows(TextMateGrammar.GrammarException.class,
                () -> TextMateGrammar.parse(
                        "{\"scopeName\": \"s\", \"patterns\": "
                                + "[{\"include\": \"source.js\"}]}"));
        // JSON illisible.
        assertThrows(TextMateGrammar.GrammarException.class,
                () -> TextMateGrammar.parse("{pas du json"));
    }
}
