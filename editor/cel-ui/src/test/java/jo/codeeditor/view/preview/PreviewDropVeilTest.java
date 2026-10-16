package jo.codeeditor.view.preview;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.TestMetrics;
import jo.codeeditor.view.preview.EditorPreviewDropGeometry.Placement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B27 (retour terrain) — verrouille le <b>voile d'eau</b> de la carte
 * d'aperçu : « il cache parfois le popup de complétion… s'il détecte
 * qu'il couvre une partie d'une autre popup il devient progressivement
 * transparent et redevient normal dès qu'il ne cache plus rien ».
 *
 * <p>Trois propriétés prouvées : (1) l'<b>hystérésis à deux plateaux</b>
 * — voile au-delà de 10 % de complétion couverte, dévoile en dessous de
 * 4 %, STABLE entre les deux (la complétion change de hauteur à chaque
 * frappe, l'état ne doit pas trembler) ; (2) le <b>scénario complet de
 * bout en bout</b> — carte ouverte, complétion vivante, petit écran où
 * aucun creux ne tient : la carte se voile, la complétion refermée la
 * dévoile ; (3) l'<b>invariant fondamental</b> — sur quatre tailles
 * d'écran balayées, la carte ne CACHE JAMAIS en silence une part
 * significative de la complétion (esquive, latéral, compactage ou
 * voile).</p>
 *
 * <p>Robolectric ne pulse pas les {@code ValueAnimator} (même constat
 * que les tests des fondus B7) : on verrouille les transitions d'état
 * synchrones — la cible du voile et son déclenchement —, l'alpha animé
 * vit sur appareil réel.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PreviewDropVeilTest {

    private static final String DOC =
        "            ffffffff\n"   // 0 : 12 espaces + token col 12
        + "    <Button />\n"      // 1 : cible du caret
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
    }

    private EditorView view;

    private EditorView newView(int w, int h, float lineH, float charW) {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(DOC)));
        view.setPreviewHost(new DropHost());
        view.setFileName("activity_main.xml");
        view.measure(w, h);
        view.layout(0, 0, w, h);
        TestMetrics.injecter(view, lineH, charW);
        return view;
    }

    /** Ouvre la carte ancrée à la ligne 0, caret descend à la ligne 1. */
    private void openCardCaretLine1(EditorView view) {
        view.getSession().setSelection(0);
        view.openPreview(false);
        assertTrue("la carte est ouverte",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals(0, view.preview.drop().getAnchorLine());
        view.getSession().setSelection(DOC.indexOf("<Button"));
    }

    /** 8 items de complétion ancrés au token de la ligne 0 (col 12). */
    private void showCompletion(EditorView view) {
        List<CompletionSession.Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(new CompletionSession.Item("f" + i, "variable",
                    "f" + i, "v", 0, 0, false, false));
        }
        view.setCompletionItems(items, 12, "f");
        assertTrue(view.isCompletionVisible());
    }

    private static void draw(EditorView view) {
        Bitmap bmp = Bitmap.createBitmap(
                view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp)); // pipeline complet : painter B27 inclus
        bmp.recycle();
    }

    /** Un placement synthétique au ratio donné (hystérésis pur). */
    private static Placement placementAtRatio(float ratio) {
        return new Placement(0f, 0f, 232f, 342f, 1f, 0f, false,
                0f, 0f, 0f, 0f, 1, ratio, ratio > 0f);
    }

    // ── 1. Hystérésis à deux plateaux ──────────────────────────────

    @Test
    public void hystérésis_voileAuDelàDe10_dévoileEnDessousDe4() {
        EditorView view = newView(640, 385, 60f, 6f);
        EditorPreviewDrop drop = view.preview.drop();
        assertFalse(drop.isVeiled());

        drop.updateVeil(placementAtRatio(0.20f));
        assertTrue("au-delà de 10 % → voilée", drop.isVeiled());

        drop.updateVeil(placementAtRatio(0.06f));
        assertTrue("dans la bande [4 %, 10 %] → STABLE voilée (pas de tremblement)",
                drop.isVeiled());

        drop.updateVeil(placementAtRatio(0.03f));
        assertFalse("en dessous de 4 % → dévoilée", drop.isVeiled());

        drop.updateVeil(placementAtRatio(0.06f));
        assertFalse("retour dans la bande → STABLE dévoilée", drop.isVeiled());
    }

    @Test
    public void hystérésis_9PourCentNeVoilePas() {
        EditorView view = newView(640, 385, 60f, 6f);
        EditorPreviewDrop drop = view.preview.drop();

        drop.updateVeil(placementAtRatio(0.09f));
        assertFalse("9 % : sous le seuil de voile, la carte reste opaque",
                drop.isVeiled());
        drop.updateVeil(placementAtRatio(0.05f));
        assertFalse(drop.isVeiled());
    }

    @Test
    public void hystérésis_pasOscillationEnBande() {
        // Alterner 5 % / 8 % (les deux dans la bande) ne doit JAMAIS
        // faire basculer l'état : deux frames consécutives idempotentes.
        EditorView view = newView(640, 385, 60f, 6f);
        EditorPreviewDrop drop = view.preview.drop();
        drop.updateVeil(placementAtRatio(0.30f));
        assertTrue(drop.isVeiled());
        for (int i = 0; i < 12; i++) {
            drop.updateVeil(placementAtRatio(i % 2 == 0 ? 0.05f : 0.08f));
            assertTrue("la bande ne fait pas osciller le voile (frame " + i + ")",
                    drop.isVeiled());
        }
        // Idempotence par frame : le même ratio répété ne relance rien.
        drop.updateVeil(placementAtRatio(0.30f));
        assertTrue(drop.isVeiled());
    }

    @Test
    public void updateVeil_null_inoffensif() {
        EditorView view = newView(640, 385, 60f, 6f);
        EditorPreviewDrop drop = view.preview.drop();
        drop.updateVeil(null);
        assertFalse(drop.isVeiled());
    }

    // ── 2. Scénario complet : la complétion voile, sa fermeture dévoile ──

    @Test
    public void complétionNonEsquivable_carteVoiléePuisDévoilée() {
        // Petit écran (640×385), complétion vivante au milieu : aucun
        // creux, pas de latéral — la carte se voile. La complétion
        // refermée : la carte redevient opaque.
        EditorView view = newView(640, 385, 60f, 6f);
        openCardCaretLine1(view);
        showCompletion(view);

        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        assertTrue("rien ne tient (petit écran) → la carte se voile au lieu de cacher",
                drop.isVeiled());
        Placement p = drop.placement();
        assertTrue("le placement avoue le recouvrement restant",
                p.obstructed && p.overlapRatio >= EditorPreviewDrop.VEIL_ON_RATIO);

        view.dismissCompletion();
        draw(view);
        assertFalse("la complétion refermée dévoile la carte",
                drop.isVeiled());
        assertFalse(view.isCompletionVisible());
    }

    @Test
    public void fermetureDeCarte_réinitialiseLeVoile() {
        EditorView view = newView(640, 385, 60f, 6f);
        EditorPreviewDrop drop = view.preview.drop();
        openCardCaretLine1(view);
        showCompletion(view);
        draw(view);
        assertTrue(drop.isVeiled());

        view.closePreviewDrop();
        assertFalse("la carte fermée n'est plus voilée",
                drop.isVeiled());
        assertEquals("l'alpha repart à opaque pour une ré-ouverture",
                1f, drop.veilAlpha(), 0.001f);
    }

    // ── 3. Invariant fondamental sur tailles balayées ─────────────

    @Test
    public void quatreTailles_laComplétionNEstJamaisCachéeEnSilence() {
        // Grand écran (esquive verticale), tablette (latéral), petit
        // écran (voile), téléphone étroit : quelle que soit la
        // géométrie, une part significative de complétion recouverte
        // implique le voile — jamais un cache opaque en silence.
        int[][] sizes = {{1080, 1920}, {820, 1000}, {640, 385}, {360, 800}};
        for (int[] size : sizes) {
            String at = size[0] + "×" + size[1];
            EditorView view = newView(size[0], size[1], 60f, 6f);
            openCardCaretLine1(view);
            showCompletion(view);
            draw(view);
            EditorPreviewDrop drop = view.preview.drop();
            Placement p = drop.placement();
            assertTrue("la complétion n'est jamais cachée en silence — " + at,
                    !p.obstructed
                            || p.overlapRatio < EditorPreviewDrop.VEIL_ON_RATIO
                            || drop.isVeiled());
        }
    }

    // ── 4. Smoke de rendu : le pipeline au complet ne crashe pas ───

    @Test
    public void draw_avecComplétionEtVoile_aucunCrash() {
        EditorView view = newView(640, 385, 60f, 6f);
        openCardCaretLine1(view);
        showCompletion(view);
        draw(view); // voile engagé, placement vivant, tige + carte.
        draw(view); // deuxième frame (idempotence de updateVeil).
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(view.preview.drop().isOpen());
    }
}
