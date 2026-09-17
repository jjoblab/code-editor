package jo.codeeditor.view;

/**
 * Identifiants de commandes du keymap piloté par les données (port de
 * l'{@code EditorCommands} de CodeAssist).
 *
 * <p>Chaque action au clavier matériel prise en charge par l'éditeur est
 * une commande nommée. {@link EditorKeymap} associe les événements de
 * touches à ces identifiants et {@code EditorKeyHandler} les exécute —
 * remplaçant l'ancienne cascade de blocs {@code if}/{@code switch}. Les
 * hôtes peuvent relier n'importe quelle commande :</p>
 *
 * <pre>{@code
 * EditorKeymap km = EditorKeymap.defaults()
 *         .unbind(EditorCommands.GO_TO_DEFINITION)
 *         .bind(EditorCommands.REDO, KeyEvent.KEYCODE_Z, true, true);
 * view.setKeymap(km);   // Ctrl+Shift+Z = rétablir (convention IntelliJ)
 * }</pre>
 */
public final class EditorCommands {

    private EditorCommands() {}

    // ── Historique ────────────────────────────────────────────────
    public static final String UNDO = "editor.undo";
    public static final String REDO = "editor.redo";

    // ── Sélection ─────────────────────────────────────────────────
    public static final String SELECT_ALL = "editor.select_all";
    public static final String COPY = "editor.copy";
    public static final String CUT = "editor.cut";
    public static final String PASTE = "editor.paste";
    public static final String DUPLICATE = "editor.duplicate";

    // ── Fichier / actions hôte ────────────────────────────────────
    public static final String FIND = "editor.find";
    public static final String SAVE = "editor.save";

    // ── Intelligence du langage ───────────────────────────────────
    public static final String TRIGGER_COMPLETION = "editor.completion";
    public static final String TRIGGER_SIGNATURE_HELP = "editor.signature_help";
    public static final String CODE_ACTIONS = "editor.code_actions";
    public static final String CODE_ACTIONS_AT_CARET = "editor.code_actions_at_caret";
    public static final String QUICK_DOC = "editor.quick_doc";
    public static final String RENAME = "editor.rename";
    public static final String FORMAT_DOCUMENT = "editor.format_document";

    // ── Navigation ────────────────────────────────────────────────
    public static final String GO_TO_LINE = "editor.go_to_line";
    public static final String GO_TO_SYMBOL = "editor.go_to_symbol";
    public static final String GO_TO_DEFINITION = "editor.go_to_definition";
    public static final String FIND_REFERENCES = "editor.find_references";

    // ── Zoom ──────────────────────────────────────────────────────
    public static final String ZOOM_IN = "editor.zoom_in";
    public static final String ZOOM_OUT = "editor.zoom_out";
    public static final String ZOOM_RESET = "editor.zoom_reset";

    // ── Édition ───────────────────────────────────────────────────
    public static final String BACKSPACE = "editor.backspace";
    public static final String DELETE_FORWARD = "editor.delete_forward";
    public static final String NEW_LINE = "editor.new_line";
    public static final String INDENT = "editor.indent";
    public static final String DEDENT = "editor.dedent";
    public static final String INSERT_SPACE = "editor.insert_space";

    // ── Bascules de commentaire (compagnes d'accords naturels, ex.
    // Ctrl+K Ctrl+C / Ctrl+K Ctrl+U à la IntelliJ) ────────────────
    public static final String TOGGLE_LINE_COMMENT = "editor.toggle_line_comment";
    public static final String TOGGLE_BLOCK_COMMENT = "editor.toggle_block_comment";

    // ── Déplacement du caret (EXTEND_* = même déplacement avec sélection) ──
    public static final String MOVE_LEFT = "editor.move_left";
    public static final String EXTEND_LEFT = "editor.extend_left";
    public static final String MOVE_RIGHT = "editor.move_right";
    public static final String EXTEND_RIGHT = "editor.extend_right";
    public static final String MOVE_UP = "editor.move_up";
    public static final String EXTEND_UP = "editor.extend_up";
    public static final String MOVE_DOWN = "editor.move_down";
    public static final String EXTEND_DOWN = "editor.extend_down";
    public static final String LINE_START = "editor.line_start";
    public static final String EXTEND_LINE_START = "editor.extend_line_start";
    public static final String LINE_END = "editor.line_end";
    public static final String EXTEND_LINE_END = "editor.extend_line_end";
    public static final String PAGE_UP = "editor.page_up";
    public static final String EXTEND_PAGE_UP = "editor.extend_page_up";
    public static final String PAGE_DOWN = "editor.page_down";
    public static final String EXTEND_PAGE_DOWN = "editor.extend_page_down";
}
