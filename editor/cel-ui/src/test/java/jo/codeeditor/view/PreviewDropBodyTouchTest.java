package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.preview.EditorPreviewDropGeometry.Placement;
import jo.codeeditor.view.preview.EditorPreviewHost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B27-C3 — verrouille le CORPS INTERACTIF de la carte « goutte d'eau » :
 * un hôte interactif ({@code wantsInteractiveDropPreview()}) reçoit ses
 * touches DANS LE REPÈRE de {@code drawDropPreview} (coords écran locales,
 * indépendantes du facteur de taille) — DOWN/MOVE/UP transférés, appui
 * long = CANCEL puis {@code onPreviewDropLongPress}, doigt sorti CLAMPÉ,
 * carte refermée en vol de geste = CANCEL honnête. Les hôtes
 * NON-interactifs (défaut) ne reçoivent RIEN (comportement historique au
 * bit près), un hôte qui DÉCLINE un DOWN retombe sur le corps inerte, et
 * l'en-tête (onglets/✕) garde ses gestes propres.
 *
 * <p>Robolectric, même harnais que {@code PreviewDropCompletionTapTest}
 * (1080×620, densité 1 : les dp deviennent px) — les coordonnées
 * transférées sont recalculées depuis le placement RÉEL et le rectangle
 * écran de la carte, jamais en dur.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PreviewDropBodyTouchTest {

    private static final float LINE_H = 80f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 620;

    private static final String DOC =
        "  <TextView />\n"
        + "     fffffff\n"
        + "</LinearLayout>\n";

    /** UNE touche transférée (action + coords écran). */
    private static final class Touche {
        final int action;
        final float sx;
        final float sy;

        Touche(int action, float sx, float sy) {
            this.action = action;
            this.sx = sx;
            this.sy = sy;
        }
    }

    /** Hôte interactif qui ENREGISTRE tout (declinerDown = décline le DOWN). */
    private static final class HoteInteractif implements EditorPreviewHost {
        final List<Touche> touches = new ArrayList<>();
        final List<float[]> longPresses = new ArrayList<>();
        boolean declinerDown = false;
        boolean dejaDecline = false;

        @Override public boolean canPreview(String fileName) { return true; }
        @Override public boolean prefersDropPreview() { return true; }
        @Override public List<String> previewDropTabs() { return new ArrayList<>(); }
        @Override public void onPreviewDropTabSelected(int index) { }
        @Override public void drawDropPreview(android.graphics.Canvas canvas,
                float x, float y, float w, float h, int tab) { }
        @Override public String previewDropBadge(int tab) { return null; }
        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) { }
        @Override public void onPreviewContentChanged(CharSequence text) { }
        @Override public void drawPreview(android.graphics.Canvas canvas,
                float offsetX, float offsetY) { }
        @Override public boolean hasPreviewContent() { return false; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }

        @Override public boolean wantsInteractiveDropPreview() { return true; }

        @Override public boolean onPreviewDropTouch(int action, float sx, float sy) {
            if (action == MotionEvent.ACTION_DOWN && declinerDown) {
                dejaDecline = true;
                return false;
            }
            touches.add(new Touche(action, sx, sy));
            return true;
        }

        @Override public void onPreviewDropLongPress(float sx, float sy) {
            longPresses.add(new float[] {sx, sy});
        }
    }

    /** Miroir JAMAIS interactif du précédent (toutes les mêmes valeurs). */
    private static final class HoteInert implements EditorPreviewHost {
        final List<int[]> touches = new ArrayList<>();

        @Override public boolean canPreview(String fileName) { return true; }
        @Override public boolean prefersDropPreview() { return true; }
        @Override public List<String> previewDropTabs() { return new ArrayList<>(); }
        @Override public void onPreviewDropTabSelected(int index) { }
        @Override public void drawDropPreview(android.graphics.Canvas canvas,
                float x, float y, float w, float h, int tab) { }
        @Override public String previewDropBadge(int tab) { return null; }
        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) { }
        @Override public void onPreviewContentChanged(CharSequence text) { }
        @Override public void drawPreview(android.graphics.Canvas canvas,
                float offsetX, float offsetY) { }
        @Override public boolean hasPreviewContent() { return false; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }

        // PAS de surcharge des méthodes C3 : le défaut doit tenir.

        @Override public boolean onPreviewDropTouch(int action, float sx, float sy) {
            touches.add(new int[] {action});
            return true;
        }

        @Override public void onPreviewDropLongPress(float sx, float sy) {
            touches.add(new int[] {-1});
        }
    }

    private static EditorView newView(EditorPreviewHost host) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(DOC)));
        view.setPreviewHost(host);
        view.setFileName("MainActivity.kt");
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        view.getSession().setSelection(0);
        view.openPreview(false);
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);
        return view;
    }

    /** Coordonnées ÉCRAN attendues d'un point vue (placement réel). */
    private static float[] ecranAttendu(EditorView view, float vx, float vy) {
        Placement p = view.preview.drop().placement();
        RectF ecran = view.preview.drop().screenRect();
        return new float[] {
            (vx - p.cardLeft) / p.scale - ecran.left,
            (vy - p.cardTop) / p.scale - ecran.top,
        };
    }

    /** Le point vue du CENTRE de l'écran du mini-téléphone. */
    private static float[] centreEcran(EditorView view) {
        Placement p = view.preview.drop().placement();
        RectF ecran = view.preview.drop().screenRect();
        return new float[] {
            p.cardLeft + ecran.centerX() * p.scale,
            p.cardTop + ecran.centerY() * p.scale,
        };
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

    // ── Scénarios ───────────────────────────────────────────────────

    /**
     * (1) Le corps interactif transfère DOWN/UP dans le REPÈRE de
     * drawDropPreview — les coords recalculées depuis le placement réel
     * tombent au pixel près, quel que soit le facteur de taille.
     */
    @Test
    public void tapCorpsEstTransfereAuRepereEcran() {
        HoteInteractif host = new HoteInteractif();
        EditorView view = newView(host);
        float[] centre = centreEcran(view);
        float[] attendu = ecranAttendu(view, centre[0], centre[1]);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, centre[0], centre[1]);
        send(view, MotionEvent.ACTION_UP, down, down + 40, centre[0], centre[1]);
        idle();
        assertEquals(2, host.touches.size());
        assertEquals(MotionEvent.ACTION_DOWN, host.touches.get(0).action);
        assertEquals(MotionEvent.ACTION_UP, host.touches.get(1).action);
        assertEquals(attendu[0], host.touches.get(0).sx, 0.5f);
        assertEquals(attendu[1], host.touches.get(0).sy, 0.5f);
    }

    /**
     * (2) Les MOVE suivent le doigt (clamping à l'écran au bord), et le
     * geste reste au hôte jusqu'au relâchement.
     */
    @Test
    public void moveCorpsSuitLeDoigtClampe() {
        HoteInteractif host = new HoteInteractif();
        EditorView view = newView(host);
        float[] centre = centreEcran(view);
        RectF ecran = view.preview.drop().screenRect();
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, centre[0], centre[1]);
        // Doigt balancé BIEN au-delà du bord droit de la carte.
        send(view, MotionEvent.ACTION_MOVE, down, down + 30, centre[0] + 900f, centre[1]);
        send(view, MotionEvent.ACTION_UP, down, down + 60, centre[0] + 900f, centre[1]);
        idle();
        assertEquals(3, host.touches.size());
        assertEquals(MotionEvent.ACTION_MOVE, host.touches.get(1).action);
        assertEquals(ecran.width(), host.touches.get(1).sx, 0.5f);
    }

    /**
     * (3) Appui long : le geste est ANNULÉ côté hôte (CANCEL transféré)
     * puis l'inspection part au hôte — l'UP qui suit n'est PAS transféré
     * (le hôte n'attend jamais l'UP d'un geste devenu long).
     */
    @Test
    public void appuiLongAnnulePuisInspecte() {
        HoteInteractif host = new HoteInteractif();
        EditorView view = newView(host);
        float[] centre = centreEcran(view);
        float[] attendu = ecranAttendu(view, centre[0], centre[1]);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, centre[0], centre[1]);
        SystemClock.sleep(1200L);
        idle();
        assertEquals(1, host.longPresses.size());
        assertEquals(attendu[0], host.longPresses.get(0)[0], 0.5f);
        assertEquals(attendu[1], host.longPresses.get(0)[1], 0.5f);
        // Le CANCEL précède l'inspection.
        assertEquals(2, host.touches.size());
        assertEquals(MotionEvent.ACTION_DOWN, host.touches.get(0).action);
        assertEquals(MotionEvent.ACTION_CANCEL, host.touches.get(1).action);
        // L'UP tardif n'est PAS transféré : le geste est mort.
        send(view, MotionEvent.ACTION_UP, down, down + 2000, centre[0], centre[1]);
        idle();
        assertEquals(2, host.touches.size());
    }

    /**
     * (4) Un drag au-delà du slop RETIRE l'appui long : c'est un DRAG
     * transféré, pas une inspection — l'UP arrive au hôte.
     */
    @Test
    public void dragAuSlopRetireLAppuiLong() {
        HoteInteractif host = new HoteInteractif();
        EditorView view = newView(host);
        float[] centre = centreEcran(view);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, centre[0], centre[1]);
        send(view, MotionEvent.ACTION_MOVE, down, down + 30, centre[0] + 60f, centre[1]);
        send(view, MotionEvent.ACTION_MOVE, down, down + 60, centre[0] + 120f, centre[1]);
        SystemClock.sleep(1200L);
        idle();
        send(view, MotionEvent.ACTION_UP, down, down + 2000, centre[0] + 120f, centre[1]);
        idle();
        assertTrue(host.longPresses.isEmpty());
        assertEquals(4, host.touches.size());
        assertEquals(MotionEvent.ACTION_UP, host.touches.get(3).action);
    }

    /**
     * (5) Hôte NON interactif (défauts C3 intacts) : le corps reste
     * englouti SANS AGIR — aucune touche, aucune inspection, le tap ne
     * bouge pas non plus le caret (comportement B27 historique).
     */
    @Test
    public void hoteNonInteractifResteInertAuBitPres() {
        HoteInert host = new HoteInert();
        EditorView view = newView(host);
        int caretAvant = view.getSession().getSelection().start;
        float[] centre = centreEcran(view);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, centre[0], centre[1]);
        SystemClock.sleep(1200L);
        send(view, MotionEvent.ACTION_UP, down, down + 2000, centre[0], centre[1]);
        idle();
        assertTrue(host.touches.isEmpty());
        assertEquals(caretAvant, view.getSession().getSelection().start);
        // La carte vit toujours (aucun ✕ touché).
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);
    }

    /**
     * (6) Un hôte interactif qui DÉCLINE le DOWN (aucun contenu) retombe
     * sur le corps inerte POUR CE GESTE : pas de MOVE/UP transférés.
     */
    @Test
    public void downDeclineRetombeInertPourCeGeste() {
        HoteInteractif host = new HoteInteractif();
        host.declinerDown = true;
        EditorView view = newView(host);
        float[] centre = centreEcran(view);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, centre[0], centre[1]);
        send(view, MotionEvent.ACTION_MOVE, down, down + 30, centre[0] + 40f, centre[1]);
        send(view, MotionEvent.ACTION_UP, down, down + 60, centre[0] + 40f, centre[1]);
        idle();
        assertTrue(host.dejaDecline);
        assertTrue(host.touches.isEmpty());
    }

    /**
     * (7) L'EN-TÊTE garde ses gestes : un DOWN sur les onglets/✕ (hors
     * écran du mini-téléphone) n'est PAS transféré — le corps ne commence
     * qu'au rectangle écran.
     */
    @Test
    public void enTeteNEstPasTransfere() {
        HoteInteractif host = new HoteInteractif();
        EditorView view = newView(host);
        Placement p = view.preview.drop().placement();
        // Milieu de l'en-tête (y local ~20 px, hors de l'écran).
        float vx = p.cardLeft + p.visualWidth() * 0.5f;
        float vy = p.cardTop + 20f * p.scale;
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, vx, vy);
        send(view, MotionEvent.ACTION_UP, down, down + 40, vx, vy);
        idle();
        assertTrue(host.touches.isEmpty());
    }

    /**
     * (8) La carte REFERMÉE en vol de geste envoie son CANCEL (le hôte
     * n'attend jamais un UP fantôme) — le MOVE suivant n'est plus
     * transféré.
     */
    @Test
    public void carteRefermeeEnVolDeGesteEnvoieCancel() {
        HoteInteractif host = new HoteInteractif();
        EditorView view = newView(host);
        float[] centre = centreEcran(view);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, centre[0], centre[1]);
        view.closePreviewDrop();
        send(view, MotionEvent.ACTION_MOVE, down, down + 30, centre[0] + 40f, centre[1]);
        idle();
        assertEquals(2, host.touches.size());
        assertEquals(MotionEvent.ACTION_CANCEL, host.touches.get(1).action);
        send(view, MotionEvent.ACTION_UP, down, down + 60, centre[0] + 40f, centre[1]);
        idle();
        assertEquals(2, host.touches.size());
    }
}
