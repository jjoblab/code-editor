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
 * 4.8.0 — aide de signature redessinée. ★ 4.10.0 — une surcharge à
 * la fois : stepper « ‹ i/n › », repli sur réponse VIDE du résolveur,
 * peek par appui long, fenêtrage des longues signatures.
 *
 * <p>Chantiers vérifiés ici :</p>
 * <ul>
 *   <li><b>Porte du repli synthétique</b> — sans résolveur, la
 *       bibliothèque ne fabrique plus de « name(…) param N » sur les
 *       langages sans syntaxe d'appel (texte brut, XML, Markdown,
 *       prose). Un résolveur branché ne reste maître que d'une
 *       réponse NON VIDE (4.10.0) ;</li>
 *   <li><b>Repli repensé</b> — le libellé est l'appel VIVANT (les
 *       arguments déjà frappés, l'actif surligné par le peintre), la
 *       documentation du paramètre actif porte son numéro
 *       (chaîne cel_signature_active_param, anglais canonique
 *       surchargeable par l'hôte). Servi AUSSI quand un résolveur
 *       branché répond VIDE (4.10.0) — le serveur muet ne condamne
 *       plus l'utilisateur au silence total ;</li>
 *   <li><b>Stepper de surcharges « ‹ i/n › »</b> — présent seulement
 *       s'il y a un choix, géométrie partagée peintre/hit-test, chaque
 *       flèche est sa propre zone tactile (BORNÉE : pas de cycle au
 *       premier/dernier index), tout autre tap referme la vue PEEK si
 *       ouverte sinon la bannière (B8) ;</li>
 *   <li><b>Peek (appui long)</b> — la signature fenêtrée s'étend en
 *       signature complète multi-lignes + documentation ;</li>
 *   <li><b>Fenêtrage</b> — au-delà de 5 paramètres, seuls l'actif ± 1
 *       restent, les autres en « … ».</li>
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
    // 4.10.0 — réponse VIDE du résolveur : repli local
    // ════════════════════════════════════════════════════════════════

    @Test
    public void avecResolveur_reponseVide_replieSurLAppelVivant() throws Exception {
        // Résolveur branché qui répond VIDE (serveur muet, analyse pas
        // prête) sur un langage à appels : le repli synthétique local
        // prend le relais — plus de silence total.
        EditorView view = vue("strlen(hello, world", "java");
        view.setSignatureHelpResolver((text, caret) -> null);
        view.getSession().setSelection(16);
        view.refreshSignatureHelp();
        assertTrue("la réponse vide retombe sur le repli local (lane features)",
                attendre(2000, () -> view.signatureHelpVisible));
        assertNotNull(view.signatureHelpData);
        assertEquals("le libellé du repli est l'appel vivant",
                "strlen(hello, world)",
                view.signatureHelpData.signatures.get(0).label);
    }

    @Test
    public void avecResolveur_reponseVide_horsLangageDAppels_seTait() throws Exception {
        // Réponse vide + Markdown (sans syntaxe d'appel) : la porte
        // callSyntax borne AUSSI le repli — jamais de « name(…) param N »
        // générique dans la prose, même quand le serveur ne répond pas.
        EditorView view = vue("Il est né (à Paris, en 1990", "markdown");
        view.setSignatureHelpResolver((text, caret) -> null);
        view.getSession().setSelection(25);
        view.refreshSignatureHelp();
        Thread.sleep(150);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("repli muet sur Markdown même en réponse vide",
                view.signatureHelpVisible);
        assertNull(view.signatureHelpData);
    }

    // ════════════════════════════════════════════════════════════════
    // Stepper de surcharges « ‹ i/n › » — géométrie partagée + tap
    // ════════════════════════════════════════════════════════════════

    @Test
    public void stepper_absentSurUneSeuleSignature() {
        EditorView view = vue("calc(un", "java");
        view.signatureHelpData = new SignatureHelpController.SignatureHelp(
                java.util.Collections.singletonList(signature("calc(int a)", "doc",
                        params(), 0)), 0, 0);
        view.signatureHelpVisible = true;
        assertNull("pas de choix → pas de stepper",
                view.signatureHelpStepperMetrics());
    }

    @Test
    public void stepper_presentEtFlechesTapees_surPlusieursSurcharges() throws Exception {
        EditorView view = vue("calc(un", "java");
        // Le contrôleur doit CONNAÎTRE l'aide (le cyclage vit sur lui) :
        // résolveur hôte + déclenchement explicite, comme en production.
        view.setSignatureHelpResolver((text, caret) -> aideDeuxSurcharges());
        view.getSession().setSelection(6);
        view.triggerSignatureHelp();
        assertTrue("le résolveur hôte répond (lane features)",
                attendre(2000, () -> view.signatureHelpVisible));
        float[] stepper = view.signatureHelpStepperMetrics();
        assertNotNull("deux surcharges → stepper « ‹ 1/2 › »", stepper);
        assertEquals("8 valeurs : flèche gauche (4) + flèche droite (4)",
                8, stepper.length);

        // Flèche GAUCHE à l'index 0 : BORNÉE — rien ne bouge, la
        // bannière reste ouverte (le geste est consommé).
        tap(view, (stepper[0] + stepper[2]) / 2f, (stepper[1] + stepper[3]) / 2f);
        assertTrue("la bannière reste ouverte", view.signatureHelpVisible);
        assertEquals("la flèche gauche bornée ne cycle pas", 0,
                view.getEffectiveActiveSignature());

        // Flèche DROITE : la surcharge suivante.
        tap(view, (stepper[4] + stepper[6]) / 2f, (stepper[5] + stepper[7]) / 2f);
        assertTrue("la bannière reste ouverte", view.signatureHelpVisible);
        assertEquals("la flèche droite avance vers la 2e surcharge",
                1, view.getEffectiveActiveSignature());

        // Flèche DROITE au DERNIER index : bornée aussi (pas de cycle).
        tap(view, (stepper[4] + stepper[6]) / 2f, (stepper[5] + stepper[7]) / 2f);
        assertEquals("la flèche droite bornée ne cycle pas", 1,
                view.getEffectiveActiveSignature());
    }

    @Test
    public void tapHorsStepper_refermeLaBanniere() {
        // B8 inchangé en dehors du stepper : tout autre tap referme.
        EditorView view = vue("calc(un", "java");
        view.signatureHelpData = aideDeuxSurcharges();
        view.signatureHelpVisible = true;
        float[] stepper = view.signatureHelpStepperMetrics();
        assertNotNull(stepper);
        // Tap en bas de la vue, loin du stepper (ligne du haut).
        tap(view, 4f, stepper[7] + 80f);
        assertFalse("le tap hors stepper referme (B8)", view.signatureHelpVisible);
    }

    // ════════════════════════════════════════════════════════════════
    // 4.10.0 — Peek par appui long + fenêtrage
    // ════════════════════════════════════════════════════════════════

    @Test
    public void appuiLongSurLePopup_basculeLePeek() {
        EditorView view = vue("calc(un", "java");
        // Amorce le contrôleur sur CET appel : le peek doit survivre aux
        // rafraîchissements du MÊME appel (en production, le contrôleur
        // a déjà résolu l'appel avant que l'utilisateur n'appuie long).
        view.signatureHelpController.resolve("calc(un", 6);
        view.signatureHelpData = aideDeuxSurcharges();
        view.signatureHelpVisible = true;
        assertFalse("peek fermé par défaut",
                view.signatureHelpController.isPeeked());
        float[] popup = view.signatureHelpPopupMetrics();
        assertNotNull(popup);
        // Appui long au centre du popup : le peek s'ouvre.
        appuiLong(view, (popup[0] + popup[2]) / 2f, (popup[1] + popup[3]) / 2f);
        assertTrue("l'appui long ouvre le peek",
                view.signatureHelpController.isPeeked());
        assertTrue("la bannière reste ouverte", view.signatureHelpVisible);
        // Un TAP ensuite referme le peek (sans fermer la bannière).
        tap(view, (popup[0] + popup[2]) / 2f, (popup[1] + popup[3]) / 2f);
        assertFalse("le tap referme le peek",
                view.signatureHelpController.isPeeked());
        assertTrue("la bannière survit à la fermeture du peek",
                view.signatureHelpVisible);
    }

    @Test
    public void fenetrage_cinqParametresOuMoins_intact() {
        int[] actif = new int[1];
        String label = "append(CharSequence s)";
        String out = jo.codeeditor.view.render.EditorAssistPopupPainter
                .windowSignature(label, 0, actif);
        assertEquals("≤ 5 paramètres : libellé intact", label, out);
        assertEquals(0, actif[0]);
    }

    @Test
    public void fenetrage_sixParametresEtPlus_actifPlusMoinsUn() {
        String label = "layout(int l, int t, int r, int b, int w, int h, int f)";
        // Actif = h (index 5) → fenêtre [4..6] : « …, int w, int h, int f »
        // (f est le DERNIER paramètre : pas de « … » de queue).
        int[] actif = new int[1];
        String out = jo.codeeditor.view.render.EditorAssistPopupPainter
                .windowSignature(label, 5, actif);
        assertEquals("layout(…, int w, int h, int f)", out);
        // L'actif (h) vit à l'index 2 du libellé fenêtré (…, w, h, f).
        assertEquals(2, actif[0]);
        // Actif = l (index 0) → fenêtre [0..1] : « int l, int t, … » (pas
        // de « … » de tête — l'actif est au bord).
        String out0 = jo.codeeditor.view.render.EditorAssistPopupPainter
                .windowSignature(label, 0, actif);
        assertEquals("layout(int l, int t, …)", out0);
        assertEquals(0, actif[0]);
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

    /** Appui long (GestureDetector : ~500 ms par défaut). */
    private static void appuiLong(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        SystemClock.sleep(600);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        MotionEvent up = MotionEvent.obtain(down, down + 620,
                MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(up);
        up.recycle();
        ShadowLooper.idleMainLooper();
    }
}
