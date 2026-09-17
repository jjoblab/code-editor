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
 * Tests du câblage de l'éditeur :
 * <ul>
 *   <li><b>Sheet diagnostic : 2 seules entrées</b> — le tap sur le SQUIGGLE
 *       ne doit PAS ouvrir la sheet (le caret se place normalement) ; seuls
 *       le chip après la fin de ligne et le dot du gutter l'ouvrent
 *       (DiagnosticChip.onClick → openSheet, glyphe gutter → openSheet) ;</li>
 *   <li><b>Dot du gutter câblé</b> — un ACTION_DOWN dans la zone des
 *       numéros de ligne arme isScrolling=true, donc le UP ne passerait
 *       JAMAIS par handleTap : un tap sans mouvement sur le dot doit être
 *       routé comme tap, un drag réel scrolle toujours ;</li>
 *   <li><b>Bracket matching</b> — curseur après {@code }} / {@code )}
 *       → scan arrière, curseur SUR {@code {} → scan avant, profondeur
 *       imbriquée, non-apparié → null, scan borné ;</li>
 *   <li><b>Inlays triés par colonne</b> — buildColumnMaps suppose une liste
 *       triée ; le serveur renvoie les hints var APRÈS les hints de
 *       paramètres, un hint désordonné était silencieusement droppé.</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion ({@link #injectMetrics}).</p>
 *
 * @author jo@Dev
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

    /** Simule un tap complet (DOWN + UP au même endroit) sur la vue. */
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

    // ── matchingBracket : appariement des crochets ──────────────────

    @Test
    public void matchingBracket_caretAfterCloseBracketScansBackward() {
        // « public class Main { » — caret juste APRÈS le '}' de la classe (ligne 4).
        int closeBrace = DOC.lastIndexOf('}');
        // Le cas exact de l'utilisateur : curseur placé après } surligne le {.
        int[] pair = EditorView.matchingBracket(DOC, closeBrace + 1);
        assertNotNull("caret after '}' must find the matching '{'", pair);
        assertEquals(DOC.indexOf('{'), pair[0]);
        assertEquals(closeBrace, pair[1]);
    }

    @Test
    public void matchingBracket_caretAfterParenScansBackward() {
        // caret juste après le ')' de « run() » → apparie son '('.
        int closeParen = DOC.indexOf(')');
        int[] pair = EditorView.matchingBracket(DOC, closeParen + 1);
        assertNotNull(pair);
        assertEquals(DOC.indexOf('('), pair[0]);
        assertEquals(closeParen, pair[1]);
    }

    @Test
    public void matchingBracket_caretOnOpenBracketScansForward() {
        // caret SUR le '{' du corps de run() → scan avant vers son '}'.
        int runOpen = DOC.indexOf('{', DOC.indexOf('{') + 1); // second '{'
        int runClose = DOC.indexOf('}'); // le premier '}' ferme le corps de run() (ligne 3)
        int[] pair = EditorView.matchingBracket(DOC, runOpen);
        assertNotNull(pair);
        assertEquals(runOpen, pair[0]);
        assertEquals(runClose, pair[1]);
    }

    @Test
    public void matchingBracket_nestedDepthCounting() {
        String nested = "(((x)))";
        // caret après le ')' le plus interne → doit trouver le '(' le plus interne.
        int[] pair = EditorView.matchingBracket(nested, 5);
        assertNotNull(pair);
        assertEquals(2, pair[0]);
        assertEquals(4, pair[1]);
        // caret après le DERNIER ')' → le PREMIER '('.
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
        // « () » : caret entre les deux → la sonde caret-1 = '(' gagne (match avant).
        int[] pair = EditorView.matchingBracket("()", 1);
        assertNotNull(pair);
        assertEquals(0, pair[0]);
        assertEquals(1, pair[1]);
    }

    @Test
    public void updateBracketPair_reactsToCaretMove() {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f); // avant setSelection (scrollCaretIntoView)
        int closeBrace = DOC.lastIndexOf('}');
        view.getSession().setSelection(closeBrace + 1);
        assertNotNull("selection change must recompute bracketPair",
            view.bracketPair);
        assertEquals(DOC.indexOf('{'), view.bracketPair[0]);
        assertEquals(closeBrace, view.bracketPair[1]);
        // Déplace vers un point sans crochet → paire effacée.
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
        view.draw(new Canvas(bmp)); // smoke drawBracketMatchBoxes — pas de crash
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
        float tapX = textAreaLeft + (col + 2) * charWidth; // SUR la plage du squiggle
        float tapY = view.metrics.getPadTop() + (line + 0.5f) * view.metrics.getLineHeight();

        tap(view, tapX, tapY);

        assertFalse("tap on the squiggle must NOT open the diagnostic sheet (v2.32)",
            view.isDiagnosticPopupVisible());
        // Le caret se place normalement sur la ligne tapée (branche else →
        // session.setCaret).
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

        // Tape dans la zone des NUMÉROS DE LIGNE (tout à gauche, où le dot
        // est dessiné). Verrouille la régression : DOWN armait isScrolling
        // → UP n'atteignait jamais handleTap → le dot était mort.
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
        // Tape le gutter à la ligne 0 — aucun diagnostic là → pas de sheet.
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

        // Ligne 2 : "        greet(name);"
        // Simule l'ordre RÉEL du serveur : hints de paramètres D'ABORD,
        // puis le hint var à une colonne PLUS TÔT — la forme non triée exacte
        // qui perdait le hint var avant le tri.
        int line = 2;
        int lineStart = doc.lineStart(line);
        int nameCol = text.indexOf("name") - lineStart;         // colonne plus tardive
        int greetCol = text.indexOf("greet") - lineStart;       // colonne plus tôt
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(lineStart + nameCol, "name:", true));
        hints.add(new DiagnosticShift.InlayHint(lineStart + greetCol, "greet:", true));

        view.getSession().setInlayHints(hints);

        LineRenderCache.LineCacheEntry entry = view.layoutForLine(line, doc.lineText(line));
        assertNotNull(entry);
        List<LineRenderCache.InlayPiece> inlays = entry.inlays;
        assertEquals("both hints must survive the weave", 2, inlays.size());
        // Triés par colonne.
        assertTrue("inlay pieces must be sorted by col",
            inlays.get(0).col <= inlays.get(1).col);
        assertEquals(greetCol, inlays.get(0).col);
        assertEquals(nameCol, inlays.get(1).col);
        // Les DEUX hints décalent le texte après eux — le plus tôt aussi.
        int afterGreet = greetCol + "greet".length();
        assertTrue("col after the EARLY hint must be shifted by both hints",
            view.visualColFor(line, nameCol + "name".length())
                >= nameCol + "name".length() + "name:".length());
        assertTrue(view.visualColFor(line, afterGreet) > afterGreet);
        // L'aller-retour fonctionne toujours.
        int vis = view.visualColFor(line, nameCol);
        assertEquals(nameCol, view.rawColFor(line, vis));
    }

    @Test
    public void inlayWeaving_threeUnsortedPiecesAllWoven() {
        EditorView view = newView();
        EditorDocument doc = view.getSession().getDocument();
        int line = 2;
        int lineStart = doc.lineStart(line);
        // cols 20, 8, 14 — complètement mélangées.
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
        // Sémantique ancre-avant-hint (rawToVisual) : la col brute ==
        // lineLength correspond à lineLength + (inlays aux cols STRICTEMENT
        // AVANT elle) = 20 + 3 + 4 = 27 — la pièce ancrée À la col de fin est
        // tissée APRÈS l'ancre, donc elle ne décale pas l'ancre elle-même.
        int rawLen = doc.lineText(line).length(); // "        greet(name);" → 20
        assertEquals(20, rawLen);
        assertEquals(rawLen + 3 + 4, view.visualColFor(line, rawLen));
        // L'ancre de FIN reste à 27 dans la map (avant la pièce traînante) —
        // la pièce traînante s'étend au-delà, jusqu'à la col visuelle 31.
        assertEquals(rawLen + 3 + 4,
            entry.rawToVisual[entry.rawToVisual.length - 1]);
    }
}
