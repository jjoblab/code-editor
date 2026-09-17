package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorCommands;
import jo.codeeditor.view.EditorView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.KeyEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Tests des chords (séquences à deux touches) dans le
 * {@link EditorKeyHandler} : armement du pending, complétion, annulation
 * par Escape, expiration après 2 s, et repli de la touche non-chord sur
 * le dispatch single-key normal.
 *
 * <p>L'horloge du handler est remplacée par un temps figé avançable
 * (réflexion sur le champ package-private {@code clock}) pour tester
 * l'expiration déterministe du pending sans Handler réel — zéro fuite
 * par design (le timeout est un timestamp, pas un postDelayed).</p>
 *
 * <p>Robolectric est requis pour instancier {@link EditorView} ; les
 * KeyEvent sont construites avec {@link KeyEvent#META_CTRL_ON}.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorChordKeyHandlerTest {

    /** Temps figé (ms) — avancé manuellement par les tests d'expiration. */
    private long fakeTime = 10_000L;

    private EditorView newView() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("hello world"));
        session.setLanguage("java");
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        // Injecte l'horloge déterministe dans le key handler (privé).
        Field f = EditorView.class.getDeclaredField("keyHandler");
        f.setAccessible(true);
        EditorKeyHandler handler = (EditorKeyHandler) f.get(view);
        handler.clock = () -> fakeTime;
        return view;
    }

    private static KeyEvent ctrl(int keyCode) {
        return new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON);
    }

    private static KeyEvent plain(int keyCode) {
        return new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
    }

    /** Ctrl+K → Ctrl+C = TOGGLE_LINE_COMMENT (convention IntelliJ). */
    private static void bindCommentChord(EditorView view) {
        EditorKeymap km = EditorKeymap.defaults()
            .bindChord(EditorCommands.TOGGLE_LINE_COMMENT,
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_K, true, false),
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_C, true, false));
        view.setKeymap(km);
    }

    @Test
    public void chordSequence_executesCommand() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        // Première frappe : consommée, arme l'état pending, texte intact.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertEquals("hello world", view.getSession().getText());
        // Deuxième frappe : complète le chord → commentaire de ligne basculé.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("// hello world", view.getSession().getText());
        // Aller-retour : rejouer le chord décommente.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void chordEscape_cancelsPending() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // Escape annule le chord pending (consommé).
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, plain(KeyEvent.KEYCODE_ESCAPE)));
        assertEquals("hello world", view.getSession().getText());
        // Ctrl+C redevient un simple COPY — le texte ne doit PAS changer.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void chordExpiry_fallsBackToSingleKey() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // Avance au-delà de la durée de vie de 2 s du pending.
        fakeTime += 3_000;
        // L'état pending a expiré : Ctrl+C se résout en simple COPY.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void incompleteChord_fallsThroughToSingleKey() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // Ctrl+A n'est pas la deuxième frappe de ce chord — il doit être
        // traité comme une frappe neuve : SELECT_ALL s'exécute.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_A, ctrl(KeyEvent.KEYCODE_A)));
        assertEquals(0, view.getSession().getSelection().start);
        assertEquals("hello world".length(), view.getSession().getSelection().end);
        // Et le texte est intact (ni commentaire, ni effet de bord du copy).
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void secondChordStart_reArmsPending() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // K n'est pas une deuxième frappe de (Ctrl+K → Ctrl+C) — il
        // retombe sur le chemin des frappes neuves, où il (RE)DÉMARRE le
        // chord.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertEquals("hello world", view.getSession().getText());
        // Le chord ré-armé se complète toujours sur Ctrl+C.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("// hello world", view.getSession().getText());
    }

    @Test
    public void noChordBound_defaultBehaviourUnchanged() throws Exception {
        // Keymap par défaut (sans chord) : Ctrl+K ne se résout en rien (ni
        // binding, ni chord) et n'est simplement pas consommé comme commande.
        EditorView view = newView();
        assertFalse(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertEquals("hello world", view.getSession().getText());
    }
}
