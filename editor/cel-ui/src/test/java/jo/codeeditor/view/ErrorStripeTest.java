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
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B11 (P1-1, feuille de route « écarts Android Studio ») — verrouille la
 * bande d'erreurs du bord droit : les diagnostics de TOUT le fichier
 * (même hors écran) deviennent visibles et navigables.
 *
 * <ul>
 *   <li>le pusher de diagnostics alimente la bande depuis la session
 *       (même source que les points de gouttière — sévérité max par
 *       ligne) ;</li>
 *   <li>tap sur un marqueur → dépliage + CENTRAGE de la ligne + fiche de
 *       diagnostic (popup de détail, ou liste groupée si plusieurs) ;</li>
 *   <li>tap dans la bande SANS marqueur → geste consommé, ni caret ni
 *       fiche derrière (la bande est un chrome) ;</li>
 *   <li>drag sur la bande → défilement ABSOLU (même mécanique que le
 *       fast-scroller de gouttière, pouce actif) ;</li>
 *   <li>setter public de bascule + silence quand un aperçu est ouvert.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ErrorStripeTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    private static final float PAD_TOP = 20f;
    private static final float PAD_BOTTOM = 0f;
    private static final int LINES = 300;
    private static final int WARN_LINE = 100;
    private static final int ERROR_LINE = 250;

    private static String doc() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            sb.append("ligne ").append(i).append('\n');
        }
        return sb.toString();
    }

    private static List<DiagnosticShift.Diagnostic> diags(EditorDocument doc) {
        List<DiagnosticShift.Diagnostic> list = new ArrayList<>();
        int warnOff = doc.lineStart(WARN_LINE);
        list.add(new DiagnosticShift.Diagnostic(
                warnOff, warnOff + 5, 2, "avertissement ligne 101"));
        int errOff = doc.lineStart(ERROR_LINE);
        list.add(new DiagnosticShift.Diagnostic(
                errOff, errOff + 5, 3, "erreur ligne 251"));
        return list;
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc()));
        view.setSession(session);
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view, LINE_H, CHAR_W, PAD_TOP, PAD_BOTTOM);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight,
            float charWidth, float padTop, float padBottom) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", padTop);
            setFloat(view.metrics, "padBottom", padBottom);
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

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * Y écran attendu du marqueur d'une ligne — MÊME formule que le
     * dessin/hit-test de la bande (docLineToY / contentH × viewH, borné).
     */
    private static float markerY(EditorView view, int line) {
        float density = view.getResources().getDisplayMetrics().density;
        float contentH = view.maxV() + VIEW_H;
        float markerH = 3f * density;
        float y = view.docLineToY(line) / contentH * VIEW_H - markerH * 0.5f;
        return Math.max(0f, Math.min(VIEW_H - markerH, y));
    }

    private static float tapX() {
        // Zone tactile : 10 dp depuis le bord droit (densité 1 en test).
        return VIEW_W - 5f;
    }

    // ── 1. Alimentation par le pusher ──────────────────────────────

    @Test
    public void lePusherAlimenteLaBandeDepuisLaSession() {
        EditorView view = newView();
        EditorDocument doc = view.getSession().getDocument();
        view.getSession().setDiagnostics(diags(doc));
        view.notifyDiagnosticsChanged();
        idle();

        assertEquals("deux marqueurs poussés (warning + erreur)",
                2, view.errorStripe.markerCount());
        // Hit-test : le marqueur de la ligne 251 est résolu à son Y.
        assertEquals("le marqueur résout SA ligne",
                ERROR_LINE, view.errorStripe.markerLineAt(
                        markerY(view, ERROR_LINE)));
        assertEquals("le marqueur d'avertissement résout SA ligne",
                WARN_LINE, view.errorStripe.markerLineAt(
                        markerY(view, WARN_LINE)));
    }

    // ── 2. Tap sur un marqueur = centrage + fiche ──────────────────

    @Test
    public void tapSurUnMarqueurCentreLaLigneEtOuvreLaFiche() {
        EditorView view = newView();
        EditorDocument doc = view.getSession().getDocument();
        view.getSession().setDiagnostics(diags(doc));
        view.notifyDiagnosticsChanged();
        idle();

        float y = markerY(view, ERROR_LINE);
        // Position attendue : la ligne 251 CENTRÉE dans le viewport.
        float expectedV = Math.max(0f, Math.min(view.maxV(),
                view.docLineToY(ERROR_LINE) - (VIEW_H - LINE_H) * 0.5f));

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, tapX(), y);
        send(view, MotionEvent.ACTION_UP, down, down + 60, tapX(), y);
        idle();

        assertTrue("la fiche de détail du diagnostic est ouverte",
                view.diagnosticPopupVisible);
        assertEquals("la ligne du marqueur est CENTRÉE dans le viewport",
                expectedV, view.scroll.vOffset, 2f);
    }

    // ── 3. Tap sans marqueur : geste consommé, rien derrière ───────

    @Test
    public void tapSansMarqueurNePlaceNiCaretNiFiche() {
        EditorView view = newView();
        EditorDocument doc = view.getSession().getDocument();
        view.getSession().setDiagnostics(diags(doc));
        view.notifyDiagnosticsChanged();
        idle();
        Selection before = view.getSession().getSelection();

        // Y de la ligne ~12 : aucun marqueur à moins de 8 dp.
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, tapX(), 480f);
        send(view, MotionEvent.ACTION_UP, down, down + 60, tapX(), 480f);
        idle();

        assertFalse("aucune fiche ouverte pour un tap sans marqueur",
                view.diagnosticPopupVisible);
        assertFalse("aucune liste groupée ouverte",
                view.diagnosticListSheetLine >= 0);
        assertEquals("le viewport ne bouge pas",
                0f, view.scroll.vOffset, 0.01f);
        assertEquals("le caret n'est pas placé derrière la bande",
                before, view.getSession().getSelection());
    }

    // ── 4. Drag sur la bande = défilement absolu ───────────────────

    @Test
    public void dragSurLaBandeDéfinitLaPositionAbsolue() {
        EditorView view = newView();
        float maxV = view.maxV();
        float contentH = maxV + VIEW_H;
        float density = view.getResources().getDisplayMetrics().density;
        float thumbH = jo.codeeditor.view.chrome.EditorFastScroller
                .thumbHeightFor(VIEW_H, contentH, density);
        float span = VIEW_H - thumbH;
        float y = 1600f;
        float expected = Math.max(0f, Math.min(span, y - thumbH * 0.5f))
                / span * maxV;

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, tapX(), 300f);
        send(view, MotionEvent.ACTION_MOVE, down, down + 50, tapX(), y);
        idle();

        assertTrue("le pouce du fast-scroller est actif pendant le drag de bande",
                view.fastScroller.isActive());
        assertEquals("le drag de bande positionne le document en absolu",
                expected, view.scroll.vOffset, 0.5f);

        send(view, MotionEvent.ACTION_UP, down, down + 100, tapX(), y);
        idle();
        assertFalse("le pouce disparaît au relâchement",
                view.fastScroller.isActive());
        assertEquals("la position absolue est conservée",
                expected, view.scroll.vOffset, 0.5f);
    }

    // ── 5. Setter public de bascule ────────────────────────────────

    @Test
    public void setterBasculeLaBande() {
        EditorView view = newView();
        assertTrue("activée par défaut (parité Android Studio)",
                view.isErrorStripeEnabled());
        assertTrue(view.errorStripe.hit(tapX(), 300f));

        view.setErrorStripeEnabled(false);
        assertFalse("désactivée : plus de zone tactile",
                view.errorStripe.hit(tapX(), 300f));
        assertFalse(view.isErrorStripeEnabled());

        view.setErrorStripeEnabled(true);
        assertTrue("réactivée : la zone tactile revient",
                view.errorStripe.hit(tapX(), 300f));
    }

    // ── 6. Aperçu ouvert : la bande se tait ────────────────────────

    @Test
    public void aperçuOuvertLaBandeRefuseLeGeste() {
        EditorView view = newView();
        assertTrue(view.errorStripe.hit(tapX(), 300f));

        // Une feuille d'aperçu ouverte : le bord droit appartient au corps
        // d'aperçu — hit ET dessin refusent.
        view.previewMode = EditorView.PreviewMode.SHEET_SPLIT;
        assertFalse("aucun hit quand une feuille d'aperçu est ouverte",
                view.errorStripe.hit(tapX(), 300f));

        view.previewMode = EditorView.PreviewMode.NONE;
        assertTrue("le hit revient à la fermeture de l'aperçu",
                view.errorStripe.hit(tapX(), 300f));
    }
}
