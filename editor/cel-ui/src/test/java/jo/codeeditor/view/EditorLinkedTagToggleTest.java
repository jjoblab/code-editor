package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * BASCULE DU RENOMMAGE LIÉ DES BALISES XML VUE PAR LA VUE (4.6.0) :
 * le délégué setLinkedTagEditsEnabled/isLinkedTagEditsEnabled porte
 * la bascule jusqu'à la session, qui possède le chemin d'édition —
 * fermée, la frappe dans un nom de balise ouvrante ne se reflète plus
 * sur la fermante, ET le délégué ferme l'écart avec les autres
 * réglages comportementaux de la vue.
 */
@RunWith(RobolectricTestRunner.class)
public class EditorLinkedTagToggleTest {

    private EditorView viewXml(String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setLanguage("xml");
        session.setSelection(doc.length());
        view.setSession(session);
        return view;
    }

    @Test
    public void delegue_atteintLaSession() {
        EditorView view = viewXml("<div></div>");
        assertTrue(view.isLinkedTagEditsEnabled());
        view.setLinkedTagEditsEnabled(false);
        assertFalse(view.isLinkedTagEditsEnabled());
        assertFalse(view.session.isLinkedTagEditsEnabled());
        view.setLinkedTagEditsEnabled(true);
        assertTrue(view.session.isLinkedTagEditsEnabled());
    }

    @Test
    public void fermee_frappeSansMiroir_leTexteSuit() {
        EditorView view = viewXml("<di></di>");
        view.setLinkedTagEditsEnabled(false);
        view.session.setSelection(3); // caret après « di »
        view.session.typeChar('v');
        assertEquals("<div></di>", view.session.getText());
    }

    @Test
    public void activee_leMiroirEstToujoursLà() {
        // Le défaut est SANS changement pour l'hôte : le comportement
        // 4.5.0 (miroir actif) reste la configuration de départ.
        EditorView view = viewXml("<di></di>");
        view.session.setSelection(3);
        view.session.typeChar('v');
        assertEquals("<div></div>", view.session.getText());
    }
}
