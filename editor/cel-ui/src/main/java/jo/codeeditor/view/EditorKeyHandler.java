package jo.codeeditor.view;

import android.os.SystemClock;
import android.view.KeyEvent;

import jo.codeeditor.document.EditorDocument;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * v3.12.0: Extracted from EditorView — handles all hardware keyboard input:
 * {@code onKeyDown} with its full switch cascade (popup navigation,
 * Ctrl+shortcuts, movement/editing keys, printable character fall-through).
 *
 * <p><b>v3.36.0 (roadmap item 6) — data-driven keymap:</b> the
 * Ctrl+shortcut and movement/editing cascades were replaced by a
 * {@link EditorKeymap} lookup (port of CodeAssist v3.20's
 * {@code EditorKeymap}/{@code EditorCommands}). The key event is resolved
 * to a command id ({@link EditorCommands}) and dispatched by
 * {@link #executeCommand}; hosts rebind commands via
 * {@code EditorView.setKeymap(EditorKeymap)}. The default table reproduces
 * the pre-v3.36.0 behavior, and the state-dependent popup interceptors
 * (completion / signature help / code actions / go-to-symbol navigation
 * while visible) run BEFORE the keymap so they keep priority.</p>
 *
 * <p>EditorView delegates {@code onKeyDown} to this class. The key handler
 * calls back into EditorView's public/package-private API for:
 * <ul>
 *   <li>Popup navigation: {@code completionSelectUp/Down/Accept},
 *       {@code goToSymbolSelect/Accept}, {@code applySelectedCodeAction},
 *       {@code dismiss*}</li>
 *   <li>Editing: {@code session.backspace/commitText/moveHorizontal/...},
 *       {@code onTextChanged}</li>
 *   <li>Clipboard: {@code copy/cut/paste}</li>
 *   <li>Zoom: {@code setFontScale/clampFontScale}</li>
 *   <li>Triggers: {@code refreshCompletion/triggerSignatureHelp/showQuickDoc/
 *       showRename/showGoToLine/showGoToSymbol/showCodeActions}</li>
 * </ul>
 *
 * @since v3.12.0
 */
class EditorKeyHandler {

    private final EditorView view;

    /**
     * v3.37.0 — chord pending state (port of CodeAssist v3.20's
     * {@code Outcome.Pending}). When non-null, the next key event is
     * matched against the chords' second strokes; the state silently
     * expires {@link #CHORD_TIMEOUT_MS} after it was armed (checked
     * against {@link #clock} on the next event — no Handler, no leak).
     */
    private EditorKeymap.KeyStroke pendingChord;
    private long pendingChordDeadline;

    /** Pending-chord lifetime (VS Code uses ~2 s). */
    private static final long CHORD_TIMEOUT_MS = 2000;

    /** Injectable clock (tests freeze/advance time deterministically). */
    LongSupplier clock = SystemClock::uptimeMillis;

    EditorKeyHandler(EditorView view) {
        this.view = view;
    }

    boolean onKeyDown(int keyCode, KeyEvent event) {
        if (view.session == null) return false;
        boolean shift = event.isShiftPressed();
        boolean ctrl = event.isCtrlPressed();

        // ── Completion popup navigation (when visible) ─────────────
        if (view.completionVisible && !ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (view.completionSelectUp()) return true;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (view.completionSelectDown()) return true;
                    break;
                case KeyEvent.KEYCODE_TAB:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (view.completionAccept()) return true;
                    break;
                case KeyEvent.KEYCODE_ESCAPE:
                    view.dismissCompletion();
                    return true;
            }
        }
        // ── Signature help popup navigation (v2.39) ───────────────
        // Up/Down cycles between overloads (activeSignature) when there
        // is more than one signature. Esc dismisses. When only one
        // signature is available, Up/Down fall through to caret movement
        // (so the user can still navigate inside the call arguments).
        if (view.signatureHelpVisible && !ctrl) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                view.dismissSignatureHelp();
                return true;
            }
            if (view.signatureHelpData != null
                && view.signatureHelpData.signatures != null
                && view.signatureHelpData.signatures.size() > 1) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    view.cycleSignatureHelp(-1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    view.cycleSignatureHelp(1);
                    return true;
                }
            }
        }
        // ── Quick doc popup dismissal (Esc) ──────────────────────
        if (view.quickDocVisible && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            view.dismissQuickDoc();
            return true;
        }
        // ── v3.36.0: grouped diagnostic list sheet dismissal (Esc) ──
        if (view.diagnosticListSheetLine >= 0 && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            view.dismissDiagnosticListSheet();
            return true;
        }
        // ── v3.36.0: diagnostic detail popup dismissal (Esc) ──────
        if (view.diagnosticPopupVisible && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            view.dismissDiagnosticPopup();
            return true;
        }
        // ── Code actions popup navigation (when visible) ─────────
        if (view.codeActionsPopupVisible && !ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (view.codeActionsSelected > 0) {
                        view.codeActionsSelected--;
                        view.invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (view.codeActionsPopupLine >= 0) {
                        List<EditorView.CodeAction> acts = view.codeActionsByLine.get(view.codeActionsPopupLine);
                        if (acts != null && view.codeActionsSelected < acts.size() - 1) {
                            view.codeActionsSelected++;
                            view.invalidate();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    view.applySelectedCodeAction();
                    return true;
                case KeyEvent.KEYCODE_ESCAPE:
                    view.dismissCodeActions();
                    return true;
            }
        }
        // ── Go-to-symbol popup navigation (when visible) ─────────
        if (view.goToSymbolVisible && !ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    view.goToSymbolSelect(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    view.goToSymbolSelect(1);
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    view.goToSymbolAccept();
                    return true;
                case KeyEvent.KEYCODE_ESCAPE:
                    view.dismissGoToSymbol();
                    return true;
            }
        }

        // ── v3.37.0 (chords): pending sequence resolution ──
        // Runs AFTER the popup interceptors (an open popup keeps
        // priority) and BEFORE the single-key dispatch: while a chord
        // is pending, the current key either completes the sequence or
        // is processed as a fresh keystroke.
        if (pendingChord != null) {
            if (clock.getAsLong() > pendingChordDeadline) {
                // Expired — drop the pending state, process normally.
                pendingChord = null;
            } else if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                // Escape cancels a pending chord (consumed).
                pendingChord = null;
                return true;
            } else {
                EditorKeymap.ChordBinding chord =
                    view.keymap.resolveChord(pendingChord, keyCode, ctrl, shift);
                pendingChord = null;
                if (chord != null) {
                    return executeCommand(chord.command);
                }
                // Not a chord second stroke — fall through: the key is
                // processed as a fresh keystroke (it may itself start a
                // new chord, checked below).
            }
        }

        // ── v3.36.0 (roadmap item 6): data-driven command dispatch ──
        // The keymap (rebindable via EditorView.setKeymap) resolves the
        // event to a command; the default table is a verbatim port of the
        // pre-v3.36.0 Ctrl+shortcut and movement/editing switch cascades.
        EditorKeymap.Binding binding = view.keymap.resolve(keyCode, ctrl, shift);
        if (binding != null) {
            return executeCommand(binding.command);
        }

        // ── v3.37.0 (chords): does this key START a chord? ──
        // Only reached when no single-key binding resolved, so chords
        // never steal keys from the (chord-free) default table.
        EditorKeymap.KeyStroke start = view.keymap.resolveChordStart(keyCode, ctrl, shift);
        if (start != null) {
            pendingChord = start;
            pendingChordDeadline = clock.getAsLong() + CHORD_TIMEOUT_MS;
            return true; // consumed — waiting for the second stroke
        }

        // Printable character fall-through: respect Shift / AltGr via unicodeChar.
        if (!event.isCtrlPressed() && !event.isMetaPressed()) {
            int cp = event.getUnicodeChar(event.getMetaState());
            if (cp >= 32 && cp != 127) {
                view.session.commitText(new String(Character.toChars(cp)));
                view.onTextChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * v3.36.0 — Executes one {@link EditorCommands} id. Returns true when
     * the key was consumed. {@link EditorCommands#CODE_ACTIONS} returns
     * false when the caret's line has no actions (the key then falls
     * through, exactly like the pre-v3.36.0 {@code if (ctrl)} cascade).
     */
    private boolean executeCommand(String command) {
        switch (command) {
            // ── History ───────────────────────────────────────────
            case EditorCommands.UNDO:
                view.session.undo(); view.onTextChanged(); return true;
            case EditorCommands.REDO:
                view.session.redo(); view.onTextChanged(); return true;

            // ── Selection / clipboard ─────────────────────────────
            case EditorCommands.SELECT_ALL:
                view.session.selectAll(); view.invalidate(); return true;
            case EditorCommands.COPY:
                view.copy(); return true;
            case EditorCommands.CUT:
                view.cut(); return true;
            case EditorCommands.PASTE:
                view.paste(); return true;
            case EditorCommands.DUPLICATE:
                view.session.duplicateSelection(); view.onTextChanged(); return true;

            // ── File / host actions ───────────────────────────────
            case EditorCommands.FIND:
                if (view.selectionListener instanceof EditorView.OnFindRequestedListener) {
                    ((EditorView.OnFindRequestedListener) view.selectionListener).onFindRequested();
                }
                return true;
            case EditorCommands.SAVE:
                if (view.selectionListener instanceof EditorView.OnSaveRequestedListener) {
                    ((EditorView.OnSaveRequestedListener) view.selectionListener).onSaveRequested();
                }
                return true;

            // ── Language intelligence ─────────────────────────────
            case EditorCommands.TRIGGER_COMPLETION:
                view.refreshCompletion();
                return true;
            case EditorCommands.TRIGGER_SIGNATURE_HELP:
                view.triggerSignatureHelp();
                return true;
            case EditorCommands.CODE_ACTIONS:
                // Ctrl+. — only consumed when the caret's line has actions
                // (LSP convention; pre-v3.36.0 behavior preserved).
                if (view.session != null) {
                    EditorDocument doc = view.session.getDocument();
                    int line = EditorView.clamp(doc.lineForOffset(view.session.getSelection().start),
                        0, doc.lineCount() - 1);
                    if (view.codeActionsByLine.containsKey(line)) {
                        view.showCodeActions(line);
                        return true;
                    }
                }
                return false;
            case EditorCommands.CODE_ACTIONS_AT_CARET:
                // Ctrl+Shift+L — unlike Ctrl+., always opens (empty state
                // shows "no actions"), an alternative for soft keyboards.
                if (view.session != null) {
                    EditorDocument doc = view.session.getDocument();
                    int line = EditorView.clamp(doc.lineForOffset(view.session.getSelection().start),
                        0, doc.lineCount() - 1);
                    view.showCodeActions(line);
                }
                return true;
            case EditorCommands.QUICK_DOC:
                if (view.session != null) view.showQuickDoc(view.session.getSelection().start);
                return true;
            case EditorCommands.RENAME:
                view.showRename();
                return true;
            case EditorCommands.FORMAT_DOCUMENT:
                view.formatDocument();
                return true;

            // ── Navigation ────────────────────────────────────────
            case EditorCommands.GO_TO_LINE:
                view.showGoToLine();
                return true;
            case EditorCommands.GO_TO_SYMBOL:
                view.showGoToSymbol();
                return true;
            case EditorCommands.GO_TO_DEFINITION:
                view.jumpToDefinition();
                return true;
            case EditorCommands.FIND_REFERENCES:
                view.showReferences();
                return true;

            // ── Zoom ──────────────────────────────────────────────
            case EditorCommands.ZOOM_IN:
                view.setFontScale(EditorView.clampFontScale(view.zoom.fontScale * 1.1f)); return true;
            case EditorCommands.ZOOM_OUT:
                view.setFontScale(EditorView.clampFontScale(view.zoom.fontScale / 1.1f)); return true;
            case EditorCommands.ZOOM_RESET:
                view.setFontScale(1f); return true;

            // ── Editing ───────────────────────────────────────────
            case EditorCommands.BACKSPACE:
                view.session.backspace(); view.onTextChanged(); return true;
            case EditorCommands.DELETE_FORWARD:
                view.session.deleteForward(); view.onTextChanged(); return true;
            case EditorCommands.NEW_LINE:
                view.session.commitText("\n"); view.onTextChanged(); return true;
            case EditorCommands.INDENT:
                view.session.indent(); view.onTextChanged(); return true;
            case EditorCommands.DEDENT:
                view.session.dedent(); view.onTextChanged(); return true;
            case EditorCommands.INSERT_SPACE:
                view.session.commitText(" "); view.onTextChanged(); return true;
            case EditorCommands.TOGGLE_LINE_COMMENT:
                view.session.toggleLineComment(); view.onTextChanged(); return true;
            case EditorCommands.TOGGLE_BLOCK_COMMENT:
                view.session.toggleBlockComment(); view.onTextChanged(); return true;

            // ── Caret movement (Shift = extend) ───────────────────
            case EditorCommands.MOVE_LEFT:
                view.session.moveHorizontal(-1, false); view.invalidate(); return true;
            case EditorCommands.EXTEND_LEFT:
                view.session.moveHorizontal(-1, true); view.invalidate(); return true;
            case EditorCommands.MOVE_RIGHT:
                view.session.moveHorizontal(1, false); view.invalidate(); return true;
            case EditorCommands.EXTEND_RIGHT:
                view.session.moveHorizontal(1, true); view.invalidate(); return true;
            case EditorCommands.MOVE_UP:
                view.session.moveVertical(-1, false); view.invalidate(); return true;
            case EditorCommands.EXTEND_UP:
                view.session.moveVertical(-1, true); view.invalidate(); return true;
            case EditorCommands.MOVE_DOWN:
                view.session.moveVertical(1, false); view.invalidate(); return true;
            case EditorCommands.EXTEND_DOWN:
                view.session.moveVertical(1, true); view.invalidate(); return true;
            case EditorCommands.LINE_START:
                view.session.moveLineStart(false); view.invalidate(); return true;
            case EditorCommands.EXTEND_LINE_START:
                view.session.moveLineStart(true); view.invalidate(); return true;
            case EditorCommands.LINE_END:
                view.session.moveLineEnd(false); view.invalidate(); return true;
            case EditorCommands.EXTEND_LINE_END:
                view.session.moveLineEnd(true); view.invalidate(); return true;
            case EditorCommands.PAGE_UP:
                view.session.moveVertical(-10, false); view.invalidate(); return true;
            case EditorCommands.EXTEND_PAGE_UP:
                view.session.moveVertical(-10, true); view.invalidate(); return true;
            case EditorCommands.PAGE_DOWN:
                view.session.moveVertical(10, false); view.invalidate(); return true;
            case EditorCommands.EXTEND_PAGE_DOWN:
                view.session.moveVertical(10, true); view.invalidate(); return true;

            default:
                return false;
        }
    }
}
