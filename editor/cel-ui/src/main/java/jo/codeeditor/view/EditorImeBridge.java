package jo.codeeditor.view;

import android.os.Build;
import android.text.InputType;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.EditSpan;

/**
 * Pont IME, extrait d'EditorView — gère toute l'intégration IME :
 * implémentation {@link android.view.inputmethod.InputConnection},
 * instantanés de texte extrait, infos d'ancre de curseur, et pont
 * {@link EditorSession.ImeListener}.
 *
 * <p>Contient :
 * <ul>
 *   <li>{@link EditorInputConnection} — la sous-classe de
 *       {@link BaseInputConnection} qui route chaque opération texte de
 *       l'IME vers {@link EditorSession}.</li>
 *   <li>{@link Api34InputConnection} / {@link Api31InputConnection} —
 *       sous-classes spécifiques d'API chargées uniquement sur API 34+ / 31+.</li>
 *   <li>{@link EditorImeBridge} — implémente {@link EditorSession.ImeListener}
 *       et pousse les rappels de session (texte changé, sélection changée)
 *       vers le {@link InputMethodManager}.</li>
 *   <li>{@link #buildExtractedText()} — instantané fenêtré pour l'IME.</li>
 *   <li>{@link #buildCursorAnchorInfo()} / {@link #pushCursorAnchorInfo()} —
 *       infos de position du caret pour les IME japonais/chinois.</li>
 *   <li>{@link #onCreateInputConnection(EditorInfo)} — configure
 *       l'{@link EditorInfo} et renvoie la bonne sous-classe
 *       {@link EditorInputConnection} selon le niveau d'API de l'appareil.</li>
 * </ul>
 *
 * <p>EditorView délègue {@code onCreateInputConnection},
 * {@code buildExtractedText}, {@code pushCursorAnchorInfo} à cette classe.
 * L'état IME ({@code extractedTextMonitorToken}, {@code cursorAnchorMonitorMode},
 * {@code connectionGeneration}) reste dans EditorView (package-private) et
 * est accédé/muté par ce pont.
 */
class EditorImeBridge {

    private final EditorView view;

    EditorImeBridge(EditorView view) {
        this.view = view;
    }

    static final int MAX_EXTRACT_CHARS = 100_000;

    // ════════════════════════════════════════════════════════════════
    // Création de l'InputConnection
    // ════════════════════════════════════════════════════════════════

    InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // inputType : texte + multiligne + sans suggestions + mot de passe visible
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_FULLSCREEN
            | EditorInfo.IME_ACTION_NONE;
        outAttrs.initialSelStart = Math.min(view.session.getSelection().start, view.session.getSelection().end);
        outAttrs.initialSelEnd = Math.max(view.session.getSelection().start, view.session.getSelection().end);
        outAttrs.initialCapsMode = 0;
        view.connectionGeneration++;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return Api34InputConnection.create(view, view.connectionGeneration);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Api31InputConnection.create(view, view.connectionGeneration);
        }
        return new EditorInputConnection(view, view.connectionGeneration);
    }

    // ════════════════════════════════════════════════════════════════
    // Texte extrait + ancre de curseur
    // ════════════════════════════════════════════════════════════════

    ExtractedText buildExtractedText() {
        String text = view.session.getText();
        ExtractedText et = new ExtractedText();
        if (text.length() <= MAX_EXTRACT_CHARS) {
            et.text = text;
            et.startOffset = 0;
            et.selectionStart = Math.min(view.session.getSelection().start, view.session.getSelection().end);
            et.selectionEnd = Math.max(view.session.getSelection().start, view.session.getSelection().end);
        } else {
            int half = MAX_EXTRACT_CHARS / 2;
            int caret = view.session.getSelection().start;
            int start = Math.max(0, caret - half);
            int end = Math.min(text.length(), start + MAX_EXTRACT_CHARS);
            if (end - start < MAX_EXTRACT_CHARS) start = Math.max(0, end - MAX_EXTRACT_CHARS);
            et.text = text.substring(start, end);
            et.startOffset = start;
            et.selectionStart = Math.min(view.session.getSelection().start, view.session.getSelection().end) - start;
            et.selectionEnd = Math.max(view.session.getSelection().start, view.session.getSelection().end) - start;
        }
        et.flags = 0;
        return et;
    }

    private android.view.inputmethod.CursorAnchorInfo buildCursorAnchorInfo() {
        if (view.session == null) return null;
        EditorDocument doc = view.session.getDocument();
        Selection sel = view.session.getSelection();
        int caret = sel.start;
        int line = EditorView.clamp(doc.lineForOffset(caret), 0, doc.lineCount() - 1);
        int col = caret - doc.lineStart(line);
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float caretX = textAreaLeft + col * charWidth - view.hOffset;
        float caretY = view.metrics.getPadTop() + line * lineHeight - view.vOffset;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        if (view.getMatrix() != null) matrix.set(view.getMatrix());
        android.view.inputmethod.CursorAnchorInfo.Builder builder =
            new android.view.inputmethod.CursorAnchorInfo.Builder();
        builder.setMatrix(matrix);
        builder.setInsertionMarkerLocation(caretX, caretY, caretY + lineHeight * 0.75f, caretY + lineHeight, 0);
        int[] comp = view.session.getComposingRegion();
        if (comp != null && comp.length >= 2) {
            builder.setComposingText(comp[0], view.session.getText().substring(comp[0], comp[1]));
        }
        return builder.build();
    }

    void pushCursorAnchorInfo() {
        if (view.cursorAnchorMonitorMode == 0 || view.session == null) return;
        InputMethodManager imm = view.imm();
        if (imm == null) return;
        android.view.inputmethod.CursorAnchorInfo info = buildCursorAnchorInfo();
        if (info != null) {
            imm.updateCursorAnchorInfo(view, info);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Implémentation de Session.ImeListener
    // ════════════════════════════════════════════════════════════════

    /**
     * Reçoit les rappels de {@link EditorSession} et les pousse vers le
     * {@link InputMethodManager}. Enregistré comme listener IME de la session.
     */
    final EditorSession.ImeListener listener = new EditorSession.ImeListener() {
        @Override
        public void onTextChanged(EditSpan span) {
            // CaretAnimator.onEditOrMove() est le point d'entrée unique —
            // il définit view.lastEditTime, réinitialise la bascule de
            // clignotement, rend le caret visible et annule tout glissement
            // en cours.
            view.caretAnim.onEditOrMove();
            if (view.extractedTextMonitorToken != -1) {
                InputMethodManager imm = view.imm();
                if (imm != null) {
                    imm.updateExtractedText(view, view.extractedTextMonitorToken, buildExtractedText());
                }
            }
            view.scrollManager.scrollCaretIntoView();
            view.postInvalidate();
        }

        @Override
        public void onSelectionChanged(int selStart, int selEnd, int composingStart, int composingEnd) {
            InputMethodManager imm = view.imm();
            if (imm != null) {
                int compStart = composingStart >= 0 ? composingStart : -1;
                int compEnd = composingEnd >= 0 ? composingEnd : -1;
                imm.updateSelection(view, selStart, selEnd, compStart, compEnd);
            }
            view.scrollManager.scrollCaretIntoView();
            view.refreshSignatureHelp();
            // Rafraîchit les surbrillances de document quand le caret bouge —
            // le symbole sous le caret a changé, donc le serveur LSP doit
            // être réinterrogé pour les nouvelles occurrences.
            view.scheduleDocumentHighlights();
            // Surbrillance des crochets appariés — recalcul synchrone
            // (scan borné, CodeAssist recalcule aussi à chaque recomposition).
            view.updateBracketPair();
            if (view.cursorAnchorMonitorMode != 0) {
                pushCursorAnchorInfo();
            }
            view.postInvalidate();
            if (view.selectionListener != null || !view.extraSelectionListeners.isEmpty()) {
                EditorDocument doc = view.session.getDocument();
                int line = EditorView.clamp(doc.lineForOffset(selStart), 0, doc.lineCount() - 1);
                int col = selStart - doc.lineStart(line);
                if (view.selectionListener != null) {
                    view.selectionListener.onSelectionChanged(line + 1, col + 1, selStart == selEnd);
                }
                for (EditorView.OnSelectionChangedListener l : view.extraSelectionListeners) {
                    l.onSelectionChanged(line + 1, col + 1, selStart == selEnd);
                }
            }
        }

        @Override
        public void onRestartInput() {
            InputMethodManager imm = view.imm();
            if (imm != null) {
                imm.restartInput(view);
            }
        }

        @Override
        public boolean isSyncingExtractedText() {
            return view.extractedTextMonitorToken != -1;
        }
    };

    // ════════════════════════════════════════════════════════════════
    // Sous-classes d'InputConnection
    // ════════════════════════════════════════════════════════════════

    /**
     * Pont entre le framework IME et la session d'éditeur.
     * Chaque opération texte est surchargée pour agir directement sur
     * {@link EditorSession}, jamais sur un {@code Editable} fantôme.
     */
    static class EditorInputConnection extends BaseInputConnection {
        final EditorView view;
        final int generation;

        EditorInputConnection(EditorView view, int generation) {
            super(view, true);
            this.view = view;
            this.generation = generation;
        }

        private EditorSession session() { return view.session; }

        @Override public boolean beginBatchEdit() {
            EditorSession s = session();
            if (s != null) s.beginBatch();
            return true;
        }
        @Override public boolean endBatchEdit() {
            EditorSession s = session();
            if (s != null) s.endBatch();
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            EditorSession s = session();
            if (s == null) return false;
            if (text == null || text.length() == 0) return true;
            String t = text.toString();
            if (t.indexOf('\n') >= 0 && view.completionVisible) {
                if (view.completionAccept()) return true;
            }
            if (t.length() == 1 && newCursorPosition == 1) {
                s.typeChar(t.charAt(0));
            } else {
                s.imeCommitText(t);
            }
            view.onTextChanged();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (text != null && text.toString().indexOf('\n') >= 0) return false;
            EditorSession s = session();
            if (s == null) return false;
            s.imeSetComposingText(text == null ? "" : text.toString(), newCursorPosition);
            view.onTextChanged();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            EditorSession s = session();
            if (s == null) return false;
            s.imeFinishComposing();
            view.invalidate();
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            EditorSession s = session();
            if (s == null) return false;
            s.imeSetComposingRegion(start, end);
            view.invalidate();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            EditorSession s = session();
            if (s == null) return false;
            s.imeDeleteSurrounding(beforeLength, afterLength);
            view.onTextChanged();
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            EditorSession s = session();
            if (s == null) return false;
            String text = s.getText();
            int caret = s.getSelection().start;
            int charBefore = codePointsToCharsBackward(text, caret, beforeLength);
            int charAfter = codePointsToCharsForward(text, caret, afterLength);
            s.imeDeleteSurrounding(charBefore, charAfter);
            view.onTextChanged();
            return true;
        }

        @Override
        public boolean setSelection(int start, int end) {
            EditorSession s = session();
            if (s == null) return false;
            s.imeSetSelection(start, end);
            view.invalidate();
            return true;
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            EditorSession s = session();
            if (s == null) return null;
            String sel = s.selectedText();
            return sel == null ? null : sel;
        }

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            EditorSession s = session();
            if (s == null) return "";
            return s.imeTextBeforeCursor(n);
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            EditorSession s = session();
            if (s == null) return "";
            return s.imeTextAfterCursor(n);
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            if (request == null) return null;
            EditorSession s = session();
            if (s == null) return null;
            view.extractedTextMonitorToken = request.token;
            return view.imeBridge.buildExtractedText();
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            EditorSession s = session();
            if (s == null) return false;
            view.cursorAnchorMonitorMode = cursorUpdateMode;
            if ((cursorUpdateMode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0) {
                view.imeBridge.pushCursorAnchorInfo();
            }
            return true;
        }

        /**
         * Garde SDK_INT explicite — SurroundingText est API 31+. La méthode
         * n'est jamais appelée que depuis {@link Api31InputConnection#
         * getSurroundingText}, que le pont instancie uniquement quand
         * {@code SDK_INT >= S} ; la garde interne rend ce contrat explicite
         * pour lint (et sûr si un futur appelant l'oublie).
         */
        android.view.inputmethod.SurroundingText getSurroundingTextCompat(int beforeLength, int afterLength, int flags) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return null; // API < 31 : le framework ne demande jamais le texte environnant.
            }
            EditorSession s = session();
            if (s == null) return null;
            int caret = s.getSelection().start;
            int selStart = Math.min(s.getSelection().start, s.getSelection().end);
            int selEnd = Math.max(s.getSelection().start, s.getSelection().end);
            int start = Math.max(0, caret - beforeLength);
            int end = Math.min(s.getDocument().length(), caret + afterLength);
            String text = s.getText().substring(start, end);
            int selectionStart = selStart - start;
            int selectionEnd = selEnd - start;
            int offset = start;
            return new android.view.inputmethod.SurroundingText(text, selectionStart, selectionEnd, offset);
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            EditorSession s = session();
            if (s == null) return false;
            if (view.completionVisible) {
                if (view.completionAccept()) return true;
            }
            s.commitText("\n");
            view.onTextChanged();
            return true;
        }

        @Override
        public boolean performContextMenuAction(int id) {
            EditorSession s = session();
            if (s == null) return false;
            if (id == android.R.id.selectAll) { s.selectAll(); view.invalidate(); return true; }
            if (id == android.R.id.cut)       { view.cut(); return true; }
            if (id == android.R.id.copy)      { view.copy(); return true; }
            if (id == android.R.id.paste)     { view.paste(); return true; }
            if (id == android.R.id.undo)      { s.undo(); view.onTextChanged(); return true; }
            if (id == android.R.id.redo)      { s.redo(); view.onTextChanged(); return true; }
            return false;
        }

        @Override
        public void closeConnection() {
            if (view.connectionGeneration == generation) {
                view.extractedTextMonitorToken = -1;
                EditorSession s = session();
                if (s != null && s.isInBatch()) {
                    try { while (s.isInBatch()) s.endBatch(); } catch (RuntimeException ignored) {}
                }
            }
            super.closeConnection();
        }

        boolean replaceTextCompat(int start, int end, CharSequence text, int newCursorPosition) {
            EditorSession s = session();
            if (s == null) return false;
            s.imeReplaceText(start, end, text == null ? "" : text.toString(), newCursorPosition);
            view.onTextChanged();
            return true;
        }

        private static int codePointsToCharsBackward(String text, int from, int codePoints) {
            int cp = 0;
            int i = from;
            while (cp < codePoints && i > 0) {
                i--;
                if (!Character.isLowSurrogate(text.charAt(i)) || i == 0
                        || !Character.isHighSurrogate(text.charAt(i - 1))) {
                } else {
                    i--;
                }
                cp++;
            }
            return from - i;
        }

        private static int codePointsToCharsForward(String text, int from, int codePoints) {
            int cp = 0;
            int i = from;
            while (cp < codePoints && i < text.length()) {
                if (Character.isHighSurrogate(text.charAt(i)) && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i += 2;
                } else {
                    i += 1;
                }
                cp++;
            }
            return i - from;
        }
    }

    /**
     * Sous-classe API 34+ qui surcharge {@code replaceText}.
     */
    private static final class Api34InputConnection extends EditorInputConnection {
        private Api34InputConnection(EditorView view, int generation) {
            super(view, generation);
        }

        static Api34InputConnection create(EditorView view, int generation) {
            return new Api34InputConnection(view, generation);
        }

        @Override
        public boolean replaceText(int start, int end, CharSequence text,
                                    int newCursorPosition,
                                    android.view.inputmethod.TextAttribute textAttribute) {
            return replaceTextCompat(start, end, text, newCursorPosition);
        }
    }

    /**
     * Sous-classe API 31+ qui surcharge {@code getSurroundingText}.
     */
    private static final class Api31InputConnection extends EditorInputConnection {
        private Api31InputConnection(EditorView view, int generation) {
            super(view, generation);
        }

        static Api31InputConnection create(EditorView view, int generation) {
            return new Api31InputConnection(view, generation);
        }

        @Override
        public android.view.inputmethod.SurroundingText getSurroundingText(
                int beforeLength, int afterLength, int flags) {
            return getSurroundingTextCompat(beforeLength, afterLength, flags);
        }
    }
}
