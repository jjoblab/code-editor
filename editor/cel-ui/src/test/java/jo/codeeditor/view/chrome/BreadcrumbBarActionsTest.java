package jo.codeeditor.view.chrome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.LinearLayout;

import java.util.concurrent.atomic.AtomicInteger;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B5 (retour terrain post-J6) — la barre de fil d'Ariane porte la zone
 * d'<b>actions d'éditeur en haut à droite</b> : les boutons dispersés
 * (agrandir/réduire la police, icône d'aperçu, tous les boutons de
 * l'hôte) se regroupent à droite des segments.
 *
 * <ul>
 *   <li><b>addAction</b> — ajoute un bouton cliquable : performClick
 *       déclenche l'écouteur (chemin standard) ;</li>
 *   <li><b>état grisé</b> — le fournisseur d'état pilote setEnabled ;
 *       refreshActions relit (le bouton Undo se grise sans historique) ;</li>
 *   <li><b>addPreviewAction</b> — l'œil s'ajoute ET RELOCALISE les icônes
 *       d'aperçu : le canvas de l'éditeur n'en dessine plus (une seule
 *       entrée d'interaction) ; réactivables via
 *       setPreviewIconsVisible(true) ;</li>
 *   <li><b>notifyTextChanged</b> — l'éditeur rafraîchit les actions de
 *       la barre sœur après chaque édition (miroir d'EditorBarTools).</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class BreadcrumbBarActionsTest {

    private static final int W = 1080;
    private static final int H = 1920;

    private EditorView newEditor() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of("public class Main {\n    int x = 1;\n}\n"));
        view.setSession(session);
        view.measure(W, H);
        view.layout(0, 0, W, H);
        return view;
    }

    @Test
    public void addAction_routeLeClicEtSeCompte() {
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(editor);
        // segmentsView est le premier enfant.
        assertEquals(1, bar.getChildCount());

        AtomicInteger clicks = new AtomicInteger();
        BreadcrumbBar.ActionButton button = bar.addAction("A+",
                "Agrandir la police", null, v -> clicks.incrementAndGet());
        assertEquals("segments + 1 action", 2, bar.getChildCount());
        assertTrue("bouton actif sans fournisseur d'état", button.isEnabled());

        button.performClick();
        assertEquals("le clic standard déclenche l'écouteur", 1, clicks.get());

        bar.removeAction(button);
        assertEquals("retrait ciblé", 1, bar.getChildCount());
    }

    @Test
    public void etatGris_suiviLeFournisseurEtLeRefresh() {
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(RuntimeEnvironment.getApplication());
        bar.bind(editor);
        // L'historique est VIDE : canUndo = faux → bouton grisé dès l'ajout.
        BreadcrumbBar.ActionButton undo = bar.addAction("↶", "Annuler",
                () -> editor.getSession().getUndoManager().canUndo(),
                v -> editor.getSession().undo());
        assertFalse("rien à annuler : bouton grisé", undo.isEnabled());

        // Une édition remplit l'historique → refreshActions le réactive.
        editor.getSession().replaceRange(0, 0, "x");
        bar.refreshActions();
        assertTrue("après édition : bouton réactivé", undo.isEnabled());
    }

    @Test
    public void addPreviewAction_relocaliseLesIconesDuCanvas() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView editor = newEditor();
        // Hôte d'aperçu + nom de fichier prévisualisable → icônes actives.
        editor.setPreviewHost(pasDeRendu());
        editor.setFileName("exemple.md");
        assertTrue("précondition : fichier prévisualisable", editor.previewable);
        assertTrue("précondition : icônes canvas visibles par défaut",
                editor.previewIconsVisible);

        BreadcrumbBar bar = new BreadcrumbBar(ctx);
        bar.bind(editor);
        BreadcrumbBar.ActionButton eye = bar.addPreviewAction();

        // L'œil prend le relais : le canvas n'a plus ses icônes.
        assertFalse("les icônes canvas se retirent (relocalisées)",
                editor.previewIconsVisible);
        assertTrue("l'œil est actif (fichier prévisualisable)", eye.isEnabled());

        // Le hit-test des icônes canvas est éteint avec le dessin.
        float density = editor.getResources().getDisplayMetrics().density;
        float iconSize = 22f * density;
        assertEquals("plus de hit sur les icônes canvas",
                0, editor.hitTestPreviewIcons(
                        W - 8 * density - iconSize * 0.5f, 8 * density + iconSize * 0.5f));

        // Réactivation explicite : les icônes canvas reviennent.
        editor.setPreviewIconsVisible(true);
        assertTrue(editor.previewIconsVisible);
        assertEquals("hit rétabli sur l'icône plein écran",
                2, editor.hitTestPreviewIcons(
                        W - 8 * density - iconSize * 0.5f, 8 * density + iconSize * 0.5f));
    }

    @Test
    public void notifyTextChanged_rafraichitLaBarreSoeur() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(ctx);
        bar.bind(editor);
        BreadcrumbBar.ActionButton undo = bar.addAction("↶", "Annuler",
                () -> editor.getSession().getUndoManager().canUndo(),
                v -> editor.getSession().undo());

        // Barre et éditeur sont sœurs dans le même parent — comme la démo.
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar);
        root.addView(editor);
        root.measure(W, H);
        root.layout(0, 0, W, H);

        assertFalse("départ : grisé (rien à annuler)", undo.isEnabled());
        editor.getSession().replaceRange(0, 0, "y");
        // notifyTextChanged (chemin public de l'hôte) → refresh via le
        // miroir d'EditorBarTools.
        editor.notifyTextChanged();
        assertTrue("l'édition rafraîchit la barre sœur", undo.isEnabled());
    }

    @Test
    public void laBarreSeDessineAvecActionsEtSegments() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView editor = newEditor();
        BreadcrumbBar bar = new BreadcrumbBar(ctx);
        bar.bind(editor);
        bar.addAction("A−", null, null, v -> { });
        bar.addAction("A+", null, null, v -> { });
        bar.setSegments(new String[]{"Main.java", "Main", "run"});

        bar.measure(W, 200);
        bar.layout(0, 0, W, bar.getMeasuredHeight());
        assertEquals("hauteur de barre 36 dp (density 1)", 36, bar.getHeight());

        // Le dessin ne doit pas planter (segments + actions + fond).
        Bitmap bmp = Bitmap.createBitmap(W, 80, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(bmp));
    }

    /** Hôte d'aperçu minimal : rien à rendre (la feuille reste fermée). */
    private static jo.codeeditor.view.preview.EditorPreviewHost pasDeRendu() {
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
        };
    }
}
