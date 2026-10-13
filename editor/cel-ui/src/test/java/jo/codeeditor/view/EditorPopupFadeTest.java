package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.popup.EditorPopupFade;
import jo.codeeditor.view.popup.EditorPopupMotion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B7 (retour terrain n°4) — fondus d'apparition/disparition des popups
 * Canvas ({@link EditorPopupFade}) et des cartes en fenêtre
 * ({@link EditorPopupMotion}) : « les popup améliorations, l'animation
 * quand ils sont affichés et disparaissent pour être plus fluides — je
 * crois que le mécanisme existe déjà dans le code ».
 *
 * <p>Robolectric ne pulse pas les {@code ValueAnimator} par défaut (même
 * constat que les tests du caret) : on verrouille ici les transitions
 * d'état SYNCHRONES — front montant d'apparition, photographie au
 * dismiss (fantôme en vol), interruption nette, réouverture en vol,
 * gardes des popups sans bornes mesurables — et le repli immédiat des
 * cartes en fenêtre jamais mesurées.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPopupFadeTest {

    private static final int W = 1080;
    private static final int H = 1920;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of("int foobar;\n"));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(W, H);
        view.layout(0, 0, W, H);
        return view;
    }

    private void draw(EditorView view) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.onDraw(new Canvas(bmp));
        bmp.recycle();
    }

    /** Montre une complétion réelle (items + ancre mesurable). */
    private void showCompletion(EditorView view) {
        view.getSession().setSelection(8); // fin de « foobar »
        view.setCompletionItems(java.util.Collections.singletonList(
                new jo.codeeditor.completion.CompletionSession.Item(
                        "foobar", "int variable", "foobar", "v", 0, 0, false, false)),
                4, "fo");
        assertTrue(view.isCompletionVisible());
    }

    // ── Fondu d'entrée (front montant dans le pipeline) ───────────

    @Test
    public void apparitionComplétion_déclencheLeFonduDentrée() {
        EditorView view = newView();
        assertEquals(EditorPopupFade.Kind.COMPLETION + " caché au départ",
                EditorPopupFade.Phase.HIDDEN, view.popupFade.phase(
                        EditorPopupFade.Kind.COMPLETION));

        showCompletion(view);
        draw(view);

        assertEquals("le premier dessin après apparition engage le fondu",
                EditorPopupFade.Phase.IN, view.popupFade.phase(
                        EditorPopupFade.Kind.COMPLETION));
    }

    @Test
    public void invisibilitéInterrromptLeFonduDentréeNet() {
        EditorView view = newView();
        showCompletion(view);
        draw(view);
        assertEquals(EditorPopupFade.Phase.IN, view.popupFade.phase(
                EditorPopupFade.Kind.COMPLETION));

        // Le popup disparaît (chemin filtrage vide par ex.) — le fondu
        // d'entrée s'arrête NET, sans fantôme (données possiblement
        // incohérentes à ce stade).
        view.completionVisible = false;
        draw(view);

        assertEquals("fondu d'entrée interrompu → caché",
                EditorPopupFade.Phase.HIDDEN, view.popupFade.phase(
                        EditorPopupFade.Kind.COMPLETION));
        assertFalse("pas de fantôme sur interruption d'entrée",
                view.popupFade.isGhostFlying(EditorPopupFade.Kind.COMPLETION));
    }

    // ── Fondu de sortie (photographie au dismiss) ─────────────────

    @Test
    public void dismissComplétion_photographieEtDémarreLeFonduDeSortie() {
        EditorView view = newView();
        showCompletion(view);
        draw(view);

        // Le dismiss réel (Échap / tap hors / édition) : l'état logique
        // meurt MAINTENANT, le fantôme s'efface après.
        view.popupManager.dismissCompletion();

        assertFalse("l'état logique est mort immédiatement",
                view.completionVisible);
        assertEquals("le fantôme est en vol",
                EditorPopupFade.Phase.OUT, view.popupFade.phase(
                        EditorPopupFade.Kind.COMPLETION));
        assertTrue("la photographie existe (bornes mesurables)",
                view.popupFade.isGhostFlying(EditorPopupFade.Kind.COMPLETION));

        // Le frame suivant dessine le fantôme sans crash.
        draw(view);
        assertEquals("toujours en fondu de sortie",
                EditorPopupFade.Phase.OUT, view.popupFade.phase(
                        EditorPopupFade.Kind.COMPLETION));
    }

    @Test
    public void dismissQuickDocSansContenu_disparitionInstantanéePropre() {
        EditorView view = newView();
        // Quick doc sans contenu → bornes non mesurables → repli instantané
        // (comme avant B7), jamais de crash.
        view.quickDocVisible = true;
        view.popupManager.dismissQuickDoc();

        assertFalse(view.quickDocVisible);
        assertEquals("pas de fantôme sans bornes",
                EditorPopupFade.Phase.HIDDEN, view.popupFade.phase(
                        EditorPopupFade.Kind.QUICK_DOC));
        assertFalse(view.popupFade.isGhostFlying(EditorPopupFade.Kind.QUICK_DOC));
    }

    @Test
    public void snuffSurPopupDéjàInvisible_estUnNoOp() {
        EditorView view = newView();
        // Aucun popup affiché — snuff ne fait rien.
        view.popupFade.snuff(EditorPopupFade.Kind.COMPLETION);
        assertEquals(EditorPopupFade.Phase.HIDDEN, view.popupFade.phase(
                EditorPopupFade.Kind.COMPLETION));
        assertFalse(view.popupFade.isGhostFlying(EditorPopupFade.Kind.COMPLETION));
    }

    // ── Réouverture en vol ────────────────────────────────────────

    @Test
    public void réouverturePendantFonduDeSortie_annuleLeFantômeEtRemonte() {
        EditorView view = newView();
        showCompletion(view);
        draw(view);
        view.popupManager.dismissCompletion();
        assertTrue("pré-condition : fantôme en vol",
                view.popupFade.isGhostFlying(EditorPopupFade.Kind.COMPLETION));

        // Le popup réapparaît avant la fin de l'effacement : le fantôme
        // meurt, le vrai popup remonte (jamais de flash plein).
        showCompletion(view);
        draw(view);

        assertFalse("le fantôme est abandonné",
                view.popupFade.isGhostFlying(EditorPopupFade.Kind.COMPLETION));
        assertEquals("le vrai popup remonte en fondu d'entrée",
                EditorPopupFade.Phase.IN, view.popupFade.phase(
                        EditorPopupFade.Kind.COMPLETION));
    }

    // ── Cartes en fenêtre (go-to-line / rename / feuille d'aperçu) ──

    @Test
    public void cardIn_poseOpacitéZéroEtDérive() {
        View card = new View(RuntimeEnvironment.getApplication());
        EditorPopupMotion.cardIn(card, -8f);
        assertEquals("opacité 0 au départ du fondu", 0f, card.getAlpha(), 0f);
        assertEquals("dérive verticale posée", -8f, card.getTranslationY(), 0f);
    }

    @Test
    public void cardOut_carteJamaisMesurée_dismissImmédiat() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newView();
        PopupWindow pw = new PopupWindow(new View(ctx), 320, 200, true);
        pw.showAtLocation(view, Gravity.NO_GRAVITY, 0, 0);
        assertTrue(pw.isShowing());

        // La carte n'a jamais été mesurée (harnais sans frame) — repli
        // immédiat : c'est la garde qui protège aussi Retour/double dismiss.
        EditorPopupMotion.cardOut(pw, 10f);

        assertFalse("la fenêtre est morte immédiatement", pw.isShowing());
    }

    @Test
    public void cardOut_fenêtreDéjàMorte_estUnNoOpSansCrash() {
        Context ctx = RuntimeEnvironment.getApplication();
        PopupWindow pw = new PopupWindow(new View(ctx), 320, 200, true);
        // Jamais montrée.
        EditorPopupMotion.cardOut(pw, 10f);
        assertFalse(pw.isShowing());
    }
}
