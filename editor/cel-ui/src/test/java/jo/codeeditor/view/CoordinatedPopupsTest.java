package jo.codeeditor.view;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import android.os.Looper;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.completion.SignatureHelpController;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.util.Collections;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 5 — placement COORDONNÉ signature ↔ complétion au niveau de la
 * VUE : les deux popups visibles SIMULTANÉMENT (comportement cible :
 * pendant la frappe d'un appel, la signature est AU-DESSUS de la bande
 * du caret, la complétion EN DESSOUS, la ligne du caret LIBRE au
 * milieu — aucun chevauchement).
 *
 * <p>Matrice imposée : position de la ligne de frappe HAUT / MILIEU /
 * BAS de vue (le caret reste DANS l'appel — la porte findCallOpen
 * l'exige — c'est le DÉFILEMENT qui déplace la bande à l'écran) × 3
 * hauteurs de vue (la plus courte modélise le clavier logiciel ouvert :
 * adjustResize réduit la hauteur réelle). Ligne WRAPPÉE : la bande
 * suit les rangées visuelles réelles (rowsForDocLine).</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CoordinatedPopupsTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** 10 lignes de marge, l'appel en ligne 10 (LONGUE → wrap 2 rangées
     *  avec CHAR_W=10 et 360 px), 10 lignes de marge dessous. */
    private static final String DOC =
            "l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\n"
                    + "class A { void m() { strlen(hello, world"
                    + " avec une longue suite de caracteres pour le wrap }\n"
                    + "l12\nl13\nl14\nl15\nl16\nl17\nl18\nl19\nl20\nl21\n";

    private static final int LIGNE_APPEL = 10;
    private static final int TOKEN = DOC.indexOf("world");

    /** L'appel vivant dont la signature aide la frappe. */
    private static SignatureHelpController.SignatureHelp aide() {
        return new SignatureHelpController.SignatureHelp(
                Collections.singletonList(new SignatureHelpController.Signature(
                        "strlen(String motif, Object... args)",
                        "Retourne une chaîne formatée.\n"
                                + "@param motif le motif\n@return la chaîne",
                        java.util.Arrays.asList(
                                new SignatureHelpController.Parameter(
                                        "String motif", "le motif avec %s"),
                                new SignatureHelpController.Parameter(
                                        "Object... args", "les valeurs")),
                        0)), 0, 0);
    }

    private static EditorView vue(int hauteurPx, boolean wrap) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        session.setLanguage("java");
        view.setSession(session);
        // Complétion vivante sur le token « world » de l'appel.
        CompletionSession.Item item = new CompletionSession.Item(
                "world", "String", "world", "v", 6, 10, false, false,
                null, null, null, -1, -1, null, null, "La doc de world.");
        view.completion.items.clear();
        view.completion.items.addAll(Collections.singletonList(item));
        view.completion.visible = true;
        view.completion.selected = 0;
        view.completion.scrollOffset = 0;
        view.completion.tokenStart = TOKEN;
        view.completion.prefix = "wor";
        view.getSession().setSelection(TOKEN + 3);   // caret dans le token
        view.setSignatureHelpResolver((text, caret) -> aide());
        view.setWordWrap(wrap);
        view.measure(360, hauteurPx);
        view.layout(0, 0, 360, hauteurPx);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        // Le comptage de rangées wrap dépend de charWidth : reconstruire
        // APRÈS l'injection (piège documenté dans AGENT.md).
        if (wrap) view.rebuildWrapModel();
        view.refreshSignatureHelp();
        attendreSignature(view);
        return view;
    }

    private static void attendreSignature(EditorView view) {
        long fin = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < fin && !view.signatureHelpVisible) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Bande écran de la ligne de frappe (même formule que les popups). */
    private static float[] bande(EditorView view) {
        float top = view.docLineToY(LIGNE_APPEL) - view.scroll.vOffset;
        return new float[]{top,
                top + view.rowsForDocLine(LIGNE_APPEL) * LINE_H};
    }

    @Test
    public void matrice_bandeHautMilieuBas_troisHauteurs_coordinationParfaite() {
        // 3 hauteurs réelles : 1920 (plein écran), 960 (moitié), 480
        // (clavier logiciel ouvert — adjustResize réduit la vue).
        int[] hauteurs = {1920, 960, 480};
        // Position visée de la bande : haut, milieu, bas de la vue.
        String[] positions = {"haut", "milieu", "bas"};
        for (int hauteur : hauteurs) {
            for (int pos = 0; pos < positions.length; pos++) {
                EditorView view = vue(hauteur, false);
                // Le caret reste DANS l'appel — c'est le DÉFILEMENT qui
                // place la bande de frappe à l'écran.
                float docY = view.docLineToY(LIGNE_APPEL);
                float bandH = view.rowsForDocLine(LIGNE_APPEL) * LINE_H;
                float vOffset;
                if (pos == 0) {
                    vOffset = docY - 2f;               // bande en haut
                } else if (pos == 1) {
                    vOffset = docY - (hauteur - bandH) * 0.5f;  // milieu
                } else {
                    vOffset = docY - (hauteur - bandH) + 2f;    // bas
                }
                view.scroll.vOffset = Math.max(0f, vOffset);
                String ctx = "hauteur " + hauteur + " bande " + positions[pos];

                float[] sig = view.signatureHelpPopupMetrics();
                float[] cmp = view.completionPopupAnchor();
                assertNotNull(ctx + " : signature visible", sig);
                assertNotNull(ctx + " : complétion visible", cmp);
                float cmpBottom = cmp[1] + cmp[3];
                float[] band = bande(view);

                // ── La bande du caret est JAMAIS recouverte ──
                assertTrue(ctx + " : la signature épargne la bande ("
                                + sig[1] + ".." + sig[3] + " vs " + band[0] + ".." + band[1] + ")",
                        sig[3] <= band[0] + 0.5f || sig[1] >= band[1] - 0.5f);
                assertTrue(ctx + " : la complétion épargne la bande",
                        cmpBottom <= band[0] + 0.5f || cmp[1] >= band[1] - 0.5f);

                // ── Aucun chevauchement signature ↔ complétion ──
                assertTrue(ctx + " : aucun chevauchement sig/cmp",
                        sig[3] <= cmp[1] + 0.5f || cmpBottom <= sig[1] + 0.5f);

                // ── Les deux popups restent dans la vue ──
                assertTrue(ctx + " : signature dans la vue",
                        sig[1] >= -0.5f && sig[3] <= hauteur + 0.5f);
                assertTrue(ctx + " : complétion dans la vue",
                        cmp[1] >= -0.5f && cmpBottom <= hauteur + 0.5f);
            }
        }
    }

    @Test
    public void ligneWrappée_laBandeSuitLesRangéesRéelles() {
        // Wrap actif : la ligne d'appel occupe PLUSIEURS rangées
        // visuelles — la bande de frappe est plus haute qu'une ligne,
        // et les popups l'épargnent quand même (rowsForDocLine, pas
        // lineHeight brut : l'ancienne ancre de signature plaçait le
        // popup SUR une ligne wrappée).
        EditorView view = vue(960, true);
        assertTrue("la ligne d'appel est wrappée sur plusieurs rangées",
                view.rowsForDocLine(LIGNE_APPEL) > 1);

        float[] sig = view.signatureHelpPopupMetrics();
        float[] cmp = view.completionPopupAnchor();
        assertNotNull(sig);
        assertNotNull(cmp);
        float[] band = bande(view);
        assertTrue("la signature épargne la bande wrappée ("
                        + sig[1] + ".." + sig[3] + " vs " + band[0] + ".." + band[1] + ")",
                sig[3] <= band[0] + 0.5f || sig[1] >= band[1] - 0.5f);
        assertTrue("la complétion épargne la bande wrappée",
                cmp[1] + cmp[3] <= band[0] + 0.5f || cmp[1] >= band[1] - 0.5f);
        assertTrue("aucun chevauchement sig/cmp",
                sig[3] <= cmp[1] + 0.5f || cmp[1] + cmp[3] <= sig[1] + 0.5f);
    }

    @Test
    public void comportementCible_signatureAuDessus_complétionEnDessous() {
        // La configuration de frappe idéale (vue haute, bande au
        // milieu) : signature AU-DESSUS de la bande, complétion EN
        // DESSOUS — la ligne du caret reste LIBRE au milieu, aucun
        // chevauchement, bords gauches ALIGNÉS (ancre X partagée).
        // Vue LARGE : aucun clamp droit — les deux popups partent du
        // X du token (aucune raison de diverger).
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        session.setLanguage("java");
        view.setSession(session);
        CompletionSession.Item item = new CompletionSession.Item(
                "world", "String", "world", "v", 6, 10, false, false,
                null, null, null, -1, -1, null, null, "La doc de world.");
        view.completion.items.clear();
        view.completion.items.addAll(Collections.singletonList(item));
        view.completion.visible = true;
        view.completion.selected = 0;
        view.completion.scrollOffset = 0;
        view.completion.tokenStart = TOKEN;
        view.completion.prefix = "wor";
        view.getSession().setSelection(TOKEN + 3);
        view.setSignatureHelpResolver((text, caret) -> aide());
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        view.refreshSignatureHelp();
        attendreSignature(view);
        float docY = view.docLineToY(LIGNE_APPEL);
        float bandH = view.rowsForDocLine(LIGNE_APPEL) * LINE_H;
        view.scroll.vOffset = Math.max(0f, docY - (1920 - bandH) * 0.5f);

        float[] sig = view.signatureHelpPopupMetrics();
        float[] cmp = view.completionPopupAnchor();
        float[] band = bande(view);
        assertTrue("la signature est AU-DESSUS de la bande",
                sig[3] <= band[0] + 0.5f);
        assertTrue("la complétion est EN DESSOUS de la bande",
                cmp[1] >= band[1] - 0.5f);
        // Bords gauches alignés : le X d'ancre est PARTAGÉ (début du
        // token, clampé à la gouttière — les deux popups partent de la
        // même colonne, l'ancienne ancre de signature suivait le caret).
        assertTrue("bords gauches alignés (X partagé)",
                Math.abs(sig[0] - cmp[0]) <= 1f);
    }
}
