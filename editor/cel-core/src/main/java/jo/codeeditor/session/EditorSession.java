package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.edit.CommentSyntax;
import jo.codeeditor.edit.EditOps;
import jo.codeeditor.edit.RangeEdit;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

import java.util.*;

/**
 * Moteur d'édition complet : possède le document, la sélection, la pile
 * d'annulation, les diagnostics, les jetons sémantiques, les régions de
 * pliage et les inlay hints.
 * <p>
 * Toutes les mutations passent par {@link #replaceRange(int, int, String)}.
 * <p>
 * Prend en charge : l'édition par lots, l'undo/redo avec coalescence,
 * l'édition intelligente via EditOps, le pont IME, l'auto-close des
 * crochets, l'indentation/désindentation, la bascule de commentaires,
 * les opérations sur lignes.
 */
public class EditorSession {

    // ── État principal ────────────────────────────────────────

    // Package-private : lus par les collaborateurs du package session
    // (RestyleEngine, ImeBridge, CommentToggler) qui détiennent une
    // référence à cette session.
    EditorDocument doc;
    // Package-private : lue/écrite par les collaborateurs du package session
    // (ImeBridge, CommentToggler) qui détiennent une référence à cette session.
    Selection selection;
    private final UndoRecorder recorder;
    // Moteur de tokenization (styledLines, tampons de révision par ligne,
    // restyle synchrone/asynchrone) — détenu par RestyleEngine.
    private final RestyleEngine restyle;
    String language = "java";
    // Bascule de commentaires (surcharge de syntaxe + bascule ligne/bloc) —
    // détenue par CommentToggler.
    private final CommentToggler comments = new CommentToggler(this);

    // ── Diagnostics / tokens / inlays / plis ────────────────
    // Diagnostics, jetons sémantiques et inlay hints : délégués au
    // collaborateur SessionAnnotations (listes volatiles remplacées à chaque
    // setter/édition, révisions globales et index par ligne mémoïsés).
    private final SessionAnnotations annotations = new SessionAnnotations();
    // Régions de pliage : déléguées au collaborateur FoldRegions (liste
    // volatile, révision monotone, application des défauts serveur).
    private final FoldRegions folds = new FoldRegions();

    // ── Tampons de révision par ligne / globaux pour le cache de rendu ──
    // (lineTextRevisions, lineTextRevCounter, listener de décalage de
    // lignes : détenus par RestyleEngine.)

    // Mode lecture-seule (consoles log embarquées).
    // Quand actif, toute mutation de texte via replaceRange*/commitText/
    // typeChar/backspace est ignorée silencieusement — la sélection,
    // le scroll et la copie restent fonctionnels. Utilisé par
    // ConsoleLogView côté app CodeIDE pour empêcher l'édition accidentelle
    // du contenu console tout en profitant du rendu/coloration de l'éditeur.
    private volatile boolean readOnly = false;

    // ── Édition par lots ─────────────────────────────────────

    // Comptabilité d'annulation (pile UndoManager, coalescence des frappes
    // simples, tampon d'édition par lots) — détenue par UndoRecorder.

    // ── IME ───────────────────────────────────────────────────────

    // Pont IME (listener hôte, région de composition, auto-espace) —
    // détenu par ImeBridge.
    private final ImeBridge ime = new ImeBridge(this);

    /**
     * Listener pour les événements IME.
     * <p>
     * Implémenté par la plateforme hôte (typiquement le pont InputConnection
     * de la View Android) pour que {@link EditorSession} puisse pousser ses
     * changements d'état vers l'InputMethodManager.
     */
    public interface ImeListener {
        /** Notifié après chaque édition de texte. Le {@code span} est null quand seule la sélection a bougé. */
        void onTextChanged(jo.codeeditor.shift.EditSpan span);

        /** Notifié après chaque changement de sélection / région de composition. */
        void onSelectionChanged(int selStart, int selEnd, int composingStart, int composingEnd);

        /**
         * Demande à l'hôte d'appeler {@code InputMethodManager.restartInput(view)}.
         * Utilisé après qu'une édition intelligente a divergé de ce que l'IME
         * a livré, pour que l'IME abandonne son tampon de composition périmé
         * et se resynchronise depuis {@link #getSelection()} /
         * {@link #getComposingRegion()}.
         */
        void onRestartInput();

        /**
         * @return vrai si l'IME surveille actuellement le texte extrait
         *     (c.-à-d. qu'un {@code getExtractedText} a armé un moniteur).
         *     Quand vrai, la session saute le {@link #onRestartInput()} disruptif
         *     sur les éditions intelligentes car le push de texte extrait par
         *     édition maintient déjà l'IME exact.
         */
        boolean isSyncingExtractedText();
    }

    // ── Callbacks ─────────────────────────────────────────────

    /**
     * Liste de listeners plutôt qu'un listener unique, pour que la couche
     * LSP puisse enregistrer son forwarder didChange SANS remplacer le
     * listener updateStats de l'app de démo. Le comportement de
     * setOnTextEditListener (listener unique) est préservé : l'appeler
     * retire tous les listeners existants puis ajoute le nouveau.
     */
    private final java.util.List<OnTextEditListener> onTextEditListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private OnSnippetEditListener onSnippetEditListener;

    public interface OnTextEditListener {
        void onTextEdit(int start, int end, String inserted);
    }

    public interface OnSnippetEditListener {
        void onSnippetEdit(int start, int end, String inserted);
    }

    /**
     * Ajoute un listener d'édition de texte SANS retirer les existants.
     * À utiliser depuis le code de la bibliothèque (p. ex. LSP) pour
     * chaîner des listeners.
     */
    public void addOnTextEditListener(OnTextEditListener listener) {
        if (listener != null) onTextEditListeners.add(listener);
    }

