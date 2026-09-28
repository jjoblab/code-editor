package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.view.chrome.EditorTheme;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Un changement de thème doit purger le LineRenderCache : les SemSpan
 * (overlays sémantiques par ligne) y ont des couleurs CUITES du thème
 * actif — sans purge, les lignes en cache gardaient les couleurs
 * sémantiques de l'ancien thème jusqu'à leur prochaine édition.
 */
@RunWith(RobolectricTestRunner.class)
public class ThemeChangeInvalidatesRenderCacheTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        greet(name);\n"
        + "    }\n"
        + "}\n";

    @Test
    public void themeSwap_recalculatesSemanticSpanColors() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        // Token sémantique "function" sur greet (ligne 2).
        int start = DOC.indexOf("greet");
        session.setSemanticTokens(Collections.singletonList(
                new DiagnosticShift.SemanticToken(start, 5, 10)));

        // 1er draw : remplit le LineRenderCache avec les couleurs du
        // thème SOMBRE (thème par défaut).
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        view.draw(canvas);
        String lineText = session.getDocument().lineText(2);
        LineRenderCache.LineCacheEntry e1 = view.layoutForLine(2, lineText);
        assertNotNull(e1);
        assertNotNull("la ligne 2 doit avoir des spans sémantiques en cache",
                e1.semSpans);
        assertEquals(1, e1.semSpans.size());
        int colorDark = e1.semSpans.get(0).color;
        assertEquals(EditorTheme.dark().colorForToken(
                        jo.codeeditor.highlight.TokenType.FUNC),
                colorDark);

        // Changement de thème : le cache doit être purgé et le span
        // sémantique recalculé avec les couleurs du NOUVEAU thème.
        view.setTheme(EditorTheme.light());
        view.draw(canvas);
        bmp.recycle();

        LineRenderCache.LineCacheEntry e2 = view.layoutForLine(2, lineText);
        assertNotNull(e2);
        assertNotNull(e2.semSpans);
        assertEquals("le span sémantique doit porter la couleur du nouveau thème",
                EditorTheme.light().colorForToken(
                        jo.codeeditor.highlight.TokenType.FUNC),
                e2.semSpans.get(0).color);
    }
}
