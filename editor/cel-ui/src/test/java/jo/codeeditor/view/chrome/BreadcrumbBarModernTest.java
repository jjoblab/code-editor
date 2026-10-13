package jo.codeeditor.view.chrome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.icons.EditorIcons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * B6 — la barre de fil d'Ariane devient « intelligente » :
 *
 * <ul>
 *   <li><b>addEditorActions</b> — EXACTEMENT trois actions (annuler,
 *       rétablir, aperçu), icônes vectorielles, câblées sur la session :
 *       undo grisé sans historique, réactivé par l'édition, le clic
 *       ANNULE réellement ;</li>
 *   <li><b>aperçu conditionnel</b> — le bouton d'aperçu est GONE pour un
 *       fichier non prévisualisable, VISIBLE dès que le nom devient
 *       prévisualisable — SANS appel manuel à refreshActions (miroir
 *       setFileName du contrôleur d'aperçu, barre sœur dans le même
 *       parent) ;</li>
 *   <li><b>bascule</b> — un tap sur l'œil ouvre la feuille scindée, un
 *       second tap la referme (l'action devient « active » : état accent
 *       piloté par le miroir setPreviewMode) ;</li>
 *   <li><b>cibles tactiles</b> — bouton-icône ≥ 40 dp, content description
 *       posée (TalkBack), dessin sans plantage.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class BreadcrumbBarModernTest {

    private static final int W = 1080;
    private static final int H = 1920;

    private EditorView newEditor() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of("alpha beta\n"));
        view.setSession(session);
        view.measure(W, H);
        view.layout(0, 0, W, H);
        return view;
    }

    /** Barre + éditeur sœurs dans le même parent — comme la démo. */
    private LinearLayout newHost(EditorView editor, BreadcrumbBar bar) {
        LinearLayout root = new LinearLayout(RuntimeEnvironment.getApplication());
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.measure(W, H);
        root.layout(0, 0, W, H);
        // Robolectric : la passe pondérée du LinearLayout mesure l'éditeur
        // à h=0 — re-mesure explicite (comme l'ancien harnais de feuille)
        // pour que l'ouverture de feuille ne soit pas différée.
        editor.measure(W, H);
        editor.layout(0, 0, W, H);
        return root;
    }

    /** Hôte d'aperçu minimal : .md prévisualisable, corps View trivial. */
    private static jo.codeeditor.view.preview.EditorPreviewHost hoteMinimal() {
        return new jo.codeeditor.view.preview.EditorPreviewHost() {
            @Override public boolean canPreview(String fileName) {
                return fileName != null && fileName.endsWith(".md");
            }
            @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                    int previewLeft, int previewWidth) { }
            @Override public void onPreviewContentChanged(CharSequence text) { }
            @Override public void drawPreview(Canvas canvas, float offsetX, float offsetY) { }
            @Override public boolean hasPreviewContent() { return false; }
            @Override public boolean hitTestPreview(float x, float y) { return false; }
            @Override public View onCreatePreviewView(Context ctx, EditorView editor,
                    EditorView.PreviewMode mode) {
                return new View(ctx);
            }
        };
    }

    @Test
    public void addEditorActions_poseExactementTroisBoutonsIcônes() {
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(editor);

        BreadcrumbBar.ActionButton[] buttons = bar.addEditorActions();

        assertEquals("segments + 3 actions", 4, bar.getChildCount());
        assertEquals("trois boutons rendus", 3, buttons.length);
        for (BreadcrumbBar.ActionButton button : buttons) {
            assertNotNull("content description posée (TalkBack)",
                    button.getContentDescription());
            assertEquals("cible tactile ≥ 40 dp",
                    Math.round(40 * bar.getResources().getDisplayMetrics().density),
                    button.getMinimumWidth());
        }
        // Annuler grisé d'office : l'historique est vide.
        assertFalse("rien à annuler : grisé", buttons[0].isEnabled());
        assertFalse("rien à rétablir : grisé", buttons[1].isEnabled());
        // Aperçu caché : pas de nom de fichier prévisualisable.
        assertEquals("aperçu absent sans fichier prévisualisable",
                View.GONE, buttons[2].getVisibility());
    }

    @Test
    public void undoCableSurLaSession_annuleReellement() {
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(editor);
        BreadcrumbBar.ActionButton[] buttons = bar.addEditorActions();

        // Une édition remplit l'historique ; refreshActions réactive.
        editor.getSession().replaceRange(0, 0, "XYZ ");
        bar.refreshActions();
        assertTrue("après édition : annuler actif", buttons[0].isEnabled());

        // Le clic standard annule — le texte revient à l'original.
        buttons[0].performClick();
        assertEquals("le clic a annulé l'édition",
                "alpha beta\n", editor.getSession().getText());
        bar.refreshActions();
        assertFalse("plus rien à annuler (après refresh)", buttons[0].isEnabled());

        // Rétablir réapparaît et restaure.
        assertTrue("rétablir actif après l'annulation", buttons[1].isEnabled());
        buttons[1].performClick();
        assertEquals("le clic a rétabli",
                "XYZ alpha beta\n", editor.getSession().getText());
    }

    @Test
    public void apercuInvisiblePuisVisibleParLeSeulChangementDeNom() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView editor = newEditor();
        editor.setPreviewHost(hoteMinimal());
        BreadcrumbBar bar = new BreadcrumbBar(ctx);
        bar.bind(editor);
        BreadcrumbBar.ActionButton[] buttons = bar.addEditorActions();
        newHost(editor, bar); // sœurs dans le même parent

        BreadcrumbBar.ActionButton preview = buttons[2];
        assertEquals("GONE tant que le fichier n'est pas prévisualisable",
                View.GONE, preview.getVisibility());

        // Miroir B6 : setFileName rafraîchit la barre SANS code hôte.
        editor.setFileName("exemple.md");
        assertEquals("VISIBLE dès le nom prévisualisable",
                View.VISIBLE, preview.getVisibility());
        assertTrue("actif (cliquable)", preview.isEnabled());

        // Et inversement — retour à un nom non prévisualisable.
        editor.setFileName("Main.java");
        assertEquals("GONE à nouveau", View.GONE, preview.getVisibility());
    }

    @Test
    public void apercuBasculeOuvrirPuisFermer() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView editor = newEditor();
        editor.setPreviewHost(hoteMinimal());
        editor.setFileName("exemple.md");
        BreadcrumbBar bar = new BreadcrumbBar(ctx);
        bar.bind(editor);
        BreadcrumbBar.ActionButton[] buttons = bar.addEditorActions();
        newHost(editor, bar);
        BreadcrumbBar.ActionButton preview = buttons[2];
        assertEquals("précondition : aperçu visible (fichier .md)",
                View.VISIBLE, preview.getVisibility());

        // Premier tap : la feuille scindée s'ouvre (l'éditeur est mesuré).
        preview.performClick();
        assertEquals("feuille scindée ouverte",
                EditorView.PreviewMode.SHEET_SPLIT, editor.getPreviewMode());
        assertNotNull("feuille vivante", editor.getPreviewSheet());
        // Le miroir setPreviewMode a re-évalué l'état actif du bouton.
        bar.refreshActions();
        assertTrue("bouton en état actif (aperçu ouvert)", preview.activeNow);

        // Second tap : la bascule referme.
        preview.performClick();
        assertEquals("second tap : refermé",
                EditorView.PreviewMode.NONE, editor.getPreviewMode());
    }

    @Test
    public void iconeDActionSeDessineEtLaBarreAussi() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(ctx);
        bar.bind(editor);
        bar.addIconAction(EditorIcons.Icon.UNDO, "Annuler", null, v -> { });
        bar.setSegments(new String[]{"Main.java", "Main", "run"});
        newHost(editor, bar);

        // Bouton-icône : dessin sans plantage (glyphe + pill + fond barre).
        Bitmap bmp = Bitmap.createBitmap(W, 80, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(bmp));
    }

    @Test
    public void addIconAction_requiertUneIcône() {
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(newEditor());
        try {
            bar.addIconAction(null, "nul", null, v -> { });
            org.junit.Assert.fail("icon null doit être rejeté");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void removeActionRetireUnBoutonDeLaRangée() {
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(editor);
        BreadcrumbBar.ActionButton[] buttons = bar.addEditorActions();
        assertEquals(4, bar.getChildCount());
        bar.removeAction(buttons[1]); // rétablir
        assertEquals("rétablir retiré : 2 actions restantes", 3, bar.getChildCount());
        bar.clearActions();
        assertEquals("segments seuls", 1, bar.getChildCount());
    }

    // ════════════════════════════════════════════════════════════════
    // B7 (retour terrain n°4) — le câblage doit vivre SANS refresh
    // manuel : le chemin IME (commitText → onTextChanged) et le clic
    // des boutons eux-mêmes réactivent les états.
    // ════════════════════════════════════════════════════════════════

    /**
     * B7 — le chemin IME réactive le bouton annuler. Avant le correctif,
     * seule notifyTextChanged() (hook hôte) rafraîchissait la barre : la
     * frappe réelle (EditorImeBridge.commitText → onTextChanged) laissait
     * les boutons grisés — « ils ne font rien quand j'appuie ».
     */
    @Test
    public void frappeIMEReactiveAnnulerSansRefreshManuel() {
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(editor);
        BreadcrumbBar.ActionButton[] buttons = bar.addEditorActions();
        newHost(editor, bar); // sœurs dans le même parent — comme la démo
        assertFalse("précondition : grisé sans historique", buttons[0].isEnabled());

        // Chemin IME : la session mute, la vue est informée via
        // onTextChanged() — AUCUN refreshActions() manuel.
        editor.getSession().replaceRange(0, 0, "XYZ ");
        editor.onTextChanged();

        assertTrue("frappe clavier : annuler réactivé SANS refresh manuel",
                buttons[0].isEnabled());
    }

    /**
     * B7 — le clic sur annuler réactive rétablir immédiatement (comptabilité
     * du raccourci clavier). Avant le correctif, redo restait grisé après
     * une annulation au bouton.
     */
    @Test
    public void clicAnnulerReactiveRetablirSansRefreshManuel() {
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(editor);
        BreadcrumbBar.ActionButton[] buttons = bar.addEditorActions();
        newHost(editor, bar);

        editor.getSession().replaceRange(0, 0, "XYZ ");
        editor.onTextChanged(); // comme la frappe
        assertTrue(buttons[0].isEnabled());

        // Le clic standard annule ET rafraîchit les deux boutons.
        buttons[0].performClick();
        assertEquals("le clic a annulé l'édition",
                "alpha beta\n", editor.getSession().getText());
        assertFalse("plus rien à annuler après le clic", buttons[0].isEnabled());
        assertTrue("rétablir réactivé PAR LE CLIC (pas de refresh manuel)",
                buttons[1].isEnabled());
    }
}
