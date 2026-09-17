package jo.codeeditor.session;

import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

/**
 * Pont IME d'une session : détient le listener hôte, la région de
 * composition (bornes + décalage à gravité gauche sur édition) et le suivi
 * d'auto-espace des claviers SwiftKey/Gboard. Existe pour isoler de
 * {@link EditorSession} la traduction des appels InputConnection en
 * mutations de session, sans en changer le comportement.
 */
final class ImeBridge {

    private final EditorSession session;

    private EditorSession.ImeListener listener;
    private int composingStart = -1;
    private int composingEnd = -1;

    /** Dernier offset où un symbole a été commité dans ce lot (pour la détection de l'auto-espace séparée). */
    private int batchImeSymbolCommitOffset = -1;

    /** Plafond de taille pour toute charge utile de texte poussée à travers le binder IME. */
    private static final int MAX_IPC_TEXT = 4096;

    ImeBridge(EditorSession session) {
        this.session = session;
    }

    // ── Listener / état de composition ────────────────────────

    void setListener(EditorSession.ImeListener listener) {
        this.listener = listener;
    }

    /** Le listener hôte courant, ou null (les appelants de la session testent la nullité eux-mêmes). */
    EditorSession.ImeListener listener() { return listener; }

    /** Vrai si l'IME est en état de composition. */
    boolean isComposing() {
        return composingStart >= 0;
    }

    /** La région de composition courante {start, end}, ou null si pas en composition. */
    int[] composingRegion() {
        if (composingStart < 0) return null;
        return new int[]{composingStart, composingEnd};
    }

    /**
     * Pousse l'état de sélection / composition courant vers le listener IME.
     */
    void notifySelectionChanged(int selStart, int selEnd) {
        if (listener != null) {
            listener.onSelectionChanged(selStart, selEnd, composingStart, composingEnd);
        }
    }

    /** Notifie le listener d'une édition de texte (no-op sans listener). */
    void onTextChanged(EditSpan span) {
        if (listener != null) {
            listener.onTextChanged(span);
        }
    }

    /**
     * Décale la région de composition après une édition — si elle chevauchait
     * l'édition, le miroir de l'IME pointerait sinon sur le mauvais texte.
     * Utiliser mapStart pour les DEUX extrémités (gravité gauche) car la
     * région de composition est une unité sémantique unique — une édition à
     * sa frontière ne doit PAS l'étendre. (Les plages de diagnostics
     * utilisent la gravité droite pour les fins, ce qui étendrait à tort la
     * région de composition quand du texte est inséré juste après elle.)
     */
    void shiftComposingRegion(EditSpan span) {
        if (composingStart >= 0) {
            composingStart = DiagnosticShift.mapStart(composingStart, span);
            composingEnd = DiagnosticShift.mapStart(composingEnd, span);
            if (composingStart >= composingEnd) {
                composingStart = -1;
                composingEnd = -1;
            }
        }
    }

    // ── Commit / composition ──────────────────────────────────

