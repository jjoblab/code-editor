package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.preview.EditorPreviewHost;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ★ B28 — verrouille les yeux d'aperçu ANCRÉS « comme Android Studio » :
 * UN œil par ligne qui porte une `@Preview` (l'icône vit à la ligne de
 * l'annotation, JAMAIS sur le caret), AUCUN œil sans `@Preview`, et le
 * modèle historique (œil unique du caret, B27) INTACT pour les hôtes qui
 * ne posent pas d'ancres.
 *
 * <ul>
 *   <li>le tap sur un œil ancre ouvre la carte « goutte d'eau » SUR CETTE
 *       rangée et notifie le hôte ({@code onPreviewAnchorTapped}) — il
 *       sélectionne l'onglet de LA preview de la ligne ;</li>
 *   <li>le re-tap sur l'œil de l'ancre GELÉE referme (bascule B27) ;</li>
 *   <li>le tap sur un AUTRE œil RÉ-ANCRE la carte (l'onglet suit) ;</li>
 *   <li>ancres VIDES : AUCUN œil, aucun tap (parité AS — aucune icône
 *       sans sujet) ; ancres {@code null} : comportement B27 au bit
 *       près.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class GutterPreviewAnchorsTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;

    private static final String DOC =
        "# Titre\n"                    // 0
        + "@Preview\n"                 // 1
        + "fun A() {}\n"               // 2
        + "@Preview\n"                // 3
        + "fun B() {}\n"               // 4
        + "fin\n";                     // 5

    /**
     * Hôte « goutte d'eau » minimal : prévisualise .kt, carte à onglets
     * (A/B), enregistre les lignes d'ancres tapées et SÉLECTIONNE
     * l'onglet de la ligne (miroir du comportement CodeIDE B28).
     */
    private static final class StubAnchoredHost implements EditorPreviewHost {
        final List<Integer> lignesTapées = new java.util.ArrayList<>();
        int ongletNotifié = -1;

        @Override public boolean canPreview(String fileName) {
            String n = fileName == null ? "" : fileName.toLowerCase();
            return n.endsWith(".kt") || n.endsWith(".kts");
        }

        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) { }

        @Override public void onPreviewContentChanged(CharSequence text) { }

        @Override public void drawPreview(Canvas canvas, float offsetX, float offsetY) { }

        @Override public boolean hasPreviewContent() { return false; }

        @Override public boolean hitTestPreview(float x, float y) { return false; }

        @Override public boolean prefersDropPreview() { return true; }

        @Override public List<String> previewDropTabs() {
            return Arrays.asList("A", "B");
        }

        @Override public void onPreviewDropTabSelected(int index) {
            ongletNotifié = index;
        }

        @Override public void onPreviewAnchorTapped(int line) {
            lignesTapées.add(line);
            // Miroir CodeIDE : la ligne 1 → onglet A (0), la ligne 3 →
            // onglet B (1) — via le relais public de la vue.
            vue.selectPreviewDropTab(line == 1 ? 0 : 1);
        }

        EditorView vue;
    }

    private EditorView newView(StubAnchoredHost host) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        host.vue = view;
        view.setPreviewHost(host);
        view.setFileName("apercu.kt");
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    private static float eyeX(EditorView view) {
        return (view.metrics.getGutterWidth()
                - view.metrics.getFoldStripWidth()) * 0.5f;
    }

    private static float rowCenterY(EditorView view, int line) {
        return view.docLineToY(line) - view.scroll.vOffset + LINE_H * 0.5f;
    }

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
        MotionEvent up = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // ── 1. Hit-test par ancre ─────────────────────────────────────

    @Test
    public void hitTest_ancresPosées_vraiAuxLignesDesPreviewsSeulement() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));

        assertEquals("œil de la ligne 1", 1,
                view.hitTestGutterPreviewAnchor(eyeX(view), rowCenterY(view, 1)));
        assertEquals("œil de la ligne 3", 3,
                view.hitTestGutterPreviewAnchor(eyeX(view), rowCenterY(view, 3)));
        assertEquals("la ligne 2 ne porte PAS d'œil", -1,
                view.hitTestGutterPreviewAnchor(eyeX(view), rowCenterY(view, 2)));
        assertEquals("la ligne du caret non ancrée : rien", -1,
                view.hitTestGutterPreviewAnchor(eyeX(view), rowCenterY(view, 0)));
    }

    @Test
    public void hitTest_ancresTriéesEtDédupliquées() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(3, 1, 3, 1));
        assertArrayEquals("tri et dédoublonnage", new int[]{1, 3},
                view.getPreviewAnchors());
    }

    @Test
    public void hitTest_ancresVides_aucuneZoneTactileNullePart() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Collections.emptyList());

        assertEquals(-1, view.hitTestGutterPreviewAnchor(
                eyeX(view), rowCenterY(view, 1)));
        // Le chemin HISTORIQUE ne répond PLUS tant que des ancres sont
        // posées (même vides) : aucun œil, aucune zone fantôme.
        assertFalse(view.hitTestGutterPreviewAction(
                eyeX(view), rowCenterY(view, 1)));
    }

    @Test
    public void ancresPosées_leCheminHistoriqueSeTait() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));
        view.getSession().setSelection(DOC.indexOf("fun A"));

        assertFalse("l'œil du caret n'existe pas en mode ancré",
                view.hitTestGutterPreviewAction(
                        eyeX(view), rowCenterY(view, 2)));
    }

    @Test
    public void ancresNull_comportementHistoriqueIntact() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        // AUCUN setPreviewAnchors : null par défaut.
        view.getSession().setSelection(DOC.indexOf("fun A"));
        assertTrue("le chemin B27 répond au caret (mode historique)",
                view.hitTestGutterPreviewAction(eyeX(view), rowCenterY(view, 2)));
        assertEquals(-1, view.hitTestGutterPreviewAnchor(
                eyeX(view), rowCenterY(view, 2)));
    }

    // ── 2. Tap : carte ancrée à la ligne tapée, hôte notifié ──────

    @Test
    public void tapSurŒilAncre_ouvreLaCarteAncréeEtSélectionneLOnglet() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));

        tap(view, eyeX(view), rowCenterY(view, 3));

        assertEquals("la carte s'ouvre en mode DROP",
                EditorView.PreviewMode.DROP, view.previewMode);
        assertTrue("la carte est ouverte", view.preview.drop().isOpen());
        assertEquals("ancrée à la ligne TAPÉE", 3,
                view.preview.drop().getAnchorLine());
        assertEquals("le hôte a appris la ligne", List.of(3), host.lignesTapées);
        assertEquals("l'onglet de LA preview de la ligne est sélectionné",
                1, view.preview.drop().getSelectedTab());
        assertEquals("le hôte a été notifié de la sélection", 1, host.ongletNotifié);
    }

    @Test
    public void reTapSurLOeilDeLAncreGelée_refermeLaCarte() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));

        tap(view, eyeX(view), rowCenterY(view, 3));
        tap(view, eyeX(view), rowCenterY(view, 3));

        assertEquals("le re-tap sur l'œil gelé referme",
                EditorView.PreviewMode.NONE, view.previewMode);
        assertFalse(view.preview.drop().isOpen());
    }

    @Test
    public void tapSurUnAutreŒil_réancreLaCarteEtSuitLOnglet() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));

        tap(view, eyeX(view), rowCenterY(view, 3));
        assertEquals(3, view.preview.drop().getAnchorLine());

        tap(view, eyeX(view), rowCenterY(view, 1));

        assertEquals("la carte reste ouverte, RÉ-ANCRÉE à la ligne 1",
                EditorView.PreviewMode.DROP, view.previewMode);
        assertEquals(1, view.preview.drop().getAnchorLine());
        assertEquals("l'onglet suit (A pour la ligne 1)",
                0, view.preview.drop().getSelectedTab());
        assertEquals("le hôte a appris les DEUX lignes",
                List.of(3, 1), host.lignesTapées);
    }

    @Test
    public void tapHorsDesYeuxEnModeAncré_neRienOuvre() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));

        // Tap sur la ligne 2 (sans œil) dans la gouttière : le caret bouge
        // comme un tap de gouttière normal, AUCUNE carte.
        tap(view, eyeX(view), rowCenterY(view, 2));

        assertEquals("aucune carte pour un tap hors œil",
                EditorView.PreviewMode.NONE, view.previewMode);
        assertTrue(host.lignesTapées.isEmpty());
    }

    @Test
    public void ancresPoséesPuisNull_retourAuModeHistorique() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));
        view.setPreviewAnchors(null);

        view.getSession().setSelection(DOC.indexOf("fun B"));
        assertEquals("null = retour au mode B27 (œil du caret)",
                -1, view.hitTestGutterPreviewAnchor(eyeX(view), rowCenterY(view, 4)));
        assertTrue("l'œil historique répond de nouveau au caret",
                view.hitTestGutterPreviewAction(eyeX(view), rowCenterY(view, 4)));
    }

    @Test
    public void selectTab_horsBornes_clampéSansException() {
        StubAnchoredHost host = new StubAnchoredHost();
        EditorView view = newView(host);
        view.setPreviewAnchors(Arrays.asList(1, 3));
        tap(view, eyeX(view), rowCenterY(view, 3));

        view.selectPreviewDropTab(99);
        assertEquals("clampé au DERNIER onglet", 1,
                view.preview.drop().getSelectedTab());
        view.selectPreviewDropTab(-5);
        assertEquals("clampé au PREMIER onglet", 0,
                view.preview.drop().getSelectedTab());
        assertEquals("la notification porte l'index CLAMPÉ", 0,
                host.ongletNotifié);
    }
}