    /** Retire un listener d'édition de texte précédemment ajouté. */
    public void removeOnTextEditListener(OnTextEditListener listener) {
        onTextEditListeners.remove(listener);
    }

    /**
     * Listener déclenché après {@link EditorSession#spliceStyles} pour que le
     * {@link jo.codeeditor.cache.LineRenderCache} de la vue puisse décaler ses
     * clés vers le nouveau découpage en lignes. {@code delta > 0} signifie que
     * des lignes ont été insérées à/après {@code fromLine} ; {@code delta < 0}
     * que des lignes ont été supprimées. {@link #onLinesReset()} est déclenché
     * sur un restyle complet (undo/redo/setLanguage) où chaque ligne est
     * re-tokenisée et le cache doit être vidé entièrement.
     */
    public interface OnLinesShiftedListener {
        void onLinesShifted(int fromLine, int delta);
        void onLinesReset();
    }

    // ── Constantes ─────────────────────────────────────────────

    private static final int MAX_UNDO_STEPS = 300;
    // Les préfixes de commentaire sont pilotés par le langage
    // (CommentSyntax.forLanguage / getCommentSyntax).

    // ── Constructeurs ──────────────────────────────────────────────

    public EditorSession() {
        this(EditorDocument.of(""));
    }

    public EditorSession(EditorDocument doc) {
        this.doc = doc;
        this.selection = Selection.cursor(0);
        this.recorder = new UndoRecorder(MAX_UNDO_STEPS);
        this.restyle = new RestyleEngine(this);
        restyle.restyleAll();
    }

    // ── Accesseurs ────────────────────────────────────────────

    public EditorDocument getDocument() { return doc; }
    public Selection getSelection() { return selection; }
    public void setSelection(Selection sel) {
        this.selection = sel;
        notifySelectionChanged();
    }
    public void setSelection(int offset) {
        this.selection = Selection.cursor(offset);
        notifySelectionChanged();
    }
    public String getText() { return doc.getText(); }
    public String getLanguage() { return language; }

    /**
     * Le profil de langage enregistré pour l'identifiant de langage de cette
     * session, ou null quand l'identifiant est inconnu du
     * {@link jo.codeeditor.languages.LanguageRegistry}. Le profil expose la
     * famille, les alias, les extensions, l'ensemble de mots-clés et la
     * syntaxe de commentaire que l'éditeur utilise réellement pour cette
     * session.
     */
    public jo.codeeditor.languages.LanguageProfile getLanguageProfile() {
        return jo.codeeditor.languages.LanguageRegistry.forName(language);
    }

    public List<StyledLine> getStyledLines() { return Collections.unmodifiableList(restyle.styledLines()); }
    public UndoManager getUndoManager() { return recorder.manager(); }
    public List<DiagnosticShift.Diagnostic> getDiagnostics() {
        // La liste publiée n'est jamais mutée après affectation : chaque
        // setter et chaque chemin d'édition REMPLACE la liste (setDiagnostics
        // fait `new ArrayList<>(...)`, le chemin d'édition fait
        // `DiagnosticShift.shiftDiagnostics(...)` qui retourne
        // une liste fraîche). Une vue non modifiable de la liste est donc
        // exactement aussi sûre qu'une copie — et évite une copie O(liste)
        // par APPEL, que le chemin de rendu effectuait une fois par ligne en
        // cache-miss (cf. layoutForLine), soit O(lignesVisibles x diagnostics)
        // par scroll, tout cela en pur déchet pour le GC. Retourner la vue.
        return Collections.unmodifiableList(annotations.diagnostics());
    }
    public List<DiagnosticShift.SemanticToken> getSemanticTokens() {
        // Même élimination de copie que getDiagnostics().
        return Collections.unmodifiableList(annotations.semanticTokens());
    }
    public List<DiagnosticShift.FoldRegion> getFoldRegions() {
        // Même élimination de copie que getDiagnostics().
        return Collections.unmodifiableList(folds.regions());
    }
    public List<DiagnosticShift.InlayHint> getInlayHints() {
        // Même élimination de copie que getDiagnostics().
        return Collections.unmodifiableList(annotations.inlayHints());
    }

    public void setLanguage(String language) {
        this.language = language;
        // Restyle asynchrone pour le parser MAISON.
        //
        // La protection grands documents est locale à chaque boucle de
        // restyle : chaque site d'appel à styleLine passe allowTextMate =
        // (lineCount <= TEXTMATE_LINE_LIMIT), calculé sur SA propre taille
        // de document — un grand document ne coupe donc pas la délégation
        // TextMate des autres onglets ouverts.
        //
        // La mécanique d'async est robuste :
        //   - Snapshot du doc (immutable EditorDocument) → pas de race
        //     avec replaceRange pendant le travail
        //   - Cancellation via pendingRestyle (AtomicReference<Future>)
        //   - Stale-result detection via restyleGeneration (volatile)
        //   - Doc-changed detection via doc != docAtStart (ref identity)
        //   - Atomic swap du volatile styledLines
        //   - Thread interruption check dans la boucle worker
        //   - Listener cross-thread (EditorView posts onLinesReset sur l'UI)
        restyleAllAsync();
        // Détection automatique des régions de pliage quand le langage est défini.
        detectFolds();
        // Nouveau document/langage → les défauts de pliage serveur
        // (imports repliés) peuvent s'appliquer une première fois.
        folds.resetDefaults();
    }

    /**
     * Coupe-circuit TextMate pour les grands documents, décidé à chaque passe
     * de restyle.
     *
     * <p>Les documents dépassant cette limite en nombre de lignes sautent la
     * délégation TextMate et utilisent le tokenizer intégré, dont le coût par
     * ligne est 10–50× inférieur. La porte est passée en paramètre à
     * {@code styleLine(line, state, language, allowTextMate)} à chaque site
     * d'appel, donc calculée depuis LE document de CETTE session — un onglet
     * de 5000 lignes ne désactive pas TextMate pour un petit onglet d'un
     * autre EditorView.</p>
     */
    static final int TEXTMATE_LINE_LIMIT = 800;

