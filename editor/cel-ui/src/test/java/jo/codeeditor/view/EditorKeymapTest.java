package jo.codeeditor.view;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * v3.36.0 — Tests de la keymap data-driven rebindable (roadmap item 6,
 * portage {@code EditorKeymap}/{@code EditorCommands} de CodeAssist v3.20).
 *
 * <p>Couvrent : la table par défaut (portage verbatim des cascades
 * v3.35.0), les 4 passes de résolution des modificateurs (exact → sans
 * ctrl → sans shift → sans aucun — préserve les habitudes Ctrl+flèches,
 * Shift+Enter, Ctrl+Shift+A), le rebind/unbind, et l'immuabilité de la
 * table par défaut (defaults() retourne une instance neuve).</p>
 *
 * <p>Test JVM pur : seules les constantes de {@link KeyEvent} sont
 * utilisées (aucun appel framework).</p>
 *
 * @since v3.36.0
 */
public class EditorKeymapTest {

    // ── Default table: verbatim port of the v3.35.0 cascades ───────

    @Test
    public void defaults_ctrlShortcutsResolve() {
        EditorKeymap km = EditorKeymap.defaults();
        assertEquals(EditorCommands.UNDO, km.resolve(KeyEvent.KEYCODE_Z, true, false).command);
        assertEquals(EditorCommands.REDO, km.resolve(KeyEvent.KEYCODE_Y, true, false).command);
        assertEquals(EditorCommands.SELECT_ALL, km.resolve(KeyEvent.KEYCODE_A, true, false).command);
        assertEquals(EditorCommands.COPY, km.resolve(KeyEvent.KEYCODE_C, true, false).command);
        assertEquals(EditorCommands.CUT, km.resolve(KeyEvent.KEYCODE_X, true, false).command);
        assertEquals(EditorCommands.PASTE, km.resolve(KeyEvent.KEYCODE_V, true, false).command);
        assertEquals(EditorCommands.DUPLICATE, km.resolve(KeyEvent.KEYCODE_D, true, false).command);
        assertEquals(EditorCommands.FIND, km.resolve(KeyEvent.KEYCODE_F, true, false).command);
        assertEquals(EditorCommands.SAVE, km.resolve(KeyEvent.KEYCODE_S, true, false).command);
        assertEquals(EditorCommands.TRIGGER_COMPLETION,
                km.resolve(KeyEvent.KEYCODE_SPACE, true, false).command);
        assertEquals(EditorCommands.TRIGGER_SIGNATURE_HELP,
                km.resolve(KeyEvent.KEYCODE_P, true, false).command);
        assertEquals(EditorCommands.CODE_ACTIONS,
                km.resolve(KeyEvent.KEYCODE_PERIOD, true, false).command);
        assertEquals(EditorCommands.GO_TO_SYMBOL,
                km.resolve(KeyEvent.KEYCODE_O, true, true).command);
        assertEquals(EditorCommands.FORMAT_DOCUMENT,
                km.resolve(KeyEvent.KEYCODE_I, true, true).command);
        assertEquals(EditorCommands.CODE_ACTIONS_AT_CARET,
                km.resolve(KeyEvent.KEYCODE_L, true, true).command);
        assertEquals(EditorCommands.GO_TO_LINE,
                km.resolve(KeyEvent.KEYCODE_G, true, false).command);
    }

    @Test
    public void defaults_zoomResolvesOnAllThreeKeys() {
        EditorKeymap km = EditorKeymap.defaults();
        assertEquals(EditorCommands.ZOOM_IN, km.resolve(KeyEvent.KEYCODE_EQUALS, true, false).command);
        assertEquals(EditorCommands.ZOOM_IN, km.resolve(KeyEvent.KEYCODE_PLUS, true, false).command);
        assertEquals(EditorCommands.ZOOM_IN, km.resolve(KeyEvent.KEYCODE_NUMPAD_ADD, true, false).command);
        assertEquals(EditorCommands.ZOOM_OUT, km.resolve(KeyEvent.KEYCODE_MINUS, true, false).command);
        assertEquals(EditorCommands.ZOOM_OUT, km.resolve(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, true, false).command);
        assertEquals(EditorCommands.ZOOM_RESET, km.resolve(KeyEvent.KEYCODE_0, true, false).command);
    }

