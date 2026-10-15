package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B9 (retour terrain n°6) — verrouille les deux correctifs du
 * PINCEMENT zoom/dézoom :
 *
 * <ul>
 *   <li><b>Traînées post-pincement</b> — quand un doigt se relève, le
 *       doigt RESTANT continue d'envoyer des MOVE : avant B9, ils
 *       déclenchaient un SCROLL (le slop était déjà franchi au DOWN,
 *       l'ancienne position) juste après le zoom — le viewport glissait
 *       et « le caret ne restait pas à une position fixe ». Le geste est
 *       un pincement : ses traînées sont englouties (parité TextView) ;</li>
 *   <li><b>Ancrage du point focal</b> — session en LECTURE SEULE (ou
 *       caret hors écran) : avant B9, AUCUN ancrage n'était appliqué (le
 *       contenu sautait sous le pincement). Depuis B9, le HAUT de la
 *       ligne sous le point focal reste à sa position écran — même
 *       contrat que le caret, à la granularité ligne.</li>
 * </ul>
 *
 * <p>L'ancrage CARET (session éditable, caret visible) reste couvert par
 * {@link EditorPinchZoomCaretAnchorTest} — les métriques injectées y
 * produisent lineHeight ∝ textSize.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PinchResidualAndFocusAnchorTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    private static final int N_LINES = 200;

    private static String doc() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N_LINES; i++) {
            sb.append("        int filler").append(i).append(" = ").append(i).append(";\n");
        }
        return sb.toString();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc()));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /**
     * Métriques proportionnelles (lineHeight ∝ textSize) — le VRAI
     * comportement d'un changement de taille de police. Reprise de la
     * stratégie d'EditorPinchZoomCaretAnchorTest : setTextSize surcharge
     * les champs dérivés.
     */
    private static final class FakeMetrics extends EditorMetrics {
        @Override
        public void setTextSize(float sizePx) {
            super.setTextSize(sizePx);
            try {
                float lh = (float) Math.ceil(sizePx * 1.5);
                float cw = sizePx * 0.6f;
                setField("lineHeight", lh);
                setField("charWidth", cw);
                setField("padTop", lh * 0.5f);
                setField("padLeft", cw * 0.5f);
                setField("padRight", cw * 0.5f);
                setField("padBottom", lh * 6f);
                setField("foldStripWidth", cw * 2f);
                setField("gutterWidth", cw * 5f + cw * 2f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void setField(String name, float value) throws Exception {
            Field f = EditorMetrics.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setFloat(this, value);
        }
    }

    private EditorView newViewWithFakeMetrics() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc()));
        view.setSession(session);
        session.setLanguage("java");
        try {
            Field mf = EditorView.class.getDeclaredField("metrics");
            mf.setAccessible(true);
            mf.set(view, new FakeMetrics());
        } catch (Exception e) {
            throw new RuntimeException("Cannot inject FakeMetrics", e);
        }
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        return view;
    }

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    private static void sendMulti(EditorView view, int action, long down, long when,
                                  float x1, float y1, float x2, float y2) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
        }
        coords[0].x = x1;
        coords[0].y = y1;
        coords[1].x = x2;
        coords[1].y = y2;
        MotionEvent e = MotionEvent.obtain(down, when, action, 2, props, coords,
                0, 0, 1f, 1f, 0, 0, 0, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // ── 1. Traînées post-pincement englouties ──────────────────────

    @Test
    public void pinchPuisDoigtRestant_neDéfinitPasLeViewport() {
        EditorView view = newViewWithFakeMetrics();
        view.wordWrap = false;

        long down = SystemClock.uptimeMillis();
        // Doigt 1 pose (milieu gauche).
        send(view, MotionEvent.ACTION_DOWN, down, down, 300f, 900f);

        // Doigt 2 pose (ACTION_POINTER_DOWN), puis pincement écarté :
        // le détecteur de scale consomme les MOVE multi-doigts.
        sendMulti(view, MotionEvent.ACTION_POINTER_DOWN, down, down + 100, 300f, 900f, 700f, 900f);
        sendMulti(view, MotionEvent.ACTION_MOVE, down, down + 150, 280f, 880f, 720f, 920f);
        sendMulti(view, MotionEvent.ACTION_MOVE, down, down + 200, 250f, 860f, 750f, 940f);

        // La taille de police a bougé (le pincement a vécu).
        assertTrue("le pincement a changé l'échelle ("
                + view.getFontScale() + ")",
                Math.abs(view.getFontScale() - 1f) > 0.01f);

        float vBefore = view.vOffset;
        float hBefore = view.hOffset;

        // Doigt 2 relevé (ACTION_POINTER_UP) PUIS le doigt 1 traîne
        // — la traînée résiduelle ne doit RIEN défiler (engloutie).
        sendMulti(view, MotionEvent.ACTION_POINTER_UP, down, down + 250, 250f, 860f, 750f, 940f);
        send(view, MotionEvent.ACTION_MOVE, down, down + 300, 240f, 850f);
        send(view, MotionEvent.ACTION_MOVE, down, down + 340, 230f, 845f);
        idle();

        assertEquals("les traînées post-pincement ne défileront pas le viewport "
                + "verticalement (v " + vBefore + " → " + view.vOffset + ")",
                vBefore, view.vOffset, 0.01f);
        assertEquals("ni horizontalement (h " + hBefore + " → " + view.hOffset + ")",
                hBefore, view.hOffset, 0.01f);

        // Le geste se termine proprement.
        send(view, MotionEvent.ACTION_UP, down, down + 380, 230f, 845f);
    }

    @Test
    public void pinchQuiDémarre_tueLeDragSelectEnCours() {
        EditorView view = newView();
        String text = view.getSession().getText();
        // Un drag-select ARMÉ (appui long)…
        long down = SystemClock.uptimeMillis();
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 5 * CHAR_W;
        send(view, MotionEvent.ACTION_DOWN, down, down, x, 120f);
        SystemClock.sleep(600);
        idle();
        send(view, MotionEvent.ACTION_MOVE, down, down + 650, x, 300f);

        // …interrompu par un second doigt (pincement) : le drag-select
        // doit être DÉSARMÉ (pas de tick d'auto-scroll zombie, pas de
        // loupe résiduelle).
        sendMulti(view, MotionEvent.ACTION_POINTER_DOWN, down, down + 700, x, 300f, 700f, 900f);
        assertFalse("un pincement désarme le drag-select en cours",
                view.isSelectionDragActive());
        assertFalse("la loupe est refermée au démarrage du pincement",
                view.magnifierActive);
        assertEquals(text.length() > 0, true);
    }

    // ── 2. Ancrage du point focal (lecture seule) ──────────────────

    @Test
    public void readOnly_pinch_ligneSousLePointFocalResteFixe() {
        EditorView view = newViewWithFakeMetrics();
        view.wordWrap = false;
        // Lecture seule : PAS de caret ancrable — le point focal prend
        // le relais (avant B9 : aucun ancrage, contenu sauvage).
        view.getSession().setReadOnly(true);
        view.vOffset = 2000f;

        // Ligne sous le point focal (focus au centre de l'écran).
        float focusX = VIEW_W * 0.5f;
        float focusY = VIEW_H * 0.5f;
        int anchorLine = view.docLineForScreenY(focusY);
        float lineTopBefore = view.docLineToY(anchorLine) - view.vOffset;

        // Pincement ×1.4 centré sur le point focal.
        view.applyPinchScale(1.4f, focusX, focusY);

        float lineTopAfter = view.docLineToY(anchorLine) - view.vOffset;
        assertEquals("le HAUT de la ligne sous le point focal reste fixe "
                + "(avant " + lineTopBefore + ", après " + lineTopAfter + ")",
                lineTopBefore, lineTopAfter, 0.5f);
    }

    @Test
    public void readOnly_pinchSansFocus_centreDuViewportCommeAncre() {
        EditorView view = newViewWithFakeMetrics();
        view.getSession().setReadOnly(true);
        view.vOffset = 1000f;

        // API historique (sans focus) : l'ancre de repli = centre du
        // viewport — le contrat ne change pas (pas de saut sauvage).
        int anchorLine = view.docLineForScreenY(VIEW_H * 0.5f);
        float lineTopBefore = view.docLineToY(anchorLine) - view.vOffset;

        view.applyPinchScale(1.25f);

        float lineTopAfter = view.docLineToY(anchorLine) - view.vOffset;
        assertEquals("l'API historique ancre le centre du viewport",
                lineTopBefore, lineTopAfter, 0.5f);
    }
}
