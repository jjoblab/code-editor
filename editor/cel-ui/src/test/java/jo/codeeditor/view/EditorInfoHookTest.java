package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * R9 (plan 09, E9 + E10) — les deux hooks hôte :
 * <ul>
 *   <li><b>E9</b> — {@link EditorView#setOnEditorInfoListener} : appelé
 *       après les valeurs par défaut de la bibliothèque, l'hôte a le
 *       dernier mot sur l'{@code EditorInfo} (ajouter
 *       NO_PERSONALIZED_LEARNING, une action d'entrée personnalisée, un
 *       type dédié…) sans toucher la bibliothèque ;</li>
 *   <li><b>E10</b> — {@link EditorView#setOnImePrivateCommandListener} :
 *       {@code performPrivateCommand} est routé vers l'écouteur (les
 *       extensions propriétaires de certains claviers ont un canal),
 *       faux (ignoré) sans écouteur.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EditorInfoHookTest {

    private EditorView newView(Context ctx) {
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("int x = 0;")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    // ── E9 : personnalisation de l'EditorInfo ────────────────────────

    @Test
    public void hookEditorInfo_lhoteADernierMot() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newView(ctx);
        view.setOnEditorInfoListener(info -> {
            info.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            info.actionLabel = "Compiler";
            info.actionId = 42;
        });

        EditorInfo info = new EditorInfo();
        view.onCreateInputConnection(info);

        assertTrue("l'hôte a ajouté NO_PERSONALIZED_LEARNING sans toucher la bibliothèque",
                (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
        assertEquals("Compiler", info.actionLabel.toString());
        assertEquals(42, info.actionId);
        // Les valeurs par défaut de la bibliothèque restent en place.
        assertTrue("les valeurs par défaut (pas d'UI d'extraction) survivent",
                (info.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        assertEquals("la sélection initiale est celle du caret",
                0, info.initialSelStart);
    }

    @Test
    public void sansHook_EditorInfoResteCeluiDeLaBibliotheque() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newView(ctx);

        EditorInfo info = new EditorInfo();
        view.onCreateInputConnection(info);

        assertNull("pas d'action personnalisée sans hook", info.actionLabel);
        assertEquals(0, info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    }

    // ── E10 : commandes privées routées ──────────────────────────────

    @Test
    public void performPrivateCommand_routeVersLhote() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newView(ctx);
        final String[] seen = {null};
        view.setOnImePrivateCommandListener((action, data) -> {
            seen[0] = action;
            return "com.demo.CAPTURE".equals(action);
        });
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        assertTrue("la commande connue est traitée (vrai)",
                ic.performPrivateCommand("com.demo.CAPTURE", Bundle.EMPTY));
        assertEquals("com.demo.CAPTURE", seen[0]);
        assertFalse("la commande inconnue est refusée (faux)",
                ic.performPrivateCommand("com.demo.OTHER", null));
    }

    @Test
    public void performPrivateCommand_sansEcouteur_ignore() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = newView(ctx);
        InputConnection ic = view.onCreateInputConnection(new EditorInfo());

        assertNotNull(ic);
        assertFalse("sans écouteur : la base ignore et répond faux",
                ic.performPrivateCommand("com.demo.ANYTHING", null));
    }
}
