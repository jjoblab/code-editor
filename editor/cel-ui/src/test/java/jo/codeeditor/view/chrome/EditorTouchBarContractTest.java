package jo.codeeditor.view.chrome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.SelectionAction;

import static org.junit.Assert.*;

/**
 * Contrat de la barre de touches de code (4.5.0) : rangée par défaut
 * posée au bind (Tab, commentaire, déplacements, duplication),
 * remplacement par les actions de l'hôte, tap qui EXÉCUTE l'action
 * sans prendre le focus, états activés estompés et rejetés, hauteur
 * imposée.
 */
@RunWith(RobolectricTestRunner.class)
public class EditorTouchBarContractTest {

    private EditorView newView(String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setSelection(doc.length());
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private static TextView keyAtIndex(EditorTouchBar bar, int index) {
        // La rangée vit dans le scroller : premier enfant du scroller.
        android.view.ViewGroup scroller = (android.view.ViewGroup) bar.getChildAt(0);
        LinearLayout row = (LinearLayout) scroller.getChildAt(0);
        return (TextView) row.getChildAt(index);
    }

    @Test
    public void bind_poseLesCinqTouchesUsuelles() {
        EditorView view = newView("let x = 1;");
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        bar.bind(view);
        assertEquals(5, bar.getActions().size());
        assertEquals("Tab", bar.getActions().get(0).label);
        // La rangée a bien cinq touches.
        android.view.ViewGroup scroller = (android.view.ViewGroup) bar.getChildAt(0);
        assertEquals(5, ((LinearLayout) scroller.getChildAt(0)).getChildCount());
    }

    @Test
    public void toucheTab_indenteLaLigneCourante() {
        EditorView view = newView("let x = 1;");
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        bar.bind(view);
        keyAtIndex(bar, 0).performClick();
        // L'unité d'indentation détectée (4 espaces par défaut) préfixe
        // la ligne.
        assertEquals("    let x = 1;", view.session.getText());
    }

    @Test
    public void toucheDupliquer_dupliqueLaLigne() {
        EditorView view = newView("ligne");
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        bar.bind(view);
        keyAtIndex(bar, 4).performClick();
        // La ligne entière est dupliquée (avec son retour à la ligne).
        assertEquals("ligne\nligne", view.session.getText());
    }

    @Test
    public void actionsHôte_remplacementComplet() {
        EditorView view = newView("x");
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        bar.bind(view);
        AtomicInteger taps = new AtomicInteger();
        SelectionAction maison = new SelectionAction("hote.mienne", "Ma touche",
                taps::incrementAndGet);
        bar.setActions(Arrays.asList(maison, null)); // null ignoré
        assertEquals(1, bar.getActions().size());

        keyAtIndex(bar, 0).performClick();
        assertEquals(1, taps.get());
        // Le tap tactile passe par performClick (même chemin TalkBack).
        keyAtIndex(bar, 0).dispatchTouchEvent(MotionEvent.obtain(
                0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0));
        keyAtIndex(bar, 0).dispatchTouchEvent(MotionEvent.obtain(
                0, 10, MotionEvent.ACTION_UP, 0, 0, 0));
        assertEquals(2, taps.get());
    }

    @Test
    public void actionDésactivée_tapRejetéToucheEstompée() {
        EditorView view = newView("x");
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        AtomicInteger taps = new AtomicInteger();
        bar.setActions(Collections.singletonList(new SelectionAction(
                "hote.bloquee", "Bloquée", taps::incrementAndGet, () -> false)));
        // refreshEnabled est appelé par setActions.
        TextView key = keyAtIndex(bar, 0);
        assertFalse(key.isEnabled());
        key.performClick();
        assertEquals("le tap d'une touche estompée est rejeté", 0, taps.get());
    }

    @Test
    public void listeVide_barreVide_sansCrash() {
        EditorView view = newView("x");
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        bar.bind(view);
        bar.setActions(Collections.emptyList());
        assertTrue(bar.getActions().isEmpty());
        bar.setActions(null);
        assertTrue(bar.getActions().isEmpty());
    }

    @Test
    public void hauteurImposee() {
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        bar.measure(1080, 1920);
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(44 * density, bar.getMeasuredHeight(), 1f);
    }

    @Test
    public void étatsActivés_rafraîchissablesParLhôte() {
        EditorView view = newView("x");
        EditorTouchBar bar = new EditorTouchBar(RuntimeEnvironment.getApplication());
        AtomicInteger enabled = new AtomicInteger(1);
        bar.setActions(Collections.singletonList(new SelectionAction(
                "hote.variable", "Var", () -> { }, () -> enabled.get() == 1)));
        assertTrue(keyAtIndex(bar, 0).isEnabled());
        enabled.set(0);
        bar.refreshEnabled();
        assertFalse(keyAtIndex(bar, 0).isEnabled());
    }
}
