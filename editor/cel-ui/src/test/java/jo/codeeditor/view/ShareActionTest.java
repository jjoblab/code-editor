package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.os.SystemClock;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * T2 (P1) — verrouille l'action intégrée <b>« Share »</b> (OPT-IN) de
 * la pill de sélection : un Intent système {@code ACTION_SEND} du
 * texte sélectionné derrière un sélecteur, sans jamais crasher quand
 * aucune application ne peut le servir.
 *
 * <ul>
 *   <li><b>Tap</b> — un tap sur l'item lance le chooser portant
 *       l'{@code ACTION_SEND text/plain} avec le texte sélectionné en
 *       {@code EXTRA_TEXT}, et referme la pill ;</li>
 *   <li><b>Sélection vide</b> — un perform sans sélection ne lance
 *       RIEN (l'action est inerte, pas de crash).</li>
 * </ul>
 *
 * <p>Robolectric enregistre les intents lancés via
 * {@code ShadowApplication.getNextStartedActivity()}.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ShareActionTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int NAME = DOC.indexOf("name");

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    @Test
    public void tapSurShare_lanceLeChooserAvecLeTexteSélectionné() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.share(view));
        view.getSession().setSelection(Selection.range(NAME, NAME + 4));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int idx = -1;
        for (int i = 0; i < m.count; i++) {
            if ("Share".equals(m.label[i])) { idx = i; break; }
        }
        assertTrue("l'item Share doit être sur la pill", idx >= 0);
        tap(view, m.itemX[idx] + m.itemW[idx] * 0.5f, m.y + m.h * 0.5f);

        // Le chooser est enregistré par le shadow de l'application.
        Intent chooser = Shadows.shadowOf(
                (android.app.Application) view.getContext().getApplicationContext())
                .getNextStartedActivity();
        assertNotNull("un intent a été lancé", chooser);
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull("le chooser porte l'ACTION_SEND en EXTRA_INTENT", send);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals("le texte sélectionné voyage en EXTRA_TEXT",
                "name", send.getStringExtra(Intent.EXTRA_TEXT));

        assertFalse("la pill se referme", view.selectionToolbarVisible);
        assertFalse("les poignées se masquent", view.handlesVisible);
    }

    @Test
    public void performSansSélection_neLanceRien() {
        EditorView view = newView();
        SelectionAction share = BuiltinSelectionActions.share(view);
        view.addSelectionAction(share);
        // Sélection réduite au caret : le perform est inerte.
        view.getSession().setSelection(NAME);

        share.perform.run();

        assertNull("aucun intent lancé sans sélection",
                Shadows.shadowOf((android.app.Application)
                        view.getContext().getApplicationContext())
                        .getNextStartedActivity());
    }
}
