package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.preview.EditorPreviewDropGeometry.Placement;
import jo.codeeditor.view.preview.EditorPreviewHost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B27 — verrouille la carte d'aperçu « goutte d'eau » de bout en bout :
 * l'ouverture par l'œil de gouttière (le routage
 * {@code prefersDropPreview} d'{@code openPreview}), l'ancre GELÉE (l'œil
 * ne suit plus le caret), le hit-test de la carte (onglets → bascule + hôte
 * notifié, ✕ → fermeture, corps → englouti sans déplacer le caret), la
 * non-modalité (un tap HORS de la carte pose le caret, la carte reste
 * vivante et ESQUIVE le bloc de frappe), le re-tap sur l'œil gelé qui
 * referme, et les purges de sûreté (changement de session, fichier non
 * prévisualisable).
 *
 * <p>Miroir des tests de la maquette : la carte ne recouvre JAMAIS la
 * zone de frappe du caret après un tap ailleurs (vérifié sur le
 * placement réel de la vue).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PreviewDropCardTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;

    private static final String DOC =
        "<?xml version=\"1.0\"?>\n"     // 0
        + "<LinearLayout>\n"             // 1
        + "  <EditText />\n"             // 2
        + "  <RecyclerView />\n"         // 3
        + "  <Button />\n"               // 4
        + "</LinearLayout>\n";           // 5

    /** Hôte « goutte d'eau » : prévisualise .xml, deux onglets, badge. */
    private static final class DropHost implements EditorPreviewHost {
        int modeChangedCount;
        EditorView.PreviewMode lastMode;
        int contentPushCount;
        int tabSelected = -1;
        boolean drawCalled;

        @Override public boolean canPreview(String fileName) {
            String n = fileName == null ? "" : fileName.toLowerCase();
            return n.endsWith(".xml");
        }
        @Override public boolean prefersDropPreview() { return true; }
        @Override public List<String> previewDropTabs() {
            List<String> tabs = new ArrayList<>();
            tabs.add("XML");
            tabs.add("Compose");
            return tabs;
        }
        @Override public void onPreviewDropTabSelected(int index) {
            tabSelected = index;
        }
        @Override public void drawDropPreview(Canvas canvas, float x, float y,
                float w, float h, int tab) {
            drawCalled = true;
        }
        @Override public String previewDropBadge(int tab) {
            return tab == 1 ? "MainActivity.kt · @Composable"
                    : "activity_main.xml · gonflé";
        }
        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) {
            modeChangedCount++;
            lastMode = mode;
        }
        @Override public void onPreviewContentChanged(CharSequence text) {
            contentPushCount++;
        }
        @Override public void drawPreview(Canvas canvas, float offsetX,
                float offsetY) { }
        @Override public boolean hasPreviewContent() { return false; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }
    }

    private DropHost host;
    private EditorView view;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(DOC)));
        host = new DropHost();
        view.setPreviewHost(host);
        view.setFileName("activity_main.xml");
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, x, y);
        send(view, MotionEvent.ACTION_UP, down, down + 50, x, y);
        idle();
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

    private static float eyeX(EditorView view) {
        return (view.metrics.getGutterWidth()
                - view.metrics.getFoldStripWidth()) * 0.5f;
    }

    private static float rowCenterY(EditorView view, int line) {
        return view.docLineToY(line) - view.scroll.vOffset + LINE_H * 0.5f;
    }

    // ── 1. Ouverture par l'œil / openPreview ───────────────────────

    @Test
    public void tapSurLOeil_ouvreLaCarteAncréeALaRangéeDuCaret() {
        EditorView view = newView();
        int caretOffset = DOC.indexOf("RecyclerView");
        view.getSession().setSelection(caretOffset);
        float ex = eyeX(view);
        float ey = rowCenterY(view, 3);

        tap(view, ex, ey);

        assertTrue("la carte est ouverte", view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals("le mode passe à DROP",
                EditorView.PreviewMode.DROP, view.previewMode);
        assertEquals("l'ancre est la rangée du caret (3)",
                3, view.preview.drop().getAnchorLine());
        assertEquals("le hôte est notifié du mode DROP",
                EditorView.PreviewMode.DROP, host.lastMode);
        assertTrue("le contenu initial est poussé", host.contentPushCount >= 1);
        assertTrue("le caret n'est PAS déplacé par le tap d'œil",
                view.getSession().getSelection().start == caretOffset);
    }

    @Test
    public void openPreview_routéVersLaCartePourUnHôteGoutte() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("EditText"));
        // Le routage du fil d'Ariane / icônes : prefersDropPreview → carte.
        view.openPreview(false);
        assertTrue("openPreview ouvre la CARTE (pas la feuille) pour .xml",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(EditorView.PreviewMode.DROP, view.previewMode);
        assertNull("aucune feuille popup n'est ouverte",
                view.getPreviewSheet());
    }

    // ── 2. Ancre gelée + hit-test de l'œil ─────────────────────────

    @Test
    public void œilGelé_leHitTestSuitLAncrePasLeCaret() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        // Le caret descend à la ligne 5 — l'œil RESTE sur la rangée 3.
        view.getSession().setSelection(DOC.indexOf("/LinearLayout"));
        assertEquals("l'ancre ne bouge pas quand le caret bouge",
                3, view.preview.drop().getAnchorLine());
        // Le hit-test de l'œil vise la rangée GELÉE…
        assertTrue("hit sur la rangée d'ancre (l'œil dessiné)",
                view.hitTestGutterPreviewAction(eyeX(view), rowCenterY(view, 3)));
        // …plus la rangée du caret.
        assertFalse("pas de hit sur la rangée du caret (l'œil y a quitté)",
                view.hitTestGutterPreviewAction(eyeX(view), rowCenterY(view, 5)));
    }

    @Test
    public void reTapSurLOeilGelé_refermeLaCarte() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        // Le caret bouge, puis re-tap sur l'œil gelé (rangée 3).
        view.getSession().setSelection(DOC.indexOf("Button"));
        tap(view, eyeX(view), rowCenterY(view, 3));

        assertFalse("le re-tap sur l'œil gelé referme la carte",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(EditorView.PreviewMode.NONE, view.previewMode);
        assertEquals("le hôte est notifié de la fermeture",
                EditorView.PreviewMode.NONE, host.lastMode);
    }

    // ── 3. Hit-test de la carte : onglets, ✕, corps ────────────────

    @Test
    public void tapDansLaCarte_surUnOnglet_basculeEtNotifieLeHôte() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        // Placement réel → rectangle du 2e onglet en coordonnées locales.
        Placement p = view.preview.drop().placement();
        RectF[] tabs = view.preview.drop().tabRects();
        assertTrue("deux onglets du hôte", tabs.length == 2);
        RectF tab2 = tabs[1];
        float sx = p.cardLeft + (tab2.centerX()) * p.scale;
        float sy = p.cardTop + (tab2.centerY()) * p.scale;

        tap(view, sx, sy);

        assertTrue("la carte reste ouverte après le tap d'onglet",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals("l'onglet 2 est sélectionné",
                1, view.preview.drop().getSelectedTab());
        assertEquals("le hôte est notifié de la bascule",
                1, host.tabSelected);
    }

    @Test
    public void tapDansLaCarte_surLaCroix_referme() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        Placement p = view.preview.drop().placement();
        RectF close = view.preview.drop().closeRect();
        float sx = p.cardLeft + close.centerX() * p.scale;
        float sy = p.cardTop + close.centerY() * p.scale;

        tap(view, sx, sy);

        assertFalse("le ✕ referme la carte", view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(EditorView.PreviewMode.NONE, view.previewMode);
    }

    @Test
    public void tapDansLeCorpsDeLaCarte_engloutiSansDéplacerLeCaret() {
        EditorView view = newView();
        int caretOffset = DOC.indexOf("RecyclerView");
        view.getSession().setSelection(caretOffset);
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        // Tap au cœur du mini-téléphone (corps).
        Placement p = view.preview.drop().placement();
        float sx = p.cardLeft + p.visualWidth() * 0.5f;
        float sy = p.cardTop + p.visualHeight() * 0.55f;

        tap(view, sx, sy);

        assertTrue("la carte reste ouverte (corps inerte)",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals("le caret n'est PAS déplacé par le tap dans la carte",
                caretOffset, view.getSession().getSelection().start);
    }

    // ── 4. Non-modalité : tap hors carte = caret + esquive ────────

    @Test
    public void tapHorsDeLaCarte_poseLeCaretLaCarteEsquive() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        // Tap sur le texte de la ligne 0 (au-dessus de la carte esquivée).
        float tx = view.metrics.getGutterWidth() + 40f;
        float ty = rowCenterY(view, 0);
        tap(view, tx, ty);

        assertTrue("la carte reste OUVERTE (non modale)",
                view.previewMode == EditorView.PreviewMode.DROP);
        int newOffset = view.getSession().getSelection().start;
        assertTrue("le caret est posé sur la ligne tapée",
                view.getSession().getDocument().lineForOffset(newOffset) == 0);

        // Invariant de la maquette : la carte esquivée ne recouvre pas
        // le bloc de frappe du NOUVEAU caret (ligne 0 ± 1).
        Placement p = view.preview.drop().placement();
        float guardTop = view.docLineToY(0) - view.scroll.vOffset - LINE_H;
        float guardBot = view.docLineToY(0) - view.scroll.vOffset + 2f * LINE_H;
        assertFalse("la carte ne recouvre jamais la zone de frappe",
                p.cardTop < guardBot
                        && p.cardTop + p.visualHeight() > guardTop);
    }

    // ── 5. Purges de sûreté ────────────────────────────────────────

    @Test
    public void changementDeSession_laCarteMeurt() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        view.setSession(new EditorSession(EditorDocument.of("autre chose")));

        assertFalse("le changement d'onglet referme la carte",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(EditorView.PreviewMode.NONE, view.previewMode);
        assertEquals(-1, view.preview.drop().getAnchorLine());
    }

    @Test
    public void fichierNonPrévisualisable_laCarteMeurt() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        tap(view, eyeX(view), rowCenterY(view, 3));
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        view.setFileName("Main.java");

        assertFalse("un fichier sans aperçu referme la carte",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(EditorView.PreviewMode.NONE, view.previewMode);
        assertFalse("l'œil disparaît (fichier non prévisualisable)",
                view.hitTestGutterPreviewAction(eyeX(view), rowCenterY(view, 3)));
    }

    @Test
    public void fermeturesPropres_lÉtatLogiqueMeurtAvec() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        view.openPreviewDropAt(3);
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        view.closePreviewDrop();

        assertFalse(view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(EditorView.PreviewMode.NONE, view.previewMode);
        // Onglets/ancre purgés : aucun fantôme au re-open.
        assertEquals(-1, view.preview.drop().getAnchorLine());
        assertEquals(0, view.preview.drop().tabRects().length);

        // Re-open : tout repart proprement (ancre 2, onglets remesurés).
        view.openPreviewDropAt(2);
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(2, view.preview.drop().getAnchorLine());
        assertEquals(2, view.preview.drop().tabRects().length);
    }

    // ── 6. Rendu smoke : la carte se dessine sans exception ────────

    @Test
    public void draw_laCarteSePeintSansException() {
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("RecyclerView"));
        view.openPreviewDropAt(3);
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                VIEW_W, VIEW_H, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        view.draw(canvas); // pipeline complet : painter B27 inclus
        bmp.recycle();
        assertTrue("le hôte a dessiné dans le mini-téléphone",
                host.drawCalled);
    }
}
