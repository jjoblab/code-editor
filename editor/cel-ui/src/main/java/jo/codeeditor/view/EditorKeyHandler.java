package jo.codeeditor.view;

import android.view.KeyEvent;

import jo.codeeditor.document.EditorDocument;

import java.util.List;

/**
 * v3.12.0: Extracted from EditorView — handles all hardware keyboard input:
 * {@code onKeyDown} with its full switch cascade (popup navigation,
 * Ctrl+shortcuts, movement/editing keys, printable character fall-through).
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

        // Ctrl-modified shortcuts
        if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_Z:
                    view.session.undo(); view.onTextChanged(); return true;
                case KeyEvent.KEYCODE_Y:
                    view.session.redo(); view.onTextChanged(); return true;
                case KeyEvent.KEYCODE_A:
                    view.session.selectAll(); view.invalidate(); return true;
                case KeyEvent.KEYCODE_C:
                    view.copy(); return true;
                case KeyEvent.KEYCODE_X:
                    view.cut(); return true;
                case KeyEvent.KEYCODE_V:
                    view.paste(); return true;
                case KeyEvent.KEYCODE_D:
                    view.session.duplicateSelection(); view.onTextChanged(); return true;
                case KeyEvent.KEYCODE_F:
                    if (view.selectionListener instanceof EditorView.OnFindRequestedListener) {
                        ((EditorView.OnFindRequestedListener) view.selectionListener).onFindRequested();
                    }
                    return true;
                case KeyEvent.KEYCODE_S:
                    if (view.selectionListener instanceof EditorView.OnSaveRequestedListener) {
                        ((EditorView.OnSaveRequestedListener) view.selectionListener).onSaveRequested();
                    }
                    return true;
                case KeyEvent.KEYCODE_SPACE:
                    // Ctrl+Space = explicit completion trigger.
                    view.refreshCompletion();
                    return true;
                case KeyEvent.KEYCODE_P:
                    // Ctrl+P = signature help trigger (LSP convention).
                    view.triggerSignatureHelp();
                    return true;
                case KeyEvent.KEYCODE_PERIOD:
                    // Ctrl+. = code actions at caret (LSP convention).
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
                case KeyEvent.KEYCODE_O:
                    // Ctrl+Shift+O = go-to-symbol (VS Code convention).
                    if (shift) {
                        view.showGoToSymbol();
                        return true;
                    }
                    return false;
                case KeyEvent.KEYCODE_I:
                    // v3.33.11: Ctrl+Shift+I = format document (VS Code
                    // convention, also matches IntelliJ's Ctrl+Alt+L on Linux).
                    if (shift) {
                        view.formatDocument();
                        return true;
                    }
                    return false;
                case KeyEvent.KEYCODE_L:
                    // v3.33.11: Ctrl+Shift+L = show code actions at caret
                    // (alternative to Ctrl+. when the latter isn't available
                    // on some soft keyboards).
                    if (shift) {
                        if (view.session != null) {
                            EditorDocument doc = view.session.getDocument();
                            int line = EditorView.clamp(doc.lineForOffset(view.session.getSelection().start),
                                0, doc.lineCount() - 1);
                            view.showCodeActions(line);
                        }
                        return true;
                    }
                    return false;
                case KeyEvent.KEYCODE_EQUALS:
                case KeyEvent.KEYCODE_PLUS:
                case KeyEvent.KEYCODE_NUMPAD_ADD:
                    view.setFontScale(EditorView.clampFontScale(view.fontScale * 1.1f)); return true;
                case KeyEvent.KEYCODE_MINUS:
                case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                    view.setFontScale(EditorView.clampFontScale(view.fontScale / 1.1f)); return true;
                case KeyEvent.KEYCODE_0:
                    view.setFontScale(1f); return true;
            }
        }

        // Movement / editing keys
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                view.session.backspace(); view.onTextChanged(); return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                view.session.deleteForward(); view.onTextChanged(); return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                view.session.commitText("\n"); view.onTextChanged(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                view.session.moveHorizontal(-1, shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                view.session.moveHorizontal(1, shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                view.session.moveVertical(-1, shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                view.session.moveVertical(1, shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                view.session.moveLineStart(shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_MOVE_END:
                view.session.moveLineEnd(shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                view.session.moveVertical(-10, shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                view.session.moveVertical(10, shift); view.invalidate(); return true;
            case KeyEvent.KEYCODE_TAB:
                if (shift) view.session.dedent(); else view.session.indent();
                view.onTextChanged(); return true;
            case KeyEvent.KEYCODE_SPACE:
                view.session.commitText(" "); view.onTextChanged(); return true;
            case KeyEvent.KEYCODE_F1:
                // F1 = quick doc (IDE convention).
                if (view.session != null) view.showQuickDoc(view.session.getSelection().start);
                return true;
            case KeyEvent.KEYCODE_F2:
                // F2 = rename (IDE convention — Gap 8).
                view.showRename();
                return true;
            case KeyEvent.KEYCODE_F12:
                // v3.33.11: F12 = go-to-definition (VS Code convention).
                // Shift+F12 = find-references.
                if (shift) {
                    view.showReferences();
                } else {
                    view.jumpToDefinition();
                }
                return true;
            case KeyEvent.KEYCODE_G:
                // Ctrl+G = go-to-line (VS Code convention — Gap 8).
                if (ctrl) {
                    view.showGoToLine();
                    return true;
                }
                break;
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
}
