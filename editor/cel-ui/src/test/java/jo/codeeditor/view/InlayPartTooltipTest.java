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
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * I3 (P2, lot 2 jalon J6) — le <b>tooltip par part</b> des inlay hints
 * structurés : un hint dont le libellé est composé de parts (texte +
 * tooltip propre) consulte le tooltip de la PART sous le doigt à
 * l'appui long — « name » et « : str » peuvent expliquer deux choses
 * différentes dans un même hint tissé.
 *
 * <ul>
 *   <li><b>Part sous le doigt</b> — l'appui long sur la sous-plage
 *       d'une part ouvre le quick doc du TOOLTIP DE CETTE part ;</li>
 *   <li><b>Part sans tooltip</b> — retombe sur le tooltip du hint
 *       entier (hint.tooltip), puis sur la résolution paresseuse ;</li>
 *   <li><b>Sans parts</b> — comportement I2 inchangé (tooltip du
 *       hint entier).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code InlayTooltipLongPressTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class InlayPartTooltipTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        int count = greet(name);\n"
        + "    }\n"
        + "}\n";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Hint structuré « name: str » : part 1 « name: » (tooltip A),
     *  part 2 « str » (tooltip B). */
    private static final String TEXT = "name: str";

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

    private void installerHintStructuré(EditorView view, String tooltipHint) {
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        List<DiagnosticShift.InlayHintPart> parts = new ArrayList<>();
        parts.add(new DiagnosticShift.InlayHintPart("name: ", "tooltip de la part NAME"));
        parts.add(new DiagnosticShift.InlayHintPart("str", "tooltip de la part TYPE"));
        hints.add(new DiagnosticShift.InlayHint(beforeName, TEXT, false,
                "parameter", tooltipHint, parts));
        view.getSession().setInlayHints(hints);
    }

    /** Appui long au centre de la sous-plage [from, to) du hint tissé. */
    private void longPressDansLeHint(EditorView view, int visStart, int from, int to,
            int line) {
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float x = textAreaLeft + (visStart + (from + to) * 0.5f) * charWidth;
        float y = view.docLineToY(line) - view.vOffset + LINE_H * 0.5f;
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        SystemClock.sleep(600);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static boolean awaitQuickDoc(EditorView view, long timeoutMs) {
        // A-06 : attente bornée, le looper Robolectric avancé entre passes.
        return jo.codeeditor.AttenteBornee.jusquA(timeoutMs, 20L, () -> view.quickDocVisible,
                () -> Shadows.shadowOf(Looper.getMainLooper()).idle());
    }

    @Test
    public void partNameTooltip_appuiLongSurLaPremièrePart() throws Exception {
        EditorView view = newView();
        installerHintStructuré(view, null);

        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        int lineStart = DOC.lastIndexOf('\n', beforeName - 1) + 1;
        int col = beforeName - lineStart;
        int visStart = view.visualColFor(2, col);
        // Sous-plage de la part 1 : [0, 6) dans le hint tissé.
        longPressDansLeHint(view, visStart, 0, 6, 2);

        assertTrue("le quick doc de la PART NAME s'ouvre",
                awaitQuickDoc(view, 3000));
        assertTrue(view.quickDocContent.toString().contains("tooltip de la part NAME")
                || view.quickDocContent.description.contains("tooltip de la part NAME"));
        assertTrue("le geste est consommé par le hint",
                view.getSession().getSelection().isCursor());
    }

    @Test
    public void partTypeTooltip_appuiLongSurLaSecondePart() throws Exception {
        EditorView view = newView();
        installerHintStructuré(view, null);

        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        int lineStart = DOC.lastIndexOf('\n', beforeName - 1) + 1;
        int col = beforeName - lineStart;
        int visStart = view.visualColFor(2, col);
        // Sous-plage de la part 2 : [6, 10) dans le hint tissé.
        longPressDansLeHint(view, visStart, 6, 10, 2);

        assertTrue("le quick doc de la PART TYPE s'ouvre",
                awaitQuickDoc(view, 3000));
        assertTrue(view.quickDocContent.toString().contains("tooltip de la part TYPE")
                || view.quickDocContent.description.contains("tooltip de la part TYPE"));
    }

    @Test
    public void partSansTooltip_retombeSurLeTooltipDuHint() throws Exception {
        EditorView view = newView();
        // Parts SANS tooltip → tooltip du hint entier.
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        List<DiagnosticShift.InlayHintPart> parts = new ArrayList<>();
        parts.add(new DiagnosticShift.InlayHintPart("name: ", null));
        parts.add(new DiagnosticShift.InlayHintPart("str", null));
        hints.add(new DiagnosticShift.InlayHint(beforeName, TEXT, false,
                "parameter", "tooltip du HINT entier", parts));
        view.getSession().setInlayHints(hints);

        int lineStart = DOC.lastIndexOf('\n', beforeName - 1) + 1;
        int col = beforeName - lineStart;
        int visStart = view.visualColFor(2, col);
        longPressDansLeHint(view, visStart, 2, 5, 2);

        assertTrue(awaitQuickDoc(view, 3000));
        assertTrue(view.quickDocContent.toString().contains("tooltip du HINT entier")
                || view.quickDocContent.description.contains("tooltip du HINT entier"));
    }

    @Test
    public void hintSansParts_comportementI2Inchangé() throws Exception {
        EditorView view = newView();
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(beforeName, TEXT, false,
                "parameter", "tooltip I2 classique"));
        view.getSession().setInlayHints(hints);

        int lineStart = DOC.lastIndexOf('\n', beforeName - 1) + 1;
        int col = beforeName - lineStart;
        int visStart = view.visualColFor(2, col);
        longPressDansLeHint(view, visStart, 3, 8, 2);

        assertTrue(awaitQuickDoc(view, 3000));
        assertFalse("pas de quick doc de part — hint plat",
                view.quickDocContent.toString().contains("part NAME"));
        assertTrue(view.quickDocContent.toString().contains("tooltip I2 classique")
                || view.quickDocContent.description.contains("tooltip I2 classique"));
        assertEquals("texte du hint intact", TEXT,
                view.getSession().getInlayHintsForLine(2).get(0).text);
    }
}
