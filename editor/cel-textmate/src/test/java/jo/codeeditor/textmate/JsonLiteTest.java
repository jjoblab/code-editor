package jo.codeeditor.textmate;

import jo.codeeditor.textmate.internal.JsonLite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (C3) — le parseur JSON interne du module de grammaires :
 * sous-ensemble des grammaires + tolérance JSONC (commentaires des
 * grammaires de l'écosystème). Le module ne dépend de :cel-core seul
 * — aucun parseur JSON tiers (D1-a).
 */
class JsonLiteTest {

    @Test
    void objetSimple_etTypes() {
        Map<String, Object> root = JsonLite.parseObject(
                "{\"scopeName\": \"source.x\", \"count\": 3, \"flag\": true, \"nil\": null}");
        assertEquals("source.x", root.get("scopeName"));
        assertEquals(3.0, root.get("count"));
        assertEquals(Boolean.TRUE, root.get("flag"));
        assertTrue(root.containsKey("nil") && root.get("nil") == null);
    }

    @Test
    void tableauxEtObjetsImbriqués() {
        Map<String, Object> root = JsonLite.parseObject(
                "{\"patterns\": [{\"match\": \"a\"}, {\"include\": \"#b\"}],"
                        + " \"repository\": {\"b\": {\"match\": \"b\"}}}");
        List<Object> patterns = JsonLite.list(root.get("patterns"));
        assertEquals(2, patterns.size());
        assertEquals("a", JsonLite.obj(patterns.get(0)).get("match"));
        Map<String, Object> repo = JsonLite.obj(root.get("repository"));
        assertEquals("b", JsonLite.obj(repo.get("b")).get("match"));
    }

    @Test
    void échappementsDeChaîne() {
        Map<String, Object> root = JsonLite.parseObject(
                "{\"re\": \"\\\\w+\\\\s*(?:\\\"q\\\")\", \"tab\": \"a\\tb\","
                        + " \"u\": \"\\u0041\"}");
        assertEquals("\\w+\\s*(?:\"q\")", root.get("re"));
        assertEquals("a\tb", root.get("tab"));
        assertEquals("A", root.get("u"));
    }

    @Test
    void toléranceJsonC_commentairesEtVirguleFinale() {
        Map<String, Object> root = JsonLite.parseObject(
                "// en-tête\n{\n  /* bloc\n     multi-ligne */\n"
                        + "  \"scopeName\": \"source.c\", // fin de ligne\n"
                        + "  \"patterns\": [ { \"match\": \"x\" }, ],\n}");
        assertEquals("source.c", root.get("scopeName"));
        assertEquals(1, JsonLite.list(root.get("patterns")).size());
    }

    @Test
    void erreurs_SyntaxeEtPosition() {
        JsonLite.JsonLiteException e = assertThrows(JsonLite.JsonLiteException.class,
                () -> JsonLite.parseObject("{\"a\": }"));
        assertTrue(e.getMessage().contains("ligne 1"));
        assertThrows(JsonLite.JsonLiteException.class,
                () -> JsonLite.parseObject("{pas un objet"));
        assertThrows(JsonLite.JsonLiteException.class,
                () -> JsonLite.parseObject("{\"a\": \"non fermée}"));
    }

    @Test
    void accesseursNullSûrs() {
        assertEquals(null, JsonLite.str(null));
        assertEquals(null, JsonLite.str(42));
        assertTrue(JsonLite.list("nope").isEmpty());
        assertTrue(JsonLite.obj("nope").isEmpty());
    }
}
