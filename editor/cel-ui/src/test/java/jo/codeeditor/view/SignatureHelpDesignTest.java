package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.completion.SignatureHelpController;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * 4.8.0 — aide de signature redessinée.
 *
 * <p>Trois chantiers vérifiés ici :</p>
 * <ul>
 *   <li><b>Porte du repli synthétique</b> — sans résolveur, la
 *       bibliothèque ne fabrique plus de « name(…) param N » sur les
 *       langages sans syntaxe d'appel (texte brut, XML, Markdown,
 *       prose). Un résolveur branché (hôte/LSP) reste maître : la
 *       porte ne borne que la synthèse maison ;</li>
 *   <li><b>Repli repensé</b> — le libellé est l'appel VIVANT (les
 *       arguments déjà frappés, l'actif surligné par le peintre), la
 *       documentation du paramètre actif porte son numéro
 *       (chaîne cel_signature_active_param, anglais canonique
 *       surchargeable par l'hôte) ;</li>
 *   <li><b>Badge de surcharges « n/m »</b> — présent seulement s'il y
 *       a un choix, géométrie partagée peintre/hit-test, le tap qui le
 *       vise fait DÉFILER les surcharges (l'équivalent tactile des
 *       flèches Haut/Bas), tout autre tap referme la bannière (B8).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class SignatureHelpDesignTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    // ════════════════════════════════════════════════════════════════
    // Repli synthétique (sans résolveur)
    // ════════════════════════════════════════════════════════════════

    @Test
    public void repliSynthetique_borneAuxLangagesDAppels() {
        // « foo(bar » dans un fichier TEXTE BRUT : plus de bannière.
        EditorView view = vue("strlen(hello, world", "text");
        view.getSession().setSelection(16);
        view.refreshSignatureHelp();
        assertFalse("le repli synthétique se tait sur du texte brut",
                view.signatureHelpVisible);
        assertNull("aucune donnée fabriquée", view.signatureHelpData);

        // Même prose en Markdown : silencieux aussi.
        EditorView md = vue("Il est né (à Paris, en 1990", "markdown");
        md.getSession().setSelection(25);
        md.refreshSignatureHelp();
        assertFalse("le repli synthétique se tait sur de la prose Markdown",
                md.signatureHelpVisible);
    }

    @Test
    public void repliSynthetique_libelleEstLAppelVivant() {
        // Java (langage à appels) : l'utilisateur voit SES arguments,
        // l'actif surligné par le peintre — plus de « strlen(…) » muet.
        EditorView view = vue("strlen(hello, world", "java");
        view.getSession().setSelection(16);
        view.refreshSignatureHelp();
        assertTrue(view.signatureHelpVisible);
        assertNotNull(view.signatureHelpData);
        assertEquals("le libellé porte l'appel vivant",
                "strlen(hello, world)",
                view.signatureHelpData.signatures.get(0).label);
    }

    @Test
    public void repliSynthetique_documentationDuParametreActif() {
        // Caret après « hello, » → paramètre d'index 1 → numéro 2
        // (1-based, chaîne cel_signature_active_param).
        EditorView view = vue("strlen(hello, world", "java");
        view.getSession().setSelection(13);
        view.refreshSignatureHelp();
        assertTrue(view.signatureHelpVisible);
        SignatureHelpController.Signature sig =
                view.signatureHelpData.signatures.get(0);
        assertEquals("Parameter 2",
                sig.parameters.get(1).documentation);
        assertEquals(1, sig.activeParameter);
    }

    // ════════════════════════════════════════════════════════════════
    // Résolveur branché : la porte ne s'applique pas (l'hôte décide)
    // ════════════════════════════════════════════════════════════════

    @Test
    public void avecResolveur_lePopupResteOuvertSurToutLangage() throws Exception {
        // Markdown avec un résolveur HÔTE qui répond : la bannière
        // s'affiche — la porte du repli ne touche que la synthèse de
        // la bibliothèque.
        EditorView view = vue("calc(un, deux", "markdown");
        view.setSignatureHelpResolver((text, caret) -> aideDeuxSurcharges());
        view.getSession().setSelection(12);
        view.refreshSignatureHelp();
        assertTrue("le résolveur hôte répond (lane features)",
                attendre(2000, () -> view.signatureHelpVisible));
        assertNotNull(view.signatureHelpData);
    }

    // ════════════════════════════════════════════════════════════════
    // Badge de surcharges — géométrie partagée + tap
    // ════════════════════════════════════════════════════════════════

    @Test
    public void badge_absentSurUneSeuleSignature() {
        EditorView view = vue("calc(un", "java");
        view.signatureHelpData = new SignatureHelpController.SignatureHelp(
                java.util.Collections.singletonList(signature("calc(int a)", "doc",
                        params(), 0)), 0, 0);
        view.signatureHelpVisible = true;
        assertNull("pas de choix → pas de badge",
                view.signatureHelpBadgeMetrics());
    }

    @Test
    public void badge_presentEtTape_surPlusieursSurcharges() throws Exception {
        EditorView view = vue("calc(un", "java");
        // Le contrôleur doit CONNAÎTRE l'aide (le cyclage badge vit
        // sur lui) : on passe par un résolveur hôte + déclenchement
        // explicite, comme en production.
        view.setSignatureHelpResolver((text, caret) -> aideDeuxSurcharges());
        view.getSession().setSelection(6);
        view.triggerSignatureHelp();
        assertTrue("le résolveur hôte répond (lane features)",
                attendre(2000, () -> view.signatureHelpVisible));
        float[] badge = view.signatureHelpBadgeMetrics();
        assertNotNull("deux surcharges → badge « 1/2 »", badge);

        // Tap au centre du badge : la surcharge suivante, bannière
        // OUVERTE (B8 : le geste n'appartient pas au texte derrière).
        tap(view, (badge[0] + badge[2]) / 2f, (badge[1] + badge[3]) / 2f);
        assertTrue("la bannière reste ouverte", view.signatureHelpVisible);
        assertEquals("le tap a fait défiler vers la 2e surcharge",
                1, view.getEffectiveActiveSignature());
    }

    @Test
    public void tapHorsBadge_refermeLaBanniere() {
        // B8 inchangé en dehors du badge : tout autre tap referme.
        EditorView view = vue("calc(un", "java");
        view.signatureHelpData = aideDeuxSurcharges();
        view.signatureHelpVisible = true;
        float[] badge = view.signatureHelpBadgeMetrics();
        assertNotNull(badge);
        // Tap tout à gauche de la vue, loin du badge (coin haut droit).
        tap(view, 4f, badge[1] + 4f);
        assertFalse("le tap hors badge referme (B8)", view.signatureHelpVisible);
    }

    // ════════════════════════════════════════════════════════════════
    // Harnais
    // ════════════════════════════════════════════════════════════════

    private static EditorView vue(String texte, String langue) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(texte));
        session.setLanguage(langue);
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    private static SignatureHelpController.SignatureHelp aideDeuxSurcharges() {
        return new SignatureHelpController.SignatureHelp(java.util.Arrays.asList(
                signature("calc(int a, int b)", "Première surcharge.", params(), 0),
                signature("calc(int a)", "Surcharge minimale.",
                        java.util.Collections.singletonList(param("a", "le dividende")), 0)),
                0, 0);
    }

    private static SignatureHelpController.Signature signature(
            String label, String doc,
            java.util.List<SignatureHelpController.Parameter> params, int active) {
        return new SignatureHelpController.Signature(label, doc, params, active);
    }

    private static java.util.List<SignatureHelpController.Parameter> params() {
        return java.util.Arrays.asList(
                param("a", "le dividende"), param("b", "le diviseur"));
    }

    private static SignatureHelpController.Parameter param(String label, String doc) {
        return new SignatureHelpController.Parameter(label, doc);
    }

    /** Spin borné : la lane « features » répond, le handler est null
     *  hors fenêtre → l'application est inline sur le worker. */
    private static boolean attendre(long ms, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    private static void tap(EditorView view, float x, float y) {
        long t0 = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(down);
        down.recycle();
        MotionEvent up = MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(up);
        up.recycle();
        ShadowLooper.idleMainLooper();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
