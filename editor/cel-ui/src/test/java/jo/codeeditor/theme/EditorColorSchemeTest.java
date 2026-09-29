package jo.codeeditor.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.util.List;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Scheme de couleurs modifiable (lot 4 #25) : maps éparses (une clé
 * absente = « pas d'opinion »), résolution en cascade (override →
 * parent si héritage visuel → base), sérialisation plate des seuls
 * overrides, application sur la vue avec purge des caches.
 */
@RunWith(RobolectricTestRunner.class)
public class EditorColorSchemeTest {

    // ── Registre ─────────────────────────────────────────────────

    @Test
    public void registry_complete_coversAllThemeKeys() {
        assertEquals("le registre doit décrire 31 attributs (les 31 couleurs du constructeur)",
                31, ColorAttributes.all().size());
        // Clés pivots présentes.
        assertTrue(ColorAttributes.isValidKey(ColorAttributes.KEY_SELECTION));
        assertTrue(ColorAttributes.isValidKey(ColorAttributes.KEY_KEYWORD));
        assertTrue(ColorAttributes.isValidKey(ColorAttributes.KEY_TEXT));
        assertFalse(ColorAttributes.isValidKey("nimporte.quoi"));
        // Un parent précède toujours ses enfants (précondition du passage unique).
        for (ColorAttribute a : ColorAttributes.all()) {
            if (a.parent == null) continue;
            int pi = -1, ci = -1;
            List<ColorAttribute> all = ColorAttributes.all();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).key.equals(a.parent)) pi = i;
                if (all.get(i).key.equals(a.key)) ci = i;
            }
            assertTrue("le parent " + a.parent + " de " + a.key
                    + " doit être déclaré avant", pi >= 0 && pi < ci);
        }
    }

    @Test
    public void registry_get_readsRealThemeField() {
        EditorTheme dark = EditorTheme.dark();
        assertEquals(dark.keyword, ColorAttributes.get(dark, ColorAttributes.KEY_KEYWORD));
        assertEquals(dark.selection, ColorAttributes.get(dark, ColorAttributes.KEY_SELECTION));
    }

    // ── Résolution : sparse map / cascade / parents ──────────────

    @Test
    public void resolve_noOpinion_returnsBaseUntouched() {
        EditorColorScheme s = EditorColorScheme.createDefault();
        EditorTheme out = s.resolve(EditorTheme.dark(), true);
        assertEquals(EditorTheme.dark().keyword, out.keyword);
        assertEquals(EditorTheme.dark().selection, out.selection);
        assertEquals(EditorTheme.dark().editorBg, out.editorBg);
    }

    @Test
    public void resolve_override_appliesOnlyToThatKey() {
        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFFFF0000, true);
        EditorTheme out = s.resolve(EditorTheme.dark(), true);
        assertEquals(0xFFFF0000, out.keyword);
        // Le reste du thème suit la base.
        assertEquals(EditorTheme.dark().string, out.string);
        assertEquals(EditorTheme.dark().editorBg, out.editorBg);
        assertEquals(EditorTheme.dark().selection, out.selection);
    }

    @Test
    public void resolve_modeIsolation_darkOverrideDoesNotLeakToLight() {
        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFFFF0000, true);
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFF00FF00, false);

        assertEquals(0xFFFF0000, s.resolve(EditorTheme.dark(), true).keyword);
        assertEquals(0xFF00FF00, s.resolve(EditorTheme.dark(), false).keyword);
    }

    @Test
    public void resolve_parentFollows_whenChildInheritedVisually() {
        // dark() : gutter.background == editor.background (1E1E1E) —
        // l'enfant héritait visuellement → recolorer le fond recolore
        // aussi la gouttière sauf opinion contraire.
        assertEquals(EditorTheme.dark().editorBg, EditorTheme.dark().gutterBg);

        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_EDITOR_BG, 0xFF101010, true);
        EditorTheme out = s.resolve(EditorTheme.dark(), true);
        assertEquals("la gouttière doit suivre le fond qu'elle héritait",
                0xFF101010, out.gutterBg);
        // …mais l'override explicite de l'enfant gagne sur l'héritage.
        s.setColor(ColorAttributes.KEY_GUTTER_BG, 0xFF202020, true);
        out = s.resolve(EditorTheme.dark(), true);
        assertEquals(0xFF101010, out.editorBg);
        assertEquals(0xFF202020, out.gutterBg);
    }

    @Test
    public void resolve_parentOverride_doesNotTouchChildWhenValuesDiffered() {
        // dark() : escape (FFD700) != string (CE9178) — aucune héritage
        // visuelle dans la base → recolorer string laisse escape intact.
        assertFalse(EditorTheme.dark().string == EditorTheme.dark().escape);

        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_STRING, 0xFF112233, true);
        EditorTheme out = s.resolve(EditorTheme.dark(), true);
        assertEquals(0xFF112233, out.string);
        assertEquals("escape ne doit pas suivre string (valeurs différentes en base)",
                EditorTheme.dark().escape, out.escape);
    }

    @Test
    public void resolve_operatorFollowsPunct_inDarkBase() {
        // dark() : operator == punct (D4D4D4) — héritage visuel.
        assertEquals(EditorTheme.dark().punct, EditorTheme.dark().operator);
        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_PUNCT, 0xFFAABBCC, true);
        assertEquals("les opérateurs suivent la ponctuation recolorée",
                0xFFAABBCC, s.resolve(EditorTheme.dark(), true).operator);
    }

    @Test
    public void resolve_unset_returnsToBase() {
        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_COMMENT, 0xFF112233, true);
        s.unsetColor(ColorAttributes.KEY_COMMENT, true);
        assertNull(s.getColor(ColorAttributes.KEY_COMMENT, true));
        assertEquals(EditorTheme.dark().comment,
                s.resolve(EditorTheme.dark(), true).comment);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setColor_unknownKey_throws() {
        EditorColorScheme.createDefault().setColor("pas.une.cle", 0xFFFF0000, true);
    }

    // ── Sérialisation plate (overrides seuls) ────────────────────

    @Test
    public void json_roundTrip_preservesOverrides() throws JSONException {
        EditorColorScheme s = EditorColorScheme.create("mon-scheme", "Mon scheme", "nord");
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFF123456, true);
        s.setColor(ColorAttributes.KEY_SELECTION, 0xFFABCDEF, false);

        EditorColorScheme back = EditorColorScheme.fromJson(s.toJson());
        assertEquals("mon-scheme", back.getId());
        assertEquals("Mon scheme", back.getName());
        assertEquals("nord", back.getBasedOn());
        assertEquals(0xFF123456, back.getColor(ColorAttributes.KEY_KEYWORD, true).intValue());
        assertEquals(0xFFABCDEF, back.getColor(ColorAttributes.KEY_SELECTION, false).intValue());
        assertEquals(1, back.overrideCount(true));
        assertEquals(1, back.overrideCount(false));
    }

    @Test
    public void json_exportContainsOnlyOverrides() throws JSONException {
        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFF123456, true);
        String json = s.toJson();
        assertTrue("la clé surchargée doit figurer dans le document",
                json.contains("syntax.keyword"));
        assertFalse("le document ne doit pas contenir les clés sans opinion (string)",
                json.contains("syntax.string"));
        assertFalse("le document ne doit pas contenir les clés sans opinion (gutter.text)",
                json.contains("gutter.text"));
    }

    @Test
    public void json_ignoresUnknownKeys() throws JSONException {
        String json = "{\"schema\":1,\"id\":\"x\",\"name\":\"X\",\"basedOn\":\"dark\","
                + "\"dark\":{\"syntax.keyword\":\"#112233\",\"cle.inconnue\":\"#445566\"},"
                + "\"light\":{}}";
        EditorColorScheme s = EditorColorScheme.fromJson(json);
        assertEquals(0xFF112233, s.getColor(ColorAttributes.KEY_KEYWORD, true).intValue());
        assertNull(s.getColor("cle.inconnue", true));
        assertEquals(1, s.overrideCount(true));
    }

    @Test
    public void json_roundTrip_resolvesIdentically() throws JSONException {
        EditorColorScheme s = EditorColorScheme.emeraldAccents();
        EditorColorScheme back = EditorColorScheme.fromJson(s.toJson());
        EditorTheme a = s.resolve(EditorTheme.dark(), true);
        EditorTheme b = back.resolve(EditorTheme.dark(), true);
        assertEquals(a.keyword, b.keyword);
        assertEquals(a.selection, b.selection);
        assertEquals(a.comment, b.comment);
        assertEquals(a.editorBg, b.editorBg);
    }

    @Test
    public void colorParsing_supportsBothFormats() {
        assertEquals(0xFF123456, EditorColorScheme.parseColor("#123456").intValue());
        assertEquals(0x80123456, EditorColorScheme.parseColor("#80123456").intValue());
        assertNull(EditorColorScheme.parseColor("rouge"));
        assertNull(EditorColorScheme.parseColor(null));
    }

    // ── Intégration vue ──────────────────────────────────────────

    @Test
    public void view_appliesScheme_andRecolorsTheme() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("int x = 42;\n"));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFFCAFE12, true);
        view.setColorScheme(s);

        assertEquals("la vue doit peindre le thème résolu",
                0xFFCAFE12, view.theme.keyword);
        assertEquals("gutter suit le fond hérité — ici sans opinion, valeurs de base",
                EditorTheme.dark().gutterBg, view.theme.gutterBg);
    }

    @Test
    public void view_schemeDarkModeSwitch_resolvesOtherMap() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("int x = 42;\n"));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFF111111, true);
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFFEEEEEE, false);
        view.setColorScheme(s);
        assertEquals(0xFF111111, view.theme.keyword);

        view.setSchemeDarkMode(false);
        assertEquals(0xFFEEEEEE, view.theme.keyword);
    }

    @Test
    public void view_nullScheme_restoresBaseTheme() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("int x = 42;\n"));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        EditorTheme base = EditorTheme.nord();
        view.setSchemeBaseTheme(base);
        EditorColorScheme s = EditorColorScheme.createDefault();
        s.setColor(ColorAttributes.KEY_KEYWORD, 0xFFCAFE12, true);
        view.setColorScheme(s);
        assertEquals(0xFFCAFE12, view.theme.keyword);

        view.setColorScheme(null);
        assertEquals("sans scheme, le thème de référence brut revient",
                base.keyword, view.theme.keyword);
    }

    @Test
    public void view_schemedTheme_rendersWithoutCrash() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(
                "// commentaire\nint x = 42;\n"));
        view.setSession(session);
        view.setWordWrap(true);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        view.setColorScheme(EditorColorScheme.emeraldAccents());
        // Le thème résolu est bien celui de la vue…
        assertEquals(EditorColorScheme.emeraldAccents()
                        .resolve(EditorTheme.dark(), true).selection,
                view.theme.selection);
        // …et une frame complète se dessine sans exception, avec le fond
        // résolu présent dans le rendu (convention « présence » du dépôt —
        // le canvas shadow Robolectric ne garantit pas plus).
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        int bgHits = 0;
        for (int y = 0; y < bmp.getHeight(); y += 3) {
            for (int x = 0; x < bmp.getWidth(); x += 3) {
                if (bmp.getPixel(x, y) == view.theme.editorBg) bgHits++;
            }
        }
        bmp.recycle();
        assertTrue("le fond du thème résolu doit apparaître dans le rendu"
                + " (touches=" + bgHits + ")", bgHits > 0);
    }
}
