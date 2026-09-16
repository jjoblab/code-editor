package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ v2.32 — Tests des correctifs de câblage de l'éditeur :
 * <ul>
 *   <li><b>Sheet diagnostic : 2 seules entrées</b> — le tap sur le SQUIGGLE
 *       ne doit PAS ouvrir la sheet (le caret se place normalement) ; seuls
 *       le chip après la fin de ligne et le dot du gutter l'ouvrent
 *       (parité CodeAssist : DiagnosticChip.onClick → openSheet, glyphe
 *       gutter → openSheet) ;</li>
 *   <li><b>Dot du gutter ENFIN câblé</b> — ACTION_DOWN dans la zone des
 *       numéros de ligne armait isScrolling=true, donc le UP ne passait
 *       JAMAIS par handleTap : un tap sans mouvement sur le dot est
 *       maintenant routé comme tap, un drag réel scrolle toujours ;</li>
 *   <li><b>Bracket matching</b> — portage exact de
 *       {@code EditorEdits.matchingBracket} (CodeAssist) : curseur après
 *       {@code }} / {@code )} → scan arrière, curseur SUR {@code {} → scan
 *       avant, profondeur imbriquée, non-apparié → null, scan borné ;</li>
 *   <li><b>Inlays triés par colonne</b> — buildColumnMaps suppose une liste
 *       triée ; le serveur renvoie les hints var APRÈS les hints de
 *       paramètres, un hint désordonné était silencieusement droppé.</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion ({@link #injectMetrics}).</p>
 *
 * @author jo@Dev
 * @since v2.32
 */
@RunWith(RobolectricTestRunner.class)
public class EditorBracketSheetTapTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
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

    /** Simulates a full tap (DOWN + UP at the same spot) on the view. */
    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(downEvent);
        downEvent.recycle();
        MotionEvent upEvent = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(upEvent);
        upEvent.recycle();
    }

    private EditorView viewWithDiagnostic(int severity) {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f);
        EditorDocument doc = view.getSession().getDocument();
        int start = doc.getText().toString().indexOf("greet");
        List<DiagnosticShift.Diagnostic> diags = new ArrayList<>();
        diags.add(new DiagnosticShift.Diagnostic(start, start + 5, severity,
            "Cannot resolve method greet"));
        view.getSession().setDiagnostics(diags);
        return view;
    }

    // ── matchingBracket : portage CodeAssist EditorEdits.kt ─────────

    @Test
    public void matchingBracket_caretAfterCloseBracketScansBackward() {
        // "public class Main {" — caret right AFTER the '}' of the class (line 4).
        int closeBrace = DOC.lastIndexOf('}');
        // The user's exact case: cursor positioned after } highlights the {.
        int[] pair = EditorView.matchingBracket(DOC, closeBrace + 1);
        assertNotNull("caret after '}' must find the matching '{'", pair);
        assertEquals(DOC.indexOf('{'), pair[0]);
        assertEquals(closeBrace, pair[1]);
    }

    @Test
    public void matchingBracket_caretAfterParenScansBackward() {
        // caret right after the ')' of "run()" → matches its '('.
        int closeParen = DOC.indexOf(')');
        int[] pair = EditorView.matchingBracket(DOC, closeParen + 1);
        assertNotNull(pair);
        assertEquals(DOC.indexOf('('), pair[0]);
        assertEquals(closeParen, pair[1]);
    }

    @Test
    public void matchingBracket_caretOnOpenBracketScansForward() {
        // caret ON the '{' of run() body → forward scan to its '}'.
        int runOpen = DOC.indexOf('{', DOC.indexOf('{') + 1); // second '{'
        int runClose = DOC.indexOf('}'); // first '}' closes run()'s body (line 3)
        int[] pair = EditorView.matchingBracket(DOC, runOpen);
        assertNotNull(pair);
        assertEquals(runOpen, pair[0]);
        assertEquals(runClose, pair[1]);
    }

    @Test
    public void matchingBracket_nestedDepthCounting() {
        String nested = "(((x)))";
        // caret after the innermost ')' → must find the innermost '('.
        int[] pair = EditorView.matchingBracket(nested, 5);
        assertNotNull(pair);
        assertEquals(2, pair[0]);
        assertEquals(4, pair[1]);
        // caret after the LAST ')' → the FIRST '('.
        pair = EditorView.matchingBracket(nested, 7);
        assertNotNull(pair);
        assertEquals(0, pair[0]);
        assertEquals(6, pair[1]);
    }

    @Test
    public void matchingBracket_unmatchedOrPlainPositionReturnsNull() {
        assertNull("unmatched '(' yields no highlight (bounded scan gives up)",
            EditorView.matchingBracket("(unclosed", 9));
        assertNull("plain identifier position → null",
            EditorView.matchingBracket("int x = 42;", 4));
        assertNull("empty text → null", EditorView.matchingBracket("", 0));
        assertNull("caret 0 on '}' of doc... out of range → null",
            EditorView.matchingBracket("no brackets", 11));
    }

    @Test
    public void matchingBracket_probeOrderPrefersCharBeforeCaret() {
        // "()": caret between the two → probe caret-1 = '(' wins (forward match).
        int[] pair = EditorView.matchingBracket("()", 1);
        assertNotNull(pair);
        assertEquals(0, pair[0]);
        assertEquals(1, pair[1]);
    }

    @Test
    public void updateBracketPair_reactsToCaretMove() {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f); // before setSelection (scrollCaretIntoView)
        int closeBrace = DOC.lastIndexOf('}');
        view.getSession().setSelection(closeBrace + 1);
        assertNotNull("selection change must recompute bracketPair",
            view.bracketPair);
        assertEquals(DOC.indexOf('{'), view.bracketPair[0]);
        assertEquals(closeBrace, view.bracketPair[1]);
        // Move to a non-bracket spot → pair cleared.
        view.getSession().setSelection(DOC.indexOf("greet"));
        assertNull(view.bracketPair);
    }

    @Test
    public void bracketBoxes_fullRenderSmoke() {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f);
        int closeBrace = DOC.lastIndexOf('}');
        view.getSession().setSelection(closeBrace + 1);
        assertNotNull(view.bracketPair);
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp)); // drawBracketMatchBoxes smoke — no crash
    }

    // ── Sheet : le squiggle n'est PAS tappable, chip + dot le sont ──

    @Test
    public void sheetTap_onSquiggleDoesNotOpenSheet_placesCaret() {
        EditorView view = viewWithDiagnostic(3);
        EditorDocument doc = view.getSession().getDocument();
        int diagStart = view.getSession().getDiagnostics().get(0).start;
        int line = doc.lineForOffset(diagStart);
        int col = diagStart - doc.lineStart(line);

        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float tapX = textAreaLeft + (col + 2) * charWidth; // ON the squiggle range
        float tapY = view.metrics.getPadTop() + (line + 0.5f) * view.metrics.getLineHeight();

        tap(view, tapX, tapY);

        assertFalse("tap on the squiggle must NOT open the diagnostic sheet (v2.32)",
            view.isDiagnosticPopupVisible());
        // The caret is placed normally on the tapped line (CodeAssist's
        // else-branch → session.setCaret).
        int caret = view.getSession().getSelection().start;
        assertTrue("caret should be placed on the tapped line, was " + caret,
            caret >= doc.lineStart(line) && caret <= doc.lineEnd(line));
    }

    @Test
    public void sheetTap_onChipOpensSheet() {
        EditorView view = viewWithDiagnostic(3);
        EditorDocument doc = view.getSession().getDocument();
        DiagnosticShift.Diagnostic diag = view.getSession().getDiagnostics().get(0);
        int line = doc.lineForOffset(diag.start);
        float[] m = view.diagnosticChipMetrics(diag, line);
        assertNotNull("chip metrics must exist for an Error diagnostic", m);

        tap(view, m[0] + m[2] * 0.5f, m[1] + m[3] * 0.5f);
        assertTrue("tap on the diagnostic CHIP must open the sheet",
            view.isDiagnosticPopupVisible());
        view.dismissDiagnosticPopup();
    }

    @Test
    public void sheetTap_gutterDotOpensSheet_lineNumberAreaTap() {
        EditorView view = viewWithDiagnostic(3);
        EditorDocument doc = view.getSession().getDocument();
        int diagStart = view.getSession().getDiagnostics().get(0).start;
        int line = doc.lineForOffset(diagStart);

        // Tap in the LINE-NUMBER area (far left, where the dot is drawn).
        // v2.31 regression this locks: DOWN armed isScrolling → UP never
        // reached handleTap → the dot was dead.
        float tapX = view.metrics.getCharWidth() * 1.5f;
        float tapY = view.metrics.getPadTop() + (line + 0.5f) * view.metrics.getLineHeight();
        assertTrue("tap must be inside the line-number gutter",
            tapX < view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth());

        tap(view, tapX, tapY);
        assertTrue("tap on the gutter DOT (line-number area) must open the sheet",
            view.isDiagnosticPopupVisible());
        view.dismissDiagnosticPopup();
    }

    @Test
    public void sheetTap_gutterLineWithoutDiagnosticJustScrollsOrNoop() {
        EditorView view = viewWithDiagnostic(3);
        // Tap the gutter at line 0 — no diagnostic there → no sheet.
        float tapX = view.metrics.getCharWidth() * 1.5f;
        float tapY = view.metrics.getPadTop() + 0.5f * view.metrics.getLineHeight();
        tap(view, tapX, tapY);
        assertFalse("gutter tap on a clean line must not open the sheet",
            view.isDiagnosticPopupVisible());
    }

    // ── Inlay hints : tri par colonne avant buildColumnMaps ────────

    @Test
    public void inlayWeaving_unsortedHintsAreSortedBeforeColumnMaps() {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f);
        EditorDocument doc = view.getSession().getDocument();
        String text = doc.getText().toString();

        // Line 2: "        greet(name);"
        // Simulate the REAL server order: parameter hints FIRST, then the
        // var hint at an EARLIER column — the exact unsorted shape that
        // dropped the var hint before the v2.32 sort.
        int line = 2;
        int lineStart = doc.lineStart(line);
        int nameCol = text.indexOf("name") - lineStart;         // later column
        int greetCol = text.indexOf("greet") - lineStart;       // earlier column
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(lineStart + nameCol, "name:", true));
        hints.add(new DiagnosticShift.InlayHint(lineStart + greetCol, "greet:", true));

        view.getSession().setInlayHints(hints);

        LineRenderCache.LineCacheEntry entry = view.layoutForLine(line, doc.lineText(line));
        assertNotNull(entry);
        List<LineRenderCache.InlayPiece> inlays = entry.inlays;
        assertEquals("both hints must survive the weave", 2, inlays.size());
        // Sorted by column.
        assertTrue("inlay pieces must be sorted by col",
            inlays.get(0).col <= inlays.get(1).col);
        assertEquals(greetCol, inlays.get(0).col);
        assertEquals(nameCol, inlays.get(1).col);
        // BOTH hints shift the text after them — the earlier one too.
        int afterGreet = greetCol + "greet".length();
        assertTrue("col after the EARLY hint must be shifted by both hints",
            view.visualColFor(line, nameCol + "name".length())
                >= nameCol + "name".length() + "name:".length());
        assertTrue(view.visualColFor(line, afterGreet) > afterGreet);
        // Round trip still works.
        int vis = view.visualColFor(line, nameCol);
        assertEquals(nameCol, view.rawColFor(line, vis));
    }

    @Test
    public void inlayWeaving_threeUnsortedPiecesAllWoven() {
        EditorView view = newView();
        EditorDocument doc = view.getSession().getDocument();
        int line = 2;
        int lineStart = doc.lineStart(line);
        // cols 20, 8, 14 — fully shuffled.
        int[] cols = {20, 8, 14};
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        for (int c : cols) {
            hints.add(new DiagnosticShift.InlayHint(lineStart + c, "x" + c + ":", true));
        }
        view.getSession().setInlayHints(hints);
        LineRenderCache.LineCacheEntry entry = view.layoutForLine(line, doc.lineText(line));
        assertEquals(3, entry.inlays.size());
        for (int i = 1; i < entry.inlays.size(); i++) {
            assertTrue("sorted after fix",
                entry.inlays.get(i - 1).col < entry.inlays.get(i).col);
        }
        // Anchor-before-hint semantics (CodeAssist rawToVisual): the raw col
        // == lineLength maps to lineLength + (inlays at cols STRICTLY BEFORE
        // it) = 20 + 3 + 4 = 27 — the piece anchored AT the end col is woven
        // AFTER the anchor, so it doesn't shift the anchor itself.
        int rawLen = doc.lineText(line).length(); // "        greet(name);" → 20
        assertEquals(20, rawLen);
        assertEquals(rawLen + 3 + 4, view.visualColFor(line, rawLen));
        // The END anchor itself stays at 27 in the map (before the trailing
        // piece) — the trailing piece extends past it, to visual col 31.
        assertEquals(rawLen + 3 + 4,
            entry.rawToVisual[entry.rawToVisual.length - 1]);
    }
}