    /**
     * Détecte les régions de pliage pour le document + langage courants.
     * Appelée automatiquement par {@link #setLanguage(String)} et peut être
     * appelée manuellement après un grand changement de texte.
     */
    public void detectFolds() {
        folds.detect(doc, language);
    }

    public void setOnTextEditListener(OnTextEditListener listener) {
        onTextEditListeners.clear();
        if (listener != null) onTextEditListeners.add(listener);
    }

    public void setOnSnippetEditListener(OnSnippetEditListener listener) {
        this.onSnippetEditListener = listener;
    }

    /**
     * Enregistre un listener déclenché après chaque splice de lignes / restyle
     * complet. La vue s'en sert pour garder les clés de son
     * {@link jo.codeeditor.cache.LineRenderCache} alignées sur le découpage
     * en lignes du document.
     */
    public void setOnLinesShiftedListener(OnLinesShiftedListener listener) {
        restyle.setLinesShiftListener(listener);
    }

    /**
     * Retourne le tampon de révision de texte de la ligne donnée. Le cache de
     * rendu de la vue s'en sert pour éviter de re-tokeniser les lignes
     * inchangées depuis la dernière frame.
     */
    public int getLineTextRevision(int line) {
        return restyle.lineTextRevision(line);
    }

    // ── Indexes par ligne (approche LineOverlay de CodeAssist) ──
    // Mémoïsation des compartiments par ligne (diagnostics, inlay hints,
    // jetons sémantiques) : détenue par SessionAnnotations, reconstruite
    // paresseusement sur changement d'identité de la liste source.

    /**
     * Retourne tous les diagnostics dont l'offset de DÉBUT se situe sur la
     * ligne du document donnée (portage du {@code diagnosticsByStartLine()}
     * de CodeAssist). Le compartiment est trié par sévérité décroissante
     * (puis par offset de début), donc le premier élément est le diagnostic
     * « principal » de la ligne — celui affiché par la puce. Mémoïsé sur la
     * référence de la liste de diagnostics selon le même schéma que
     * {@link #getInlayHintsForLine} : re-pousser la même liste est gratuit ;
     * une édition (qui remplace la liste via DiagnosticShift) coûte une
     * reconstruction O(D) au lieu d'un filtrage O(D) par ligne interrogée.
     */
    public List<DiagnosticShift.Diagnostic> getDiagnosticsForLine(int line) {
        return annotations.diagnosticsForLine(line, doc);
    }

    /**
     * Retourne les inlay hints tombant sur la ligne du document donnée
     * (offset du hint dans [lineStart, lineEnd]). O(compartiment) au lieu
     * d'un filtrage O(total hints) sur la liste complète par ligne.
     */
    public List<DiagnosticShift.InlayHint> getInlayHintsForLine(int line) {
        return annotations.inlayHintsForLine(line, doc);
    }

    /**
     * Retourne les jetons sémantiques intersectant la ligne du document
     * donnée (un token couvrant les lignes 5..9 apparaît dans chaque
     * compartiment de 5 à 9). O(compartiment) au lieu de O(total tokens)
     * par ligne.
     */
    public List<DiagnosticShift.SemanticToken> getSemanticTokensForLine(int line) {
        return annotations.semanticTokensForLine(line, doc);
    }

    /** Révision globale des jetons sémantiques (incrémentée par {@link #setSemanticTokens}). */
    public int getSemanticTokensRevision() { return annotations.semanticTokensRevision(); }

    /** Révision globale des inlay hints (incrémentée par {@link #setInlayHints}). */
    public int getInlayHintsRevision() { return annotations.inlayHintsRevision(); }

    public void setImeListener(ImeListener listener) {
        ime.setListener(listener);
    }

    // ── Gestion des diagnostics / tokens ───────────────────────

    public void setDiagnostics(List<DiagnosticShift.Diagnostic> diagnostics) {
        annotations.setDiagnostics(diagnostics);
    }

    public void setSemanticTokens(List<DiagnosticShift.SemanticToken> tokens) {
        annotations.setSemanticTokens(tokens);
    }

    public void setFoldRegions(List<DiagnosticShift.FoldRegion> regions) {
        folds.set(regions);
    }

    /**
     * Révision du jeu de plis (incrémentée à chaque mutation).
     * Les consommateurs mémoïsent leurs index dérivés des plis sur cette valeur.
     */
    public int getFoldRevision() {
        return folds.revision();
    }

    /**
     * Applique un jeu de plis AUTORITATIF venu du serveur LSP (portage
     * d'{@code applyCodeFolds} de CodeAssist / EditorSession.kt) :
     * <ul>
     *   <li>une région PRÉCÉDEMMENT repliée (même [start, end]) le reste ;</li>
     *   <li>{@code collapsedByDefault} s'applique UNE SEULE FOIS par
     *       document — un re-tir serveur (après une édition) ne re-plie pas
     *       une région que l'utilisateur a dépliée ;</li>
     *   <li>une région fraîchement apparue suit son défaut.</li>
     * </ul>
     */
    public void applyCodeFolds(List<DiagnosticShift.FoldRegion> fresh) {
        folds.applyCodeFolds(fresh);
    }

    /**
     * Bascule l'état replié de toute région de pliage DÉBUTANT à la ligne du
     * document donnée. Retourne vrai si un pli a été basculé.
     * <p>
     * Les régions de pliage sont appariées par ligne — l'appelant
     * (typiquement un tap sur la gouttière) passe la ligne doc du chevron
     * qu'il veut basculer.
     */
    public boolean toggleFoldAtLine(int docLine) {
        return folds.toggleAtLine(docLine, doc);
    }

