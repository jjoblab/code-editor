package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import android.content.Context;
import android.os.Looper;

import jo.codeeditor.completion.SignatureHelpController;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 4 — géométrie de l'aide de signature PRÉSENTÉE PAR SECTIONS
 * (paramètre actif sur bande teintée, résumé ≤ 2 lignes, ligne
 * retourne dédiée) sous trois largeurs de vue : la carte reste bornée
 * [min, max] et par l'écran, la hauteur suit les sections, l'ancre
 * reste au-dessus de la ligne du caret (bascule en dessous sinon).
 *
 * <p>Robolectric avec métriques injectées (measureText y retourne 0 :
 * chaque paragraphe doc vaut UNE ligne — les hauteurs attendues en
 * découlent de façon déterministe).</p>
 */
@RunWith(RobolectricTestRunner.class)
public class SignatureSectionsGeometryTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private static EditorView vue(int largeurPx) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(
                "strlen(hello, world"));
        session.setLanguage("java");
        view.setSession(session);
        view.getSession().setSelection(16);
        view.setSignatureHelpResolver((text, caret) -> aideRiche());
        view.measure(largeurPx, 1920);
        view.layout(0, 0, largeurPx, 1920);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        view.refreshSignatureHelp();
        attendreSignature(view);
        return view;
    }

    /** La lane « features » répond de façon asynchrone — attente bornée. */
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

    /** Une surcharge, deux paramètres documentés, doc complète. */
    private static SignatureHelpController.SignatureHelp aideRiche() {
        return new SignatureHelpController.SignatureHelp(
                java.util.Collections.singletonList(
                        new SignatureHelpController.Signature(
                                "strlen(String motif, Object... args)",
                                "Retourne une chaîne formatée selon le motif.\n"
                                        + "@param motif le motif avec spécificateurs\n"
                                        + "@param args les valeurs substituées\n"
                                        + "@return la chaîne formatée\n"
                                        + "@throws NullPointerException si motif est null",
                                java.util.Arrays.asList(
                                        new SignatureHelpController.Parameter(
                                                "String motif",
                                                "le motif avec spécificateurs %s, %d…"),
                                        new SignatureHelpController.Parameter(
                                                "Object... args",
                                                "les valeurs substituées dans le motif")),
                                0)),
                0, 0);
    }

    private static float hauteur(EditorView view) {
        float[] m = view.signatureHelpPopupMetrics();
        assertNotNull("le popup vit", m);
        return m[3] - m[1];
    }

    @Test
    public void troisLargeurs_carteBornée_ancreAuDessusDuCaret() {
        for (int largeur : new int[]{320, 360, 600}) {
            EditorView view = vue(largeur);
            float[] m = view.signatureHelpPopupMetrics();
            assertNotNull("largeur " + largeur + " : le popup vit", m);
            // {left, top, right, bottom} : la carte reste dans la vue.
            assertTrue("largeur " + largeur + " : la carte reste dans la vue",
                    m[0] >= 0 && m[2] <= largeur + 0.5f);
            // Hauteur finie, positive, dans la vue.
            assertTrue("largeur " + largeur + " : hauteur saine",
                    m[3] > m[1] && m[3] <= 1920 + 0.5f);
            // L'ancre ne recouvre pas la bande du caret : le popup est
            // au-dessus (la ligne 0 laisse la place) ou en dessous.
            float bandTop = view.docLineToY(0) - view.scroll.vOffset;
            float bandBottom = bandTop + view.rowsForDocLine(0) * LINE_H;
            assertTrue("largeur " + largeur
                            + " : la bande du caret reste libre",
                    m[3] <= bandTop + 0.5f || m[1] >= bandBottom - 0.5f);
        }
    }

    @Test
    public void avecDocumentation_laCarteEstPlusHaute() {
        // La doc riche (paramètre + résumé + retour) ajoute des sections
        // par rapport à la même signature NUE : la hauteur suit.
        EditorView riche = vue(360);
        EditorView nue = vue(360);
        nue.setSignatureHelpResolver((text, caret) ->
                new SignatureHelpController.SignatureHelp(
                        java.util.Collections.singletonList(
                                new SignatureHelpController.Signature(
                                        "strlen(String motif, Object... args)", "",
                                        java.util.Arrays.asList(
                                                new SignatureHelpController.Parameter(
                                                        "String motif", ""),
                                                new SignatureHelpController.Parameter(
                                                        "Object... args", "")),
                                        0)),
                        0, 0));
        nue.refreshSignatureHelp();
        attendreSignature(nue);
        assertTrue("les sections ajoutent de la hauteur",
                hauteur(riche) > hauteur(nue) + 20f);
    }

    @Test
    public void plafondDoc_respecté_laCarteResteBornée() {
        // Une documentation interminable ne fait pas exploser la carte :
        // le plafond SIGNATURE_DOC_MAX_LINES borne les lignes de contenu
        // (la hauteur reste modérée quelle que soit la doc).
        EditorView view = vue(360);
        StringBuilder interminable = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            interminable.append("paragraphe ").append(i).append(" de documentation.\n");
        }
        view.setSignatureHelpResolver((text, caret) ->
                new SignatureHelpController.SignatureHelp(
                        java.util.Collections.singletonList(
                                new SignatureHelpController.Signature(
                                        "strlen(String motif)",
                                        interminable.toString(),
                                        java.util.Collections.singletonList(
                                                new SignatureHelpController.Parameter(
                                                        "String motif",
                                                        interminable.toString())),
                                        0)),
                        0, 0));
        view.refreshSignatureHelp();
        attendreSignature(view);
        // 6 lignes de contenu max + sections structurelles : bien en
        // dessous d'un écran — la carte reste raisonnable.
        assertTrue("la doc interminable reste bornée",
                hauteur(view) < 600f);
    }
}
