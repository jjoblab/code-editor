package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * v3.36.0 — Tests du host de painters plugins (roadmap item 9, portage
 * {@code EditorPainterHost} de CodeAssist v3.20).
 *
 * <p>Vérifie : la collecte par frame (text decorations + gutter marks +
 * plugin inlays), la politique fail-safe (un painter qui throw est RETIRÉ
 * du registre au lieu de crasher l'éditeur, les autres painters et le
 * rendu survivent), le listener de retrait, register/unregister avec
 * déduplication par id, et le smoke render avec painters actifs.</p>
 *
 * @since v3.36.0
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPainterHostTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        greet(name); // TODO fix\n"
        + "    }\n"
        + "}\n";

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(DOC)));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /** A painter that decorates the TODO line. */
    private static class TodoPainter implements EditorDecorationPainter {
        int painted = 0;

        @Override
        public void paint(EditorPaintContext ctx) {
            painted++;
            EditorDocument doc = ctx.getDocument();
            for (int line = ctx.getFirstVisibleLine();
                    line <= ctx.getLastVisibleLine(); line++) {
                String text = doc.lineText(line);
                int i = text.indexOf("TODO");
                if (i >= 0) {
                    int start = doc.lineStart(line) + i;
                    ctx.addTextDecoration(start, start + 4, 0xFFFFB300,
                            EditorDecorations.DecorationStyles.UNDERLINE);
                    ctx.addGutterMark(line, 0xFF00E676);
                    ctx.addPluginInlay(start, "todo!", 0xFF9E9E9E);
                }
            }
        }
    }

    /** A painter that always throws. */
    private static class BrokenPainter implements EditorDecorationPainter {
        @Override
        public String id() {
            return "broken-painter";
        }

        @Override
        public void paint(EditorPaintContext ctx) {
            throw new IllegalStateException("plugin bug");
        }
    }

    @Test
    public void frame_collectsAllThreeDecorationKinds() {
        EditorView view = newView();
        TodoPainter painter = new TodoPainter();
        view.getPainterHost().register(painter);

        EditorPainterHost.Frame frame = view.painterHost.apply(view, 0, 4);
        assertEquals(1, frame.textDecorations.size());
        assertEquals(1, frame.gutterMarks.size());
        assertEquals(1, frame.pluginInlays.size());
        assertEquals(DOC.indexOf("TODO"), frame.textDecorations.get(0).start);
        assertEquals(2, frame.gutterMarks.get(0).line);
        assertTrue(frame.pluginInlays.get(0).text.contains("todo"));
        assertFalse(frame.isEmpty());
        assertEquals(1, painter.painted);
        // Empty host → shared empty frame.
        assertTrue(new EditorPainterHost().apply(view, 0, 4).isEmpty());
    }

    @Test
    public void throwingPainter_isRemoved_andOthersSurvive() {
        EditorView view = newView();
        TodoPainter good = new TodoPainter();
        BrokenPainter broken = new BrokenPainter();
        AtomicReference<EditorDecorationPainter> removed = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        view.getPainterHost().addListener((p, t) -> {
            removed.set(p);
            error.set(t);
        });
        view.getPainterHost().register(broken);
        view.getPainterHost().register(good);

        // First frame: broken painter throws → removed; good painter's
        // decorations still collected.
        EditorPainterHost.Frame frame = view.painterHost.apply(view, 0, 4);
        assertEquals(1, frame.textDecorations.size());
        assertSame(broken, removed.get());
        assertTrue(error.get() instanceof IllegalStateException);
        assertEquals(1, view.getPainterHost().painters().size());

        // Second frame: only the good painter runs, no listener fires again.
        removed.set(null);
        frame = view.painterHost.apply(view, 0, 4);
        assertEquals(1, frame.textDecorations.size());
        assertEquals(2, good.painted);
        assertNull(removed.get());
    }

    @Test
    public void register_deduplicatesById_unregisterRemoves() {
        EditorView view = newView();
        EditorPainterHost host = view.getPainterHost();
        TodoPainter a = new TodoPainter();
        TodoPainter b = new TodoPainter();
        host.register(a);
        host.register(b); // same id (class name) → ignored
        assertEquals(1, host.painters().size());
        assertTrue(host.unregister(a));
        assertEquals(0, host.painters().size());
        assertFalse(host.unregister(a)); // already gone
        host.register(null); // no-op
        assertEquals(0, host.painters().size());
    }

    @Test
    public void paintContext_clampsHostileInput() {
        EditorView view = newView();
        EditorPaintContext ctx = new EditorPaintContext(
                view, view.getSession().getDocument(), 0, 4, 2f);
        int len = DOC.length();
        // Inverted / out-of-range ranges are dropped or clamped.
        ctx.addTextDecoration(10, 5, 0xFF000000, 0);      // inverted → dropped
        assertEquals(0, ctx.textDecorations.size());
        ctx.addTextDecoration(-5, 3, 0xFF000000, 0);      // negative start → dropped
        assertEquals(0, ctx.textDecorations.size());
        ctx.addTextDecoration(0, len + 1000, 0xFF000000, 0); // end clamped
        assertEquals(len, ctx.textDecorations.get(0).end);
        ctx.addGutterMark(-1, 0xFF000000);                // negative line → dropped
        assertEquals(0, ctx.gutterMarks.size());
        ctx.addPluginInlay(-1, "x", 0xFF000000);          // negative offset → dropped
        ctx.addPluginInlay(0, null, 0xFF000000);          // null text → dropped
        ctx.addPluginInlay(0, "", 0xFF000000);            // empty text → dropped
        assertEquals(0, ctx.pluginInlays.size());
        // Viewport accessors.
        assertEquals(0, ctx.getFirstVisibleLine());
        assertEquals(4, ctx.getLastVisibleLine());
        assertEquals(2f, ctx.getDensity(), 0f);
        assertTrue(ctx.getLineHeight() >= 0f);
        assertTrue(ctx.getCharWidth() >= 0f);
    }

    @Test
    public void smokeRender_withPainters_noCrash() {
        EditorView view = newView();
        view.getPainterHost().register(new TodoPainter());
        view.getPainterHost().register(new BrokenPainter());
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        // First draw removes the broken painter (throw during apply),
        // second draw runs with only the good painter.
        view.draw(canvas);
        view.draw(canvas);
        assertEquals(1, view.getPainterHost().painters().size());
        bmp.recycle();
    }
}
