package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.PopupWindow;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.session.EditorSession;

import org.robolectric.Shadows;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B8 (retour terrain n°5) — « quand le popup est visible, le touché en
 * dehors du cadre dismiss le popup ».
 *
 * <p>Audit complet de la fermeture extérieure :</p>
 * <ul>
 *   <li><b>Canvas</b> : aide de signature (bannière passive — tout tap la
 *       referme), popup d'actions de code et aller-au-symbole (tap hors
 *       cadre → fermeture + le caret suit le tap, la cascade continue) ;</li>
 *   <li><b>Fenêtres</b> : feuille d'aperçu et carte aller-à-la-ligne
 *       portent la recette Android complète ({@code setOutsideTouchable}
 *       + fond non nul) qui traduit un tap hors cadre en
 *       {@code ACTION_OUTSIDE} → dismiss automatique.</li>
 * </ul>
 *
 * <p>Avant B8 : le tap hors cadre des popups d'actions de code /
 * aller-au-symbole tombait dans la cascade SANS rien refermer (le popup
 * restait ancré à sa ligne), l'aide de signature ignorait les taps, et
 * les fenêtres PopupWindow verrouillaient explicitement la fermeture
 * extérieure.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PopupOutsideTapDismissTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    /** Tap simple (DOWN + UP rapide, sans mouvement). */
    private static void tap(EditorView view, float x, float y) {
        long t0 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t0, t0, x, y);
        send(view, MotionEvent.ACTION_UP, t0, t0 + 40, x, y);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Position écran visant le quart gauche du caractère à l'offset donné. */
    private float[] midCharPos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        return new float[]{pos[0] + CHAR_W * 0.25f, pos[1] + LINE_H / 2f};
    }

    // ════════════════════════════════════════════════════════════════
    // Canvas — aide de signature
    // ════════════════════════════════════════════════════════════════

    @Test
    public void aideDeSignature_tapQuelconque_laReferme() {
        EditorView view = newView();
        view.signatureHelpVisible = true;
        assertTrue("pré-condition : bannière visible",
                view.signatureHelpVisible);

        // Tap sur « public » ligne 0 — le geste vise le texte derrière la
        // bannière passive.
        int pub = DOC.indexOf("public");
        float[] pos = midCharPos(view, pub);
        tap(view, pos[0], pos[1]);

        assertFalse("le tap a refermé l'aide de signature (B8)",
                view.signatureHelpVisible);
        // Le tap a bien atteint le texte : le caret est posé.
        assertEquals("le caret suit le tap (cascade inchangée)",
                pub, view.getSession().getSelection().start);
    }

    // ════════════════════════════════════════════════════════════════
    // Canvas — popup d'actions de code
    // ════════════════════════════════════════════════════════════════

    @Test
    public void actionsDeCode_tapHorsCadre_refermeEtPlaceLeCaret() {
        EditorView view = newView();
        // Popup ancré sur la ligne 2 (l'ampoule d'une ligne de diagnostic).
        view.codeActionsPopupLine = 2;
        view.codeActionsSelected = 0;
        view.codeActionsByLine.put(2, Collections.singletonList(
                new EditorView.CodeAction("Commenter la ligne", "refactor",
                        () -> { /* sans objet ici */ })));
        view.codeActionsPopupVisible = true;

        // Tap sur « public » ligne 0 — LOIN du cadre (ancre ligne 2,
        // gutter gauche) : hors bornes X/Y du hit-test.
        int pub = DOC.indexOf("public");
        float[] pos = midCharPos(view, pub);
        tap(view, pos[0], pos[1]);

        assertFalse("le tap hors cadre a refermé le popup d'actions (B8)",
                view.codeActionsPopupVisible);
        assertEquals("le caret suit le tap (la cascade continue derrière)",
                pub, view.getSession().getSelection().start);
    }

    // ════════════════════════════════════════════════════════════════
    // Canvas — popup aller-au-symbole
    // ════════════════════════════════════════════════════════════════

    @Test
    public void allerAuSymbole_tapHorsCadre_refermeEtPlaceLeCaret() {
        EditorView view = newView();
        // Popup peuplé d'un symbole, ancré au CENTRE-HAUT de la vue.
        view.goToSymbolFiltered = List.of(new NavigationMenu.Symbol(
                "Main", DOC.indexOf("Main"), "class", "Main"));
        view.goToSymbolSelected = 0;
        view.goToSymbolScrollOffset = 0;
        view.goToSymbolVisible = true;

        // Tap sur le « } » de la ligne 4 — en bas, loin du cadre haut.
        int closing = DOC.indexOf('}', DOC.indexOf("run()"));
        float[] pos = midCharPos(view, closing);
        tap(view, pos[0], pos[1]);

        assertFalse("le tap hors cadre a refermé le popup symboles (B8)",
                view.goToSymbolVisible);
        assertEquals("le caret suit le tap (la cascade continue derrière)",
                closing, view.getSession().getSelection().start);
    }

    // ════════════════════════════════════════════════════════════════
    // Fenêtres — recette complète de rejet extérieur
    // ════════════════════════════════════════════════════════════════

    /** Ouvre la feuille d'aperçu (stub .md, repli canvas) et retourne la
     *  PopupWindow sous-jacente par réflexion. */
    private PopupWindow sheetPopupOf(EditorView view) throws Exception {
        view.setPreviewHost(new SheetStubHost());
        view.setFileName("exemple.md");
        view.setPreviewMode(EditorView.PreviewMode.SHEET_SPLIT);
        Object sheet = view.getPreviewSheet();
        assertNotNull("la feuille doit être ouverte", sheet);
        return (PopupWindow) fieldOf(sheet, "popup");
    }

    @Test
    public void feuilleApercu_tapExterieurDismiss_recetteComplete() throws Exception {
        EditorView view = newView();
        PopupWindow popup = sheetPopupOf(view);

        assertNotNull("feuille ouverte", popup);
        assertTrue("la feuille est affichée", popup.isShowing());
        assertTrue("outsideTouchable posé (ACTION_OUTSIDE)",
                popup.isOutsideTouchable());
        assertNotNull("fond NON NULL (fenêtre non-modale au toucher — "
                        + "l'autre moitié obligatoire de la recette)",
                popup.getBackground());

        view.closePreviewSheet();
        assertNull("nettoyage : feuille fermée", view.getPreviewSheet());
    }

    @Test
    public void carteAllerALigne_tapExterieurDismiss_recetteComplete() throws Exception {
        EditorView view = newView();
        view.showGoToLine();
        assertTrue("carte visible", view.isGoToLineVisible());

        // Réflexion : EditorView.popupManager (package) → goToLine (privé)
        // → goToLinePopup (package).
        Object manager = fieldOf(view, "popupManager");
        Object goToLine = fieldOf(manager, "goToLine");
        PopupWindow popup = (PopupWindow) fieldOf(goToLine, "goToLinePopup");
        assertNotNull("PopupWindow de la carte", popup);

        assertTrue("outsideTouchable posé (ACTION_OUTSIDE)",
                popup.isOutsideTouchable());
        assertNotNull("fond NON NULL (fenêtre non-modale au toucher)",
                popup.getBackground());
        assertTrue("la carte reste focusable (saisie clavier intacte)",
                popup.isFocusable());

        view.dismissGoToLine();
        assertFalse("nettoyage : carte fermée", view.isGoToLineVisible());
    }

    private static Object fieldOf(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** Hôte .md minimal (repli canvas — le corps n'a aucune importance
     *  pour la recette de fenêtre testée ici). */
    private static class SheetStubHost implements jo.codeeditor.view.preview.EditorPreviewHost {
        @Override public boolean canPreview(String fileName) {
            return fileName != null && fileName.toLowerCase().endsWith(".md");
        }
        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) {}
        @Override public void onPreviewContentChanged(CharSequence text) {}
        @Override public void drawPreview(android.graphics.Canvas canvas,
                float offsetX, float offsetY) {}
        @Override public boolean hasPreviewContent() { return false; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }
    }
}