    /**
     * Retourne les régions de pliage actuellement repliées (liste fraîche).
     * Utilisée par la vue pour construire un {@link jo.codeeditor.fold.FoldModel}
     * pour le rendu et la conversion offset↔ligne visuelle.
     */
    public List<DiagnosticShift.FoldRegion> getCollapsedFolds() {
        return folds.collapsed();
    }

    /**
     * Retourne vrai si la ligne du document donnée est actuellement masquée
     * par une région de pliage repliée. Méthode de commodité — la vue s'en
     * sert pour sauter le dessin des lignes masquées.
     */
    public boolean isLineFolded(int docLine) {
        return folds.isLineFolded(docLine, doc);
    }

    /**
     * Si le caret est dans une région de pliage repliée, la déplie. Utilisée
     * par la navigation programmatique (go-to-def, rename, résultats de
     * recherche) pour que le caret n'atterrisse jamais dans du texte masqué.
     */
    public void expandFoldAt(int offset) {
        folds.expandAt(offset);
    }

    public void setInlayHints(List<DiagnosticShift.InlayHint> hints) {
        annotations.setInlayHints(hints);
    }

    // ── Édition par lots ─────────────────────────────────────

    /**
     * Commence une édition par lots. Les éditions multiples d'un lot sont
     * regroupées en une seule étape d'annulation.
     */
    public void beginBatch() {
        recorder.begin(selection.start);
    }

    /**
     * Termine une édition par lots. Si cela referme le lot le plus externe,
     * les éditions groupées sont poussées comme une seule étape d'annulation.
     */
    public void endBatch() {
        recorder.end(selection.start);
    }

    public boolean isInBatch() { return recorder.inBatch(); }

    /**
     * Active/désactive le mode lecture-seule.
     * En lecture-seule, les mutations de texte sont ignorées ; le caret,
     * la sélection, la copie et le scroll continuent de fonctionner.
     */
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public boolean isReadOnly() { return readOnly; }

    // ── Mutation principale ────────────────────────────────────

    /**
     * LE point de mutation. Tout passe par ici.
     * Enregistre l'annulation (avec coalescence pour la frappe d'un seul
     * caractère), épisse les styles de lignes incrémentalement, décale
     * diagnostics/jetons/plis/inlays.
     * <p>
     * Après l'appel, la sélection est un caret à {@code start + insertion.length()}.
     * Si une autre position de caret est nécessaire (p. ex. skip-over), utiliser
     * {@link #replaceRangeWithCaret(int, int, String, int)} à la place.
     */
    public void replaceRange(int start, int end, String insertion) {
        replaceRangeWithCaret(start, end, insertion, start + insertion.length());
    }

    /**
     * Variante de {@link #replaceRange(int, int, String)} laissant l'appelant
     * spécifier explicitement la position finale du caret. Nécessaire pour les
     * opérations d'édition intelligente comme le skip-over, l'expansion de
     * paire vide ou l'indentation intelligente, où le caret n'est pas
     * simplement {@code start + insertion.length()}.
     * <p>
     * Si l'édition est un no-op (start == end ET insertion vide ET caret
     * inchangé), rien n'est enregistré sur la pile d'annulation et aucun
     * callback n'est déclenché.
     */
    public void replaceRangeWithCaret(int start, int end, String insertion, int caretAfter) {
        if (readOnly && !(start == end && (insertion == null || insertion.isEmpty()))) {
            // Lecture-seule : on ignore silencieusement la mutation. C'est
            // aussi le chemin d'application des undo/redo et du paste/cut —
            // tout est couvert par ce seul point d'entrée.
            return;
        }
        if (start == end && (insertion == null || insertion.isEmpty())) {
            // Pur déplacement de curseur (p. ex. skip-over). Ne pas polluer la pile d'annulation.
            selection = Selection.cursor(caretAfter);
            notifySelectionChanged();
            return;
        }
        // L'IME était-il en composition quand cette édition est arrivée ?
        // Le cas échéant, il faudra forcer un restartInput ensuite — l'IME
        // garde son propre tampon de composition (le préfixe pré-acceptation)
        // et la frappe suivante le réinsérerait sans restart.
        boolean wasComposing = ime.isComposing();
        EditSpan span = doReplaceRange(start, end, insertion, caretAfter);
        // Notifier tous les listeners chaînés.
        if (!onTextEditListeners.isEmpty()) {
            int s = span.start, e = span.start + span.removed;
            for (OnTextEditListener l : onTextEditListeners) {
                l.onTextEdit(s, e, insertion);
            }
        }
        if (ime.listener() != null) {
            ime.onTextChanged(span);
            ime.notifySelectionChanged(selection.start, selection.end);
            if (wasComposing && !ime.listener().isSyncingExtractedText()) {
                ime.listener().onRestartInput();
            }
        }
    }

