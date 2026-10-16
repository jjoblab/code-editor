package jo.codeeditor.view.preview;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.animation.AnimationUtils;

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
 * B27 (retour terrain n°5 : « ça manque de fluidité » — les
 * re-placements d'esquive SAUTAIENT frame à frame) — verrouille le
 * <b>glissement fluide</b> de la carte d'aperçu « goutte d'eau » en
 * TROIS couches et en VARIANTE B : la carte ne téléporte plus d'un
 * placement à l'autre, elle GLISSE vers la cible.
 *
 * <p>Quatre familles de propriétés prouvées : (1) la <b>continuité</b>
 * — au frame du re-placement, la carte part EXACTEMENT de sa position
 * dessinée d'avant (aucune téléportation), puis rejoint la cible en
 * {@link EditorPreviewDrop#GLIDE_MS} ms ; (2) les <b>exemptions</b> —
 * l'ORIGINALE reste la maquette exacte (jamais de glissement), le
 * geste de la barre de taille reste DIRECT (la carte suit le doigt
 * sans inertie — retour terrain n°3 « drag vivant »), et le suivi de
 * l'œil au SCROLL reste RIGIDE (aucun retard sur le défilement) ;
 * (3) la <b>cadence de frappe</b> — un deuxième re-placement en plein
 * vol repart de la position dessinée courante (la complétion filtre à
 * chaque frappe, la cible bouge en continu) ; (4) la <b>scène du
 * terrain</b> — la complétion qui SURVIENT fait esquiver la carte en
 * GLISSANT plutôt qu'en sautant.</p>
 *
 * <p>Horloge : le glissement vit sur
 * {@code AnimationUtils.currentAnimationTimeMillis()} — le harnais
 * l'avance explicitement par {@code SystemClock.setCurrentTimeMillis}
 * (l'horloge Robolectric, démarée à 100 ms, pilote l'animation de
 * façon DÉTERMINISTE — aucun ValueAnimator à pulser).</p>
 *
 * <p>Le glissement est purement VISUEL (miroir des fondus B7 et du
 * voile d'eau) : l'interception des gestes vit sur le placement CIBLE
 * — le transitoire dure {@link EditorPreviewDrop#GLIDE_MS} ms.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PreviewDropGlideTest {

    /** Document 5 lignes : ancre ligne 0, espace de re-placement large. */
    private static final String DOC =
        "            ffffffff\n"   // 0 : token col 12 (œil ancré)
        + "    <Button />\n"      // 1
        + "</LinearLayout>\n"     // 2
        + "\n"                    // 3
        + "    <TextView />\n";   // 4

    /** Document haut (40 lignes) : scroll vertical large sans clamp. */
    private static final String DOC_TALL = buildTall();

    private static String buildTall() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            b.append("ligne ").append(i).append(" padding padding\n");
        }
        return b.toString();
    }

    /** Hôte « goutte d'eau » minimal (prévisualise .xml, 2 onglets). */
    private static final class DropHost implements jo.codeeditor.view.preview.EditorPreviewHost {
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
        @Override public void onPreviewDropScaleChanged(float scale) { }
    }

    private EditorView view;

    private EditorView newView(int w, int h, float lineH, float charW) {
        return newView(w, h, lineH, charW, DOC);
    }

    private EditorView newView(int w, int h, float lineH, float charW, String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(doc)));
        view.setPreviewHost(new DropHost());
        view.setFileName("activity_main.xml");
        // Opt-in explicite : le glissement est une POLITIQUE (B27,
        // option propriétaire) — l'ORIGINALE est le défaut.
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.THREE_LAYERS);
        view.measure(w, h);
        view.layout(0, 0, w, h);
        TestMetrics.injecter(view, lineH, charW);
        return view;
    }

    /** Ouvre la carte ancrée à la ligne du caret courant (sans bouger
     *  le caret ensuite — chaque test pilote SON re-placement). */
    private void openCard(EditorView view, int caretOffset) {
        view.getSession().setSelection(caretOffset);
        view.openPreview(false);
        assertTrue("la carte est ouverte",
                view.previewMode == EditorView.PreviewMode.DROP);
    }

    /** Offset du premier caractère de la ligne donnée. */
    private static int lineStart(String doc, int line) {
        int off = 0;
        for (int i = 0; i < line; i++) {
            off = doc.indexOf('\n', off) + 1;
        }
        return off;
    }

    private static void draw(EditorView view) {
        Bitmap bmp = Bitmap.createBitmap(
                view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp)); // pipeline complet : painter B27 inclus
        bmp.recycle();
    }

    /** Le placement dessiné LU au frame courant (réévalue le transitoire). */
    private Placement drawn(EditorView view) {
        EditorPreviewDrop drop = view.preview.drop();
        return drop.drawnPlacement(drop.placement());
    }

    // ── 1. Continuité : pas de téléportation, arrivée exacte ───────

    @Test
    public void troisCouches_rePlacement_laCartePartDeSonAnciennePosition() {
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        draw(view); // frame 1 : acquisition (snap — le fondu d'entrée couvre)
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();

        // Re-placement : le caret descend loin → la bande de garde force
        // un nouveau placement (le saut que le terrain trouvait brutal).
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p1 = drop.placement();
        assertTrue("pré-condition : la cible a bougé (re-placement réel)",
                Math.abs(p1.cardTop - p0.cardTop) > 10f);

        Placement d1 = drop.drawnPlacement(p1);
        assertEquals("continuité : au frame du saut, la carte part EXACTEMENT "
                + "de son ancienne position (aucune téléportation)",
                p0.cardTop, d1.cardTop, 0.5f);
        assertEquals(p0.cardLeft, d1.cardLeft, 0.5f);
        assertTrue("le glissement est engagé : dessiné ≠ cible",
                Math.abs(d1.cardTop - p1.cardTop) > 1f);
    }

    @Test
    public void troisCouches_arrivéeExacteAprèsLeGlissement() {
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p1 = drop.placement();
        drop.drawnPlacement(p1); // engage le glissement (t = 0)

        // Le transitoire vit sur l'horloge : GLIDE_MS écoulés → arrivée
        // (relatif à l'horloge RÉELLE — le pipeline de dessin avance
        // l'horloge de quelques ms par frame, aucun temps absolu).
        SystemClock.setCurrentTimeMillis(AnimationUtils.currentAnimationTimeMillis()
                + EditorPreviewDrop.GLIDE_MS + 10L);
        Placement arrived = drop.drawnPlacement(p1);
        assertEquals("après GLIDE_MS, la carte est arrivée EXACTEMENT sur la cible",
                p1.cardTop, arrived.cardTop, 0.01f);
        assertEquals(p1.cardLeft, arrived.cardLeft, 0.01f);
        assertEquals("l'échelle porte l'arrivée aussi",
                p1.scale, arrived.scale, 0.001f);
        // La frame suivante reste au repos (idempotence).
        assertEquals(p1.cardTop, drop.drawnPlacement(p1).cardTop, 0.01f);
    }

    @Test
    public void troisCouches_milieuDeVol_laCarteEstEntreLesDeuxPositions() {
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p1 = drop.placement();
        drop.drawnPlacement(p1); // t = 0 : résidu plein

        // Mi-vol : la décélération quadratique laisse 25 % du chemin
        // (relatif à l'horloge réelle — voir l'arrivée ci-dessus).
        SystemClock.setCurrentTimeMillis(AnimationUtils.currentAnimationTimeMillis()
                + EditorPreviewDrop.GLIDE_MS / 2L);
        Placement mid = drop.drawnPlacement(p1);
        float total = Math.abs(p0.cardTop - p1.cardTop);
        float restant = Math.abs(mid.cardTop - p1.cardTop);
        assertTrue("mi-vol : la carte est ENCORE EN ROUTE (ni partie ni arrivée)",
                restant > total * 0.10f && restant < total * 0.90f);
        assertTrue("et elle avance monotone vers la cible",
                restant < total);
    }

    // ── 2. Exemptions : originale / geste taille / scroll ─────────

    @Test
    public void originale_neGlisseJamais_maquetteExacte() {
        EditorView view = newView(1080, 1920, 60f, 6f);
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.ORIGINAL);
        openCard(view, 0);
        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p1 = drop.placement();
        assertTrue(Math.abs(p1.cardTop - p0.cardTop) > 10f);

        // L'ORIGINALE est la maquette exacte : le re-placement est
        // APPLIQUÉ immédiatement, aucun transitoire.
        Placement d1 = drop.drawnPlacement(p1);
        assertEquals("ORIGINALE : jamais de glissement — la maquette exacte",
                p1.cardTop, d1.cardTop, 0.0f);
        assertEquals(p1.cardLeft, d1.cardLeft, 0.0f);
    }

    @Test
    public void varianteB_glisseAussiVersLaCible() {
        EditorView view = newView(1080, 1920, 60f, 6f);
        view.setPreviewDropPolicy(EditorView.PreviewDropPolicy.VARIANT_B);
        openCard(view, 0);
        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p1 = drop.placement();
        assertTrue(Math.abs(p1.cardTop - p0.cardTop) > 10f);

        Placement d1 = drop.drawnPlacement(p1);
        assertEquals("VARIANTE B : même continuité — la carte glisse",
                p0.cardTop, d1.cardTop, 0.5f);
        SystemClock.setCurrentTimeMillis(AnimationUtils.currentAnimationTimeMillis()
                + EditorPreviewDrop.GLIDE_MS + 10L);
        assertEquals("et arrive sur la cible après GLIDE_MS",
                p1.cardTop, drop.drawnPlacement(p1).cardTop, 0.01f);
    }

    @Test
    public void suiviRigideAuScroll_aucunRetardNiGlissement() {
        // Document haut : ancre au milieu, scroll de 100 px — l'œil et
        // la bande de garde se déplacent ENSEMBLE (même delta écran) :
        // le placement translate à l'identique. La carte doit suivre
        // RIGIDEMENT (aucun retard sur le défilement — un glissement
        // piloté par le scroll serait une inertie insupportable).
        EditorView view = newView(1080, 1920, 60f, 6f, DOC_TALL);
        openCard(view, lineStart(DOC_TALL, 10));
        draw(view); // acquisition
        EditorPreviewDrop drop = view.preview.drop();
        Placement before = drop.placement();

        view.scroll.vOffset = 100f; // scroll pur : œil ET garde suivent
        Placement after = drop.placement();
        assertEquals("pré-condition : placement translaté du même delta",
                before.cardTop - 100f, after.cardTop, 0.01f);

        Placement d = drop.drawnPlacement(after);
        assertEquals("scroll : suivi RIGIDE — la carte est déjà sur place "
                +"(aucun transitoire déclenché par le défilement)",
                after.cardTop, d.cardTop, 0.01f);
        assertEquals(after.cardLeft, d.cardLeft, 0.01f);
    }

    @Test
    public void barreDeTaille_manipulationDirecte_laCarteSuitLeDoigtSansGlissement() {
        // Retour terrain n°3 : « drag vivant » — la carte se
        // redimensionne SOUS le doigt. Un glissement pendant le geste
        // serait de l'inertie : le doigt pilote, la carte suit SANS
        // retard ni retard visuel.
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        draw(view); // acquisition
        EditorPreviewDrop drop = view.preview.drop();
        RectF track = drop.scaleSliderRect();
        Placement p = drop.placement();
        float cx = p.cardLeft + track.centerX() * p.scale;
        float cy = p.cardTop + track.centerY() * p.scale;

        drop.sliderPress(cx, cy);
        drop.sliderDragTo(cx + 40f, cy); // la cible grandit sous le doigt
        Placement target = drop.placement();
        assertTrue("pré-condition : le drag a changé la cible",
                target.scale > 1.01f);

        Placement d = drop.drawnPlacement(target);
        assertEquals("geste taille : DIRECT — aucune inertie, la carte est "
                + "exactement sur la cible à chaque frame du drag",
                target.cardTop, d.cardTop, 0.0f);
        assertEquals(target.scale, d.scale, 0.0f);
    }

    @Test
    public void ouverture_acquisitionDeLaPositionSansGlissement() {
        // L'apparition de la carte vit déjà dans le fondu d'entrée B7
        // (120 ms) : la PREMIÈRE frame pose la position exacte — aucun
        // transitoire à l'ouverture.
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        view.getSession().setSelection(lineStart(DOC, 4)); // avant frame 1
        EditorPreviewDrop drop = view.preview.drop();
        Placement target = drop.placement();
        Placement d = drop.drawnPlacement(target);
        assertEquals("ouverture : la première frame adopte la position exacte",
                target.cardTop, d.cardTop, 0.0f);
    }

    // ── 3. Cadence de frappe : la cible bouge en plein vol ─────────

    @Test
    public void cadenceDeFrappe_deuxièmeSautSansTéléportation() {
        // La complétion filtre à CHAQUE frappe : la cible se
        // re-place en continu pendant que la carte glisse. Un nouveau
        // saut en plein vol repart de la position DESSINÉE courante —
        // jamais de téléportation, même en rafale.
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();

        view.getSession().setSelection(lineStart(DOC, 2));
        Placement p1 = drop.placement();
        drop.drawnPlacement(p1); // premier saut, t = 0

        SystemClock.setCurrentTimeMillis(AnimationUtils.currentAnimationTimeMillis()
                + EditorPreviewDrop.GLIDE_MS / 2L);
        Placement mid = drop.drawnPlacement(p1); // position dessinée courante

        // Deuxième re-placement en plein vol (la frappe suivante).
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p2 = drop.placement();
        Placement d2 = drop.drawnPlacement(p2);
        assertEquals("deuxième saut en plein vol : la carte repart de la "
                + "position DESSINÉE courante (aucune téléportation)",
                mid.cardTop, d2.cardTop, 0.01f);
        assertTrue("et le nouveau transitoire est engagé vers p2",
                Math.abs(d2.cardTop - p2.cardTop) > 1f);
    }

    // ── 4. La scène du terrain : la complétion qui survient ────────

    @Test
    public void complétionQuiSurvient_laCarteEsquiveEnGlissant() {
        // Le retour d'origine : la complétion surgit pendant la frappe
        // — la carte doit esquiver EN GLISSANT, pas sauter.
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, lineStart(DOC, 1)); // caret ligne 1 : garde loin de l'œil
        draw(view); // acquisition SANS obstacle
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();

        List<CompletionSession.Item> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(new CompletionSession.Item("f" + i, "variable",
                    "f" + i, "v", 0, 0, false, false));
        }
        view.setCompletionItems(items, 12, "f");
        assertTrue(view.isCompletionVisible());

        Placement p1 = drop.placement();
        assertTrue("pré-condition : l'obstacle force un nouveau placement",
                Math.abs(p1.cardLeft - p0.cardLeft) + Math.abs(p1.cardTop - p0.cardTop) > 10f);

        Placement d1 = drop.drawnPlacement(p1);
        assertEquals("la complétion qui survient : la carte esquive EN GLISSANT "
                + "— elle part de son ancienne position",
                p0.cardTop, d1.cardTop, 0.5f);
        assertEquals(p0.cardLeft, d1.cardLeft, 0.5f);
    }

    // ── 5. La tige : l'œil reste fixe, la goutte suit la carte ─────

    @Test
    public void laTige_lOeilResteFixeExtrémitéGoutteSuitLaCarte() {
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        Placement p0 = drop.placement();
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p1 = drop.placement();
        Placement d1 = drop.drawnPlacement(p1);

        assertTrue("pré-condition : la tige est visible (esquive loin)",
                d1.stemVisible);
        // Le départ de la tige vit sur l'ŒIL (fixe, hors transitoire) ;
        // l'arrivée suit la carte dessinée du même offset.
        assertEquals("la tige part toujours de l'œil — jamais décalée",
                p1.stemX0, d1.stemX0, 0.01f);
        assertEquals(p1.stemY0, d1.stemY0, 0.01f);
        float offX = d1.cardLeft - p1.cardLeft;
        float offY = d1.cardTop - p1.cardTop;
        assertEquals("l'extrémité goutte suit la carte glissante",
                p1.stemX1 + offX, d1.stemX1, 0.01f);
        assertEquals(p1.stemY1 + offY, d1.stemY1, 0.01f);
    }

    @Test
    public void glissementTerminé_laCarteResteSurLaCible() {
        EditorView view = newView(1080, 1920, 60f, 6f);
        openCard(view, 0);
        draw(view);
        EditorPreviewDrop drop = view.preview.drop();
        view.getSession().setSelection(lineStart(DOC, 4));
        Placement p1 = drop.placement();
        drop.drawnPlacement(p1); // engage
        SystemClock.setCurrentTimeMillis(AnimationUtils.currentAnimationTimeMillis()
                + EditorPreviewDrop.GLIDE_MS + 10L);
        drop.drawnPlacement(p1); // arrivée

        // Frames suivantes au repos : dessin == cible, idempotent.
        assertEquals(p1.cardTop, drawn(view).cardTop, 0.01f);
        SystemClock.setCurrentTimeMillis(AnimationUtils.currentAnimationTimeMillis()
                + 500L);
        assertEquals("bien après l'arrivée, la carte vit sur la cible",
                p1.cardTop, drawn(view).cardTop, 0.01f);
    }
}
