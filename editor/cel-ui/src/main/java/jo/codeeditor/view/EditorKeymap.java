package jo.codeeditor.view;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v3.36.0 — Rebindable, data-driven keymap (roadmap item 6, port of
 * CodeAssist v3.20's {@code EditorKeymap}).
 *
 * <p>A keymap is an ordered table of {@link Binding}s (command id + key
 * code + ctrl/shift modifiers). {@link EditorKeyHandler} resolves each
 * hardware key event through the view's keymap and executes the matching
 * command — the v1.x cascade of {@code if}/{@code switch} blocks is gone,
 * and hosts can rebind, add or remove shortcuts at runtime:</p>
 *
 * <pre>{@code
 * EditorKeymap km = EditorKeymap.defaults()
 *         .unbind(EditorCommands.UNDO)
 *         .bind(EditorCommands.UNDO, KeyEvent.KEYCODE_BACK, false, false);
 * view.setKeymap(km);
 * }</pre>
 *
 * <h2>Resolution order</h2>
 * <p>{@link #resolve(int, boolean, boolean)} walks four passes so the
 * table can be exact while legacy modifier habits still work:</p>
 * <ol>
 *   <li>exact match (keyCode + ctrl + shift);</li>
 *   <li>ignore ctrl, keep shift — e.g. Ctrl+Left still moves the caret,
 *       Ctrl+Tab still indents;</li>
 *   <li>keep ctrl, ignore shift — e.g. Ctrl+Shift+A still selects all
 *       (the v1.x ctrl-switch ignored shift);</li>
 *   <li>ignore both — e.g. Shift+Enter still inserts a newline.</li>
 * </ol>
 * <p>Alt/meta are never part of a binding (don't-care). Chords
 * (two-key sequences, CodeAssist {@code Outcome.Pending}) are future
 * work.</p>
 *
 * <p>The default table reproduces the pre-v3.36.0
 * {@code EditorKeyHandler} behavior: Ctrl+Z/Y/A/C/X/V/D/F/S, Ctrl+Space,
 * Ctrl+P, Ctrl+., Ctrl+Shift+O/I/L, Ctrl+Plus/Minus/0, Ctrl+G,
 * Backspace/Delete/Enter/Tab/Space, arrows + Home/End/PageUp/PageDown
 * (Shift = extend), F1/F2/F12 (Shift+F12 = references).</p>
 *
 * <p>Not thread-safe — read and mutate on the UI thread, then hand the
 * instance to {@code EditorView.setKeymap}.</p>
 *
 * @since v3.36.0
 */
public final class EditorKeymap {

    /** One table row: {@code command} bound to {@code keyCode} + modifiers. */
    public static final class Binding {
        public final String command;
        public final int keyCode;
        public final boolean ctrl;
        public final boolean shift;

        public Binding(String command, int keyCode, boolean ctrl, boolean shift) {
            this.command = command;
            this.keyCode = keyCode;
            this.ctrl = ctrl;
            this.shift = shift;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Binding)) return false;
            Binding b = (Binding) o;
            return keyCode == b.keyCode && ctrl == b.ctrl && shift == b.shift
                    && command.equals(b.command);
        }

        @Override
        public int hashCode() {
            int h = command.hashCode();
            h = 31 * h + keyCode;
            h = 31 * h + (ctrl ? 1 : 0);
            h = 31 * h + (shift ? 1 : 0);
            return h;
        }

        @Override
        public String toString() {
            return command + "@" + KeyEvent.keyCodeToString(keyCode)
                    + (ctrl ? "+ctrl" : "") + (shift ? "+shift" : "");
        }
    }

    private final List<Binding> table = new ArrayList<>();

    /** An empty keymap (every key falls through to the printable path). */
    public EditorKeymap() {}

    /**
     * The default table — a verbatim port of the pre-v3.36.0
     * {@code EditorKeyHandler} switch cascades.
     */
    public static EditorKeymap defaults() {
        EditorKeymap km = new EditorKeymap();
        // ── History ───────────────────────────────────────────────
        km.bind(EditorCommands.UNDO, KeyEvent.KEYCODE_Z, true, false);
        km.bind(EditorCommands.REDO, KeyEvent.KEYCODE_Y, true, false);
        // ── Selection / clipboard ─────────────────────────────────
        km.bind(EditorCommands.SELECT_ALL, KeyEvent.KEYCODE_A, true, false);
        km.bind(EditorCommands.COPY, KeyEvent.KEYCODE_C, true, false);
        km.bind(EditorCommands.CUT, KeyEvent.KEYCODE_X, true, false);
        km.bind(EditorCommands.PASTE, KeyEvent.KEYCODE_V, true, false);
        km.bind(EditorCommands.DUPLICATE, KeyEvent.KEYCODE_D, true, false);
        // ── File / host actions ───────────────────────────────────
        km.bind(EditorCommands.FIND, KeyEvent.KEYCODE_F, true, false);
        km.bind(EditorCommands.SAVE, KeyEvent.KEYCODE_S, true, false);
        // ── Language intelligence ─────────────────────────────────
        km.bind(EditorCommands.TRIGGER_COMPLETION, KeyEvent.KEYCODE_SPACE, true, false);
        km.bind(EditorCommands.TRIGGER_SIGNATURE_HELP, KeyEvent.KEYCODE_P, true, false);
        km.bind(EditorCommands.CODE_ACTIONS, KeyEvent.KEYCODE_PERIOD, true, false);
        km.bind(EditorCommands.GO_TO_SYMBOL, KeyEvent.KEYCODE_O, true, true);
        km.bind(EditorCommands.FORMAT_DOCUMENT, KeyEvent.KEYCODE_I, true, true);
        km.bind(EditorCommands.CODE_ACTIONS_AT_CARET, KeyEvent.KEYCODE_L, true, true);
        km.bind(EditorCommands.QUICK_DOC, KeyEvent.KEYCODE_F1, false, false);
        km.bind(EditorCommands.RENAME, KeyEvent.KEYCODE_F2, false, false);
        km.bind(EditorCommands.GO_TO_DEFINITION, KeyEvent.KEYCODE_F12, false, false);
        km.bind(EditorCommands.FIND_REFERENCES, KeyEvent.KEYCODE_F12, false, true);
        // ── Navigation ────────────────────────────────────────────
        km.bind(EditorCommands.GO_TO_LINE, KeyEvent.KEYCODE_G, true, false);
        // ── Zoom ──────────────────────────────────────────────────
        km.bind(EditorCommands.ZOOM_IN, KeyEvent.KEYCODE_EQUALS, true, false);
        km.bind(EditorCommands.ZOOM_IN, KeyEvent.KEYCODE_PLUS, true, false);
        km.bind(EditorCommands.ZOOM_IN, KeyEvent.KEYCODE_NUMPAD_ADD, true, false);
        km.bind(EditorCommands.ZOOM_OUT, KeyEvent.KEYCODE_MINUS, true, false);
        km.bind(EditorCommands.ZOOM_OUT, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, true, false);
        km.bind(EditorCommands.ZOOM_RESET, KeyEvent.KEYCODE_0, true, false);
        // ── Editing ───────────────────────────────────────────────
        km.bind(EditorCommands.BACKSPACE, KeyEvent.KEYCODE_DEL, false, false);
        km.bind(EditorCommands.DELETE_FORWARD, KeyEvent.KEYCODE_FORWARD_DEL, false, false);
        km.bind(EditorCommands.NEW_LINE, KeyEvent.KEYCODE_ENTER, false, false);
        km.bind(EditorCommands.NEW_LINE, KeyEvent.KEYCODE_NUMPAD_ENTER, false, false);
        km.bind(EditorCommands.NEW_LINE, KeyEvent.KEYCODE_DPAD_CENTER, false, false);
        km.bind(EditorCommands.INDENT, KeyEvent.KEYCODE_TAB, false, false);
        km.bind(EditorCommands.DEDENT, KeyEvent.KEYCODE_TAB, false, true);
        km.bind(EditorCommands.INSERT_SPACE, KeyEvent.KEYCODE_SPACE, false, false);
        // ── Caret movement (Shift = extend) ───────────────────────
        km.bind(EditorCommands.MOVE_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, false, false);
        km.bind(EditorCommands.EXTEND_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, false, true);
        km.bind(EditorCommands.MOVE_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, false, false);
        km.bind(EditorCommands.EXTEND_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, false, true);
        km.bind(EditorCommands.MOVE_UP, KeyEvent.KEYCODE_DPAD_UP, false, false);
        km.bind(EditorCommands.EXTEND_UP, KeyEvent.KEYCODE_DPAD_UP, false, true);
        km.bind(EditorCommands.MOVE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, false, false);
        km.bind(EditorCommands.EXTEND_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, false, true);
        km.bind(EditorCommands.LINE_START, KeyEvent.KEYCODE_MOVE_HOME, false, false);
        km.bind(EditorCommands.EXTEND_LINE_START, KeyEvent.KEYCODE_MOVE_HOME, false, true);
        km.bind(EditorCommands.LINE_END, KeyEvent.KEYCODE_MOVE_END, false, false);
        km.bind(EditorCommands.EXTEND_LINE_END, KeyEvent.KEYCODE_MOVE_END, false, true);
        km.bind(EditorCommands.PAGE_UP, KeyEvent.KEYCODE_PAGE_UP, false, false);
        km.bind(EditorCommands.EXTEND_PAGE_UP, KeyEvent.KEYCODE_PAGE_UP, false, true);
        km.bind(EditorCommands.PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, false, false);
        km.bind(EditorCommands.EXTEND_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, false, true);
        return km;
    }

    /**
     * Binds {@code keyCode}+modifiers to {@code command} (fluent). A later
     * binding for the same key+modifiers replaces an earlier one; the same
     * command may live on several keys (e.g. ZOOM_IN on =/+/numpad+).
     */
    public EditorKeymap bind(String command, int keyCode, boolean ctrl, boolean shift) {
        if (command == null) throw new IllegalArgumentException("command is null");
        for (int i = 0; i < table.size(); i++) {
            Binding b = table.get(i);
            if (b.keyCode == keyCode && b.ctrl == ctrl && b.shift == shift) {
                table.set(i, new Binding(command, keyCode, ctrl, shift));
                return this;
            }
        }
        table.add(new Binding(command, keyCode, ctrl, shift));
        return this;
    }

    /** Removes EVERY binding of {@code command} (fluent). */
    public EditorKeymap unbind(String command) {
        if (command == null) return this;
        table.removeIf(b -> b.command.equals(command));
        return this;
    }

    /** True when {@code command} has at least one binding. */
    public boolean isBound(String command) {
        for (Binding b : table) {
            if (b.command.equals(command)) return true;
        }
        return false;
    }

    /** The first binding of {@code command} (introspection / UI), or null. */
    public Binding bindingFor(String command) {
        for (Binding b : table) {
            if (b.command.equals(command)) return b;
        }
        return null;
    }

    /** An unmodifiable view of the whole table (introspection / UI). */
    public List<Binding> bindings() {
        return Collections.unmodifiableList(table);
    }

    /**
     * Resolves a key event to its binding, or null when the key is not
     * bound. See the class javadoc for the four-pass modifier fallback
     * (exact → drop ctrl → drop shift → drop both).
     */
    public Binding resolve(int keyCode, boolean ctrl, boolean shift) {
        Binding b = match(keyCode, ctrl, shift);
        if (b == null) b = match(keyCode, false, shift);
        if (b == null) b = match(keyCode, ctrl, false);
        if (b == null) b = match(keyCode, false, false);
        return b;
    }

    private Binding match(int keyCode, boolean ctrl, boolean shift) {
        for (int i = 0; i < table.size(); i++) {
            Binding b = table.get(i);
            if (b.keyCode == keyCode && b.ctrl == ctrl && b.shift == shift) return b;
        }
        return null;
    }
}
