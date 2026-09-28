package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * La loupe doit retomber (magnifierActive = false) dans TOUS les chemins
 * de fin de geste ou d'interruption : UP/CANCEL (déjà couverts par
 * EditorMagnifierHandleDragTest), mais aussi perte de focus,
 * changement de session (fichier/onglet) et détachement de la vue.
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierStateFallbackTest {

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("int x = 1;\n")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test
    public void setSession_extinguishesMagnifierAndHandleDrag() {
        EditorView view = newView();
        view.magnifierActive = true;
        view.handleDragMode = 2;
        view.setSession(new EditorSession(EditorDocument.of("other();\n")));
        assertFalse("un changement de session doit éteindre la loupe",
                view.magnifierActive);
        assertEquals("un changement de session doit terminer le drag de poignée",
                0, view.handleDragMode);
    }

    @Test
    public void focusLoss_extinguishesMagnifier() {
        EditorView view = newView();
        view.magnifierActive = true;
        view.onFocusChanged(false, 0, null);
        assertFalse("la perte de focus doit éteindre la loupe",
                view.magnifierActive);
    }

    @Test
    public void detach_extinguishesMagnifier() {
        EditorView view = newView();
        view.magnifierActive = true;
        view.onDetachedFromWindow();
        assertFalse("le détachement de la vue doit éteindre la loupe",
                view.magnifierActive);
    }
}