    /**
     * Mutation interne. Effectue l'épissure réelle de doc/styles/diagnostics/etc.
     * et décale la région de composition. Ne déclenche PAS onTextChanged ni
     * onSelectionChanged — les appelants (qui savent s'ils étaient en
     * composition ou non) en sont responsables.
     * <p>
     * Retourne l'{@link EditSpan} pour que l'appelant puisse l'inclure dans
     * sa charge utile de callback.
     */
    EditSpan doReplaceRange(int start, int end, String insertion, int caretAfter) {
        if (insertion == null) insertion = "";

        // Borner les offsets au document — un offset périmé issu d'une race
        // entre une édition et une invalidation ne doit jamais faire planter
        // le moteur.
        start = Math.max(0, Math.min(start, doc.length()));
        end = Math.max(start, Math.min(end, doc.length()));
        caretAfter = Math.max(0, Math.min(caretAfter, doc.length() + insertion.length()));

        // Si l'édition s'avère être un no-op après bornage, déplacer simplement le caret.
        if (start == end && insertion.isEmpty()) {
            selection = Selection.cursor(caretAfter);
            return new EditSpan(start, 0, 0);
        }

        String removed = "";
        if (end > start) {
            removed = doc.getText().substring(start, end);
        }
        EditOp op = new EditOp(start, removed, insertion);
        int selBefore = selection.start;

        // Enregistrer l'annulation
        recorder.record(op, selBefore, caretAfter);

        // Calculer la plage de lignes pour l'épissure de styles
        int firstLine = doc.lineForOffset(start);
        int lastLine = (end > start) ? doc.lineForOffset(end) : firstLine;
        int removedLines = lastLine - firstLine + 1;

        // Créer l'EditSpan pour les décalages
        EditSpan span = new EditSpan(start, end - start, insertion.length());

        // Appliquer au document
        doc = doc.replace(start, end, insertion);
        selection = Selection.cursor(caretAfter);

        // Épisser les styles
        restyle.splice(firstLine, removedLines, insertion);

        // Décaler diagnostics, jetons sémantiques, inlay hints, régions de
        // pliage.
        annotations.shift(span);
        folds.shift(span);

        // Décaler aussi la région de composition (gravité gauche — cf.
        // ImeBridge.shiftComposingRegion).
        ime.shiftComposingRegion(span);
        return span;
    }

    /** Pousse l'état courant de sélection / composition vers le listener IME. */
    private void notifySelectionChanged() {
        ime.notifySelectionChanged(selection.start, selection.end);
    }

    // ── Restyle (délégué à RestyleEngine) ─────────────────────

    /**
     * Version asynchrone du restyle complet — déléguée à
     * {@link RestyleEngine#restyleAsync()} : snapshot immuable du doc,
     * cancellation du restyle en vol, détection de résultats périmés par
     * génération, re-soumission quand le doc a changé pendant la passe.
     * Le thread UI rend la main immédiatement ; les anciens spans restent
     * affichés pendant l'intervalle asynchrone.
     */
    void restyleAllAsync() {
        restyle.restyleAsync();
    }

    /**
     * Attend (bloquant) la complétion de tout restyle asynchrone en attente.
     *
     * <p>Pour les tests : garantit que les assertions sur styledLines voient
     * l'état post-restyle, pas l'intervalle en vol.
     *
     * @throws InterruptedException si le thread appelant est interrompu
     */
    void awaitPendingRestyle() throws InterruptedException {
        restyle.awaitPendingRestyle();
    }

    /**
     * Indique si un restyle asynchrone est actuellement en vol.
     * Pour les tests + le diagnostic.
     */
    boolean isAsyncRestylePending() {
        return restyle.isAsyncRestylePending();
    }

    // ── Insertion de texte (délègue à EditOps) ─────────────────

    /**
     * Insère du texte au curseur. Utilise smartInsert pour un seul caractère,
     * insertion simple pour plusieurs.
     */
    public void commitText(String text) {
        if (text.length() == 1) {
            typeChar(text.charAt(0));
            return;
        }
        replaceRange(selection.start, selection.end, text);
    }

    /**
     * Frappe un seul caractère avec édition intelligente.
     * <p>
     * Si le résultat de l'édition intelligente diverge de « remplacer la
     * sélection par le caractère frappé et poser le caret juste après »
     * (p. ex. auto-close de crochets, skip-over de fermeur), le modèle de
     * l'IME est périmé — on force un {@code restartInput} pour qu'il se
     * resynchronise. Le restart est sauté quand un moniteur de texte extrait
     * est actif — le push par édition maintient déjà cet IME exact.
     */
    public void typeChar(char ch) {
        int selMin = Math.min(selection.start, selection.end);
        int selMax = Math.max(selection.start, selection.end);
        RangeEdit re = EditOps.smartInsert(doc.getText(), selMin, selMax, ch, language);
        replaceRangeWithCaret(re.start, re.end, re.text, re.caret);
        // Détecter la divergence avec la sémantique littérale « frapper ce caractère » attendue par l'IME.
        boolean diverged = re.start != selMin || re.end != selMax
            || !re.text.equals(String.valueOf(ch)) || re.caret != selMin + 1;
        // Redemander un restart seulement si l'IME ne suit pas déjà le texte
        // extrait (le push par édition le maintient exact). L'ancien re-test
        // interne de isSyncingExtractedText() était toujours vrai ici — le
        // garde externe le garantit déjà, aucun appel hôte entre les deux.
        if (diverged && ime.listener() != null && !ime.listener().isSyncingExtractedText()) {
            ime.listener().onRestartInput();
        }
    }

    /**
     * Backspace intelligent avec suppression de paire vide, collapse de lignes
     * vides, indentation intelligente.
     */
    public void backspace() {
        backspace(false);
    }

    /**
     * Backspace avec suppression optionnelle au niveau du mot.
     */
    public void backspace(boolean word) {
        if (word) {
            int pos = EditOps.wordBoundaryLeft(doc.getText(), selection.start);
            replaceRangeWithCaret(pos, selection.start, "", pos);
            return;
        }
        int selMin = Math.min(selection.start, selection.end);
        int selMax = Math.max(selection.start, selection.end);
        RangeEdit re = EditOps.smartBackspace(doc.getText(), selMin, selMax, language);
        replaceRangeWithCaret(re.start, re.end, re.text, re.caret);
        // Détecter la divergence avec la sémantique littérale « supprimer un
        // caractère avant le caret » attendue par l'IME. Les règles
        // intelligentes ont pu supprimer une paire vide (2 caractères),
        // collapsé une ligne vide (1 caractère + indentation), ou
        // désindenter (4 espaces).
        boolean diverged = re.start != Math.max(0, selMin - 1)
            || re.end != selMax || !re.text.isEmpty();
        if (diverged && ime.listener() != null && !ime.listener().isSyncingExtractedText()) {
            ime.listener().onRestartInput();
        }
    }

