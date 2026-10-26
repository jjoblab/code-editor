package jo.codeeditor.view.popup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import static org.junit.Assert.*;

/**
 * La carte des variables de choix (4.5.0) : ouverte quand le champ
 * courant d'une session de snippet porte des options, une option
 * toucheuse REMPLACE le champ et ADVANCE (le champ suivant portant
 * lui-même des choix rouvre sa carte), la fin de session referme.
 */
@RunWith(RobolectricTestRunner.class)
public class SnippetChoicePopupTest {

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

    @Test
    public void champLibre_aucuneCarte() {
        EditorView view = newView("");
        view.session.snippets.apply(0, 0, "a $1 b $0");
        view.popupManager.showSnippetChoicesIfNeeded();
        assertFalse(view.popupManager.snippetChoices.isShowing());
        assertEquals(0, view.popupManager.snippetChoices.optionCount());
    }

    @Test
    public void champDeChoix_carteOuverte_optionsProposées() {
        EditorView view = newView("");
        view.session.snippets.apply(0, 0, "${1|val,var|} = $2;");
        view.popupManager.showSnippetChoicesIfNeeded();
        assertTrue(view.popupManager.snippetChoices.isShowing());
        assertEquals(2, view.popupManager.snippetChoices.optionCount());
    }

    @Test
    public void optionToucheuse_remplaceLeChamp_etAvance() {
        EditorView view = newView("");
        // Champs successifs : le 1 porte des choix, le 2 aussi —
        // l'option toucheuse remplace « val » puis la carte du champ 2
        // se rouvre.
        view.session.snippets.apply(0, 0, "${1|val,var|} = ${2|int,str|};");
        view.popupManager.showSnippetChoicesIfNeeded();
        assertTrue(view.popupManager.snippetChoices.isShowing());

        view.popupManager.snippetChoices.choose("var");

        assertEquals("var = int;", view.session.getText());
        // Avancé au champ 2, sélectionné, sa carte ouverte.
        assertTrue(view.session.snippets.isActive());
        assertEquals(2, view.session.snippets.get().getCurrentIndex());
        assertTrue(view.popupManager.snippetChoices.isShowing());
        assertEquals(2, view.popupManager.snippetChoices.optionCount());
    }

    @Test
    public void optionToucheure_surDernierChamp_sessionTerminéeProprement() {
        EditorView view = newView("");
        // Un seul champ de choix, suivi de l'arrêt final $0 : le choix
        // franchit $0 — caret posé, session close, carte refermée.
        view.session.snippets.apply(0, 0, "let ${1|x,y|} = $0;");
        view.popupManager.showSnippetChoicesIfNeeded();
        view.popupManager.snippetChoices.choose("y");
        assertEquals("let y = ;", view.session.getText());
        assertFalse(view.session.snippets.isActive());
        assertFalse(view.popupManager.snippetChoices.isShowing());
    }

    @Test
    public void choix_miroirsSuivent() {
        EditorView view = newView("");
        // Le champ 1 est répété (miroir) : choisir une option propage
        // le texte aux miroirs par le pipeline de session.
        view.session.snippets.apply(0, 0, "${1|alpha,beta|} + $1");
        view.popupManager.showSnippetChoicesIfNeeded();
        view.popupManager.snippetChoices.choose("beta");
        assertEquals("beta + beta", view.session.getText());
    }

    @Test
    public void acceptationDeGabarit_ouvreLaCarteAutomatiquement() {
        // Voie complète : un item de complétion snippet accepté dont
        // le premier champ porte des choix → la carte s'ouvre toute
        // seule (complétionAccept → showSnippetChoicesIfNeeded).
        EditorView view = newView("log");
        view.setCompletionItems(java.util.Collections.singletonList(
                new CompletionSession.Item("log", "gabarit",
                        "System.out.println(${1|msg,err|});", "s", 15, 0, false, true)),
                0, "log");
        assertTrue(view.completionAccept());
        assertEquals("System.out.println(msg);", view.session.getText());
        assertTrue(view.popupManager.snippetChoices.isShowing());
        assertEquals(2, view.popupManager.snippetChoices.optionCount());
    }

    @Test
    public void finDeSession_refermeLaCarte() {
        EditorView view = newView("");
        view.session.snippets.apply(0, 0, "${1|val,var|}");
        view.popupManager.showSnippetChoicesIfNeeded();
        assertTrue(view.popupManager.snippetChoices.isShowing());
        // La session se termine (undo/redo/échapp côté hôte) : la
        // notification de texte referme la carte.
        view.session.snippets.end();
        view.onTextChanged();
        assertFalse(view.popupManager.snippetChoices.isShowing());
    }
}
