package jo.codeeditor.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.popup.EditorPopupHitTester;
import jo.codeeditor.view.state.CompletionState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Panneau de documentation du popup de complétion (4.4.0) : le champ
 * {@code documentation} transporté depuis 4.3.0 est enfin AFFICHÉ —
 * panneau sous la liste sur vue suffisamment large, lignes plafonnées,
 * hauteur comptée dans l'ancre, garde « ne JAMAIS recouvrir la bande du
 * caret » préservée par construction, cédage quand la place manque,
 * glyphe de rangée sur vue étroite, et hit-test conscient du panneau
 * (un tap dans le panneau n'est pas une rangée).
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionDocPanelTest {

    private static final String DOC =
            "public class Main {\n    obj\n    obj\n    obj\n    obj\n}\n";

    /** Documentation longue : plusieurs lignes une fois repliée. */
    private static final String LONG_DOC =
            "Ajoute un élément à la fin de cette liste. La capacité interne "
            + "est doublée si nécessaire, et la liste notifie ses observateurs "
            + "enregistrés avant de retourner. @param élément l'élément à "
            + "ajouter, null interdit @return true si la liste a changé "
    //      + "@throws NullPointerException si élément est null"
            ;

    private EditorView viewWithDoc(String documentation, int widthPx, int heightPx) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        CompletionSession.Item item = new CompletionSession.Item(
                "obj", "java.lang.Object", "obj", "v", 6, 10, false, false,
                null, null, null, -1, -1, null,
                null, documentation);
        view.completion.items.clear();
        view.completion.items.addAll(Collections.singletonList(item));
        view.completion.visible = true;
        view.completion.selected = 0;
        view.completion.scrollOffset = 0;
        view.completion.tokenStart = DOC.indexOf("obj");
        view.completion.prefix = "ob";
        view.measure(widthPx, heightPx);
        view.layout(0, 0, widthPx, heightPx);
        return view;
    }

    // ── Panneau affiché ─────────────────────────────────────────────

    @Test
    public void panneau_lignesPlafonnées_hauteurComptéeDansLancre() {
        EditorView view = viewWithDoc(LONG_DOC, 1080, 1920);
        float[] a = view.completionPopupAnchor();
        assertEquals("l'ancre transporte la hauteur du panneau (6ᵉ élément)",
                6, a.length);
        assertTrue("le panneau est montré sur vue large", a[5] > 0f);
        assertTrue("les lignes plafonnées sont posées pour le peintre",
                !view.completion.docPanelLines.isEmpty()
                && view.completion.docPanelLines.size() <= CompletionState.DOC_PANEL_MAX_LINES);
        // popupH = rangées × rowH + panneau — la zone de liste en est
        // déduite.
        float rowH = a[4];
        float listH = a[3] - a[5];
        assertEquals("la hauteur du popup compte la liste ET le panneau",
                a[3], Math.round(listH / rowH) * rowH + a[5], 0.5f);
    }

    @Test
    public void panneau_neRecouvreJamaisLaBandeDuCaret() {
        // Balaye plusieurs lignes de frappe : le popup complet (liste +
        // panneau) ne recouvre JAMAIS la bande verticale de la ligne du
        // token — la garde historique reste vraie avec le panneau.
        for (int ligne = 1; ligne <= 4; ligne++) {
            EditorView view = viewWithDoc(LONG_DOC, 1080, 1920);
            int offset = DOC.indexOf("obj", DOC.indexOf("obj") + 1);
            for (int i = 1; i < ligne; i++) {
                offset = DOC.indexOf("obj", offset + 1);
            }
            view.completion.tokenStart = offset;
            float[] a = view.completionPopupAnchor();
            // Bande de la ligne du token (même formule que le contrôleur).
            int line = view.getSession().getDocument().lineForOffset(offset);
            float bandTop = view.docLineToY(line) - view.scroll.vOffset;
            float bandBottom = bandTop
                    + view.rowsForDocLine(line) * view.metrics.getLineHeight();
            boolean auDessus = a[1] + a[3] <= bandTop + 0.5f;
            boolean enDessous = a[1] >= bandBottom - 0.5f;
            assertTrue("ligne " + ligne + " : le popup (panneau compris) ne recouvre "
                    + "jamais la bande du caret", auDessus || enDessous);
        }
    }

    @Test
    public void rangéesRéduites_quandLePanneauPrendDeLaPlace() {
        // Le panneau est DÉDUIT de la place disponible : avec lui, la
        // fenêtre de rangées ne peut qu'être ≤ à la fenêtre sans
        // documentation (même items, même bande).
        EditorView avec = viewWithDoc(LONG_DOC, 1080, 700);
        EditorView sans = viewWithDoc(null, 1080, 700);
        float[] aAvec = avec.completionPopupAnchor();
        float[] aSans = sans.completionPopupAnchor();
        assertTrue("précondition : le panneau est actif", aAvec[5] > 0f);
        assertEquals("sans documentation, pas de panneau", 0f, aSans[5], 0f);
        assertTrue("les rangées cèdent de la place au panneau",
                (aAvec[3] - aAvec[5]) <= aSans[3] + 0.5f);
    }

    // ── Cédage du panneau ───────────────────────────────────────────

    @Test
    public void sansDocumentation_pasDePanneau() {
        EditorView view = viewWithDoc(null, 1080, 1920);
        float[] a = view.completionPopupAnchor();
        assertEquals(0f, a[5], 0f);
        assertTrue(view.completion.docPanelLines.isEmpty());
    }

    @Test
    public void vueÉtroite_panneauRemplacéParLeGlyphe() {
        // 300 dp < DOC_PANEL_MIN_VIEW_WIDTH_DP (340) : écran partagé —
        // la documentation se réduit au glyphe de rangée.
        EditorView view = viewWithDoc(LONG_DOC, 300, 640);
        float[] a = view.completionPopupAnchor();
        assertEquals("vue étroite : pas de panneau", 0f, a[5], 0f);
        assertTrue(view.completion.docPanelLines.isEmpty());
    }

    @Test
    public void placeInsuffisante_panneauCédé() {
        // Vue dont l'espace disponible (le meilleur des deux côtés de la
        // bande) est JUSTE insuffisant pour une rangée + le panneau : le
        // panneau cède (garde « jamais sur la ligne du caret » prioritaire
        // sur l'affichage de la doc). Hauteur calculée depuis le panneau
        // réel pour rester déterministe quelles que soient les métriques.
        EditorView large = viewWithDoc(LONG_DOC, 1080, 1920);
        float panelH = large.completionPopupAnchor()[5];
        assertTrue("précondition : le panneau tient sur vue large", panelH > 0f);
        int line = large.getSession().getDocument().lineForOffset(large.completion.tokenStart);
        float b0 = large.docLineToY(line) - large.scroll.vOffset;
        float b1 = b0 + large.rowsForDocLine(line) * large.metrics.getLineHeight();
        float rowH = CompletionState.ROW_HEIGHT_DP;   // densité 1 sous Robolectric
        // Espace sous la bande = panelH + rowH - 2 (juste insuffisant) ;
        // l'espace au-dessus est plus petit (ligne 1 proche du sommet).
        int hauteur = Math.round(b1 + 4 + panelH + rowH - 2);
        EditorView serrée = viewWithDoc(LONG_DOC, 1080, hauteur);
        float[] a = serrée.completionPopupAnchor();
        assertEquals("place insuffisante : pas de panneau", 0f, a[5], 0f);
        assertTrue(serrée.completion.docPanelLines.isEmpty());
        assertTrue("la liste garde au moins une rangée", a[3] >= rowH - 0.5f);
    }

    // ── Hit-test ────────────────────────────────────────────────────

    @Test
    public void hitTest_lePanneauNestPasUneRangée() {
        EditorView view = viewWithDoc("Retourne un objet.", 1080, 1920);
        float[] a = view.completionPopupAnchor();
        assertTrue("précondition : panneau actif", a[5] > 0f);
        EditorPopupHitTester hit = new EditorPopupHitTester(view);
        float rowH = a[4];
        float listBottom = a[1] + Math.round((a[3] - a[5]) / rowH) * rowH;
        float cx = a[0] + a[2] * 0.5f;

        // Dans la liste : première rangée.
        assertEquals(view.completion.scrollOffset,
                hit.hitTestCompletionPopup(cx, a[1] + rowH * 0.5f));
        // Dans le panneau : PAS une rangée, mais le panneau possède ses
        // points (tap consommé sans action).
        assertEquals(-1, hit.hitTestCompletionPopup(cx, listBottom + a[5] * 0.5f));
        assertTrue(hit.hitCompletionDocPanel(cx, listBottom + a[5] * 0.5f));
        // Hors du popup : ni rangée ni panneau.
        assertFalse(hit.hitCompletionDocPanel(cx + a[2] + 50f, listBottom + 10f));
    }

    // ── Smoke de rendu ──────────────────────────────────────────────

    @Test
    public void dessine_avecPanneau_nePlantePas() {
        EditorView view = viewWithDoc(LONG_DOC, 1080, 1920);
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        assertTrue("les lignes du panneau vivent après le rendu",
                !view.completion.docPanelLines.isEmpty());
    }

    @Test
    public void dessine_vueÉtroite_glyphe_nePlantePas() {
        EditorView view = viewWithDoc("Retourne un objet.", 300, 640);
        Bitmap bmp = Bitmap.createBitmap(300, 640, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        assertTrue(view.completion.docPanelLines.isEmpty());
    }
}