    /**
     * Suppression avant avec sensibilité aux paires.
     */
    public void deleteForward() {
        deleteForward(false);
    }

    /**
     * Suppression avant avec suppression optionnelle au niveau du mot.
     */
    public void deleteForward(boolean word) {
        if (word) {
            int pos = EditOps.wordBoundaryRight(doc.getText(), selection.start);
            replaceRangeWithCaret(selection.start, pos, "", selection.start);
            return;
        }
        RangeEdit re = EditOps.smartDeleteForward(doc.getText(), selection.start, selection.end, language);
        replaceRangeWithCaret(re.start, re.end, re.text, re.caret);
    }

    // ── Indentation / Désindentation ───────────────────────────

    public void indent() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        // Si la sélection finit exactement à un début de ligne (et pas sur la
        // même ligne que le début), exclure cette ligne suivante — elle n'est
        // pas réellement sélectionnée.
        if (selection.end != selection.start
            && selection.end == doc.lineStart(endLine)
            && endLine > startLine) {
            endLine--;
        }
        if (endLine < startLine) return;
        indentLines(startLine, endLine, true);
    }

    public void dedent() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        if (selection.end != selection.start
            && selection.end == doc.lineStart(endLine)
            && endLine > startLine) {
            endLine--;
        }
        if (endLine < startLine) return;
        indentLines(startLine, endLine, false);
    }

    private void indentLines(int startLine, int endLine, boolean indent) {
        String indentUnit = EditOps.detectIndentUnit(doc.getText());
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = doc.lineText(i);
            if (i > startLine) sb.append('\n');
            if (indent) {
                sb.append(indentUnit).append(line);
            } else {
                if (line.startsWith(indentUnit)) {
                    sb.append(line.substring(indentUnit.length()));
                } else {
                    int spaces = 0;
                    int maxRemove = indentUnit.length();
                    while (spaces < line.length() && spaces < maxRemove && line.charAt(spaces) == ' ') {
                        spaces++;
                    }
                    sb.append(line.substring(spaces));
                }
            }
        }
        int rangeStart = doc.lineStart(startLine);
        int rangeEnd = doc.lineEnd(endLine);
        replaceRange(rangeStart, rangeEnd, sb.toString());
    }

    // ── Bascule de commentaires (déléguée à CommentToggler) ──

    /**
     * La syntaxe de commentaire effective. La surcharge explicite de
     * {@link #setCommentSyntax(CommentSyntax)} gagne, sinon la syntaxe est
     * résolue depuis l'identifiant de langage courant via
     * {@link CommentSyntax#forLanguage(String)} (défaut Java/famille C
     * préservé à l'octet près).
     */
    public CommentSyntax getCommentSyntax() {
        return comments.commentSyntax();
    }

    /**
     * Surcharge la syntaxe de commentaire dérivée de l'identifiant de langage
     * — pour les hôtes éditant des langages que
     * {@link CommentSyntax#forLanguage(String)} ne connaît pas. Passer
     * {@code null} pour revenir à la résolution par langage.
     */
    public void setCommentSyntax(CommentSyntax syntax) {
        comments.setCommentSyntaxOverride(syntax);
    }

    /**
     * Bascule les commentaires de ligne sur la sélection, avec la syntaxe de
     * commentaire du langage COURANT.
     *
     * <p>La résolution passe par {@link #getCommentSyntax()} :</p>
     * <ul>
     *   <li>les langages AVEC un commentaire de ligne (Java, Python, Lua,
     *       SQL…) basculent ce préfixe exactement comme le comportement
     *       famille C historique (insérer {@code prefix + ' '}, retirer le
     *       préfixe + une espace optionnelle) ;</li>
     *   <li>les langages avec SEULEMENT une paire de bloc (XML/HTML/Markdown)
     *       retombent sur l'encadrement de chaque ligne par
     *       {@code blockStart … blockEnd} — le comportement VS Code pour
     *       XML ;</li>
     *   <li>les langages sans AUCUNE syntaxe de commentaire (JSON) sont un
     *       no-op documenté.</li>
     * </ul>
     */
    public void toggleLineComment() {
        comments.toggleLineComment();
    }

    /**
     * Bascule un commentaire de bloc autour de la sélection, avec la paire
     * de bloc du langage COURANT.
     *
     * <p>La paire vient de {@link #getCommentSyntax()} (XML obtient
     * {@code <!-- … -->}, Lua {@code --[[ … ]]}…). Les langages sans
     * commentaire de bloc (Python, JSON, shell…) sont un no-op documenté.</p>
     */
    public void toggleBlockComment() {
        comments.toggleBlockComment();
    }


    // ── Opérations de sélection ────────────────────────────────

    public void selectAll() {
        selection = Selection.range(0, doc.length());
    }

    public void selectWordAt(int offset) {
        int[] range = EditOps.wordRangeAt(doc.getText(), offset);
        selection = Selection.range(range[0], range[1]);
    }

    public void selectLineAt(int offset) {
        int line = doc.lineForOffset(offset);
        int start = doc.lineStart(line);
        int end = doc.lineEnd(line);
        selection = Selection.range(start, end);
    }

    // ── Déplacement du curseur ─────────────────────────────────

    public void moveHorizontal(int delta, boolean selecting) {
        int newPos = Math.max(0, Math.min(doc.length(), selection.start + delta));
        if (selecting) {
            int anchor = selection.isCursor() ? selection.start : (delta > 0 ? selection.start : selection.end);
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveVertical(int lines, boolean selecting) {
        int currentLine = doc.lineForOffset(selection.start);
        int currentCol = selection.start - doc.lineStart(currentLine);
        int targetLine = Math.max(0, Math.min(doc.lineCount() - 1, currentLine + lines));
        int targetLineStart = doc.lineStart(targetLine);
        int targetLineEnd = doc.lineEnd(targetLine);
        int newPos = Math.min(targetLineStart + currentCol, targetLineEnd);

        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveLineStart(boolean selecting) {
        int line = doc.lineForOffset(selection.start);
        int lineStart = doc.lineStart(line);
        String lineText = doc.lineText(line);
        // Début de ligne intelligent : aller au premier non-blanc, ou au début
        // de ligne si on y est déjà
        int firstNonWs = lineStart;
        for (int i = 0; i < lineText.length(); i++) {
            if (!Character.isWhitespace(lineText.charAt(i))) {
                firstNonWs = lineStart + i;
                break;
            }
        }
        int newPos = (selection.start == firstNonWs) ? lineStart : firstNonWs;

        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveLineEnd(boolean selecting) {
        int line = doc.lineForOffset(selection.start);
        int newPos = doc.lineEnd(line);
        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveDocBoundary(boolean toEnd, boolean selecting) {
        int newPos = toEnd ? doc.length() : 0;
        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    // ── Opérations sur les lignes ───────────────────────────────

    public void duplicateSelection() {
        if (selection.isCursor()) {
            duplicateLine();
            return;
        }
        String selected = doc.getText().substring(selection.start, selection.end);
        replaceRange(selection.end, selection.end, selected);
    }

    public void duplicateLine() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        int insertPos = doc.lineEnd(endLine);
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        for (int i = startLine; i <= endLine; i++) {
            if (i > startLine) sb.append('\n');
            sb.append(doc.lineText(i));
        }
        replaceRange(insertPos, insertPos, sb.toString());
    }

    public void moveLines(boolean down) {
        if (down) {
            moveLineDown();
        } else {
            moveLineUp();
        }
    }

    public void deleteLines() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        int rangeStart = doc.lineStart(startLine);
        int rangeEnd = doc.lineEnd(endLine);
        if (rangeEnd < doc.length()) {
            rangeEnd++;
        } else if (rangeStart > 0) {
            rangeStart--;
        }
        replaceRange(rangeStart, rangeEnd, "");
    }

    public void moveLineUp() {
        int line = doc.lineForOffset(selection.start);
        if (line <= 0) return;
        String currentLine = doc.lineText(line);
        String prevLine = doc.lineText(line - 1);
        int prevStart = doc.lineStart(line - 1);
        int currEnd = doc.lineEnd(line);
        replaceRange(prevStart, currEnd, currentLine + "\n" + prevLine);
    }

    public void moveLineDown() {
        int line = doc.lineForOffset(selection.start);
        if (line >= doc.lineCount() - 1) return;
        String currentLine = doc.lineText(line);
        String nextLine = doc.lineText(line + 1);
        int currStart = doc.lineStart(line);
        int nextEnd = doc.lineEnd(line + 1);
        replaceRange(currStart, nextEnd, nextLine + "\n" + currentLine);
    }

    public void joinLines() {
        int line = doc.lineForOffset(selection.start);
        if (line >= doc.lineCount() - 1) return;
        int lineEnd = doc.lineEnd(line);
        int nextEnd = doc.lineEnd(line + 1);
        String nextLine = doc.lineText(line + 1);
        replaceRange(lineEnd, Math.min(nextEnd, doc.length()), " " + nextLine.trim());
    }

    // ── Opérations de presse-papiers ────────────────────────────

    public String selectedText() {
        if (selection.isCursor()) return "";
        return doc.getText().substring(selection.start, selection.end);
    }

    public String cutSelection() {
        String text = selectedText();
        if (!text.isEmpty()) {
            replaceRange(selection.start, selection.end, "");
        }
        return text;
    }

    // ── Navigation dans les diagnostics ────────────────────────

    public void goToDiagnostic(boolean forward) {
        List<DiagnosticShift.Diagnostic> diagnostics = annotations.diagnostics();
        if (diagnostics.isEmpty()) return;
        int pos = selection.start;
        DiagnosticShift.Diagnostic best = null;

        if (forward) {
            for (DiagnosticShift.Diagnostic d : diagnostics) {
                if (d.start > pos && (best == null || d.start < best.start)) {
                    best = d;
                }
            }
            if (best == null) best = diagnostics.get(0); // bouclage
        } else {
            for (DiagnosticShift.Diagnostic d : diagnostics) {
                if (d.start < pos && (best == null || d.start > best.start)) {
                    best = d;
                }
            }
            if (best == null) best = diagnostics.get(diagnostics.size() - 1); // bouclage
        }

        selection = Selection.cursor(best.start);
    }

    // ── Suppression autour ──────────────────────────────────────

    public void deleteSurrounding(int beforeLength, int afterLength) {
        int start = selection.start - beforeLength;
        int end = selection.end + afterLength;
        start = Math.max(0, start);
        end = Math.min(doc.length(), end);
        if (start < end) replaceRange(start, end, "");
    }

    // ── Undo / Redo ───────────────────────────────────────────────

    public boolean undo() {
        UndoStep step = recorder.manager().undo();
        if (step == null) return false;
        for (int i = step.edits.size() - 1; i >= 0; i--) {
            EditOp op = step.edits.get(i);
            EditOp inv = op.inverse();
            doc = doc.replace(inv.start, inv.start + inv.removed.length(), inv.inserted);
        }
        selection = Selection.cursor(step.selBefore);
        // Restyle asynchrone pour l'undo : un restyleAll() synchrone pouvait
        // bloquer l'UI 200 ms+ sur un gros fichier lors d'un Ctrl+Z. En
        // asynchrone : le snapshot du doc est le nouveau doc (post-undo) car
        // la mutation doc = doc.replace(...) est faite juste au-dessus, donc
        // le docAtStart capturé dans restyleAllAsync == ce nouveau doc, et le
        // contrôle doc != docAtStart renvoie faux → le restyle n'est pas
        // jeté.
        restyleAllAsync();
        // Notifier le listener IME pour que la vue sache que le caret a bougé
        // et que le texte a changé — sans cela, l'undo/redo laissait le caret
        // figé (pas de redémarrage du clignotement, pas de scroll-into-view).
        if (ime.listener() != null) {
            ime.onTextChanged(new EditSpan(0, 0, 0));
            ime.notifySelectionChanged(selection.start, selection.end);
        }
        return true;
    }

    public boolean redo() {
        UndoStep step = recorder.manager().redo();
        if (step == null) return false;
        for (EditOp op : step.edits) {
            doc = doc.replace(op.start, op.start + op.removed.length(), op.inserted);
        }
        selection = Selection.cursor(step.selAfter);
        // Restyle asynchrone pour le redo (même logique que l'undo).
        restyleAllAsync();
        // Comme l'undo : notifier le listener.
        if (ime.listener() != null) {
            ime.onTextChanged(new EditSpan(0, 0, 0));
            ime.notifySelectionChanged(selection.start, selection.end);
        }
        return true;
    }

    // ── Pont IME (délégué à ImeBridge) ─────────────────────

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
    public void imeCommitText(String text) {
        ime.commitText(text);
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
    public void imeSetComposingText(String text, int newCursorPosition) {
        ime.setComposingText(text, newCursorPosition);
    }

    /**
     * L'IME définit la région de composition.
     */
    public void imeSetComposingRegion(int start, int end) {
        ime.setComposingRegion(start, end);
    }

    /**
     * L'IME termine la composition (commite le texte de composition comme
     * final).
     */
    public void imeFinishComposing() {
        ime.finishComposing();
    }

    /**
     * L'IME supprime du texte autour du curseur.
     * <p>
     * Les suppressions de blancs restent LITTÉRALES — l'échange « pas
     * d'espace avant la ponctuation » de SwiftKey est identique octet par
     * octet à un tap backspace utilisateur, et les règles de backspace
     * intelligent mangeraient du vrai code à chaque échange (le bug « taper
     * ) supprime mon indentation »). Les règles intelligentes ne
     * s'appliquent que via {@link #backspace()}.
     */
    public void imeDeleteSurrounding(int beforeLength, int afterLength) {
        ime.deleteSurrounding(beforeLength, afterLength);
    }

    /**
     * L'IME définit la sélection (offsets absolus). Bornée au document.
     */
    public void imeSetSelection(int start, int end) {
        ime.setSelection(start, end);
    }

    /**
     * L'IME remplace une plage absolue ({@code replaceText} API 34+).
     * Utilisé par certains IMEs pour la rétro-correction. Passe par
     * {@link #replaceRange} pour que l'annulation, l'épissure de styles et
     * le décalage des diagnostics restent cohérents.
     */
    public void imeReplaceText(int start, int end, String text, int newCursorPosition) {
        ime.replaceText(start, end, text, newCursorPosition);
    }

    /**
     * Retourne jusqu'à {@code n} caractères avant le caret. Utilisé par les
     * IMEs qui lisent le contexte pour la prédiction. Borné à MAX_IPC_TEXT
     * pour rester compatible Binder.
     */
    public String imeTextBeforeCursor(int n) {
        return ime.textBeforeCursor(n);
    }

    /**
     * Retourne jusqu'à {@code n} caractères après le caret. Utilisé par les
     * IMEs qui lisent le contexte pour la prédiction. Borné à MAX_IPC_TEXT
     * pour rester compatible Binder.
     */
    public String imeTextAfterCursor(int n) {
        return ime.textAfterCursor(n);
    }

    /**
     * Retourne la région de composition courante, ou null si pas en composition.
     */
    public int[] getComposingRegion() {
        return ime.composingRegion();
    }

    /**
     * Retourne vrai si l'IME est en état de composition.
     */
    public boolean isComposing() {
        return ime.isComposing();
    }


    // ── Cycle de vie : disposal ─────────────────────────────────

    /**
     * Libère les ressources d'arrière-plan de la session.
     *
     * <p>Sans cet appel, l'exécuteur mono-thread
     * ({@link #restyleExecutor}) n'est jamais arrêté : une app ouvrant un
     * éditeur par onglet fuirait un thread (et sa file de tâches en attente)
     * par session pendant toute la vie du process. Les threads sont des
     * démons donc la JVM sort quand même, mais sur Android chaque thread
     * fui garde le rope du document de sa session fortement référençable —
     * une fuite mémoire lente et invisible pour les longues sessions d'édition.
     *
     * <p>Appeler quand l'éditeur est détruit définitivement (typiquement
     * depuis {@code EditorView.onDetachedFromWindow} après un
     * {@code onAttachStateChangeListener#onViewDetachedFromWindow} non suivi
     * d'un re-attach — l'{@code EditorView} intégré l'appelle pour vous).
     * Après {@code dispose()} : les restyles asynchrones en attente sont
     * annulés, l'exécuteur est arrêté, et tout {@code setLanguage}/undo/redo
     * ultérieur retombe sur le chemin SYNCHRONE {@code restyleAll()} du
     * moteur (correct, simplement bloquant). Les éditions de texte restent
     * pleinement fonctionnelles.
     */
    public void dispose() {
        restyle.dispose();
    }

    /** Indique si {@link #dispose()} a été appelé. */
    public boolean isDisposed() {
        return restyle.isDisposed();
    }
}