    @Test
    public void defaults_editingAndMovementResolve() {
        EditorKeymap km = EditorKeymap.defaults();
        assertEquals(EditorCommands.BACKSPACE, km.resolve(KeyEvent.KEYCODE_DEL, false, false).command);
        assertEquals(EditorCommands.DELETE_FORWARD,
                km.resolve(KeyEvent.KEYCODE_FORWARD_DEL, false, false).command);
        assertEquals(EditorCommands.NEW_LINE, km.resolve(KeyEvent.KEYCODE_ENTER, false, false).command);
        assertEquals(EditorCommands.NEW_LINE, km.resolve(KeyEvent.KEYCODE_NUMPAD_ENTER, false, false).command);
        assertEquals(EditorCommands.NEW_LINE, km.resolve(KeyEvent.KEYCODE_DPAD_CENTER, false, false).command);
        assertEquals(EditorCommands.INDENT, km.resolve(KeyEvent.KEYCODE_TAB, false, false).command);
        assertEquals(EditorCommands.DEDENT, km.resolve(KeyEvent.KEYCODE_TAB, false, true).command);
        assertEquals(EditorCommands.INSERT_SPACE, km.resolve(KeyEvent.KEYCODE_SPACE, false, false).command);

        assertEquals(EditorCommands.MOVE_LEFT, km.resolve(KeyEvent.KEYCODE_DPAD_LEFT, false, false).command);
        assertEquals(EditorCommands.EXTEND_LEFT, km.resolve(KeyEvent.KEYCODE_DPAD_LEFT, false, true).command);
        assertEquals(EditorCommands.MOVE_UP, km.resolve(KeyEvent.KEYCODE_DPAD_UP, false, false).command);
        assertEquals(EditorCommands.EXTEND_DOWN, km.resolve(KeyEvent.KEYCODE_DPAD_DOWN, false, true).command);
        assertEquals(EditorCommands.LINE_START, km.resolve(KeyEvent.KEYCODE_MOVE_HOME, false, false).command);
        assertEquals(EditorCommands.EXTEND_LINE_END, km.resolve(KeyEvent.KEYCODE_MOVE_END, false, true).command);
        assertEquals(EditorCommands.PAGE_UP, km.resolve(KeyEvent.KEYCODE_PAGE_UP, false, false).command);
        assertEquals(EditorCommands.EXTEND_PAGE_DOWN, km.resolve(KeyEvent.KEYCODE_PAGE_DOWN, false, true).command);

        assertEquals(EditorCommands.QUICK_DOC, km.resolve(KeyEvent.KEYCODE_F1, false, false).command);
        assertEquals(EditorCommands.RENAME, km.resolve(KeyEvent.KEYCODE_F2, false, false).command);
        assertEquals(EditorCommands.GO_TO_DEFINITION, km.resolve(KeyEvent.KEYCODE_F12, false, false).command);
        assertEquals(EditorCommands.FIND_REFERENCES, km.resolve(KeyEvent.KEYCODE_F12, false, true).command);
    }

    @Test
    public void defaults_unboundKeysResolveToNull() {
        EditorKeymap km = EditorKeymap.defaults();
        // Plain letters are not commands (printable fall-through).
        assertNull(km.resolve(KeyEvent.KEYCODE_A, false, false));
        assertNull(km.resolve(KeyEvent.KEYCODE_G, false, false));
        assertNull(km.resolve(KeyEvent.KEYCODE_Z, false, false));
        // Ctrl+O alone (without shift) is not bound.
        assertNull(km.resolve(KeyEvent.KEYCODE_O, true, false));
    }

    // ── Modifier fallback passes (legacy habit preservation) ────────

    @Test
    public void resolve_fallbackPasses_preserveLegacyModifierHabits() {
        EditorKeymap km = EditorKeymap.defaults();
        // Pass 2 (drop ctrl, keep shift): Ctrl+Left still moves the caret
        // (v3.35.0 movement switch ignored ctrl).
        assertEquals(EditorCommands.MOVE_LEFT, km.resolve(KeyEvent.KEYCODE_DPAD_LEFT, true, false).command);
        assertEquals(EditorCommands.EXTEND_LEFT, km.resolve(KeyEvent.KEYCODE_DPAD_LEFT, true, true).command);
        // Ctrl+Tab still indents (v3.35.0 behavior).
        assertEquals(EditorCommands.INDENT, km.resolve(KeyEvent.KEYCODE_TAB, true, false).command);
        // Pass 3 (keep ctrl, drop shift): Ctrl+Shift+A still selects all
        // (the v3.35.0 ctrl-switch ignored shift).
        assertEquals(EditorCommands.SELECT_ALL, km.resolve(KeyEvent.KEYCODE_A, true, true).command);
        assertEquals(EditorCommands.UNDO, km.resolve(KeyEvent.KEYCODE_Z, true, true).command);
        // Pass 4 (drop both): Shift+Enter still inserts a newline.
        assertEquals(EditorCommands.NEW_LINE, km.resolve(KeyEvent.KEYCODE_ENTER, false, true).command);
        assertEquals(EditorCommands.BACKSPACE, km.resolve(KeyEvent.KEYCODE_DEL, false, true).command);
        // Exact matches ALWAYS win over fallbacks — Ctrl+Shift+O is
        // go-to-symbol, not "move" anything.
        assertEquals(EditorCommands.GO_TO_SYMBOL, km.resolve(KeyEvent.KEYCODE_O, true, true).command);
        // Shift+Tab is DEDENT (exact), never the fallback INDENT.
        assertEquals(EditorCommands.DEDENT, km.resolve(KeyEvent.KEYCODE_TAB, false, true).command);
        // Alt is a don't-care: Alt+Left behaves like Left.
        assertEquals(EditorCommands.MOVE_LEFT, km.resolve(KeyEvent.KEYCODE_DPAD_LEFT, false, false).command);
    }

