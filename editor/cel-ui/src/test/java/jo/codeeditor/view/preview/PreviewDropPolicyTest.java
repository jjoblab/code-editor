package jo.codeeditor.view.preview;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.TestMetrics;
import jo.codeeditor.view.render.EditorPreviewDropPainter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B27 (option propriétaire) — verrouille les <b>trois politiques de
 * cohabitation</b> de la carte « goutte d'eau » avec les popups de
 * frappe, l'ORIGINALE étant le <b>défaut</b> :
 *
 * <p>(1) <b>ORIGINALE</b> — la maquette d'origine restaurée : la carte
 * est aveugle aux popups (placement sans obstacles), ne se voile
 * JAMAIS, et engloutit les taps sur les rangées qu'elle recouvre
 * (la complétion reste ouverte dessous — comportement historique
 * exact d'avant le retour terrain) ; (2) <b>TROIS COUCHES</b> — opt-in
 * explicite : esquive + voile d'eau revivent ; (3) <b>VARIANTE B</b> —
 * la complétion se dessine au-dessus de la carte (drapeau du z-order)
 * et gagne les taps, mais AUCUN voile (la lisibilité brute n'en a
 * pas besoin).</p>
 *
 * <p>Verrouille aussi l'<b>option taille</b> (facteur d'agrandissement
 * borné [1, 1,5], placement et ✕ suivent) et le <b>design resserré</b>
 * (marge mini-téléphone réduite, carte plus courte).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PreviewDropPolicyTest {

    private static final String DOC =
        "  <TextView />\n"      // 0 : ligne d'ancre de la carte
        + "     fffffff\n"     // 1 : 5 espaces + token col 5 (caret ici)
        + "</LinearLayout>\n";

    /** Document du scénario voile — miroir de PreviewDropVeilTest
     *  (token ligne 0, caret descendu ligne 1 : la complétion vit HAUT,
     *  le croisement avec la carte compactée est significatif). */
    private static final String DOC_VOILE =
        "            fffffff\n"  // 0 : 12 espaces + token col 12
        + "    <Button />\n"   // 1 : cible du caret
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

    private EditorView newView(int w, int h, float lineH, float charW) {
        return newView(w, h, lineH, charW, DOC);
    }

    private EditorView newView(int w, int h, float lineH, float charW, String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(doc)));
        view.setPreviewHost(new DropHost());
        view.setFileName("activity_main.xml");
        view.measure(w, h);
        view.layout(0, 0, w, h);
        TestMetrics.injecter(view, lineH, charW);
        return view;
    }

    /** Scénario voile (mêmes métriques/document que PreviewDropVeilTest). */
    private EditorView scenarioVoile() {
        EditorView view = newView(640, 385, 60f, 6f, DOC_VOILE);
        view.getSession().setSelection(0);
        view.openPreview(false);
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);
        view.getSession().setSelection(DOC_VOILE.indexOf("<Button"));
        List<CompletionSession.Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(new CompletionSession.Item("f" + i, "variable",
                    "f" + i, "v", 0, 0, false, false));
        }
        view.setCompletionItems(items, 12, "f");
        assertTrue(view.isCompletionVisible());
        return view;
    }

    /** Scénario taps (mêmes métriques que PreviewDropCompletionTapTest). */
    private EditorView scenarioTaps() {
        return scenario(1080, 620, 80f, 10f);
    }

    /** Carte ouverte ancrée ligne 0, caret ligne 1, complétion 8 items. */
    private EditorView scenario(int w, int h, float lineH, float charW) {
        EditorView view = newView(w, h, lineH, charW);
        view.getSession().setSelection(0);
        view.openPreview(false);
        assertTrue(view.previewMode == EditorView.PreviewMode.DROP);
        int tokenStart = DOC.indexOf("fffffff");
        view.getSession().setSelection(tokenStart + 7);
        List<CompletionSession.Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(new CompletionSession.Item("f" + i, "variable",
                    "f" + i, "v", 0, 0, false, false));
        }
        view.setCompletionItems(items, tokenStart, "f");
        assertTrue(view.isCompletionVisible());
        return view;
    }

    private static void draw(EditorView view) {
        Bitmap bmp = Bitmap.createBitmap(
                view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        bmp.recycle();
    }

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(down, down,
                MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
        MotionEvent up = MotionEvent.obtain(down, down + 50,
                MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // ── 1. Le défaut est l'ORIGINALE ──────────────────────────────

    @Test
    public void défaut_origine() {
        EditorView view = newView(1080, 900, 80f, 10f);
        assertEquals("la politique par défaut est l'ORIGINALE (maquette)",
                EditorView.PreviewDropPolicy.ORIGINAL,
                view.getPreviewDropPolicy());
        assertEquals("miroir contrôleur",
                EditorView.PreviewDropPolicy.ORIGINAL,
                view.preview.drop().policy());
    }

    // ── 2. ORIGINALE : aveugle aux popups, jamais voilée, taps ─────

    @Test
    public void originale_géométrieAveugleAuxPopups() {
        EditorView view = scenario(1080, 900, 80f, 10f);
        EditorPreviewDrop drop = view.preview.drop();
        EditorPreviewDropGeometry.Placement avec = drop.placement();
        view.dismissCompletion();
        EditorPreviewDropGeometry.Placement sans = drop.placement();
        assertEquals("ORIGINALE : la complétion n'entre PAS dans la géométrie",
                sans.cardLeft, avec.cardLeft, 0.5f);
        assertEquals(sans.cardTop, avec.cardTop, 0.5f);
        assertEquals("même échelle (aucune esquive d'obstacle)",
                sans.scale, avec.scale, 0.001f);
        assertEquals("même niveau de placement", sans.level, avec.level);
        assertFalse("aucun recouvrement avoué", avec.obstructed);
    }

    @Test
    public void originale_jamaisVoilée() {
        EditorView view = scenarioVoile();
        draw(view);
        assertFalse("ORIGINALE : aucun voile d'eau, la carte reste opaque",
                view.preview.drop().isVeiled());
    }

    @Test
    public void originale_tapSurRangéeRecouverte_engloutiCommeLHistorique() {
        // Comportement d'origine restauré : le tap dans la carte lui
        // appartient (corps → englouti) — la complétion enterrée reste
        // ouverte, rien ne s'insère, le caret ne bouge pas.
        EditorView view = scenarioTaps();
        assertTrue("pré-condition : le tap tombe dans la carte",
                view.preview.hitTestDrop(150f, 425f));
        int caretBefore = view.getSession().getSelection().start;

        tap(view, 150f, 425f);

        assertTrue("la complétion reste ouverte dessous (pas de priorité)",
                view.isCompletionVisible());
        assertFalse("rien ne s'insère", view.getSession().getText().contains("f7"));
        assertTrue("la carte vit (premier plan)",
                view.previewMode == EditorView.PreviewMode.DROP);
        assertEquals("le corps engloutit : le caret ne bouge pas",
                caretBefore, view.getSession().getSelection().start);
    }

    @Test
    public void originale_changementDePolitiqueRéinitialiseLeVoile() {
        EditorView view = scenarioVoile();
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
        draw(view);
        assertTrue(view.preview.drop().isVeiled());
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.ORIGINAL);
        assertFalse("le retour à l'ORIGINALE réinitialise le voile",
                view.preview.drop().isVeiled());
        assertEquals(1f, view.preview.drop().veilAlpha(), 0.001f);
    }

    // ── 3. TROIS COUCHES : opt-in explicite ───────────────────────

    @Test
    public void troisCouches_leVoileDEauRevit() {
        EditorView view = scenarioVoile();
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
        draw(view);
        assertTrue("opt-in TROIS couches : la carte se voile (petit écran)",
                view.preview.drop().isVeiled());
    }

    // ── 4. VARIANTE B : complétion au-dessus, pas de voile ────────

    @Test
    public void varianteB_complétionDessinéeAuDessus() {
        EditorView view = newView(1080, 900, 80f, 10f);
        assertFalse("ORIGINALE : carte au premier plan",
                view.preview.drop().drawsUnderTypingPopups());
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
        assertFalse("TROIS couches : la carte reste au premier plan (voile)",
                view.preview.drop().drawsUnderTypingPopups());
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);
        assertTrue("VARIANTE B : la carte se dessine SOUS les popups",
                view.preview.drop().drawsUnderTypingPopups());
    }

    @Test
    public void varianteB_tapSurRangéeRecouverte_accepté() {
        EditorView view = scenarioTaps();
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);

        tap(view, 150f, 425f);

        assertFalse("la complétion gagne le tap (elle est au-dessus)",
                view.isCompletionVisible());
        assertTrue("l'item tapé est inséré",
                view.getSession().getText().contains("f7"));
        assertTrue("la carte reste ouverte",
                view.previewMode == EditorView.PreviewMode.DROP);
    }

    @Test
    public void varianteB_jamaisVoilée() {
        EditorView view = scenarioVoile();
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);
        draw(view);
        assertFalse("VARIANTE B : pas de voile (verre opaque assumé)",
                view.preview.drop().isVeiled());
    }

    @Test
    public void varianteB_renduComplet_aucunCrash() {
        EditorView view = scenarioVoile();
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);
        draw(view); // branche de rendu pré-popups du pipeline
        draw(view);
        assertTrue(view.preview.drop().isOpen());
    }

    // ── 4bis. VARIANTE B : la goutte devient transparente à 10–15 % ─

    @Test
    public void varianteB_goutteTransparenteÀ10à15Pourcent() {
        EditorView view = newView(1080, 900, 80f, 10f);
        EditorPreviewDrop drop = view.preview.drop();
        assertEquals("ORIGINALE : opaque", 1f, drop.ghostAlpha(), 0.0001f);
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
        assertEquals("TROIS couches : opaque (le voile est SON mécanisme)",
                1f, drop.ghostAlpha(), 0.0001f);
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);
        assertTrue("VARIANTE B : la goutte devient transparente à 10–15 % "
                + "(alpha " + drop.ghostAlpha() + ")",
                drop.ghostAlpha() >= 0.10f && drop.ghostAlpha() <= 0.15f);
    }

    /** Canvas enregistreur : capture les couches alpha posées par le
     *  painter (voile d'eau / fantôme de la variante B). */
    private static final class LayerCanvas extends Canvas {
        int layers = 0;
        int lastAlpha = 255;
        LayerCanvas(Bitmap bmp) { super(bmp); }
        @Override public int saveLayerAlpha(RectF bounds, int alpha) {
            layers++;
            lastAlpha = alpha;
            return super.saveLayerAlpha(bounds, alpha);
        }
    }

    @Test
    public void varianteB_lePainterFantômeLaGoutte() {
        EditorView view = newView(1080, 900, 80f, 10f);
        view.getSession().setSelection(0);
        view.openPreview(false);
        Bitmap bmp = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                Bitmap.Config.ARGB_8888);

        // ORIGINALE : aucune couche alpha — la goutte est opaque.
        LayerCanvas c1 = new LayerCanvas(bmp);
        new EditorPreviewDropPainter(view).drawCard(c1);
        assertEquals("ORIGINALE : aucune couche (opaque)", 0, c1.layers);

        // TROIS couches non voilée : toujours aucune couche.
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
        LayerCanvas c2 = new LayerCanvas(bmp);
        new EditorPreviewDropPainter(view).drawCard(c2);
        assertEquals("TROIS couches non voilée : aucune couche", 0, c2.layers);

        // VARIANTE B : le painter enveloppe la goutte dans une couche à
        // 10–15 % d'opacité (0,12 × 255 = 30).
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);
        LayerCanvas c3 = new LayerCanvas(bmp);
        new EditorPreviewDropPainter(view).drawCard(c3);
        assertTrue("VARIANTE B : une couche fantôme est posée",
                c3.layers >= 1);
        assertTrue("alpha de la couche dans [10 %, 15 %] de 255 ("
                        + c3.lastAlpha + ")",
                c3.lastAlpha >= 26 && c3.lastAlpha <= 38);
    }

    // ── 5. Option taille : facteur borné, carte agrandie ─────────

    @Test
    public void échelle_agranditLaCarte() {
        EditorView view = newView(1080, 900, 80f, 10f);
        view.getSession().setSelection(0);
        view.openPreview(false);
        view.setPreviewDropScale(1.3f);
        EditorPreviewDropGeometry.Placement p = view.preview.drop().placement();
        assertEquals(1.3f, view.getPreviewDropScale(), 0.001f);
        assertEquals("l'échelle visuelle porte l'agrandissement",
                1.3f, p.scale, 0.05f);
        assertEquals("la carte visuelle est plus large de 30 %",
                EditorPreviewDrop.CARD_W_DP * 1.3f, p.visualWidth(), 1.5f);
        assertTrue("la carte agrandie reste dans l'éditeur",
                p.cardLeft >= 0f && p.cardLeft + p.visualWidth() <= 1080f);
    }

    @Test
    public void échelle_bornée() {
        EditorView view = newView(1080, 900, 80f, 10f);
        view.setPreviewDropScale(3f);
        assertEquals("plafond 1,5", EditorPreviewDrop.MAX_CARD_SCALE,
                view.getPreviewDropScale(), 0.001f);
        view.setPreviewDropScale(0.2f);
        assertEquals("plancher 1 (la taille de la maquette est le minimum)",
                EditorPreviewDrop.MIN_CARD_SCALE,
                view.getPreviewDropScale(), 0.001f);
    }

    @Test
    public void échelle_leXDeLaCarteAgrandieFermeLaCarte() {
        EditorView view = newView(1080, 900, 80f, 10f);
        view.getSession().setSelection(0);
        view.openPreview(false);
        view.setPreviewDropScale(1.3f);
        EditorPreviewDropGeometry.Placement p = view.preview.drop().placement();
        // ✕ local ≈ centre de closeRect : (cardW − 17dp, 18dp), écran
        // = cardLeft + local × échelle (échelle inclut l'agrandissement).
        float x = p.cardLeft + (EditorPreviewDrop.CARD_W_DP - 17f) * p.scale;
        float y = p.cardTop + 18f * p.scale;

        tap(view, x, y);

        assertFalse("le ✕ de la carte agrandie la referme (hit-test suivi)",
                view.previewMode == EditorView.PreviewMode.DROP);
    }

    // ── 6. Design resserré (marges réduites) ──────────────────────

    @Test
    public void design_margesResserréesCartePlusCourte() {
        assertEquals("marge mini-téléphone resserrée (retour terrain)",
                4f, EditorPreviewDrop.PHONE_MARGIN_DP, 0.001f);
        assertEquals("hauteur de carte recalculée (marges −12 dp)",
                EditorPreviewDrop.HEADER_H_DP + 4f + EditorPreviewDrop.PHONE_H_DP
                        + 4f + EditorPreviewDrop.FOOTER_H_DP,
                EditorPreviewDrop.CARD_H_DP, 0.001f);
    }
}