    /**
     * L'IME commite du texte (remplace la région de composition ou insère au
     * curseur).
     * <p>
     * Gère l'auto-espace de SwiftKey / Gboard après ponctuation dans les
     * DEUX formes :
     * <ul>
     *   <li><b>Groupée</b> — l'IME commite {@code "p "} en une seule chaîne
     *       avec {@code newCursorPosition == 1}. On retire l'espace finale et
     *       on force un {@code restartInput}.</li>
     *   <li><b>Séparée</b> — l'IME commite {@code "p"} puis un {@code " "}
     *       distinct dans le même lot. On avale le commit d'espace nue quand
     *       il suit un symbole dans le même lot.</li>
     * </ul>
     * Sans cela, taper {@code foo() ;} donnerait {@code foo() ;} (avec
     * l'auto-espace), ce qui est faux pour du code.
     */
    void commitText(String text) {
        if (text == null || text.isEmpty()) {
            // Commit vide = l'IME confirme la fin de la composition. Simplement effacer.
            composingStart = -1;
            composingEnd = -1;
            return;
        }
        // Détecter l'auto-espace groupée : "p " avec le curseur à la position 1
        // (l'IME s'attend à ce que le caret atterrisse AVANT l'espace, pour que
        // l'utilisateur puisse continuer à taper). L'espace finale est
        // l'auto-espace du clavier après un symbole de ponctuation.
        if (text.length() >= 2 && text.charAt(text.length() - 1) == ' '
            && isAutoSpacedSymbol(text.charAt(text.length() - 2))) {
            String stripped = text.substring(0, text.length() - 1);
            int commitStart, commitEnd;
            if (composingStart >= 0 && composingEnd > composingStart) {
                commitStart = composingStart;
                commitEnd = composingEnd;
                composingStart = -1;
                composingEnd = -1;
            } else {
                commitStart = session.selection.start;
                commitEnd = session.selection.end;
            }
            session.replaceRange(commitStart, commitEnd, stripped);
            // Le modèle de l'IME a encore l'espace fantôme — forcer un restart.
            if (listener != null && !listener.isSyncingExtractedText()) {
                listener.onRestartInput();
            }
            return;
        }
        // Détecter l'auto-espace séparée : une " " nue commitée juste après un
        // commit de symbole dans le même lot. Suivre l'offset du dernier
        // commit de symbole pour détecter cela au commit suivant.
        if (text.equals(" ") && batchImeSymbolCommitOffset >= 0) {
            // C'est l'auto-espace du clavier après un symbole — l'avaler.
            batchImeSymbolCommitOffset = -1;
            if (listener != null && !listener.isSyncingExtractedText()) {
                listener.onRestartInput();
            }
            return;
        }
        // Suivre les commits de symboles pour que le prochain commit d'espace
        // nue puisse être détecté comme l'auto-espace du clavier.
        if (text.length() == 1 && isAutoSpacedSymbol(text.charAt(0))) {
            batchImeSymbolCommitOffset = session.selection.start;
        } else {
            batchImeSymbolCommitOffset = -1;
        }
        if (composingStart >= 0 && composingEnd > composingStart) {
            // Remplacer la région de composition
            int commitStart = composingStart;
            int commitEnd = composingEnd;
            composingStart = -1;
            composingEnd = -1;
            session.replaceRange(commitStart, commitEnd, text);
        } else {
            session.replaceRange(session.selection.start, session.selection.end, text);
        }
    }

    /** Symboles après lesquels SwiftKey/Gboard insèrent une auto-espace. */
    private static boolean isAutoSpacedSymbol(char c) {
        return c == ')' || c == ']' || c == '}' || c == ';'
            || c == ',' || c == '.' || c == '!' || c == '?'
            || c == ':' || c == '%';
    }

    /**
     * L'IME définit le texte de composition (affiché comme aperçu inline).
     * <p>
     * REMPLACE toujours la région de composition existante (ou la sélection
     * courante si aucune région de composition n'est encore définie).
     * Ajouter au lieu de remplacer casserait le contrat « je vous ai envoyé
     * un nouveau mot de composition, remplacez l'ancien » de l'IME — taper
     * "hello" puis backspace produirait "hellohell" car la suppression de
     * l'IME cible le mot précédent.
     */
    void setComposingText(String text, int newCursorPosition) {
        int regionStart, regionEnd;
        if (composingStart >= 0) {
            regionStart = composingStart;
            regionEnd = composingEnd;
        } else {
            // Premier appel de composition — remplacer la sélection courante
            // pour que le mot de composition remplace ce qui était sélectionné.
            regionStart = session.selection.start;
            regionEnd = session.selection.end;
        }
        // Épisser le nouveau texte de composition.
        int insertionLen = text.length();
        // Utiliser une mutation directe pour ne pas déclencher récursivement
        // onRestartInput pour nos propres éditions de composition.
        session.doReplaceRange(regionStart, regionEnd, text, regionStart + insertionLen);
        // Mettre à jour la région de composition vers la nouvelle plage de texte.
        composingStart = regionStart;
        composingEnd = regionStart + insertionLen;
        // Calculer le caret selon le contrat IME :
        //   newCaretPos > 0  → relatif à la FIN du nouveau texte de composition
        //   newCaretPos <= 0 → relatif au DÉBUT du nouveau texte de composition
        // (avec newCaretPos == 1 → immédiatement après le texte inséré)
        int caretPos;
        if (newCursorPosition > 0) {
            caretPos = composingEnd + (newCursorPosition - 1);
        } else {
            caretPos = composingStart + newCursorPosition;
        }
        caretPos = Math.max(0, Math.min(session.doc.length(), caretPos));
        session.setSelection(Selection.cursor(caretPos));
    }