    // ── Rebinding ──────────────────────────────────────────────────

    @Test
    public void bind_rebindsCommands() {
        EditorKeymap km = EditorKeymap.defaults();
        // Ctrl+Shift+Z = redo (IntelliJ convention).
        km.bind(EditorCommands.REDO, KeyEvent.KEYCODE_Z, true, true);
        assertEquals(EditorCommands.REDO, km.resolve(KeyEvent.KEYCODE_Z, true, true).command);
        // The historical Ctrl+Y still works.
        assertEquals(EditorCommands.REDO, km.resolve(KeyEvent.KEYCODE_Y, true, false).command);

        // Rebinding the SAME key+mods replaces the previous command.
        km.bind(EditorCommands.SAVE, KeyEvent.KEYCODE_Z, true, true);
        assertEquals(EditorCommands.SAVE, km.resolve(KeyEvent.KEYCODE_Z, true, true).command);
        // Other commands untouched.
        assertTrue(km.isBound(EditorCommands.FORMAT_DOCUMENT));
        assertNotNull(km.bindingFor(EditorCommands.FORMAT_DOCUMENT));
    }

    @Test
    public void unbind_removesEveryKeyOfTheCommand() {
        EditorKeymap km = EditorKeymap.defaults();
        assertTrue(km.isBound(EditorCommands.ZOOM_IN));
        assertNotNull(km.resolve(KeyEvent.KEYCODE_EQUALS, true, false));
        km.unbind(EditorCommands.ZOOM_IN);
        assertFalse(km.isBound(EditorCommands.ZOOM_IN));
        assertNull(km.resolve(KeyEvent.KEYCODE_EQUALS, true, false));
        assertNull(km.resolve(KeyEvent.KEYCODE_PLUS, true, false));
        assertNull(km.resolve(KeyEvent.KEYCODE_NUMPAD_ADD, true, false));
        // Other commands untouched.
        assertEquals(EditorCommands.ZOOM_OUT, km.resolve(KeyEvent.KEYCODE_MINUS, true, false).command);
    }

    @Test
    public void defaults_returnsFreshInstances() {
        EditorKeymap a = EditorKeymap.defaults();
        EditorKeymap b = EditorKeymap.defaults();
        assertNotSame(a, b);
        a.unbind(EditorCommands.UNDO);
        assertFalse(a.isBound(EditorCommands.UNDO));
        assertTrue("defaults() must not share state", b.isBound(EditorCommands.UNDO));
    }

    @Test
    public void emptyKeymap_resolvesNothing() {
        EditorKeymap km = new EditorKeymap();
        assertNull(km.resolve(KeyEvent.KEYCODE_Z, true, false));
        assertNull(km.resolve(KeyEvent.KEYCODE_ENTER, false, false));
        assertEquals(0, km.bindings().size());
    }

    @Test
    public void bindingFor_exposesPrimaryBinding() {
        EditorKeymap km = EditorKeymap.defaults();
        EditorKeymap.Binding b = km.bindingFor(EditorCommands.NEW_LINE);
        assertNotNull(b);
        assertEquals(KeyEvent.KEYCODE_ENTER, b.keyCode);
        assertEquals(EditorCommands.NEW_LINE, b.command);
        assertNull(km.bindingFor("editor.nonexistent"));
    }

    @Test
    public void binding_equalityAndHashCode() {
        EditorKeymap.Binding b1 = new EditorKeymap.Binding("cmd", 21, true, false);
        EditorKeymap.Binding b2 = new EditorKeymap.Binding("cmd", 21, true, false);
        EditorKeymap.Binding b3 = new EditorKeymap.Binding("cmd", 21, false, false);
        assertEquals(b1, b2);
        assertEquals(b1.hashCode(), b2.hashCode());
        assertNotEquals(b1, b3);
        assertNotEquals(b1, null);
        assertNotEquals(b1, "cmd");
        assertNotNull(b1.toString());
    }
}
