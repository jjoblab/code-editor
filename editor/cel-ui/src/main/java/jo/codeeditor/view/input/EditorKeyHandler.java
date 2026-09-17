package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorCommands;
import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.os.SystemClock;
import android.view.KeyEvent;

import jo.codeeditor.document.EditorDocument;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Gestionnaire du clavier physique, extrait d'EditorView — traite toute
 * l'entrée clavier matérielle : {@code onKeyDown} avec sa cascade complète
 * de switch (navigation popup, raccourcis Ctrl, touches de
 * déplacement/édition, repli sur caractère imprimable).
 *
 * <p><b>Keymap piloté par les données :</b> les cascades de raccourcis
 * Ctrl et de déplacement/édition ont été remplacées par une recherche
 * dans {@link EditorKeymap} (portage des {@code EditorKeymap}/
 * {@code EditorCommands} de CodeAssist). L'événement touche est résolu
 * en un identifiant de commande ({@link EditorCommands}) et distribué
 * par {@link #executeCommand} ; les hôtes rebindent les commandes via
 * {@code EditorView.setKeymap(EditorKeymap)}. La table par défaut
 * reproduit le comportement historique, et les intercepteurs de popup
 * dépendants de l'état (navigation complétion / aide de signature /
 * actions de code / aller-au-symbole quand visibles) s'exécutent AVANT
 * le keymap pour garder la priorité.</p>
 *
 * <p>EditorView délègue {@code onKeyDown} à cette classe. Le gestionnaire
 * de touches rappelle l'API publique/package-private d'EditorView pour :
 * <ul>
 *   <li>Navigation popup : {@code completionSelectUp/Down/Accept},
 *       {@code goToSymbolSelect/Accept}, {@code applySelectedCodeAction},
 *       {@code dismiss*}</li>
 *   <li>Édition : {@code session.backspace/commitText/moveHorizontal/...},
 *       {@code onTextChanged}</li>
 *   <li>Presse-papiers : {@code copy/cut/paste}</li>
 *   <li>Zoom : {@code setFontScale/clampFontScale}</li>
 *   <li>Déclencheurs : {@code refreshCompletion/triggerSignatureHelp/showQuickDoc/
 *       showRename/showGoToLine/showGoToSymbol/showCodeActions}</li>
 * </ul>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorKeyHandler {

    private final EditorView view;

    /**
     * État de séquence (chord) en attente (portage du
     * {@code Outcome.Pending} de CodeAssist). Quand non-null, le prochain
     * événement touche est comparé aux secondes frappes des chords ; l'état
     * expire silencieusement {@link #CHORD_TIMEOUT_MS} après son armement
     * (contrôlé via {@link #clock} au prochain événement — pas de Handler,
     * pas de fuite).
     */
    private EditorKeymap.KeyStroke pendingChord;
    private long pendingChordDeadline;

    /** Durée de vie d'un chord en attente (VS Code utilise ~2 s). */
    private static final long CHORD_TIMEOUT_MS = 2000;

    /** Horloge injectable (les tests figent/avancent le temps de façon déterministe). */
    LongSupplier clock = SystemClock::uptimeMillis;

    public EditorKeyHandler(EditorView view) {
        this.view = view;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (view.session == null) return false;
        boolean shift = event.isShiftPressed();
        boolean ctrl = event.isCtrlPressed();

        // ── Navigation popup de complétion (quand visible) ─────────────
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
        // ── Navigation popup d'aide de signature ───────────────
        // Haut/Bas fait défiler les surcharges (activeSignature) quand
        // il y a plus d'une signature. Échap ferme. Quand une seule
        // signature est disponible, Haut/Bas retombent sur le déplacement
        // du caret (pour que l'utilisateur puisse quand même naviguer
        // dans les arguments de l'appel).
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
        // ── Fermeture du popup de doc rapide (Échap) ──────────────────────
        if (view.quickDocVisible && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            view.dismissQuickDoc();
            return true;
        }
        // ── Fermeture de la fiche de liste des diagnostics groupés (Échap) ──
        if (view.diagnosticListSheetLine >= 0 && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            view.dismissDiagnosticListSheet();
            return true;
        }
        // ── Fermeture du popup de détail de diagnostic (Échap) ──────
        if (view.diagnosticPopupVisible && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            view.dismissDiagnosticPopup();
            return true;
        }
        // ── Navigation popup d'actions de code (quand visible) ─────────
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
        // ── Navigation popup aller-au-symbole (quand visible) ─────────
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

        // ── Résolution de séquence (chord) en attente ──
        // S'exécute APRÈS les intercepteurs de popup (un popup ouvert
        // garde la priorité) et AVANT la distribution mono-touche :
        // pendant qu'un chord est en attente, la touche courante
        // complète la séquence ou est traitée comme une frappe fraîche.
        if (pendingChord != null) {
            if (clock.getAsLong() > pendingChordDeadline) {
                // Expiré — abandonne l'état en attente, traitement normal.
                pendingChord = null;
            } else if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                // Échap annule un chord en attente (consommé).
                pendingChord = null;
                return true;
            } else {
                EditorKeymap.ChordBinding chord =
                    view.keymap.resolveChord(pendingChord, keyCode, ctrl, shift);
                pendingChord = null;
                if (chord != null) {
                    return executeCommand(chord.command);
                }
                // Pas une seconde frappe de chord — repli : la touche est
                // traitée comme une frappe fraîche (elle peut elle-même
                // démarrer un nouveau chord, contrôlé ci-dessous).
            }
        }

        // ── Distribution de commandes pilotée par les données ──
        // Le keymap (rebindable via EditorView.setKeymap) résout
        // l'événement en une commande ; la table par défaut est un
        // portage verbatim des cascades de switch historiques
        // (raccourcis Ctrl et déplacement/édition).
        EditorKeymap.Binding binding = view.keymap.resolve(keyCode, ctrl, shift);
        if (binding != null) {
            return executeCommand(binding.command);
        }

        // ── Cette touche DÉMARRE-t-elle un chord ? ──
        // Atteint seulement quand aucune liaison mono-touche n'a résolu,
        // ainsi les chords ne volent jamais de touches à la table par
        // défaut (sans chord).
        EditorKeymap.KeyStroke start = view.keymap.resolveChordStart(keyCode, ctrl, shift);
        if (start != null) {
            pendingChord = start;
            pendingChordDeadline = clock.getAsLong() + CHORD_TIMEOUT_MS;
            return true; // consommé — attente de la seconde frappe
        }

        // Repli sur caractère imprimable : respecte Shift / AltGr via unicodeChar.
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
     * Exécute un identifiant {@link EditorCommands}. Renvoie vrai quand la
     * touche a été consommée. {@link EditorCommands#CODE_ACTIONS} renvoie
     * faux quand la ligne du caret n'a pas d'actions (la touche retombe
     * alors, exactement comme la cascade historique {@code if (ctrl)}).
     */
    private boolean executeCommand(String command) {
        switch (command) {
            // ── Historique ───────────────────────────────────────
            case EditorCommands.UNDO:
                view.session.undo(); view.onTextChanged(); return true;
            case EditorCommands.REDO:
                view.session.redo(); view.onTextChanged(); return true;

            // ── Sélection / presse-papiers ─────────────────────────
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

            // ── Fichier / actions hôte ───────────────────────────
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

            // ── Intelligence du langage ─────────────────────────
            case EditorCommands.TRIGGER_COMPLETION:
                view.refreshCompletion();
                return true;
            case EditorCommands.TRIGGER_SIGNATURE_HELP:
                view.triggerSignatureHelp();
                return true;
            case EditorCommands.CODE_ACTIONS:
                // Ctrl+. — consommé seulement quand la ligne du caret a des
                // actions (convention LSP ; comportement historique préservé).
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
                // Ctrl+Shift+L — contrairement à Ctrl+., ouvre toujours
                // (l'état vide affiche « no actions »), une alternative
                // pour les claviers logiciels.
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

            // ── Édition ─────────────────────────────────────────
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

            // ── Déplacement du caret (Shift = étendre) ───────────
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
