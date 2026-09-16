package jo.codeeditor.view;

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
 * v3.37.0 — Tests des chords (séquences à deux touches) dans le
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
 *
 * @since v3.37.0
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
        // Inject the deterministic clock into the (private) key handler.
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

    /** Ctrl+K → Ctrl+C = TOGGLE_LINE_COMMENT (IntelliJ convention). */
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
        // First stroke: consumed, arms the pending state, text untouched.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertEquals("hello world", view.getSession().getText());
        // Second stroke: completes the chord → line comment toggled.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("// hello world", view.getSession().getText());
        // Round-trip: replaying the chord uncomments.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void chordEscape_cancelsPending() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // Escape cancels the pending chord (consumed).
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, plain(KeyEvent.KEYCODE_ESCAPE)));
        assertEquals("hello world", view.getSession().getText());
        // Ctrl+C is now a plain COPY again — the text must NOT change.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void chordExpiry_fallsBackToSingleKey() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // Advance past the 2 s pending lifetime.
        fakeTime += 3_000;
        // The pending state expired: Ctrl+C resolves as plain COPY.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void incompleteChord_fallsThroughToSingleKey() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // Ctrl+A is not this chord's second stroke — it must be processed
        // as a fresh keystroke: SELECT_ALL runs.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_A, ctrl(KeyEvent.KEYCODE_A)));
        assertEquals(0, view.getSession().getSelection().start);
        assertEquals("hello world".length(), view.getSession().getSelection().end);
        // And the text is untouched (no comment, no copy side effect).
        assertEquals("hello world", view.getSession().getText());
    }

    @Test
    public void secondChordStart_reArmsPending() throws Exception {
        EditorView view = newView();
        bindCommentChord(view);
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        // K is not a second stroke of (Ctrl+K → Ctrl+C) — it falls through
        // to the fresh-key path, where it STARTS the chord again.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertEquals("hello world", view.getSession().getText());
        // The re-armed chord still completes on Ctrl+C.
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, ctrl(KeyEvent.KEYCODE_C)));
        assertEquals("// hello world", view.getSession().getText());
    }

    @Test
    public void noChordBound_defaultBehaviourUnchanged() throws Exception {
        // Default keymap (chord-free): Ctrl+K resolves to nothing (no
        // binding, no chord) and is simply not consumed as a command.
        EditorView view = newView();
        assertFalse(view.onKeyDown(KeyEvent.KEYCODE_K, ctrl(KeyEvent.KEYCODE_K)));
        assertEquals("hello world", view.getSession().getText());
    }
}