    /**
     * L'IME définit la région de composition.
     */
    void setComposingRegion(int start, int end) {
        if (start >= end) {
            composingStart = -1;
            composingEnd = -1;
        } else {
            composingStart = Math.max(0, Math.min(start, session.doc.length()));
            composingEnd = Math.max(composingStart, Math.min(end, session.doc.length()));
        }
        notifySelectionChanged(session.selection.start, session.selection.end);
    }

    /**
     * L'IME termine la composition (commite le texte de composition comme
     * final).
     */
    void finishComposing() {
        composingStart = -1;
        composingEnd = -1;
        notifySelectionChanged(session.selection.start, session.selection.end);
    }

    // ── Suppression / sélection / remplacement ────────────────

    /**
     * L'IME supprime du texte autour du curseur.
     * <p>
     * Les suppressions de blancs restent LITTÉRALES — l'échange « pas
     * d'espace avant la ponctuation » de SwiftKey est identique octet par
     * octet à un tap backspace utilisateur, et les règles de backspace
     * intelligent mangeraient du vrai code à chaque échange (le bug « taper
     * ) supprime mon indentation »). Les règles intelligentes ne
     * s'appliquent que via le backspace de la session.
     */
    void deleteSurrounding(int beforeLength, int afterLength) {
        int selStart = session.selection.start;
        int selEnd = session.selection.end;
        int delStart = Math.max(0, selStart - beforeLength);
        int delEnd = Math.min(session.doc.length(), selEnd + afterLength);
        if (delEnd > delStart) {
            session.replaceRange(delStart, delEnd, "");
        }
    }

    /**
     * L'IME définit la sélection (offsets absolus). Bornée au document.
     */
    void setSelection(int start, int end) {
        start = Math.max(0, Math.min(start, session.doc.length()));
        end = Math.max(0, Math.min(end, session.doc.length()));
        if (start == end) {
            session.setSelection(Selection.cursor(start));
        } else {
            session.setSelection(Selection.range(Math.min(start, end), Math.max(start, end)));
        }
    }

    /**
     * L'IME remplace une plage absolue ({@code replaceText} API 34+).
     * Utilisé par certains IMEs pour la rétro-correction. Passe par le
     * replaceRange de la session pour que l'annulation, l'épissure de styles
     * et le décalage des diagnostics restent cohérents.
     */
    void replaceText(int start, int end, String text, int newCursorPosition) {
        int regionStart = Math.max(0, Math.min(start, session.doc.length()));
        int regionEnd = Math.max(regionStart, Math.min(end, session.doc.length()));
        int newCaret;
        if (newCursorPosition > 0) {
            newCaret = regionStart + text.length() + (newCursorPosition - 1);
        } else {
            newCaret = regionStart + newCursorPosition;
        }
        newCaret = Math.max(0, Math.min(session.doc.length() + text.length(), newCaret));
        session.replaceRangeWithCaret(regionStart, regionEnd, text, newCaret);
    }

    // ── Contexte pour la prédiction ───────────────────────────

    /**
     * Jusqu'à {@code n} caractères avant le caret. Utilisé par les IMEs qui
     * lisent le contexte pour la prédiction. Borné à MAX_IPC_TEXT pour
     * rester compatible Binder.
     */
    String textBeforeCursor(int n) {
        int end = session.selection.start;
        int start = Math.max(0, end - Math.min(n, MAX_IPC_TEXT));
        return session.doc.getText().substring(start, end);
    }

    /**
     * Jusqu'à {@code n} caractères après le caret. Utilisé par les IMEs qui
     * lisent le contexte pour la prédiction. Borné à MAX_IPC_TEXT pour
     * rester compatible Binder.
     */
    String textAfterCursor(int n) {
        int start = session.selection.end;
        int end = Math.min(session.doc.length(), start + Math.min(n, MAX_IPC_TEXT));
        return session.doc.getText().substring(start, end);
    }
}
