package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * I2 (P1, lot 2 jalon J3) — verrouille la <b>consultation du tooltip des
 * inlay hints</b> : un appui long sur la plage tissée d'un hint ouvre la
 * fiche quick-doc EXISTANTE portant son tooltip (zéro nouveau chrome) —
 * tooltip direct si le serveur l'a servi, résolution paresseuse sinon
 * (une seule requête, sur la voie « features »), appui long de sélection
 * classique hors hint.
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class InlayTooltipLongPressTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        int count = greet(name);\n"
        + "    }\n"
        + "}\n";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

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
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    /** Appui long au centre de la plage tissée du hint (colonne visuelle
     *  start + len/2). */
    private static void longPressOnHint(EditorView view, int visStart, int len, int line) {
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float x = textAreaLeft + (visStart + len * 0.5f) * charWidth;
        float y = view.docLineToY(line) - view.vOffset + LINE_H * 0.5f;
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        // Le long-press du GestureDetector : ViewConfiguration par défaut
        // ~500 ms — la posted GestureListener l'attrape après le délai.
        SystemClock.sleep(600);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Attend (poll borné) que le quick doc devienne visible. */
    private static boolean awaitQuickDoc(EditorView view, long timeoutMs)
            throws InterruptedException {
        // A-06 : attente bornée, looper avancé entre passes.
        return jo.codeeditor.AttenteBornee.jusquA(timeoutMs, 20L, () -> view.quickDocVisible,
                () -> Shadows.shadowOf(Looper.getMainLooper()).idle());
    }

    private static int hintVisStart(EditorView view, int line, int col) {
        return view.visualColFor(line, col);
    }

    @Test
    public void tooltipDirect_longPressSurHint_ouvreLeQuickDoc() throws Exception {
        EditorView view = newView();
        // Hint PARAMÈTRE « name: » sur la ligne 2, tooltip DIRECT.
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(beforeName, "name: ", false,
                "parameter", "Le nom du paramètre passé à greet"));
        view.getSession().setInlayHints(hints);

        int lineStart = DOC.lastIndexOf('\n', beforeName - 1) + 1;
        int col = beforeName - lineStart;
        int visStart = view.visualColFor(2, col);

        longPressOnHint(view, visStart, "name: ".length(), 2);
        assertTrue("le quick doc du tooltip doit s'ouvrir (tooltip direct, zéro requête)",
                awaitQuickDoc(view, 3000));
        assertTrue(view.quickDocVisible);
        assertNotNull(view.quickDocContent);
        // Le contenu parsé porte le tooltip (description).
        boolean found = view.quickDocContent.toString().contains("Le nom du paramètre")
                || view.quickDocContent.description.contains("Le nom du paramètre");
        assertTrue("le quick doc porte le tooltip du hint", found);
        // Le geste est CONSOMMÉ par le hint : pas de sélection de mot.
        assertTrue("aucun mot n'est sélectionné (le hint a englouti le geste)",
                view.getSession().getSelection().isCursor());
    }

    @Test
    public void résolutionParesseuse_uneSeuleRequête_puisQuickDoc() throws Exception {
        EditorView view = newView();
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(beforeName, "name: ", false, "parameter", null));
        view.getSession().setInlayHints(hints);

        AtomicInteger calls = new AtomicInteger();
        StringBuilder seen = new StringBuilder();
        view.setInlayTooltipResolver((docText, offset, hint) -> {
            calls.incrementAndGet();
            seen.append(hint);
            return "java.lang.String — type inféré";
        });

        int lineStart = DOC.lastIndexOf('\n', beforeName - 1) + 1;
        int col = beforeName - lineStart;
        int visStart = view.visualColFor(2, col);
        longPressOnHint(view, visStart, "name: ".length(), 2);

        assertTrue("le quick doc résolu doit s'ouvrir", awaitQuickDoc(view, 5000));
        assertEquals("exactement UNE résolution par appui", 1, calls.get());
        assertEquals("le resolver reçoit le TEXTE du hint", "name: ", seen.toString());
        assertTrue(view.quickDocContent.toString().contains("java.lang.String")
                || (view.quickDocContent.description != null
                    && view.quickDocContent.description.contains("java.lang.String")));
    }

    @Test
    public void sansResolverNiTooltip_appuiLongClassique() throws Exception {
        EditorView view = newView();
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(beforeName, "name: ", false, "parameter", null));
        view.getSession().setInlayHints(hints);
        // AUCUN resolver branché.

        int lineStart = DOC.lastIndexOf('\n', beforeName - 1) + 1;
        int col = beforeName - lineStart;
        int visStart = view.visualColFor(2, col);
        longPressOnHint(view, visStart, "name: ".length(), 2);
        ShadowLooper.idleMainLooper();

        // Comportement historique : le mot SOUS le hint est sélectionné
        // (offsetAt aligne sur la colonne d'ancrage → « name »).
        assertFalse(view.quickDocVisible);
        Selection sel = view.getSession().getSelection();
        assertFalse("l'appui long hors résolution reste l'appui long de sélection",
                sel.isCursor());
        assertTrue(DOC.substring(Math.min(sel.start, sel.end),
                Math.max(sel.start, sel.end)).contains("name")
                || DOC.substring(Math.min(sel.start, sel.end),
                        Math.max(sel.start, sel.end)).trim().length() > 0);
        assertTrue("la pill de sélection est ouverte", view.selectionToolbarVisible);
    }

    @Test
    public void longPressHorsHint_sélectionNormale() throws Exception {
        EditorView view = newView();
        int countOffset = DOC.indexOf("count");
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        hints.add(new DiagnosticShift.InlayHint(beforeName, "name: ", false, "parameter", "tip"));
        view.getSession().setInlayHints(hints);

        // Appui long sur « count » (aucun hint tissé là).
        int lineStart = DOC.lastIndexOf('\n', countOffset - 1) + 1;
        int col = countOffset - lineStart + 1;
        int visStart = view.visualColFor(2, col);
        longPressOnHint(view, visStart, 2, 2);
        ShadowLooper.idleMainLooper();

        assertFalse(view.quickDocVisible);
        assertFalse(view.getSession().getSelection().isCursor());
        assertTrue(view.selectionToolbarVisible);
    }
}
