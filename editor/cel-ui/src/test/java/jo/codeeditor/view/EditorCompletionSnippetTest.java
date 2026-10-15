package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.KeyEvent;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * L1/D-01 — acceptation des complétions enrichies côté vue :
 * expansion des snippets (texte apuré, premier champ sélectionné,
 * navigation Tab), respect exact de la plage de remplacement
 * {@code textEdit} (sémantique remplacement — pas de
 * « console.console.log »), comportement historique sans plage, et
 * acceptation en document XML (non-régression de la porte de contexte
 * famille XML, zone fragile récemment corrigée).
 *
 * <p>Rouge avant : l'acceptation insérait {@code item.insertText} brut
 * sur {@code [tokenStart, caret)} — les marqueurs $1/${1:ph} atterrissaient
 * littéralement dans le document et la plage du serveur était ignorée.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorCompletionSnippetTest {

    private EditorView newView(EditorSession session) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(session);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private static CompletionSession.Item item(String label, String insertText,
                                               boolean isSnippet) {
        return new CompletionSession.Item(label, "détail", insertText, "m",
                2, 10, false, isSnippet, null);
    }

    private static CompletionSession.Item itemAvecPlage(String label, String insertText,
                                                        int start, int end) {
        return new CompletionSession.Item(label, "détail", insertText, "m",
                2, 10, false, false, null, null, null, start, end, null);
    }

    // ── Expansion de snippet à l'acceptation ─────────────────────

    @Test
    public void accept_snippet_expansionSansMarqueursRésiduels() {
        EditorSession session = new EditorSession(EditorDocument.of("int a = "));
        session.setSelection(8);
        EditorView view = newView(session);
        view.setCompletionItems(java.util.Collections.singletonList(
                item("appel", "appel(${1:arg}, $2)$0", true)), 8, "");
        assertTrue(view.isCompletionVisible());

        assertTrue(view.completionAccept());

        assertEquals("texte apuré — aucun $ résiduel", "int a = appel(arg, )", session.getDocument().getText());
        assertTrue("session de snippet posée", session.snippets.isActive());
        // Premier champ $1 « arg » sélectionné : [14, 17).
        assertEquals(14, session.getSelection().start);
        assertEquals(17, session.getSelection().end);
    }

    @Test
    public void accept_snippet_navigationTabDepuisLeClavier() {
        EditorSession session = new EditorSession(EditorDocument.of(""));
        session.setSelection(0);
        EditorView view = newView(session);
        view.setCompletionItems(java.util.Collections.singletonList(
                item("sout", "System.out.println($1);$0", true)), 0, "");

        assertTrue(view.completionAccept());
        assertEquals("System.out.println();", session.getDocument().getText());
        assertEquals("champ $1 entre les parenthèses", 19, session.getSelection().start);
        assertTrue(session.snippets.isActive());

        // Tab navigue vers $0 : session close, caret final.
        KeyEvent tab = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB);
        assertTrue("Tab est consommé par la navigation de snippet",
                view.onKeyDown(KeyEvent.KEYCODE_TAB, tab));
        assertFalse(session.snippets.isActive());
        assertEquals("caret sur $0 (après le ;)", 21, session.getSelection().start);
    }

    // ── Plage de remplacement textEdit (sémantique remplacement) ─

    @Test
    public void accept_plageDeRemplacement_appliquéeExactement() {
        // Le serveur remplace TOUT « console.con » (préfixe tapé inclus)
        // par « console.log( » — la plage couvre le texte AVANT le token.
        EditorSession session = new EditorSession(EditorDocument.of("console.con"));
        session.setSelection(11); // caret après « con »
        EditorView view = newView(session);
        // tokenStart du popup = 8 (« con »), mais la plage du serveur
        // couvre [0, 11).
        view.setCompletionItems(java.util.Collections.singletonList(
                itemAvecPlage("log", "console.log(", 0, 11)), 8, "con");

        assertTrue(view.completionAccept());

        assertEquals("la plage du serveur est appliquée EXACTEMENT — jamais le token "
                        + "ET la plage (c'est la double application qui produit "
                        + "« console.console.log »)",
                "console.log(", session.getDocument().getText());
    }

    @Test
    public void accept_sansPlage_comportementHistoriqueDuToken() {
        EditorSession session = new EditorSession(EditorDocument.of("int fo"));
        session.setSelection(6);
        EditorView view = newView(session);
        view.setCompletionItems(java.util.Collections.singletonList(
                item("foobar", "foobar", false)), 4, "fo");

        assertTrue(view.completionAccept());

        assertEquals("sans plage : remplacement du token [tokenStart, caret)", "int foobar", session.getDocument().getText());
    }

    @Test
    public void accept_plageAvantLeCaret_aussiAppliquée() {
        // Plage entièrement AVANT le caret (serveur qui étend une
        // édition au delà du token) : la plage reste la référence.
        EditorSession session = new EditorSession(EditorDocument.of("foo.bar.con"));
        session.setSelection(11);
        EditorView view = newView(session);
        view.setCompletionItems(java.util.Collections.singletonList(
                itemAvecPlage("log", "log", 4, 11)), 8, "con");

        assertTrue(view.completionAccept());
        assertEquals("foo.log", session.getDocument().getText());
    }

    // ── Non-régression porte de contexte famille XML (N-2) ────────

    @Test
    public void accept_snippet_enDocumentXml_porteContexteÉcartée() {
        // N-2 : la zone complétion est active et fragile — l'acceptation
        // d'un snippet en document XML (famille écartée de la porte
        // chaîne/commentaire) doit fonctionner comme en Java.
        EditorSession session = new EditorSession(
                EditorDocument.of("<Button android:text=\"\""));
        session.setLanguage("xml");
        session.setSelection(23);
        EditorView view = newView(session);
        view.setCompletionItems(java.util.Collections.singletonList(
                item("label", "${1:Bonjour}.$0", true)), 23, "");

        assertTrue(view.completionAccept());

        assertEquals("snippet accepté en document XML (aucun marqueur résiduel)",
                "<Button android:text=\"\"Bonjour.", session.getDocument().getText());
        assertTrue(session.snippets.isActive());
    }
}
