package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ v2.34 — verrouille le câblage du popup de sélection (portage
 * {@code SelectionToolbar}/{@code SelectionToolbarLayer} de CodeAssist) :
 *
 * <ul>
 *   <li><b>Animation d'entrée vivante</b> — {@code showSelectionToolbar()}
 *       horodate le show ({@code selectionToolbarShownAt}) ; avant v2.34 le
 *       champ n'était JAMAIS assigné et l'animation ne jouait pas ;</li>
 *   <li><b>Métriques partagées</b> — mode COLLAPSED (re-tap : Paste/Select
 *       all sans Copy/Cut), boutons Docs ℹ / Actions ⋯ conditionnels + divider,
 *       {@code actionAt} résout les 6 actions (les gaps/dividers → -1) ;</li>
 *   <li><b>Actions</b> — Copy/Cut/Paste referment la pill + masquent les
 *       poignées, Select all la LAISSE ouverte (parité CodeAssist), Docs →
 *       quick-doc, Actions → popup quick-fixes de la ligne ;</li>
 *   <li><b>Re-tap collapsed</b> — un second tap au même endroit que le caret
 *       BASCULE la pill Paste/Select all (le toggle
 *       {@code handlesVisible = reTap && !handlesVisible} de CodeAssist) ;
 *       un tap ailleurs la referme ;</li>
 *   <li><b>Tap dans la sélection</b> — re-affiche la pill (parité
 *       CodeAssist : la toolbar suit handlesVisible).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorBracketSheetTapTest}).</p>
 *
 * @author jo@Dev
 * @since v2.34
 */
