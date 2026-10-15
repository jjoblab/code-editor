package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.chrome.GutterView;
import jo.codeeditor.view.icons.EditorIcons;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * B15 (P2-2) — verrouille l'API {@code GutterAction} généralisée :
 * le registre public {@link EditorView#gutterActions} (add / remove /
 * clear / actionForLine), le hit-test déterministe (même pill que B9c),
 * le tap qui exécute perform() sans toucher au caret ni tomber dans la
 * cascade multi-tap, le rendu (pill de la ligne ancrée, priorité sur
 * l'œil d'aperçu, fournisseur actif relu au dessin) et l'icône
 * BREAKPOINT du moteur.
 *
 * <ul>
 *   <li>UNE action par ligne : add retire la concurrente de même ligne
 *       (et déplace le même id) — jamais d'empilement de pills ;</li>
 *   <li>le hit-test résout la ligne par docLineForScreenY (wrap + plis)
 *       — rangée hors écran ou hors pill : rien (pas de zone tactile
 *       fantôme, parité B9c) ;</li>
 *   <li>le tap exécute perform() et N'appartient PAS au texte : caret
 *       non déplacé, cascade multi-tap ignorée (un double-tap sur un
 *       point d'arrêt le bascule deux fois) ;</li>
 *   <li>au rendu, l'action de l'hôte passe AVANT l'œil d'aperçu sur la
 *       rangée du caret (donnée fixe de l'hôte &gt; œil fugace) et
 *       {@code active} est RELU à chaque dessin (fournisseur vivant) ;</li>
 *   <li>une ligne au-delà du document n'est ni dessinée ni touchable.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class GutterActionsTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;

    private static final String DOC =
        "# Titre\n"                    // 0
        + "paragraphe court\n"         // 1
        + "- item un\n"                // 2
        + "- item deux\n"              // 3
        + "fin du markdown\n";         // 4

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view);
        return view;
    }

    private static void injectMetrics(EditorView view) {
        try {
            setFloat(view.metrics, "lineHeight", LINE_H);
            setFloat(view.metrics, "charWidth", CHAR_W);
            setFloat(view.metrics, "padTop", LINE_H * 0.5f);
            setFloat(view.metrics, "padLeft", CHAR_W * 0.5f);
            setFloat(view.metrics, "gutterWidth", CHAR_W * 7f);
            setFloat(view.metrics, "foldStripWidth", CHAR_W * 2f);
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

    /** Centre X de la pill (zone des numéros : gutter 70 − fold 20 → 25). */
    private static float pillCx() {
        return (CHAR_W * 7f - CHAR_W * 2f) * 0.5f;
    }

    /** Y écran du centre de la rangée donnée (wrap et plis OFF). */
    private static float rowCy(EditorView view, int line) {
        return view.docLineToY(line) - view.scroll.vOffset + LINE_H * 0.5f;
    }

    private static GutterAction action(int line, Runnable perform) {
        return new GutterAction("act-" + line, line, EditorIcons.Icon.BREAKPOINT, perform);
    }

    // ── 1. Rectangle déterministe partagé (B9c = source unique) ──

    @Test
    public void hostActionRect_partageLaGéométrieDeLaPillB9c() {
        EditorView view = newView();
        RectF host = new RectF();
        RectF eye = new RectF();
        GutterView.hostActionRect(view.metrics, 1f, 220f, LINE_H, host);
        GutterView.previewActionRect(view.metrics, 1f, 220f, LINE_H, eye);
        // Même formule déterministe : dessin et hit-test partagent la pill.
        assertEquals(eye, host);
        // Deux appels → mêmes valeurs (aucun état mutable).
        RectF host2 = new RectF();
        GutterView.hostActionRect(view.metrics, 1f, 220f, LINE_H, host2);
        assertEquals(host, host2);
    }

    // ── 2. Registre : une action par ligne, id stable ────────────

    @Test
    public void add_poseLaPremièreActionDeSaLigne() {
        EditorView view = newView();
        GutterAction a = action(2, null);
        view.gutterActions.add(a);
        assertEquals(1, view.gutterActions.size());
        assertSame("actionForLine rend l'action posée", a,
                view.gutterActions.actionForLine(2));
        assertNull("ligne non annotée → null",
                view.gutterActions.actionForLine(3));
    }

    @Test
    public void add_remplaceLaConcurrenteDeMêmeLigneEtDéplaceLeMêmeId() {
        EditorView view = newView();
        GutterAction first = action(2, null);
        GutterAction second = new GutterAction("autre", 2,
                EditorIcons.Icon.CHEVRON_UP, null);
        view.gutterActions.add(first);
        view.gutterActions.add(second);
        // UNE pill par ligne : la dernière inscription gagne, pas
        // d'empilement.
        assertEquals(1, view.gutterActions.size());
        assertSame(second, view.gutterActions.actionForLine(2));

        // Même id sur une AUTRE ligne : l'action est déplacée (pas
        // dupliquée) — re-pose = repositionnement.
        GutterAction moved = new GutterAction("autre", 4,
                EditorIcons.Icon.BREAKPOINT, null);
        view.gutterActions.add(moved);
        assertEquals(1, view.gutterActions.size());
        assertSame(moved, view.gutterActions.actionForLine(4));
        assertNull(view.gutterActions.actionForLine(2));
    }

    @Test
    public void removeEtClear_videntLeRegistre() {
        EditorView view = newView();
        view.gutterActions.add(action(1, null));
        view.gutterActions.add(action(2, null));
        assertTrue("remove par id", view.gutterActions.remove("act-1"));
        assertFalse("remove d'un id absent", view.gutterActions.remove("act-1"));
        assertEquals(1, view.gutterActions.size());
        view.gutterActions.clear();
        assertEquals(0, view.gutterActions.size());
        view.gutterActions.clear(); // idempotent
        // add(null) : ignoré sans crash.
        view.gutterActions.add(null);
        assertEquals(0, view.gutterActions.size());
    }

    // ── 3. Hit-test déterministe ─────────────────────────────────

    @Test
    public void hitTest_dansLaPill_deLaLigneAnnotée() {
        EditorView view = newView();
        GutterAction a = action(2, null);
        view.gutterActions.add(a);
        GutterAction hit = view.gutterActions.hitTest(pillCx(), rowCy(view, 2));
        assertSame("hit dans la pill de la ligne annotée", a, hit);
    }

    @Test
    public void hitTest_horsPillOuRangéeSansAction_null() {
        EditorView view = newView();
        GutterAction a = action(2, null);
        view.gutterActions.add(a);
        // Rangée voisine sans action.
        assertNull("rangée non annotée → null",
                view.gutterActions.hitTest(pillCx(), rowCy(view, 3)));
        // Dans la gouttière mais HORS de la pill (bord gauche des points
        // de diagnostic) : le tap n'appartient pas à l'action.
        assertNull("x dans la gouttière mais hors pill → null",
                view.gutterActions.hitTest(5f, rowCy(view, 2)));
        // Hors gouttière (zone texte).
        assertNull("hors de la gouttière → null",
                view.gutterActions.hitTest(
                        view.metrics.getGutterWidth() + 50f, rowCy(view, 2)));
    }

    @Test
    public void hitTest_rangéeHorsÉcran_aucuneZoneFantôme() {
        EditorView view = newView();
        // Action posée sur la ligne que docLineForScreenY résoudra à
        // y=300 avec vOffset=4000 (clamp au document : ligne 4).
        GutterAction a = action(4, null);
        view.gutterActions.add(a);
        view.scroll.vOffset = 4000f;
        assertNull("rangée hors écran (repliée au-dessus du viewport) → null",
                view.gutterActions.hitTest(pillCx(), 300f));
    }

    // ── 4. Tap : perform(), caret non déplacé, pas de cascade ────

    @Test
    public void tap_exécutePerformSansDéplacerLeCaret() {
        EditorView view = newView();
        view.getSession().setSelection(0);
        jo.codeeditor.document.Selection selBefore =
                view.getSession().getSelection();
        AtomicInteger calls = new AtomicInteger();
        view.gutterActions.add(action(2, calls::incrementAndGet));

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, pillCx(), rowCy(view, 2));
        send(view, MotionEvent.ACTION_UP, down, down + 60, pillCx(), rowCy(view, 2));
        idle();

        assertEquals("le tap exécute perform() de l'action", 1, calls.get());
        assertEquals("le caret n'est PAS déplacé par le tap d'action",
                selBefore, view.getSession().getSelection());
    }

    @Test
    public void tap_deuxTapsRapides_performDeuxFois_horsCascade() {
        EditorView view = newView();
        view.getSession().setSelection(0);
        AtomicInteger calls = new AtomicInteger();
        view.gutterActions.add(action(2, calls::incrementAndGet));

        // Deux DOWN/UP dans la fenêtre multi-tap : les taps de
        // gouttière n'entrent JAMAIS dans la cascade (précédent B9c /
        // bande de fold) — chaque tap exécute perform().
        long down1 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down1, down1, pillCx(), rowCy(view, 2));
        send(view, MotionEvent.ACTION_UP, down1, down1 + 60, pillCx(), rowCy(view, 2));
        idle();
        long down2 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down2, down2, pillCx(), rowCy(view, 2));
        send(view, MotionEvent.ACTION_UP, down2, down2 + 60, pillCx(), rowCy(view, 2));
        idle();

        assertEquals("un double-tap sur la pill exécute perform() deux fois "
                + "(pas de cascade mot/ligne derrière)", 2, calls.get());
    }

    @Test
    public void tap_actionSansPerform_gesteConsomméSansCrash() {
        EditorView view = newView();
        view.getSession().setSelection(0);
        jo.codeeditor.document.Selection selBefore =
                view.getSession().getSelection();
        // perform null : la pill reste un chrome — le geste est consommé
        // (caret non déplacé) sans exception.
        view.gutterActions.add(action(3, null));

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, pillCx(), rowCy(view, 3));
        send(view, MotionEvent.ACTION_UP, down, down + 60, pillCx(), rowCy(view, 3));
        idle();

        assertEquals("action sans perform : le caret reste en place",
                selBefore, view.getSession().getSelection());
    }

    // ── 5. Rendu : pill sur SA ligne, priorité sur l'œil, actif relu ──

    /** GutterView standalone branché sur le registre de la vue (pattern B9c). */
    private GutterView newGutter(EditorView view) {
        GutterView gutter = new GutterView(view.metrics, view.getTheme());
        gutter.setDensity(1f);
        gutter.setHostActions(view.gutterActions);
        return gutter;
    }

    private static int drawPathCount(Canvas canvas) {
        return Shadows.shadowOf(canvas).getPathPaintHistoryCount();
    }

    @Test
    public void rendu_pillDeLaLigneAncrée_prioritéSurLOeil() {
        EditorView view = newView();
        // L'œil B9c est visible (prévisualisable) et posé sur la rangée
        // du caret (currentLine = 1 au draw).
        GutterView gutter = newGutter(view);
        gutter.setPreviewAction(true, true);

        // Cas A — action d'hôte SUR la rangée du caret : UNE seule pill
        // (l'action remplace l'œil — priorité à la donnée fixe de l'hôte).
        view.gutterActions.add(action(1, null));
        Bitmap bmpA = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888);
        Canvas canvasA = new Canvas(bmpA);
        gutter.draw(canvasA, 0f, 400f, 5, 1);
        assertEquals("action sur la rangée du caret : une SEULE pill "
                + "(l'œil cède sa place)", 1, drawPathCount(canvasA));
        bmpA.recycle();

        // Cas B — action d'hôte sur une AUTRE ligne (2) : DEUX pills
        // (l'œil de la rangée 1 + l'action de la rangée 2).
        view.gutterActions.clear();
        view.gutterActions.add(action(2, null));
        Bitmap bmpB = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888);
        Canvas canvasB = new Canvas(bmpB);
        gutter.draw(canvasB, 0f, 400f, 5, 1);
        assertEquals("action ailleurs + œil au caret : deux pills", 2,
                drawPathCount(canvasB));
        bmpB.recycle();

        // Cas C — sans action : l'œil seul (comportement B9c intact).
        view.gutterActions.clear();
        Bitmap bmpC = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888);
        Canvas canvasC = new Canvas(bmpC);
        gutter.draw(canvasC, 0f, 400f, 5, 1);
        assertEquals("sans action d'hôte : l'œil B9c seul", 1,
                drawPathCount(canvasC));
        bmpC.recycle();
    }

    @Test
    public void rendu_activeEstReluAuDessin_fournisseurVivant() {
        EditorView view = newView();
        AtomicBoolean armed = new AtomicBoolean(false);
        view.gutterActions.add(new GutterAction("bp", 1,
                EditorIcons.Icon.BREAKPOINT, null, armed::get));
        GutterView gutter = newGutter(view);
        int themeKeyword = view.getTheme().keyword;

        Bitmap bmp = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        gutter.draw(canvas, 0f, 400f, 5, -1);
        assertEquals("pill dessinée (icône de l'action)", 1, drawPathCount(canvas));
        Paint drawn = Shadows.shadowOf(canvas).getDrawnPathPaint(0);
        int sober = drawn.getColor();
        assertTrue("état sobre ≠ accent", sober != themeKeyword);
        bmp.recycle();

        // Le fournisseur change d'avis → le PROCHAIN rendu lit l'accent
        // (aucun ré-enregistrement : invalidate suffit).
        armed.set(true);
        Bitmap bmp2 = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas2 = new Canvas(bmp2);
        gutter.draw(canvas2, 0f, 400f, 5, -1);
        Paint drawn2 = Shadows.shadowOf(canvas2).getDrawnPathPaint(0);
        assertEquals("état actif relu au dessin : glyphe à l'accent",
                themeKeyword, drawn2.getColor());
        bmp2.recycle();
    }

    @Test
    public void rendu_etHitTest_ligneHorsDocument_pasDePillFantôme() {
        EditorView view = newView();
        // Ligne 100 dans un document de 5 lignes : ni dessinée ni
        // touchable (la boucle de rendu s'arrête à totalLines et
        // docLineForScreenY clampe au document).
        view.gutterActions.add(action(100, null));
        GutterView gutter = newGutter(view);
        Bitmap bmp = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        gutter.draw(canvas, 0f, 400f, 5, 1);
        assertEquals("ligne au-delà du document : aucune pill dessinée", 0,
                drawPathCount(canvas));
        bmp.recycle();
        assertNull("et aucune zone tactile",
                view.gutterActions.hitTest(pillCx(), rowCy(view, 4)));
    }

    // ── 6. Icône BREAKPOINT (moteur) ─────────────────────────────

    @Test
    public void icôneBreakpoint_disquePleinFamilleFill() {
        // Bornes du viewport 24×24 : le disque couvre [4, 20] (r = 8
        // autour du centre 12, approché par QUATRE cubiques — le
        // parseur rejette les arcs elliptiques, parité SEARCH).
        RectF bounds = EditorIcons.bounds(EditorIcons.Icon.BREAKPOINT);
        assertEquals(4f, bounds.left, 0.1f);
        assertEquals(4f, bounds.top, 0.1f);
        assertEquals(20f, bounds.right, 0.1f);
        assertEquals(20f, bounds.bottom, 0.1f);

        // Dessin : UN drawPath en FILL (disque plein), peinture de
        // l'appelant préservée (contrat EditorIcons.draw).
        Bitmap bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFE06C75);
        EditorIcons.draw(canvas, EditorIcons.Icon.BREAKPOINT, 24, 24, 40, paint);
        org.robolectric.shadows.ShadowCanvas shadow = Shadows.shadowOf(canvas);
        assertEquals("UN drawPath", 1, shadow.getPathPaintHistoryCount());
        assertEquals("famille FILL (disque plein)", Paint.Style.FILL,
                shadow.getDrawnPathPaint(0).getStyle());
        assertEquals("couleur de l'appelant préservée", 0xFFE06C75,
                shadow.getDrawnPathPaint(0).getColor());
        bmp.recycle();
    }
}
