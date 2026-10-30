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
 * Documentation du popup de complétion (4.4.0, refonte phase 4) : le
 * champ {@code documentation} transporté depuis 4.3.0 est affiché en
 * TROIS BLOCS — bande signature teintée (fence du serveur), résumé,
 * lignes PARAM/RETOUR indentées. Panneau sous la liste sur vue large
 * (≥ 340 dp), BOTTOM SHEET en bas de vue sur vue étroite (la liste
 * garde toutes ses rangées), glyphe ⓘ seulement si même le sheet ne
 * tient pas ; lignes plafonnées, hauteur comptée dans l'ancre, garde
 * « ne JAMAIS recouvrir la bande du caret », hit-test conscient du
 * panneau ET du sheet (un tap dedans n'est pas une rangée).
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
        assertTrue("les rangées plafonnées sont posées pour le peintre",
                !view.completion.docPanelRows.isEmpty()
                && view.completion.docPanelRows.size() <= CompletionState.DOC_PANEL_MAX_LINES);
        assertEquals("vue large : présentation PANNEAU",
                CompletionState.DOC_PANEL, view.completion.docPresentation);
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
        assertTrue(view.completion.docPanelRows.isEmpty());
        assertEquals("sans documentation : présentation cachée",
                CompletionState.DOC_HIDDEN, view.completion.docPresentation);
    }

    @Test
    public void vueÉtroite_bottomSheet_laListeGardeSaPlace() {
        // 300 dp < DOC_PANEL_MIN_VIEW_WIDTH_DP (340) : écran partagé —
        // la documentation ne se réduit PLUS au glyphe : elle vit en
        // BOTTOM SHEET en bas de vue, et la liste ne lui cède rien.
        EditorView view = viewWithDoc(LONG_DOC, 300, 640);
        float[] a = view.completionPopupAnchor();
        assertEquals("vue étroite : pas de panneau intégré", 0f, a[5], 0f);
        assertEquals("vue étroite : présentation SHEET",
                CompletionState.DOC_SHEET, view.completion.docPresentation);
        assertTrue("les rangées vivent pour le sheet",
                !view.completion.docPanelRows.isEmpty());
        float[] sheet = view.completionDocSheetMetrics();
        org.junit.Assert.assertNotNull("le sheet a sa géométrie", sheet);
        // Le sheet vit en BAS de vue, dans la vue.
        org.junit.Assert.assertTrue("le sheet reste dans la vue",
                sheet[1] >= 0 && sheet[3] <= 640 + 0.5f && sheet[2] <= 300 + 0.5f);
        // La liste garde TOUTE la hauteur : le popup ne porte que des
        // rangées (aucune hauteur de panneau).
        assertTrue("la liste ne cède pas de place au sheet",
                (a[3] - a[5]) >= a[3] - 0.5f);
        // Hit-test : un tap dans le sheet est possédé par la
        // documentation (pas une rangée, pas un placement de caret).
        EditorPopupHitTester hit = new EditorPopupHitTester(view);
        float cx = (sheet[0] + sheet[2]) * 0.5f;
        float cy = (sheet[1] + sheet[3]) * 0.5f;
        org.junit.Assert.assertTrue(hit.hitCompletionDocPanel(cx, cy));
        assertEquals("le tap dans le sheet n'est pas une rangée", -1,
                hit.hitTestCompletionPopup(cx, cy));
    }

    @Test
    public void vueÉtroite_sheetImpossible_glypheDeRangée() {
        // Vue étroite ET trop basse pour le sheet : la documentation
        // retombe au glyphe ⓘ (le sheet lui-même ne tient pas).
        EditorView view = viewWithDoc(LONG_DOC, 300, 20);
        float[] a = view.completionPopupAnchor();
        assertEquals(0f, a[5], 0f);
        assertEquals("sheet impossible : présentation GLYPHE",
                CompletionState.DOC_GLYPH, view.completion.docPresentation);
        assertTrue(view.completion.docPanelRows.isEmpty());
        org.junit.Assert.assertNull(view.completionDocSheetMetrics());
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
        assertEquals("place insuffisante (vue large) : glyphe",
                CompletionState.DOC_GLYPH, serrée.completion.docPresentation);
        assertTrue(serrée.completion.docPanelRows.isEmpty());
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
        assertTrue("les rangées du panneau vivent après le rendu",
                !view.completion.docPanelRows.isEmpty());
    }

    @Test
    public void dessine_vueÉtroite_sheet_nePlantePas() {
        EditorView view = viewWithDoc("Retourne un objet.", 300, 640);
        Bitmap bmp = Bitmap.createBitmap(300, 640, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        // Vue étroite : la documentation vit en sheet (pas de glyphe).
        assertEquals(CompletionState.DOC_SHEET, view.completion.docPresentation);
        assertTrue(!view.completion.docPanelRows.isEmpty());
    }

    @Test
    public void basculeSheetSousSurLeSeuil() {
        // Seuil DOC_PANEL_MIN_VIEW_WIDTH_DP (340 dp, densité 1) : la
        // présentation bascule SHELL ↔ PANNEAU de part et d'autre.
        // L'ancre est la source unique : elle pose la présentation.
        EditorView étroite = viewWithDoc(LONG_DOC, 339, 640);
        étroite.completionPopupAnchor();
        assertEquals("sous le seuil : sheet",
                CompletionState.DOC_SHEET, étroite.completion.docPresentation);
        EditorView large = viewWithDoc(LONG_DOC, 341, 1920);
        large.completionPopupAnchor();
        assertEquals("sur le seuil : panneau intégré",
                CompletionState.DOC_PANEL, large.completion.docPresentation);
    }
}
