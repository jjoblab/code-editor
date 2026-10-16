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

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.preview.EditorPreviewHost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B27 (retour terrain) — verrouille la <b>priorité des taps</b> de la
 * surface de frappe sur la carte d'aperçu « goutte d'eau » : la carte
 * pouvait recouvrir le popup de complétion ET engloutir les taps sur
 * ses rangées (hit-test de la carte AVANT celui de la complétion) — un
 * mur invisible pire que le cache visuel.
 *
 * <p>Trois comportements prouvés sur le placement réel (1080×620, carte
 * compactée sous le bloc fusionné bande+complétion — la carte recouvre
 * bien une bande de rangées) : (1) un tap sur une RANGÉE de complétion
 * recouverte par la carte est ACCEPTÉ (l'item s'insère, la complétion se
 * referme) ; (2) un tap sur le CORPS de la carte hors complétion referme
 * la complétion puis engloutit (le caret ne bouge pas, la carte vit) ;
 * (3) le ✕ de la carte ferme la carte (la complétion refermée d'abord).
 * Avant le correctif, le test (1) était ROUGE : le tap tombait dans le
 * corps de la carte et ne faisait RIEN.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PreviewDropCompletionTapTest {

    private static final float LINE_H = 80f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 620;

    private static final String DOC =
        "  <TextView />\n"      // 0 : ligne d'ancre de la carte
        + "     fffffff\n"     // 1 : 5 espaces + token col 5 (caret ici)
        + "</LinearLayout>\n";

    /** Hôte « goutte d'eau » minimal (prévisualise .xml, 2 onglets). */
    private static final class DropHost implements EditorPreviewHost {
        @Override public boolean canPreview(String fileName) { return true; }
        @Override public boolean prefersDropPreview() { return true; }
        @Override public List<String> previewDropTabs() {
            List<String> tabs = new ArrayList<>();
            tabs.add("XML");
            tabs.add("Compose");
            return tabs;
        }
        @Override public void onPreviewDropTabSelected(int index) { }
        @Override public void drawDropPreview(android.graphics.Canvas canvas,
                float x, float y, float w, float h, int tab) { }
        @Override public String previewDropBadge(int tab) { return "gonflé"; }
        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) { }
        @Override public void onPreviewContentChanged(CharSequence text) { }
        @Override public void drawPreview(android.graphics.Canvas canvas,
                float offsetX, float offsetY) { }
        @Override public boolean hasPreviewContent() { return false; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(DOC)));
        view.setPreviewHost(new DropHost());
        view.setFileName("activity_main.xml");
        // Opt-in explicite : la priorité des taps à la surface de frappe
        // est une POLITIQUE (B27, option propriétaire) — l'ORIGINALE
        // (carte d'abord, complétion enterrée) est le défaut.
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    /**
     * Carte ouverte ancrée ligne 0, caret ligne 1 (fin du token),
     * complétion vivante (8 items ancrés au token ligne 1) — la carte
     * compactée sous le bloc fusionné recouvre la dernière bande de
     * rangées de complétion (intersection x [125, 178.6] × y [422.6,
     * 428] — carte 330 dp, marges resserrées retour terrain).
     */
    private EditorView scenarioCarteSurComplétion() {
        EditorView view = newView();
        view.getSession().setSelection(0);
        view.openPreview(false);
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(0, view.preview.drop().getAnchorLine());
        int tokenStart = DOC.indexOf("fffffff");
        view.getSession().setSelection(tokenStart + 7); // fin du token
        List<CompletionSession.Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(new CompletionSession.Item("f" + i, "variable",
                    "f" + i, "v", 0, 0, false, false));
        }
        view.setCompletionItems(items, tokenStart, "f");
        assertTrue(view.isCompletionVisible());
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

    // ── 1. Le tap sur une rangée recouverte est ACCEPTÉ ────────────

    @Test
    public void tapSurRangéeRecouverteParLaCarte_laComplétionGagne() {
        EditorView view = scenarioCarteSurComplétion();
        // Pré-condition : le point est DANS la carte ET dans une rangée
        // de complétion (le chevauchement existe vraiment — c'est le
        // retour terrain). Avant le correctif, hitTestDrop engloutissait
        // ce tap : rien ne se passait.
        assertTrue("pré-condition : le tap tombe dans la carte",
                view.preview.hitTestDrop(150f, 425f));

        tap(view, 150f, 425f);

        assertFalse("la complétion est consommée (acceptée), pas ignorée",
                view.isCompletionVisible());
        assertTrue("l'item tapé est inséré dans le document",
                view.getSession().getText().contains("f7"));
        assertTrue("la carte reste ouverte (non modale)",
                view.previewMode == EditorView.PreviewMode.DROP);
    }

    // ── 2. Tap sur le corps de la carte (hors complétion) ──────────

    @Test
    public void tapCorpsDeCarte_laComplétionSeRefermeLaCarteVit() {
        EditorView view = scenarioCarteSurComplétion();
        int caretBefore = view.getSession().getSelection().start;

        // (60, 500) : dans la carte (compactée), hors du rect de
        // complétion — la complétion se referme (règle « tout tap hors
        // du popup le referme »), puis le corps engloutit.
        tap(view, 60f, 500f);

        assertFalse("la complétion ne traîne plus sous la carte",
                view.isCompletionVisible());
        assertTrue("la carte est toujours ouverte",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals("le corps engloutit : le caret ne bouge pas",
                caretBefore, view.getSession().getSelection().start);
    }

    // ── 3. Le ✕ ferme la carte (complétion refermée avant) ─────────

    @Test
    public void tapSurLaFermeture_laCarteSeReferme() {
        EditorView view = scenarioCarteSurComplétion();

        // Le ✕ vit en haut-droite locale de la carte compactée —
        // (170, 430) tombe dedans, hors des rangées de complétion.
        tap(view, 170f, 430f);

        assertFalse("la carte est fermée",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertFalse("la complétion est refermée aussi",
                view.isCompletionVisible());
    }
}
