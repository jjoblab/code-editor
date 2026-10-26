package jo.codeeditor.view;

import androidx.annotation.RestrictTo;

import android.os.Build;
import android.text.InputType;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import android.content.res.Configuration;
import android.text.TextUtils;

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
 *   <li>{@link Api34InputConnection} / {@link Api33InputConnection} /
 *       {@link Api31InputConnection} — sous-classes spécifiques d'API
 *       chargées uniquement sur API 34+ / 33+ / 31+.</li>
 *   <li>Capitales réelles ({@code getCursorCapsMode} / {@code initialCapsMode})
 *       sur une fenêtre bornée — R3 (plan 09).</li>
 *   <li>Instantané atomique {@code takeSnapshot} (API 33) pour la survie de
 *       la composition à une mort de processus — R4 (plan 09).</li>
 *   <li>{@code setImeConsumesInput} (API 34, écriture manuscrite au stylet) :
 *       caret caché pendant la consommation, invalidation légère au retour —
 *       R5 (plan 09).</li>
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
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorImeBridge {

    private final EditorView view;

    EditorImeBridge(EditorView view) {
        this.view = view;
    }

    static final int MAX_EXTRACT_CHARS = 100_000;

    /** R3 : fenêtre max lue pour les capitales (caractères AVANT le caret).
     * getCapsMode ne regarde que vers l'ARRIÈRE — une fenêtre de 1 000
     * caractères suffit à la décision « début de phrase » sans matérialiser
     * le document entier. */
    static final int CAPS_WINDOW_CHARS = 1_000;

    /** R4 : contexte de l'instantané {@code takeSnapshot} de chaque côté du
     * caret (2 048 + 2 048 ≤ plafond Binder confortable). */
    static final int SNAPSHOT_CONTEXT_CHARS = 2_048;

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
        // R3 (plan 09, E3) : capitales réelles au point d'insertion — WORDS +
        // SENTENCES seulement (bits positionnels) : CHARACTERS est un simple
        // écho de la requête dans getCapsMode, l'inclure ferait croire à
        // l'IME qu'un mode TOUT EN MAJUSCULES est actif.
        outAttrs.initialCapsMode = computeCursorCapsMode(
            TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES);
        // R9 (plan 09, E9) : dernier mot à l'hôte — il peut ajuster
        // imeOptions/inputType (ex. NO_PERSONALIZED_LEARNING, action
        // d'entrée personnalisée) sans toucher la bibliothèque.
        if (editorInfoListener != null) {
            editorInfoListener.onBuildEditorInfo(outAttrs);
        }
        view.connectionGeneration++;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return Api34InputConnection.create(view, view.connectionGeneration);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Api33InputConnection.create(view, view.connectionGeneration);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Api31InputConnection.create(view, view.connectionGeneration);
        }
        return new EditorInputConnection(view, view.connectionGeneration);
    }

    // ── R9 : écouteurs hôte (champs ici, façade de réglage sur EditorView) ──

    /** Écouteur facultatif de personnalisation de l'{@link EditorInfo} (R9, E9) :
     * appelé APRÈS les valeurs par défaut de {@link #onCreateInputConnection},
     * l'hôte a le dernier mot (imeOptions, inputType, action d'entrée…). */
    EditorView.OnEditorInfoListener editorInfoListener;

    /** Écouteur facultatif des commandes privées de l'IME (R9, E10). */
    EditorView.OnImePrivateCommandListener imePrivateCommandListener;

    // ── R5 : état « l'IME consomme l'entrée » (écriture manuscrite au stylet) ──

    private boolean imeConsumingInput = false;
    private boolean caretVisibleBeforeIme = true;

    /**
     * R1 (plan 09, E1) — purge de l'état IME au changement de session.
     *
     * <p>Le miroir du clavier décrit l'ANCIEN document : texte extrait,
     * sélection, éventuellement une composition en cours. Sans purge,
     * l'IME rejoue son miroir périmé ou réveille une région fantôme
     * quand l'utilisateur revient frapper. On :</p>
     * <ul>
     *   <li>incrémente la GÉNÉRATION — toute InputConnection vivante
     *       devient muette (ses rapports asynchrones tombent à l'eau,
     *       miroir de closeConnection) ;</li>
     *   <li>invalide le JETON D'EXTRACTION (plus de updateExtractedText
     *       vers un moniteur mort) ;</li>
     *   <li>raz le MODE ANCRE (le moniteur CursorAnchorInfo se
     *       réarmera à la prochaine requête de l'IME) ;</li>
     *   <li>redemande la connexion ({@code restartInput}) si une
     *       connexion a déjà servi — le clavier reconstruit son miroir
     *       sur le NOUVEAU document.</li>
     * </ul>
     */
    void purgeForSessionChange() {
        // Génération 0 : AUCUNE connexion n'a jamais servi — le jeton
        // d'extraction et le mode ancre ne peuvent pas exister (ils ne
        // se posent que DEPUIS une InputConnection) : rien à purger, et
        // un restartInput serait un appel fantôme vers un clavier jamais
        // attaché. Le premier onCreateInputConnection partira propre.
        if (view.connectionGeneration == 0) return;
        // Sinon : la génération monte (toute connexion vivante devient
        // muette — miroir de closeConnection), le moniteur d'extraction
        // et le mode ancre meurent, et le clavier reconstruit son miroir
        // sur le NOUVEAU document.
        view.connectionGeneration++;
        view.extractedTextMonitorToken = -1;
        view.extractedTextMaxChars = MAX_EXTRACT_CHARS; // R6 : la fenêtre demandée mourait avec le moniteur
        view.cursorAnchorMonitorMode = 0;
        InputMethodManager imm = view.imm();
        if (imm != null) {
            imm.restartInput(view);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Texte extrait + ancre de curseur
    // ════════════════════════════════════════════════════════════════

    ExtractedText buildExtractedText() {
        // ★ B19 : la longueur lue sur le document SANS matérialiser le
        // texte — le grand fichier n'est copié que si l'IME demande
        // vraiment une fenêtre complète autour du curseur.
        // R6 (plan 09, E6) : la fenêtre honore le hintMaxChars de la
        // DERNIÈRE requête d'extraction (certains IME demandent
        // exprès de petites fenêtres et paieraient sinon le prix du
        // peloton complet) — bornée au plafond maison MAX_EXTRACT_CHARS.
        int maxChars = (view.extractedTextMaxChars > 0)
            ? Math.min(view.extractedTextMaxChars, MAX_EXTRACT_CHARS)
            : MAX_EXTRACT_CHARS;
        EditorDocument doc = view.session.getDocument();
        int docLen = doc.length();
        ExtractedText et = new ExtractedText();
        if (docLen <= maxChars) {
            String text = view.session.getText();
            et.text = text;
            et.startOffset = 0;
            et.selectionStart = Math.min(view.session.getSelection().start, view.session.getSelection().end);
            et.selectionEnd = Math.max(view.session.getSelection().start, view.session.getSelection().end);
        } else {
            int half = maxChars / 2;
            int caret = view.session.getSelection().start;
            int start = Math.max(0, caret - half);
            int end = Math.min(docLen, start + maxChars);
            if (end - start < maxChars) start = Math.max(0, end - maxChars);
            // ★ B19 : tranche via la rope — pas de copie du document entier.
            et.text = doc.subText(start, end);
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
        float caretX = textAreaLeft + col * charWidth - view.scroll.hOffset;
        float caretY = view.metrics.getPadTop() + line * lineHeight - view.scroll.vOffset;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        if (view.getMatrix() != null) matrix.set(view.getMatrix());
        android.view.inputmethod.CursorAnchorInfo.Builder builder =
            new android.view.inputmethod.CursorAnchorInfo.Builder();
        builder.setMatrix(matrix);
        builder.setInsertionMarkerLocation(caretX, caretY, caretY + lineHeight * 0.75f, caretY + lineHeight, 0);
        int[] comp = view.session.getComposingRegion();
        if (comp != null && comp.length >= 2) {
            builder.setComposingText(comp[0], doc.subText(comp[0], comp[1])); // ★ B19
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

    // ── R3 : capitales réelles au caret ───────────────────────────

    /**
     * R3 (plan 09, E3) — mode de capitalisation réel à la position du
     * caret, via {@link TextUtils#getCapsMode} sur une FENÊTRE BORNÉE
     * (jamais le document entier) : getCapsMode ne scanne que vers
     * l'ARRIÈRE depuis le curseur, les 1 000 caractères précédents
     * suffisent à la décision « début de phrase / de mot ».
     *
     * <p>Alimente {@code EditorInfo.initialCapsMode} (la majuscule en
     * début de phrase s'active) et répond à
     * {@code InputConnection.getCursorCapsMode}. {@code reqModes == 0}
     * → 0 (aucune information demandée).</p>
     */
    int computeCursorCapsMode(int reqModes) {
        if (view.session == null || reqModes == 0) return 0;
        // Le point d'insertion de l'IME est le DÉBUT de la sélection.
        int caret = Math.min(view.session.getSelection().start, view.session.getSelection().end);
        int windowStart = Math.max(0, caret - CAPS_WINDOW_CHARS);
        CharSequence window = view.session.getDocument().subText(windowStart, caret); // ★ B19
        return TextUtils.getCapsMode(window, window.length(), reqModes);
    }

    // ── R4 : instantané atomique (API 33) ──────────────────────────

    /**
     * R4 (plan 09, E4) — instantané atomique de l'état d'édition pour
     * le système ({@code InputConnection.takeSnapshot}, API 33) :
     * texte environnant (2 048 caractères de chaque côté du caret,
     * bornés), sélection absolue, région de composition (offsets
     * ABSOLUS du document, -1/-1 sans composition — miroir des
     * candidats d'updateSelection) et capitales au caret.
     *
     * <p>Le système s'en sert quand l'application est tuée en
     * arrière-plan : la composition en cours survit à la mort du
     * processus. {@code null} si indisponible (pas de session, API < 33).</p>
     */
    android.view.inputmethod.TextSnapshot buildTextSnapshot() {
        // Garde SDK_INT explicite — TextSnapshot est API 33+. La méthode
        // n'est appelée que depuis Api33InputConnection.takeSnapshot,
        // instancié uniquement quand SDK_INT >= TIRAMISU ; la garde
        // interne rend ce contrat explicite pour lint (et sûr si un
        // futur appelant l'oublie).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null;
        }
        EditorSession s = view.session;
        if (s == null) return null;
        EditorDocument doc = s.getDocument();
        int caret = s.getSelection().start;
        int selStart = Math.min(s.getSelection().start, s.getSelection().end);
        int selEnd = Math.max(s.getSelection().start, s.getSelection().end);
        int start = Math.max(0, caret - SNAPSHOT_CONTEXT_CHARS);
        int end = Math.min(doc.length(), caret + SNAPSHOT_CONTEXT_CHARS);
        String text = doc.subText(start, end); // ★ B19 : tranche, pas de copie complète
        android.view.inputmethod.SurroundingText surrounding =
            new android.view.inputmethod.SurroundingText(
                text, selStart - start, selEnd - start, start);
        int[] comp = s.getComposingRegion();
        int compStart = (comp != null && comp.length >= 2) ? comp[0] : -1;
        int compEnd = (comp != null && comp.length >= 2) ? comp[1] : -1;
        int caps = computeCursorCapsMode(
            TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES);
        return new android.view.inputmethod.TextSnapshot(surrounding, compStart, compEnd, caps);
    }

    // ── R5 : écriture manuscrite au stylet (API 34) ──────────────────

    /**
     * R5 (plan 09, E5) — l'IME consomme l'entrée ({@code setImeConsumesInput},
     * API 34 : écriture manuscrite directe au stylet en cours). Contrat
     * de la plateforme : l'éditeur ne reçoit plus les frappes — il CACHE
     * le caret pendant la consommation ; au RETOUR ({@code false}), le
     * caret revit et l'état IME est ré-invalidé via
     * {@code InputMethodManager.invalidateInput} (le protocole léger
     * API 33 appairé à {@code takeSnapshot} : le système re-capture
     * l'état d'édition à la demande, sans restartInput complet).
     *
     * @return vrai — la requête est acceptée (le protocole d'écriture
     *     manuscrite devient disponible pour ce champ)
     */
    boolean onImeConsumesInputChanged(boolean consumes) {
        if (imeConsumingInput != consumes) {
            imeConsumingInput = consumes;
            if (consumes) {
                // Cacher le caret : l'éditeur ne reçoit plus l'entrée.
                caretVisibleBeforeIme = view.caretVisible;
                view.caretVisible = false;
            } else {
                // Le caret revit (à sa visibilité d'avant-consommation).
                view.caretVisible = caretVisibleBeforeIme;
                view.caretAnim.onEditOrMove();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    InputMethodManager imm = view.imm();
                    if (imm != null) {
                        imm.invalidateInput(view);
                    }
                }
            }
            view.postInvalidate();
        }
        return true;
    }

    /** Vrai pendant que l'IME consomme l'entrée (écriture au stylet en cours). */
    boolean isImeConsumingInput() {
        return imeConsumingInput;
    }

    // ── R8 : politique d'affichage du clavier logiciel ──────────────

    /**
     * R8 (plan 09, E7) — vrai si un clavier MATERIEL est présent et
     * exposé (configuration courante de la vue).
     */
    boolean isHardwareKeyboardVisible() {
        return isHardwareKeyboardVisible(view.getResources().getConfiguration());
    }

    /** Variante pure (testable) : la configuration est fournie par l'appelant. */
    static boolean isHardwareKeyboardVisible(Configuration cfg) {
        return cfg != null
            && cfg.keyboard != Configuration.KEYBOARD_NOKEYS
            && cfg.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    /**
     * R8 (plan 09, E7 + E8) — montre le clavier logiciel SI (et seulement
     * si) l'éditeur est éditable et qu'aucun clavier matériel n'est
     * exposé. Chemin UNIFIÉ du tap, de l'appui long, du bouton public et
     * du refocus :
     * <ul>
     *   <li>E8 : en LECTURE SEULE, le tap ne fait plus monter un clavier
     *       dont les frappes seraient silencieusement ignorées ;</li>
     *   <li>E7 : avec un clavier MATÉRIEL exposé, le tap ne fait pas
     *       surgir le clavier logiciel (l'utilisateur tape dessus) ;</li>
     *   <li>sans clavier matériel : {@code wantsKeyboard} est armé et le
     *       clavier logiciel est demandé.</li>
     * </ul>
     */
    public void showSoftInputIfEditable() {
        InputMethodManager imm = view.imm();
        if (imm == null) return;
        if (view.session != null && view.session.isReadOnly()) {
            view.wantsKeyboard = false;
            return;
        }
        if (isHardwareKeyboardVisible()) {
            view.wantsKeyboard = false;
            return;
        }
        view.wantsKeyboard = true;
        imm.showSoftInput(view, 0);
    }

    // ════════════════════════════════════════════════════════════════
    // Implémentation de Session.ImeListener
    // ════════════════════════════════════════════════════════════════

    /**
     * Reçoit les rappels de {@link EditorSession} et les pousse vers le
     * {@link InputMethodManager}. Enregistré comme listener IME de la session.
     */
    public final EditorSession.ImeListener listener = new EditorSession.ImeListener() {
        @Override
        public void onTextChanged(EditSpan span) {
            // CaretAnimator.onEditOrMove() est le point d'entrée unique —
            // il met à jour l'horodatage de dernière activité, réinitialise
            // la bascule de clignotement, rend le caret visible et annule
            // tout glissement en cours.
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
            // B9 (retour terrain n°6) : PAS de recentrage pendant un drag
            // de sélection (poignée ou drag-select au doigt). Chaque
            // application de sélection du drag passait ICI et déclenchait
            // scrollCaretIntoView, qui tirait le viewport vers sel.start
            // pendant que l'auto-scroll du drag le poussait dans la
            // direction du doigt — les deux luttaient à chaque MOVE et
            // les lignes « secouaient ». Pendant le drag, le geste et son
            // auto-scroll dirigent seuls le viewport ; le recentrage
            // reprend au geste suivant (frappe, tap, clavier).
            if (!view.isSelectionDragActive()) {
                view.scrollManager.scrollCaretIntoView();
            }
            view.refreshSignatureHelp();
            // Rafraîchit les surbrillances de document quand le caret bouge —
            // le symbole sous le caret a changé, donc le serveur LSP doit
            // être réinterrogé pour les nouvelles occurrences.
            view.scheduleDocumentHighlights();
            // Occurrences du mot sous le caret (4.4.0) — recalcul différé
            // au repos : le mot touché par le caret a changé.
            view.scheduleWordOccurrences();
            // Surbrillance des crochets appariés — recalcul synchrone
            // (scan borné, recalculé à chaque frame).
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
            if (t.indexOf('\n') >= 0 && view.completion.visible) {
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
            EditorDocument docB = s.getDocument();
            int caret = s.getSelection().start;
            int charBefore = codePointsToCharsBackward(docB.charSequence(), caret, beforeLength); // ★ B19
            int charAfter = codePointsToCharsForward(docB.charSequence(), caret, afterLength); // ★ B19
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
            // R2 (plan 09, E2) : la réponse traverse le binder (transaction
            // plafonnée ~1 Mo partagé) — une sélection géante demandée par
            // un clavier curieux (recherche, réécriture) levait une
            // TransactionTooLargeException. Borne symétrique à
            // imeTextBeforeCursor : MAX_IPC_TEXT.
            return s.imeSelectedText();
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
            // R6 (plan 09, E6) : honorer le hintMaxChars de la requête —
            // les mises à jour ULTÉRIEURES du moniteur garderont la même
            // fenêtre (le miroir de l'IME reste à la taille demandée).
            view.extractedTextMaxChars = request.hintMaxChars > 0
                ? request.hintMaxChars : MAX_EXTRACT_CHARS;
            return view.imeBridge.buildExtractedText();
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            // R3 (plan 09, E3) : capitales réelles au caret — la base
            // répondait 0, l'IME ne savait pas activer la majuscule en
            // début de phrase.
            EditorSession s = session();
            if (s == null) return 0;
            return view.imeBridge.computeCursorCapsMode(reqModes);
        }

        @Override
        public boolean performPrivateCommand(String action, android.os.Bundle data) {
            // R9 (plan 09, E10) : les commandes privées des claviers ont
            // un canal — routées vers l'écouteur hôte facultatif (vrai si
            // traitées, faux sinon : la base ignore et répond faux).
            if (view.imeBridge.imePrivateCommandListener != null) {
                return view.imeBridge.imePrivateCommandListener.onImePrivateCommand(action, data);
            }
            return super.performPrivateCommand(action, data);
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
            String text = s.getDocument().subText(start, end); // ★ B19
            int selectionStart = selStart - start;
            int selectionEnd = selEnd - start;
            int offset = start;
            return new android.view.inputmethod.SurroundingText(text, selectionStart, selectionEnd, offset);
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            EditorSession s = session();
            if (s == null) return false;
            if (view.completion.visible) {
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
                    // Fermeture best-effort des lots IME ouverts : la
                    // connexion est déjà morte, un lot résiduel sera purgé
                    // par la prochaine frappe.
                    try { while (s.isInBatch()) s.endBatch(); } catch (RuntimeException ignored) {
                        // Fermeture best-effort : voir commentaire ci-dessus.
                    }
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

        private static int codePointsToCharsBackward(CharSequence text, int from, int codePoints) {
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

        private static int codePointsToCharsForward(CharSequence text, int from, int codePoints) {
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
     * Sous-classe API 34+ : {@code replaceText} (rétro-correction) et
     * {@code setImeConsumesInput} (protocole d'écriture manuscrite au
     * stylet — R5, plan 09).
     */
    private static final class Api34InputConnection extends Api33InputConnection {
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

        @Override
        public boolean setImeConsumesInput(boolean imeConsumesInput) {
            // R5 (plan 09, E5) : sans retour positif, la saisie au stylet
            // sur la zone d'édition restait un simple geste — le protocole
            // d'écriture manuscrite directe exige que l'éditeur l'accepte.
            // Le caret se cache pendant la consommation, revit au retour
            // avec invalidation légère (voir onImeConsumesInputChanged).
            return view.imeBridge.onImeConsumesInputChanged(imeConsumesInput);
        }
    }

    /**
     * Sous-classe API 33+ : {@code takeSnapshot} (instantané atomique de
     * l'état d'édition, R4, plan 09) — héritée par {@link Api34InputConnection}.
     */
    static class Api33InputConnection extends EditorInputConnection {
        Api33InputConnection(EditorView view, int generation) {
            super(view, generation);
        }

        static Api33InputConnection create(EditorView view, int generation) {
            return new Api33InputConnection(view, generation);
        }

        @Override
        public android.view.inputmethod.TextSnapshot takeSnapshot() {
            // R4 (plan 09, E4) : le système sauvegarde l'état IME quand
            // l'application est tuée en arrière-plan — la composition en
            // cours survit à une mort de processus.
            return view.imeBridge.buildTextSnapshot();
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
