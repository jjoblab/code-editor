package jo.codeeditor.view.preview;

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

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.TestMetrics;
import jo.codeeditor.view.render.EditorPreviewDropPainter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B27 (retour terrain n°3) — verrouille la <b>barre de taille de la
 * goutte d'eau</b> : le réglage vit DANS la carte, « à côté du texte
 * fit », sous forme d'une petite barre de progression — « comme ça
 * l'utilisateur pourra choisir quelle taille au lieu du 232 dp ».
 *
 * <p>Propriétés prouvées : (1) la piste est posée dans le PIED de la
 * carte, à gauche de la puce « fit · NN % » (formule partagée
 * contrôleur/painter — aucun chevauchement possible) ; (2) le
 * <b>press saute immédiatement</b> le curseur (miroir du sélecteur de
 * couleur B26), le drag suit VIVANT et le relâchement notifie le hôte
 * UNE fois (persistance) ; (3) les bornes [1, 1,5] sont tenues dans
 * les deux sens (clamp) ; (4) la <b>progression est STABLE et
 * exacte</b> : le repère de piste est GELÉ au press — un doigt
 * immobile ne fait jamais osciller la valeur (le re-mappage sur le
 * placement vivant, lui, diverge — boucle découverte par ce test) et
 * la progression reste une fonction linéaire de la position du doigt
 * pendant que la carte grandit sous lui ; (5) le reste du pied reste
 * englouti (la piste ne mange pas le corps) ; (6) le painter dessine
 * piste + curseur (cercle du pouce au bout du remplissage, formule
 * partagée avec le hit-test).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PreviewDropSizeSliderTest {

    /** Ligne 0 = ancre de la carte ; le caret vit à la FIN (loin de la
     *  carte : placement ANCRÉ, échelle = facteur d'agrandissement seul). */
    private static final String DOC =
        "  <TextView />\n"       // 0 : ligne d'ancre de la carte
        + "</LinearLayout>\n"    // 1
        + "\n"                   // 2
        + "\n"                   // 3
        + "\n"                   // 4
        + "\n"                   // 5
        + "\n"                   // 6
        + "\n"                   // 7
        + "\n";                  // 8 : le caret vit ici

    /** Hôte « goutte d'eau » avec enregistreur du crochet de persistance. */
    private static final class DropHost implements EditorPreviewHost {
        final List<Float> scaleChanges = new ArrayList<>();

        @Override public boolean canPreview(String fileName) { return true; }
        @Override public boolean prefersDropPreview() { return true; }
        @Override public List<String> previewDropTabs() {
            List<String> tabs = new ArrayList<>();
            tabs.add("XML");
            tabs.add("Compose");
            return tabs;
        }
        @Override public void onPreviewDropTabSelected(int index) { }
        @Override public void drawDropPreview(Canvas canvas, float x, float y,
                float w, float h, int tab) { }
        @Override public String previewDropBadge(int tab) { return "gonflé"; }
        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) { }
        @Override public void onPreviewContentChanged(CharSequence text) { }
        @Override public void drawPreview(Canvas canvas, float offsetX,
                float offsetY) { }
        @Override public boolean hasPreviewContent() { return false; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }
        @Override public void onPreviewDropScaleChanged(float scale) {
            scaleChanges.add(scale);
        }
    }

    private EditorView view;
    private DropHost host;

    private EditorView newView(int w, int h, float lineH, float charW) {
        Context ctx = RuntimeEnvironment.getApplication();
        host = new DropHost();
        view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(DOC)));
        view.setPreviewHost(host);
        view.setFileName("activity_main.xml");
        view.measure(w, h);
        view.layout(0, 0, w, h);
        TestMetrics.injecter(view, lineH, charW);
        return view;
    }

    /** Carte ouverte ancrée ligne 0, caret descendu à la fin du doc. */
    private EditorView openCard() {
        newView(1080, 900, 80f, 10f);
        view.getSession().setSelection(0);
        view.openPreview(false);
        assertTrue("la carte est ouverte",
                view.previewMode == EditorView.PreviewMode.DROP);
        view.getSession().setSelection(DOC.length());
        return view;
    }

    /** Tap simple DOWN+UP au point donné. */
    private static void tap(EditorView view, float x, float y) {
        gesture(view, new float[]{x}, y);
    }

    /** Séquence tactile : DOWN à xs[0], MOVE vers xs[1..], UP au dernier. */
    private static void gesture(EditorView view, float[] xs, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN,
                xs[0], y, 0);
        view.onTouchEvent(e);
        e.recycle();
        for (int i = 1; i < xs.length; i++) {
            MotionEvent m = MotionEvent.obtain(t, t + i * 16L,
                    MotionEvent.ACTION_MOVE, xs[i], y, 0);
            view.onTouchEvent(m);
            m.recycle();
        }
        MotionEvent up = MotionEvent.obtain(t, t + xs.length * 16L,
                MotionEvent.ACTION_UP, xs[xs.length - 1], y, 0);
        view.onTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Abcisse écran d'une coordonnée LOCALE de piste (échelle courante). */
    private static float screenX(EditorView view, RectF track, float localX) {
        EditorPreviewDropGeometry.Placement p = view.preview.drop().placement();
        return p.cardLeft + localX * p.scale;
    }

    /** Ordonnée écran du centre de piste (échelle courante). */
    private static float trackScreenY(EditorView view, RectF track) {
        EditorPreviewDropGeometry.Placement p = view.preview.drop().placement();
        return p.cardTop + track.centerY() * p.scale;
    }

    // ── 1. La piste vit dans le pied, à côté de la puce « fit » ────

    @Test
    public void piste_poséeDansLePiedÀCôtéDeLaPuceFit() {
        EditorView view = openCard();
        RectF track = view.preview.drop().scaleSliderRect();
        assertFalse("piste posée à l'ouverture", track.isEmpty());

        float d = view.getResources().getDisplayMetrics().density;
        assertEquals("largeur de piste (dp verrouillé)",
                EditorPreviewDrop.SCALE_TRACK_W_DP * d, track.width(), 0.5f);
        assertEquals("hauteur de piste (dp verrouillé)",
                EditorPreviewDrop.SCALE_TRACK_H_DP * d, track.height(), 0.5f);

        float cardH = EditorPreviewDrop.CARD_H_DP;
        float footerTop = cardH - EditorPreviewDrop.FOOTER_H_DP;
        assertEquals("centrée verticalement dans le pied",
                (footerTop + EditorPreviewDrop.FOOTER_H_DP * 0.5f) * d,
                track.centerY(), 0.5f);
        assertTrue("entièrement dans le pied",
                track.top >= footerTop * d - 0.5f
                        && track.bottom <= cardH * d + 0.5f);

        // Formule partagée avec le painter : la piste se pose 8 dp à
        // gauche de la puce « fit » LA PLUS LARGE possible (« fit ·
        // 150 % », texte 9 dp + padding 16 dp) — jamais de chevauchement,
        // quelle que soit la valeur affichée.
        Paint p9 = new Paint(Paint.ANTI_ALIAS_FLAG);
        p9.setTextSize(9f * d);
        float fitMaxW = p9.measureText("fit · 150 %") + 16f * d;
        float cardW = EditorPreviewDrop.CARD_W_DP * d;
        assertEquals("à gauche de la puce fit (formule partagée)",
                cardW - 12f * d - fitMaxW - 8f * d
                        - EditorPreviewDrop.SCALE_TRACK_W_DP * d,
                track.left, 0.5f);
        assertTrue("dégage la puce « rendu vivant » (gauche)",
                track.left > 60f * d);
    }

    // ── 2. Hit-test : piste + marges de tolérance ──────────────────

    @Test
    public void hitTest_pisteEtMarges() {
        EditorView view = openCard();
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        float d = view.getResources().getDisplayMetrics().density;
        EditorPreviewDropGeometry.Placement p = drop.placement();
        float cx = p.cardLeft + track.centerX() * p.scale;
        float cy = p.cardTop + track.centerY() * p.scale;

        // Fermée : aucun hit.
        EditorView fermée = newView(1080, 900, 80f, 10f);
        assertFalse("carte fermée : pas de hit",
                fermée.preview.drop().hitTestSlider(cx, cy));

        assertTrue("centre de piste", drop.hitTestSlider(cx, cy));
        assertTrue("marge droite (+12 dp)", drop.hitTestSlider(
                p.cardLeft + (track.right + EditorPreviewDrop.SCALE_HIT_PAD_X_DP * d) * p.scale, cy));
        assertTrue("marge gauche (−12 dp)", drop.hitTestSlider(
                p.cardLeft + (track.left - EditorPreviewDrop.SCALE_HIT_PAD_X_DP * d) * p.scale, cy));
        assertFalse("au-delà de la marge droite", drop.hitTestSlider(
                p.cardLeft + (track.right + 14f * d) * p.scale, cy));
        assertFalse("au-dessus du pied (corps)", drop.hitTestSlider(cx,
                p.cardTop + (track.top - 16f * d) * p.scale));
        assertFalse("sous le pied", drop.hitTestSlider(cx,
                p.cardTop + (track.bottom + 14f * d) * p.scale));
        assertFalse("hors de la carte", drop.hitTestSlider(
                p.cardLeft - 40f * d, cy));
    }

    // ── 3. Press = saut immédiat ; tap ne fait rien d'autre ────────

    @Test
    public void press_leCurreurSauteImmédiatement() {
        EditorView view = openCard();
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        EditorPreviewDropGeometry.Placement p = drop.placement();
        int caretBefore = view.getSession().getSelection().start;

        // Tap au milieu de piste : progression 0,5 → facteur 1,25.
        tap(view, p.cardLeft + track.centerX() * p.scale,
                p.cardTop + track.centerY() * p.scale);

        assertEquals("press = saut immédiat au milieu de la piste",
                1.25f, drop.cardScale(), 0.02f);
        assertTrue("la carte reste ouverte", drop.isOpen());
        assertEquals("le reste du tap est englouti (caret immobile)",
                caretBefore, view.getSession().getSelection().start);
        assertFalse("rien ne s'insère dans le document",
                view.getSession().getText().contains("f7"));
    }

    // ── 4. Drag vivant, borné dans les deux sens ───────────────────

    @Test
    public void drag_vivantEtBorné() {
        EditorView view = openCard();
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();

        // DOWN au bord gauche → progression 0 → facteur 1.
        gesture(view, new float[]{screenX(view, track, track.left)},
                trackScreenY(view, track));
        assertEquals("bord gauche : maquette", 1f, drop.cardScale(), 0.001f);

        // MOVE au-delà du bord droit (même geste) → clampé au plafond.
        gesture(view, new float[]{screenX(view, track, track.left),
                screenX(view, track, track.right + 40f)},
                trackScreenY(view, track));
        assertEquals("au-delà du bord droit : clampé 1,5", 1.5f,
                drop.cardScale(), 0.001f);

        // MOVE au-delà du bord gauche → clampé au plancher. (Positions
        // et ordonnée recalculées sur le placement COURANT : la carte
        // est désormais 1,5× — le pied a bougé à l'écran.)
        gesture(view, new float[]{screenX(view, track, track.right),
                screenX(view, track, track.left - 40f)},
                trackScreenY(view, track));
        assertEquals("au-delà du bord gauche : clampé 1 (plancher)", 1f,
                drop.cardScale(), 0.001f);
    }

    // ── 5. Progression stable et exacte (repère gelé au press) ────

    @Test
    public void drag_progressionStableEtExacte() {
        EditorView view = openCard();
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        EditorPreviewDropGeometry.Placement p0 = drop.placement();
        assertEquals("pré-condition : carte ancrée", 1, p0.level);

        // Doigt immobile (DOWN + MOVE au même point, jitter réel des
        // écrans tactiles) : la progression reste EXACTEMENT 0,5 — le
        // re-mappage sur le placement vivant divergeait ici (la carte
        // grandit, la position locale du doigt rétrécit, la valeur
        // oscille puis tombe au plancher) ; le repère gelé la tient.
        float y0 = p0.cardTop + track.centerY() * p0.scale;
        float xMid = p0.cardLeft + track.centerX() * p0.scale;
        gesture(view, new float[]{xMid, xMid, xMid}, y0);
        assertEquals("doigt immobile : 1,25 exact, aucune oscillation",
                1.25f, drop.cardScale(), 0.001f);

        // La progression reste linéaire envers le doigt DANS un geste :
        // press au centre (recalé sur la carte agrandie) puis un quart
        // de piste plus loin → 0,75 → 1,375.
        EditorPreviewDropGeometry.Placement p1 = drop.placement();
        float y1 = p1.cardTop + track.centerY() * p1.scale;
        float xMid1 = p1.cardLeft + track.centerX() * p1.scale;
        float xQuart = p1.cardLeft
                + (track.centerX() + track.width() * 0.25f) * p1.scale;
        gesture(view, new float[]{xMid1, xQuart}, y1);
        assertEquals("progression linéaire du doigt (repère gelé)",
                1.375f, drop.cardScale(), 0.001f);

        // Retour exact au plancher dans le même geste.
        EditorPreviewDropGeometry.Placement p2 = drop.placement();
        float y2 = p2.cardTop + track.centerY() * p2.scale;
        float xEnd = p2.cardLeft + track.right * p2.scale;
        float xStart = p2.cardLeft + track.left * p2.scale;
        gesture(view, new float[]{xEnd, xStart}, y2);
        assertEquals("retour au plancher dans le même geste", 1f,
                drop.cardScale(), 0.001f);
    }

    // ── 6. Relâchement : hôte notifié UNE fois ─────────────────────

    @Test
    public void release_notifieLEHôteUneFois() {
        EditorView view = openCard();
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        EditorPreviewDropGeometry.Placement p0 = drop.placement();
        float y = p0.cardTop + track.centerY() * p0.scale;

        gesture(view, new float[]{screenX(view, track, track.left),
                screenX(view, track, track.right)}, y);

        assertEquals("une seule notification (au relâchement)", 1,
                host.scaleChanges.size());
        assertEquals("avec le facteur final", 1.5f,
                host.scaleChanges.get(0), 0.001f);

        // Voie programmatique : aucun couplage parasite au hôte.
        view.setPreviewDropScale(1.2f);
        assertEquals("la voie programmatique ne notifie pas", 1,
                host.scaleChanges.size());
    }

    @Test
    public void hôteSansSurcharge_défautInoffensif() {
        EditorView view = newView(1080, 900, 80f, 10f);
        // Hôte qui n'implémente PAS onPreviewDropScaleChanged : le
        // crochet par défaut est un no-op, le geste vit quand même.
        view.setPreviewHost(new EditorPreviewHost() {
            @Override public boolean canPreview(String fileName) { return true; }
            @Override public boolean prefersDropPreview() { return true; }
            @Override public void onPreviewModeChanged(EditorView.PreviewMode m,
                    int l, int w) { }
            @Override public void onPreviewContentChanged(CharSequence t) { }
            @Override public void drawPreview(Canvas c, float ox, float oy) { }
            @Override public boolean hasPreviewContent() { return false; }
            @Override public boolean hitTestPreview(float x, float y) { return false; }
        });
        view.getSession().setSelection(0);
        view.openPreview(false);
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        EditorPreviewDropGeometry.Placement p = drop.placement();

        gesture(view, new float[]{p.cardLeft + track.centerX() * p.scale,
                p.cardLeft + track.right * p.scale},
                p.cardTop + track.centerY() * p.scale);

        assertEquals("le drag vit sans surcharge du crochet", 1.5f,
                drop.cardScale(), 0.001f);
        assertTrue(drop.isOpen());
    }

    // ── 7. Formules de mapping (progression ⇄ facteur) ─────────────

    @Test
    public void mapping_formulesMiroir() {
        assertEquals(1f, EditorPreviewDrop.scaleFromProgress(0f), 0.0001f);
        assertEquals(1.5f, EditorPreviewDrop.scaleFromProgress(1f), 0.0001f);
        assertEquals(1.25f, EditorPreviewDrop.scaleFromProgress(0.5f), 0.0001f);
        assertEquals("progression clampée bas",
                0f, EditorPreviewDrop.progressFromScale(0.2f), 0.0001f);
        assertEquals("progression clampée haut",
                1f, EditorPreviewDrop.progressFromScale(9f), 0.0001f);
        for (float s = 1f; s <= 1.5f; s += 0.01f) {
            assertEquals("miroir exact pour " + s, s,
                    EditorPreviewDrop.scaleFromProgress(
                            EditorPreviewDrop.progressFromScale(s)), 0.0001f);
        }
    }

    // ── 8. Le reste du pied reste englouti ─────────────────────────

    @Test
    public void leCorpsHorsPisteResteEnglouti() {
        EditorView view = openCard();
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        EditorPreviewDropGeometry.Placement p = drop.placement();
        int caretBefore = view.getSession().getSelection().start;

        // Tap dans le pied, à GAUCHE de la piste (zone « rendu vivant »).
        tap(view, p.cardLeft + 30f * p.scale,
                p.cardTop + track.centerY() * p.scale);

        assertEquals("englouti : le caret ne bouge pas", caretBefore,
                view.getSession().getSelection().start);
        assertTrue("la carte reste ouverte", drop.isOpen());
        assertEquals("la taille ne bouge pas (hors piste)", 1f,
                drop.cardScale(), 0.001f);
    }

    // ── 9. Le painter dessine piste + curseur ──────────────────────

    /** Canvas enregistreur : capture les cercles (curseur de la piste). */
    private static final class RecordingCanvas extends Canvas {
        final List<float[]> circles = new ArrayList<>();
        RecordingCanvas(Bitmap bmp) { super(bmp); }
        @Override public void drawCircle(float cx, float cy, float radius,
                Paint paint) {
            circles.add(new float[]{cx, cy, radius});
            super.drawCircle(cx, cy, radius, paint);
        }
    }

    @Test
    public void render_pisteEtCurseurDessinés() {
        EditorView view = openCard();
        view.setPreviewDropScale(1.3f); // progression 0,6
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        float d = view.getResources().getDisplayMetrics().density;

        RecordingCanvas canvas = new RecordingCanvas(Bitmap.createBitmap(
                view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888));
        new EditorPreviewDropPainter(view).drawCard(canvas);

        // Le curseur : cercle du pouce (5,5 dp de rayon) au bout du
        // remplissage (60 % de la piste), centré dans le pied — la
        // même formule que le hit-test (rect du contrôleur).
        float expectedCx = track.left + 0.6f * track.width();
        boolean found = false;
        for (float[] c : canvas.circles) {
            if (Math.abs(c[2] - EditorPreviewDrop.SCALE_THUMB_D_DP * 0.5f * d) < 0.01f
                    && Math.abs(c[0] - expectedCx) < 0.5f
                    && Math.abs(c[1] - track.centerY()) < 0.5f) {
                found = true;
            }
        }
        assertTrue("curseur de la piste dessiné au bout du remplissage "
                + "(formule partagée dessin/hit-test)", found);
        // La puce « rendu vivant » garde SON point (2,5 dp) : deux
        // cercles distincts dans le pied.
        boolean liveDot = false;
        for (float[] c : canvas.circles) {
            if (Math.abs(c[2] - 2.5f * d) < 0.01f) liveDot = true;
        }
        assertTrue("la puce « rendu vivant » garde son point", liveDot);
    }
}
