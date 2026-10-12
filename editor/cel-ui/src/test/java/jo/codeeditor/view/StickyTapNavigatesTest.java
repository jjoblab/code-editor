package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.MotionEvent;
import android.os.SystemClock;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * S3 (P2) — verrouille la <b>navigation au tap sur une ligne
 * sticky</b> : la bande collée en haut (≤ stickyMaxLines × lineHeight
 * depuis paddingTop) est dessinée PAR-DESSUS le gutter et le texte —
 * un tap dedans centre la ligne d'ORIGINE de la rangée touchée dans
 * le viewport, AVANT le placement du caret.
 *
 * <ul>
 *   <li><b>Rangée tapée</b> — tap sur la 2e rangée de la bande (la
 *       méthode, ligne 1) → la ligne 1 est centrée : son centre Y
 *       écran vaut la moitié du viewport ;</li>
 *   <li><b>Première rangée</b> — tap sur la 1re rangée (la classe,
 *       ligne 0) → la ligne 0 est centrée ;</li>
 *   <li><b>Sans sticky actif</b> — pas de bande collée (haut du
 *       document) : le même tap reste un placement de caret (sélection
 *       réduite au curseur, vOffset inchangé).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code SelectionDragAutoScrollTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class StickyTapNavigatesTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    /** La classe est en ligne 40, la méthode en 41 — assez profond pour
     *  que le CENTRAGE (S3) ne clampe pas vOffset à 0, avec 60 lignes de
     *  contexte en dessous. */
    private static final int CLASS_LINE = 40;
    private static final int METHOD_LINE = 41;
    private static final int N_LINES = 110;
    private static final float PAD_TOP = LINE_H * 0.5f;

    private static String doc() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CLASS_LINE; i++) {
            sb.append("        int filler").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("public class Main {\n")     // 40 — sticky classe
          .append("    void run() {\n")         // 41 — sticky méthode
          .append("        int count = 0;\n"); // 42
        for (int i = 0; i < N_LINES - CLASS_LINE - 5; i++) {
            sb.append("        int filler2_").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    }\n").append("}\n");
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
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
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

    /** La ligne {@code doc} est-elle centrée à ±1 px dans le viewport ? */
    private static boolean lineIsCentered(EditorView view, int line) {
        float lineCenterY = view.docLineToY(line) + LINE_H * 0.5f - view.vOffset;
        return Math.abs(lineCenterY - VIEW_H * 0.5f) <= 1f;
    }

    @Test
    public void tapSurLaRangéeMéthode_centreLaLigneOrigine() {
        EditorView view = newView();
        // Scroller assez loin : les en-têtes 40 (classe) et 41 (méthode)
        // passent au-dessus du viewport → bande sticky active.
        view.vOffset = 2600f;
        java.util.List<int[]> active = view.activeStickyLines();
        assertEquals("deux lignes collées : classe + méthode",
                2, active.size());
        assertEquals(CLASS_LINE, active.get(0)[0]);
        assertEquals(METHOD_LINE, active.get(1)[0]);

        // Rangée 1 (la méthode) : centre de la 2e cellule de la bande.
        float y = PAD_TOP + 1 * LINE_H + LINE_H * 0.5f;
        tap(view, VIEW_W * 0.5f, y);

        assertTrue("la ligne d'origine 41 est centrée après le tap",
                lineIsCentered(view, METHOD_LINE));
    }

    @Test
    public void tapSurLaRangéeClasse_centreLaLigneOrigine() {
        EditorView view = newView();
        view.vOffset = 2600f;

        // Rangée 0 (la classe) : centre de la 1re cellule de la bande.
        float y = PAD_TOP + 0 * LINE_H + LINE_H * 0.5f;
        tap(view, VIEW_W * 0.5f, y);

        assertTrue("la ligne d'origine 40 est centrée après le tap",
                lineIsCentered(view, CLASS_LINE));
    }

    @Test
    public void sansStickyActif_leTapResteUnPlacementDeCaret() {
        EditorView view = newView();
        // Haut du document : les en-têtes (40-41) sont visibles, bien
        // en dessous du haut → aucune bande collée.
        view.vOffset = 0f;
        assertTrue(view.activeStickyLines().isEmpty());
        view.getSession().setSelection(0);

        // Même position Y que la rangée 1 du premier test — mais pas de
        // bande : le tap pose le caret, ne défile pas.
        float y = PAD_TOP + 1 * LINE_H + LINE_H * 0.5f;
        tap(view, VIEW_W * 0.5f, y);

        assertTrue("la sélection est réduite au caret (placement)",
                view.getSession().getSelection().isCursor());
        assertEquals("aucun défilement", 0f, view.vOffset, 0.01f);
    }
}