@RunWith(RobolectricTestRunner.class)
public class EditorSelectionToolbarTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;   // lineHeight * 0.5
    private static final float PAD_LEFT = 5f;   // charWidth * 0.5
    private static final float GUTTER_W = 70f;  // charWidth * 7

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
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

    /** Simulates a full tap (DOWN + UP) at the same spot. */
    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(downEvent);
        downEvent.recycle();
        MotionEvent upEvent = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(upEvent);
        upEvent.recycle();
    }

    /** Screen position (center) of the given document offset. */
    private static float[] screenPosFor(int offset) {
        int line = 0, col = 0;
        for (int i = 0; i < offset; i++) {
            if (DOC.charAt(i) == '\n') { line++; col = 0; } else col++;
        }
        return new float[]{
            GUTTER_W + PAD_LEFT + col * CHAR_W + CHAR_W * 0.5f,
            PAD_TOP + line * LINE_H + LINE_H * 0.5f};
    }

    /** Selects the word « greet » (line 2), shows the toolbar, returns the metrics. */
    private static EditorPopupAnchors.SelectionToolbarMetrics viewWithWordSelected(EditorView view) {
        int greet = DOC.indexOf("greet");
        view.getSession().selectWordAt(greet);
        view.showSelectionToolbar();
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        assertNotNull("metrics must exist when the toolbar is visible", m);
        return m;
    }

    private static ClipboardManager clipboard(EditorView view) {
        return (ClipboardManager) view.getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private static int indexOfAction(EditorPopupAnchors.SelectionToolbarMetrics m, int action) {
        for (int i = 0; i < m.count; i++) {
            if (m.action[i] == action) return i;
        }
        return -1;
    }

    // ── Animation d'entrée (avant v2.34 : shownAt JAMAIS assigné) ──

    @Test
    public void showSelectionToolbar_stampsAnimationTime() {
        EditorView view = newView();
        assertEquals(0L, view.selectionToolbarShownAt);
        view.getSession().selectWordAt(DOC.indexOf("greet"));
        view.showSelectionToolbar();
        assertTrue("shownAt must be stamped on show (was always 0 before v2.34)",
                view.selectionToolbarShownAt > 0);
        // And resets the press feedback.
        assertEquals(-1, view.selectionToolbarPressedIdx);
    }

    @Test
    public void toolbarRenderSmoke_withAnimationWindow() {
        EditorView view = newView();
        viewWithWordSelected(view);
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        view.draw(canvas); // animation in flight (tOverall ~ 0) — must not crash
        assertTrue(view.selectionToolbarVisible);
    }

    // ── Métriques : mode collapsed + boutons conditionnels ─────────

    @Test
    public void metrics_collapsedSelection_pasteSelectAllAndIcons() {
        EditorView view = newView();
        // Cursor selection at 'greet' start — the collapsed (re-tap) mode.
        view.getSession().setSelection(DOC.indexOf("greet"));
        view.showSelectionToolbar();
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        assertNotNull(m);
        // ★ v2.36 : parité CodeAssist EXACTE — onDocs/onMenu TOUJOURS fournis :
        // le mode collapsed montre Paste + Select all | ℹ Docs | ⋯ Actions.
        assertEquals("collapsed mode: no Copy/Cut, Paste + Select all + both icons",
                4, m.count);
        assertEquals(EditorView.SEL_ACT_PASTE, m.action[0]);
        assertEquals(EditorView.SEL_ACT_SELECT_ALL, m.action[1]);
        assertTrue("Docs icon always visible (v2.36)", m.isIcon[2]);
        assertTrue("Actions icon always visible (v2.36)", m.isIcon[3]);
        assertEquals("divider always present (v2.36)", 1, m.dividerCount);
    }

    @Test
    public void metrics_withSelection_sixActionsAlways() {
        EditorView view = newView();
        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        // ★ v2.36 : Copy/Cut/Paste/Select all + ℹ + ⋯ TOUJOURS — CodeAssist
        // fournit onDocs/onMenu en permanence (CodeEditor.kt l.1057-1063) ;
        // avant, sans quick-fixes sur la ligne, l'utilisateur perdait l'accès
        // à Docs et au menu GO TO.
        assertEquals(6, m.count);
        assertEquals(EditorView.SEL_ACT_COPY, m.action[0]);
        assertEquals(EditorView.SEL_ACT_CUT, m.action[1]);
        assertEquals(EditorView.SEL_ACT_PASTE, m.action[2]);
        assertEquals(EditorView.SEL_ACT_SELECT_ALL, m.action[3]);
        assertTrue("Docs icon even without resolvers", m.isIcon[4]);
        assertTrue("Actions icon even without quick-fixes", m.isIcon[5]);
        assertEquals(1, m.dividerCount);
    }

    @Test
    public void metrics_docsAndActionsIcons_afterDivider() {
        EditorView view = newView();
        view.setQuickDocResolver((text, offset) -> "doc text");
        view.setCodeActionsResolver((text, line) -> new ArrayList<>());
        int greetLine = 2;
        List<EditorView.CodeAction> actions = new ArrayList<>();
        actions.add(new EditorView.CodeAction("Fix it", "quickfix", () -> { }));
        view.codeActionsByLine.put(greetLine, actions);

        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        assertEquals("Copy/Cut/Paste/SelectAll + Docs + Actions", 6, m.count);
        assertEquals(1, m.dividerCount);
        int docsIdx = indexOfAction(m, EditorView.SEL_ACT_DOCS);
        int actionsIdx = indexOfAction(m, EditorView.SEL_ACT_ACTIONS);
        assertTrue("Docs must be an icon item", m.isIcon[docsIdx]);
        assertTrue("Actions must be an icon item", m.isIcon[actionsIdx]);
        assertTrue("divider sits between the text group and the icon group",
                m.dividerX != Float.MIN_VALUE
                        && m.dividerX > m.itemX[docsIdx] - m.itemW[docsIdx]
                        && m.dividerX < m.itemX[docsIdx]);
        // ★ v2.36 : SANS quick-fixes sur la ligne, les icônes RESTENT — le
        // bouton Actions ouvre le menu unifié qui montre GO TO ou
        // « Nothing found in source. » (CodeAssist : onMenu toujours fourni).
        view.codeActionsByLine.remove(greetLine);
        EditorPopupAnchors.SelectionToolbarMetrics m2 = view.selectionToolbarMetrics();
        assertEquals("icons stay without quick-fixes (v2.36)", 6, m2.count);
        assertEquals(1, m2.dividerCount);
    }

    @Test
    public void metrics_actionAt_resolvesEveryButton_gapsReturnMinusOne() {
        EditorView view = newView();
        view.setQuickDocResolver((text, offset) -> "doc");
        view.setCodeActionsResolver((text, line) -> new ArrayList<>());
        view.codeActionsByLine.put(2, List.of(
                new EditorView.CodeAction("Fix", "quickfix", () -> { })));
        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        float cy = m.y + m.h * 0.5f;

        // Every actionable item resolves to ITS action at its center.
        for (int i = 0; i < m.count; i++) {
            float cx = m.itemX[i] + m.itemW[i] * 0.5f;
            assertEquals("button " + i + " center must resolve to its action",
                    m.action[i], m.actionAt(cx, cy));
        }
        // A point in the gap between two items is NOT actionable
        // (CodeAssist: only the items have onClick).
        int pasteIdx = indexOfAction(m, EditorView.SEL_ACT_PASTE);
        float gapX = m.itemX[pasteIdx] + m.itemW[pasteIdx] + m.btnGap * 0.5f;
        assertEquals("gap between items is not actionable", -1, m.actionAt(gapX, cy));
        // Outside the pill → -1.
        assertEquals(-1, m.actionAt(m.x - 5, cy));
        assertEquals(-1, m.actionAt(m.x + m.w + 5, cy));
        assertEquals(-1, m.actionAt(m.x + 5, m.y - 5));
    }

    // ── Actions : comportements CodeAssist ─────────────────────────

    @Test
    public void tapCopy_copiesToClipboard_andDismissesChrome() {
        EditorView view = newView();
        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        int copyIdx = indexOfAction(m, EditorView.SEL_ACT_COPY);
        view.handlesVisible = true;

        tap(view, m.itemX[copyIdx] + m.itemW[copyIdx] * 0.5f, m.y + m.h * 0.5f);

        ClipData clip = clipboard(view).getPrimaryClip();
        assertNotNull("copy must reach the clipboard", clip);
        assertEquals("greet", clip.getItemAt(0).getText().toString());
        assertFalse("Copy closes the pill (CodeAssist parity)",
                view.selectionToolbarVisible);
        assertFalse("Copy hides the handles (CodeAssist parity)",
                view.handlesVisible);
    }

    @Test
    public void tapCut_removesText_andDismissesChrome() {
        EditorView view = newView();
        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        int cutIdx = indexOfAction(m, EditorView.SEL_ACT_CUT);
        view.handlesVisible = true;

        tap(view, m.itemX[cutIdx] + m.itemW[cutIdx] * 0.5f, m.y + m.h * 0.5f);

        ClipData clip = clipboard(view).getPrimaryClip();
        assertNotNull(clip);
        assertEquals("greet", clip.getItemAt(0).getText().toString());
        assertFalse("cut removes the word", view.getSession().getText().toString().contains("greet"));
        assertFalse(view.selectionToolbarVisible);
        assertFalse(view.handlesVisible);
    }

    @Test
    public void tapSelectAll_keepsToolbarVisible() {
        EditorView view = newView();
        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        int allIdx = indexOfAction(m, EditorView.SEL_ACT_SELECT_ALL);

        tap(view, m.itemX[allIdx] + m.itemW[allIdx] * 0.5f, m.y + m.h * 0.5f);

        assertEquals(0, view.getSession().getSelection().start);
        assertEquals(DOC.length(), view.getSession().getSelection().end);
        assertTrue("Select all KEEPS the pill open (CodeAssist parity — "
                + "Copy/Cut become available on the full selection)",
                view.selectionToolbarVisible);
        // And the refreshed metrics now include Copy/Cut on the full selection.
        EditorPopupAnchors.SelectionToolbarMetrics m2 = view.selectionToolbarMetrics();
        assertEquals(6, m2.count);
        assertEquals(EditorView.SEL_ACT_COPY, m2.action[0]);
    }

    @Test
    public void collapsedToolbar_tapPaste_pastesAtCaret() {
        EditorView view = newView();
        // Collapsed (re-tap) mode: cursor at 'greet' start.
        int greet = DOC.indexOf("greet");
        view.getSession().setSelection(greet);
        view.showSelectionToolbar();
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        assertNotNull(m);
        clipboard(view).setPrimaryClip(ClipData.newPlainText("test", "hi()"));

        int pasteIdx = indexOfAction(m, EditorView.SEL_ACT_PASTE);
        tap(view, m.itemX[pasteIdx] + m.itemW[pasteIdx] * 0.5f, m.y + m.h * 0.5f);

        assertTrue("paste must insert the clipboard text at the caret",
                view.getSession().getText().toString().contains("hi()greet"));
        assertFalse(view.selectionToolbarVisible);
    }

    @Test
    public void tapDocs_dismissesToolbar_tapActions_opensNavMenu() throws Exception {
        EditorView view = newView();
        view.setQuickDocResolver((text, offset) -> "doc");
        view.setCodeActionsResolver((text, line) -> new ArrayList<>());
        List<EditorView.CodeAction> actions = new ArrayList<>();
        actions.add(new EditorView.CodeAction("Create method greet()", "quickfix", () -> { }));
        view.codeActionsByLine.put(2, actions);

        // Docs button → chrome hidden.
        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        int docsIdx = indexOfAction(m, EditorView.SEL_ACT_DOCS);
        tap(view, m.itemX[docsIdx] + m.itemW[docsIdx] * 0.5f, m.y + m.h * 0.5f);
        assertFalse("Docs closes the pill", view.selectionToolbarVisible);

        // ★ v2.36 : le bouton Actions ouvre le MENU CONTEXTUEL UNIFIÉ
        // (NavMenu de CodeAssist) — plus la popup plate de quick-fixes.
        viewWithWordSelected(view);
        EditorPopupAnchors.SelectionToolbarMetrics m2 = view.selectionToolbarMetrics();
        int actsIdx = indexOfAction(m2, EditorView.SEL_ACT_ACTIONS);
        tap(view, m2.itemX[actsIdx] + m2.itemW[actsIdx] * 0.5f, m2.y + m2.h * 0.5f);
        assertFalse("Actions closes the pill", view.selectionToolbarVisible);
        assertTrue("Actions opens the unified nav menu (async)",
                awaitNavMenu(view, 5000));
        assertEquals(2, view.navMenuLine);
        assertEquals(DOC.indexOf("greet"), view.navMenuCaretOffset);
        // Sans résolveur de définition : pas de section GO TO, mais le
        // quick-fix de la ligne alimente QUICK FIXES.
        assertTrue(view.navMenuOptions.isEmpty());
        assertEquals(1, view.navMenuQuickFixes.size());
        assertEquals("Create method greet()", view.navMenuQuickFixes.get(0).title);
        assertTrue(view.navMenuIntentions.isEmpty());
        assertFalse("la popup plate quick-fixes n'est plus le chemin Actions",
                view.codeActionsPopupVisible);
    }

    /** Attend l'ouverture async du menu contextuel unifié (FEATURE_EXECUTOR). */
    private static boolean awaitNavMenu(EditorView view, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (view.navMenuVisible) return true;
            Thread.sleep(25);
        }
        return view.navMenuVisible;
    }

    // ── Re-tap collapsed (toggle CodeAssist) ───────────────────────

    @Test
    public void retapOnCaret_togglesCollapsedToolbar() throws Exception {
        EditorView view = newView();
        float[] pos = screenPosFor(DOC.indexOf("greet"));

        // 1st tap: places the caret, toolbar hidden.
        tap(view, pos[0], pos[1]);
        assertTrue(view.getSession().getSelection().isCursor());
        assertFalse("first tap: no toolbar", view.selectionToolbarVisible);

        // 2nd tap at the SAME spot (> multi-tap window): toggles the
        // collapsed Paste/Select-all pill (CodeAssist re-tap).
        Thread.sleep(320);
        tap(view, pos[0], pos[1]);
        assertTrue("re-tap on the caret must show the collapsed toolbar",
                view.selectionToolbarVisible);
        assertTrue(view.selectionToolbarShownAt > 0);
        // Collapsed: Paste/Select all + the two icons (v2.36).
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        assertEquals(4, m.count);
        assertEquals(EditorView.SEL_ACT_PASTE, m.action[0]);

        // 3rd tap at the same spot: toggles OFF.
        Thread.sleep(320);
        tap(view, pos[0], pos[1]);
        assertFalse("third re-tap toggles the toolbar off",
                view.selectionToolbarVisible);
    }

    @Test
    public void tapElsewhere_afterRetap_hidesToolbar() throws Exception {
        EditorView view = newView();
        float[] pos = screenPosFor(DOC.indexOf("greet"));
        tap(view, pos[0], pos[1]);
        Thread.sleep(320);
        tap(view, pos[0], pos[1]);
        assertTrue(view.selectionToolbarVisible);

        // A tap at a DIFFERENT spot hides it (CodeAssist: « tapping a new
        // spot hides it ») and moves the caret. NOTE: the collapsed pill
        // floats ABOVE the caret line, so « elsewhere » must be outside its
        // bounds — line 0 col 0 is well clear of it.
        float[] other = screenPosFor(0); // « public » on line 0
        Thread.sleep(320);
        tap(view, other[0], other[1]);
        assertFalse("tap elsewhere must hide the toolbar",
                view.selectionToolbarVisible);
        assertTrue(view.getSession().getSelection().isCursor());
    }

    // ── Tap dans la sélection : re-affiche la pill ─────────────────

    @Test
    public void tapInsideSelection_reshowsToolbar() {
        EditorView view = newView();
        viewWithWordSelected(view);
        // Simulate a programmatic dismissal (e.g. after a Copy the selection
        // is still live in this scenario — the pill must be recallable).
        view.dismissSelectionToolbar();
        assertFalse(view.selectionToolbarVisible);

        // Tap INSIDE the selection → keeps it + re-shows the pill + handles.
        float[] pos = screenPosFor(DOC.indexOf("greet") + 2);
        tap(view, pos[0], pos[1]);
        assertTrue("tap inside the selection re-shows the pill (CodeAssist parity)",
                view.selectionToolbarVisible);
        assertTrue(view.handlesVisible);
        assertFalse("selection is kept", view.getSession().getSelection().isCursor());
    }

    @Test
    public void tapOnToolbarGap_doesNotMoveCaretOrDismiss() {
        EditorView view = newView();
        EditorPopupAnchors.SelectionToolbarMetrics m = viewWithWordSelected(view);
        int caretBefore = view.getSession().getSelection().start;
        int pasteIdx = indexOfAction(m, EditorView.SEL_ACT_PASTE);
        // A gap between items: swallowed (CodeAssist Popup parity — the
        // window consumes the touch, nothing happens).
        float gapX = m.itemX[pasteIdx] + m.itemW[pasteIdx] + m.btnGap * 0.5f;
        tap(view, gapX, m.y + m.h * 0.5f);
        assertTrue("gap tap keeps the pill", view.selectionToolbarVisible);
        assertEquals("gap tap must not move the caret", caretBefore,
                view.getSession().getSelection().start);
    }
}
