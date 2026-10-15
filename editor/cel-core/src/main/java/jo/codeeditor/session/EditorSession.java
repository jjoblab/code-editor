package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.edit.CommentSyntax;
import jo.codeeditor.edit.EditOps;
import jo.codeeditor.edit.RangeEdit;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
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

    /**
     * Contrôleur de session de snippet (L1/D-01) — collaborateur sur
     * le modèle de {@code RestyleEngine} : application des snippets à
     * l'acceptation de complétion, navigation des champs (Tab /
     * Shift+Tab), ré-ancrage sur édition, miroirs, arrêt final $0.
     * Champ public final (référence stable) : l'API de navigation
     * vit sur le contrôleur, le plafond de méthodes publiques de la
     * session reste intact (cliquet A-01).
     */
    public final SnippetController snippets;

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
        this.snippets = new SnippetController(this);
        restyle.restyleAll();
    }

    // ── Accesseurs ────────────────────────────────────────────

    public EditorDocument getDocument() { return doc; }
    public Selection getSelection() { return selection; }
    public void setSelection(Selection sel) {
        // Lot A.4 (audit v3.41.0) : déplie le pli si le caret
        // (start ou end de la sélection) se retrouve à l'intérieur
        // strict d'un pli replié. Sans ce correctif, les
        // déplacements purs de caret (moveHorizontal, moveVertical,
        // moveLineStart, moveLineEnd, moveDocBoundary) pouvaient
        // laisser le caret sur une position invisible (à
        // l'intérieur d'un pli masqué). Le Lot A.2 couvrait les
        // mutations via replaceRangeWithCaret, mais pas les
        // déplacements purs qui passent par setSelection.
        //
        // On déplie le pli AVANT d'appliquer la sélection pour que
        // le rendu suivant affiche le caret au bon endroit. Le
        // dépliage n'est PAS enregistré sur la pile d'annulation
        // (cohérent avec la décision 3.3 point 4 du Lot A.2).
        //
        // Bornes : on déplie si start OU end est dans l'intérieur
        // STRICT du pli (r.start < offset < r.end). Les positions
        // r.start (juste avant la chip) et r.end (juste après la
        // chip, sur le fermant du suffixe) sont visibles et ne
        // déclenchent pas le dépliage.
        if (folds != null && folds.hasCollapsed()) {
            folds.expandAt(sel.start);
            if (sel.start != sel.end) {
                folds.expandAt(sel.end);
            }
            // La vue est notifiée via le mécanisme existant
            // (getFoldRegions est appelé à chaque frame par le
            // renderer ; foldRev est incrémenté par expandAt pour
            // invalider les caches dérivés de la vue).
        }
        this.selection = sel;
        notifySelectionChanged();
    }
    public void setSelection(int offset) {
        setSelection(Selection.cursor(offset));
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
        snippets.setOnSnippetEditListener(listener);
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

    // ── Indexes par ligne (un compartiment par ligne) ──
    // Mémoïsation des compartiments par ligne (diagnostics, inlay hints,
    // jetons sémantiques) : détenue par SessionAnnotations, reconstruite
    // paresseusement sur changement d'identité de la liste source.

    /**
     * Retourne tous les diagnostics dont l'offset de DÉBUT se situe sur la
     * ligne du document donnée (portage du {@code diagnosticsByStartLine()}
     * standard des éditeurs). Le compartiment est trié par sévérité décroissante
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
     * d'application des plis fraîchement détectés) :
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
     * ★ B21d : y a-t-il AU MOINS un pli replié ? Test sans allocation —
     * la voie historique {@code getCollapsedFolds().isEmpty()} allouait
     * une liste fraîche à CHAQUE FRAME de rendu juste pour ce test.
     */
    public boolean hasCollapsedFolds() {
        return folds.hasCollapsed();
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

    /** B14 (P1-5) — « Replier au niveau N » : profondeur EXACTE (1 = classes, 2 = méthodes). */
    public int collapseFoldsAtLevel(int level) { return folds.collapseAtLevel(level); }

    /** B14 (P1-5) — « Tout replier » (tous niveaux). */
    public int collapseAllFolds() { return folds.collapseAll(); }

    /** B14 (P1-5) — « Tout déplier ». */
    public int expandAllFolds() { return folds.expandAll(); }

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
        // Lot A (audit v3.41.0) : si l'édition touche l'intérieur d'un
        // pli replié, bornes incluses (start <= r.end ET end >= r.start,
        // c.-à-d. que la zone éditée chevauche l'intérieur du pli),
        // déplier ce pli AVANT d'appliquer l'édition. Sans ce correctif,
        // l'utilisateur pouvait détruire silencieusement plusieurs
        // lignes masquées en appuyant sur Retour arrière alors que le
        // caret était à r.end (juste après la chip, sur le } fermant).
        //
        // Le dépliage est un effet de bord visuel : il n'est PAS enregistré
        // sur la pile d'annulation (pas d'étape de texte), donc l'undo
        // annule l'édition mais laisse le pli déplié. Comportement
        // voulu : l'utilisateur voit le contenu et peut refaire
        // le pli manuellement s'il le souhaite.
        //
        // On déplie tous les plis touchés par la zone d'édition. Une
        // édition peut traverser plusieurs plis (sélection large), il
        // faut tous les déplier. La recherche est linéaire en nombre de
        // plis — négligeable sur des fichiers à peu de plis.
        if (folds.hasCollapsed()) {
            // Lot A : déplie les plis touchés par la zone d'édition.
            // La vue est notifiée via le mécanisme existant
            // (getFoldRegions() est appelé à chaque frame par le
            // renderer ; foldRev est incrémenté par expandInRange
            // pour invalider les caches dérivés de la vue, e.g.
            // l'index de sommes préfixes de plis).
            folds.expandInRange(start, end);
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
        return doReplaceRange(start, end, insertion, caretAfter, true);
    }

    /**
     * Mutation interne. Effectue l'épissure réelle de doc/styles/diagnostics/etc.
     * et décale la région de composition. Ne déclenche PAS onTextChanged ni
     * onSelectionChanged — les appelants (qui savent s'ils étaient en
     * composition ou non) en sont responsables.
     * <p>
     * {@code recordUndo = false} applique l'édition SANS l'enregistrer sur
     * la pile d'annulation : c'est le chemin d'application des undo/redo
     * (l'étape a déjà été dépilée de la pile correspondante).
     * <p>
     * Retourne l'{@link EditSpan} pour que l'appelant puisse l'inclure dans
     * sa charge utile de callback.
     */
    EditSpan doReplaceRange(int start, int end, String insertion, int caretAfter,
                            boolean recordUndo) {
        if (insertion == null) insertion = "";

        // Borner les offsets au document — un offset périmé issu d'une race
        // entre une édition et une invalidation ne doit jamais faire planter
        // le moteur.
        start = Math.max(0, Math.min(start, doc.length()));
        end = Math.max(start, Math.min(end, doc.length()));
        // Le caret est borné à la longueur FINALE du document — pas
        // doc.length() + insertion.length() : une édition qui remplace une
        // plage par un texte plus court laissait un caret hors bornes.
        int finalLength = doc.length() - (end - start) + insertion.length();
        caretAfter = Math.max(0, Math.min(caretAfter, finalLength));

        // Si l'édition s'avère être un no-op après bornage, déplacer simplement le caret.
        if (start == end && insertion.isEmpty()) {
            selection = Selection.cursor(caretAfter);
            return new EditSpan(start, 0, 0);
        }

        String removed = "";
        if (end > start) {
            removed = doc.subText(start, end); // ★ B19 : tranche, pas de copie complète
        }
        EditOp op = new EditOp(start, removed, insertion);
        int selBefore = selection.start;

        // Enregistrer l'annulation (sauf application d'un undo/redo —
        // l'étape est déjà sur la pile correspondante).
        if (recordUndo) {
            recorder.record(op, selBefore, caretAfter);
        }

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

        // Session de snippet active (L1/D-01) : ré-ancrage des champs,
        // synchronisation des miroirs quand l'édition a eu lieu DANS le
        // champ courant, notification du listener de champ.
        snippets.onEdit(span, insertion);

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
     * Rabat la durée de vie du thread de restyle inactif (30 s en
     * production) — réservé aux tests de la borne de fuite D-02.
     */
    void setRestyleThreadKeepAliveMs(long ms) {
        restyle.setThreadKeepAliveMs(ms);
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
     * insertion simple pour plusieurs. Tente aussi la complétion postfix
     * avant l'insertion.
     */
    public void commitText(String text) {
        // Postfix completion : si le texte inséré complète un postfix
        // (.if, .for, .notnull, .return, .var, .while, .try, .else),
        // déclencher la complétion postfix au lieu de l'insertion
        // simple. D-04 : le résultat immuable voyage par appel — plus
        // d'état statique partagé entre sessions.
        if (text.length() >= 2) {
            CharSequence docChars = doc.charSequence();
            int pos = selection.start;
            // P-01 (audit 3.69.0) : simuler le texte après insertion en
            // VUE PARESSEUSE — l'ancien chemin matérialisait le préfixe
            // entier du document (O(pos), ~1 Mo par frappe sur un document
            // d'un million de caractères) alors que la complétion postfix
            // ne lit que l'arrière-garde (postfix ≤ 8 c., expression,
            // retrait de ligne). La rope sert les lectures en O(log n) par
            // caractère, aucune copie tant que l'analyse ne demande pas
            // de tranche.
            CharSequence simulated = jo.codeeditor.edit.EditOps.simulatedInsert(
                    docChars.subSequence(0, pos), text);
            int simulatedPos = pos + text.length();
            jo.codeeditor.edit.EditOps.PostfixResult pc =
                    jo.codeeditor.edit.EditOps.tryPostfix(
                            simulated, simulatedPos, language);
            if (pc != null) {
                int realStart = pc.start;
                int realEnd = Math.min(pc.end, pos + text.length());
                replaceRangeWithCaret(realStart, realEnd,
                        pc.insertText, pc.caret);
                return;
            }
        }
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
        RangeEdit re = EditOps.smartInsert(doc.charSequence(), selMin, selMax, ch, language); // ★ B19
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
            int pos = EditOps.wordBoundaryLeft(doc.charSequence(), selection.start); // ★ B19
            replaceRangeWithCaret(pos, selection.start, "", pos);
            return;
        }
        int selMin = Math.min(selection.start, selection.end);
        int selMax = Math.max(selection.start, selection.end);
        RangeEdit re = EditOps.smartBackspace(doc.charSequence(), selMin, selMax, language); // ★ B19
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
            int pos = EditOps.wordBoundaryRight(doc.charSequence(), selection.start); // ★ B19
            replaceRangeWithCaret(selection.start, pos, "", selection.start);
            return;
        }
        RangeEdit re = EditOps.smartDeleteForward(doc.charSequence(), selection.start, selection.end, language); // ★ B19
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
        // Lot 1+3 (audit v3.41.0) : utiliser doc.charSequence() (rope,
        // pas de copie) plutôt que doc.getText() (copie complète du
        // document à chaque Tab) pour détecter l'unité d'indentation.
        String indentUnit = EditOps.detectIndentUnit(doc.charSequence());
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = doc.lineText(i);
            if (i > startLine) sb.append('\n');
            if (indent) {
                // Lot 1 : ne pas indenter les lignes purement vides
                // (uniquement des espaces/tabulations) — cela évite
                // d'introduire du whitespace
                // involontaire dans des lignes que l'utilisateur
                // laissera probablement vides. Une ligne non-vide
                // (contenant au moins un caractère non-blanc) reçoit
                // l'indentation.
                if (line.isEmpty() || line.trim().isEmpty()) {
                    sb.append(line);
                } else {
                    sb.append(indentUnit).append(line);
                }
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
        // Lot 1 : préserver la sélection après l'indentation. Sans
        // cela, replaceRange() réduit la sélection à un caret, ce
        // qui rendait les Maj+Tab successifs inopérants (le 2e
        // Maj+Tab ne dédentait que la dernière ligne, faute de
        // sélection multi-lignes).
        int oldSelStart = selection.start;
        int oldSelEnd = selection.end;
        replaceRange(rangeStart, rangeEnd, sb.toString());
        // Recaler la sélection sur la nouvelle étendue. Le bloc a été
        // remplacé : [rangeStart, rangeEnd] → [rangeStart, newRangeEnd].
        // Les offsets à l'intérieur de la zone remplacée sont
        // mappés aux bornes correspondantes (début/fin du nouveau
        // bloc) ; les offsets après la zone sont décalés du delta.
        int newRangeEnd = rangeStart + sb.length();
        int delta = newRangeEnd - rangeEnd;
        int newSelStart;
        if (oldSelStart <= rangeStart) {
            newSelStart = oldSelStart;
        } else if (oldSelStart >= rangeEnd) {
            newSelStart = oldSelStart + delta;
        } else {
            // L'offset était dans la zone remplacée — pinner au
            // début du nouveau bloc.
            newSelStart = rangeStart;
        }
        int newSelEnd;
        if (oldSelEnd <= rangeStart) {
            newSelEnd = oldSelEnd;
        } else if (oldSelEnd >= rangeEnd) {
            newSelEnd = oldSelEnd + delta;
        } else {
            newSelEnd = newRangeEnd;
        }
        setSelection(Selection.range(newSelStart, Math.max(newSelStart, newSelEnd)));
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
     *       {@code blockStart … blockEnd} pour XML ;</li>
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
    // ★ B21f : tous les mutateurs passent par setSelection(...) —
    // l'ancienne écriture directe du champ ne notifiait JAMAIS l'IME
    // (updateSelection jamais poussé) : le miroir de l'IME (contexte
    // d'autocorrection, sélection extraite) désynchronisait après
    // Ctrl+A, les flèches, Select all…

    public void selectAll() {
        setSelection(Selection.range(0, doc.length()));
    }

    public void selectWordAt(int offset) {
        int[] range = EditOps.wordRangeAt(doc.charSequence(), offset); // ★ B19
        setSelection(Selection.range(range[0], range[1]));
    }

    public void selectLineAt(int offset) {
        int line = doc.lineForOffset(offset);
        int start = doc.lineStart(line);
        int end = doc.lineEnd(line);
        setSelection(Selection.range(start, end));
    }

    /**
     * Sélectionne le PARAGRAPHE contenant l'offset (B10, 4e tap de la
     * cascade mot → ligne → paragraphe) : le bloc maximal de lignes
     * contiguës NON VIDES autour de la ligne de l'offset, borné par les
     * lignes vides (ou blanches : espaces/tablulations seuls) et les
     * extrémités du document. Sans le saut de ligne final — parité
     * {@link #selectLineAt}. Sur une ligne vide, le « paragraphe » est
     * cette ligne seule.
     */
    public void selectParagraphAt(int offset) {
        int line = doc.lineForOffset(offset);
        int count = doc.lineCount();
        int first = line;
        int last = line;
        while (first > 0 && !isBlankDocLine(first - 1)) first--;
        while (last < count - 1 && !isBlankDocLine(last + 1)) last++;
        setSelection(Selection.range(doc.lineStart(first), doc.lineEnd(last)));
    }

    /** Ligne du document vide ou blanche (espaces/tablulations seuls). */
    private boolean isBlankDocLine(int line) {
        return doc.lineText(line).trim().isEmpty();
    }

    // ── Expand selection (lot 4 #27) ───────────────────────────

    /**
     * Étend la sélection courante d'UN cran (audit §1D lot 4 #27) :
     * curseur → mot → syntagme → ligne(s).
     *
     * <p>La cascade complète, d'un appel à l'appel suivant :</p>
     * <ol>
     *   <li><b>Curseur sans sélection</b> → sélection du mot sous le
     *       curseur (mêmes bornes de mot que l'appui long) ;</li>
     *   <li><b>Mot</b> → <b>syntagme</b> : le span syntaxique englobant
     *       (chaîne, caractère, commentaire…) s'il couvre la sélection —
     *       guillemets inclus — sinon la paire de crochets englobante la
     *       plus interne ({@code ()} {@code []} {@code {}}, délimiteurs
     *       inclus) ;</li>
     *   <li><b>Syntagme</b> → <b>ligne(s) entière(s)</b> touchées par la
     *       sélection (sans le saut de ligne final) ;</li>
     *   <li><b>Lignes complètes</b> → inclut le saut de ligne de la
     *       dernière ligne ; au-delà (ou en fin de document), plus rien
     *       à étendre.</li>
     * </ol>
     *
     * @return {@code true} si la sélection a changé, {@code false} si elle
     *         couvre déjà tout ce qui peut l'être (document entier).
     */
    public boolean expandSelection() {
        CharSequence cs = doc.charSequence();

        // 1. Curseur → mot.
        if (selection.isCursor()) {
            int[] range = EditOps.wordRangeAt(cs, selection.start);
            if (range[1] > range[0]) {
                setSelection(Selection.range(range[0], range[1]));
                return true;
            }
            return false;
        }

        // 2. Lignes complètes déjà couvertes ? (le niveau « ligne » de la
        //    cascade prime sur les crochets : sans cela, une sélection de
        //    ligne complète à l'intérieur d'un bloc re-basculerait vers la
        //    paire d'accolades englobante au lieu de progresser.)
        int firstLine0 = doc.lineForOffset(selection.start);
        int lastLine0 = doc.lineForOffset(Math.max(selection.start, selection.end - 1));
        if (selection.start == doc.lineStart(firstLine0)
                && selection.end >= doc.lineEnd(lastLine0)) {
            boolean endsAtDocEnd = selection.end >= doc.length();
            boolean endsWithNewline = selection.end < doc.length()
                    && cs.charAt(selection.end - 1) == '\n';
            if (endsAtDocEnd || endsWithNewline) {
                return false; // lignes + saut final : plus rien à étendre
            }
            // Inclure le saut de ligne de la dernière ligne sélectionnée.
            setSelection(Selection.range(selection.start, selection.end + 1));
            return true;
        }

        // 3. Mot → syntagme : span syntaxique englobant (chaîne/commentaire),
        //    sinon paire de crochets englobante la plus interne.
        int[] span = enclosingSyntaxSpanRange(selection.start, selection.end);
        if (span != null && !(span[0] == selection.start && span[1] == selection.end)) {
            setSelection(Selection.range(span[0], span[1]));
            return true;
        }
        int[] brackets = enclosingBracketRange(cs, selection.start, selection.end);
        if (brackets != null && !(brackets[0] == selection.start
                && brackets[1] == selection.end)) {
            setSelection(Selection.range(brackets[0], brackets[1]));
            return true;
        }

        // 4. → ligne(s) entière(s) touchées (sans le saut de ligne final —
        //    il sera inclus à l'appel suivant par l'étape 2).
        int firstLine = doc.lineForOffset(selection.start);
        int lastLine = doc.lineForOffset(Math.max(selection.start, selection.end - 1));
        int start = doc.lineStart(firstLine);
        int lastLineEnd = doc.lineEnd(lastLine);
        setSelection(Selection.range(start, lastLineEnd));
        return true;
    }

    /**
     * Recherche le span syntaxique (chaîne, caractère, chaîne brute,
     * commentaire, doc-comment, emphase, regexp) qui couvre ENTIÈREMENT
     * la sélection [start, end) — bornes converties en offsets document.
     * Renvoie {@code null} si la sélection s'étend sur plusieurs lignes
     * ou si aucun span englobant n'existe. Les bornes du span INCLUENT
     * les délimiteurs (guillemets, {@code /*} et {@code *&#47;}).
     */
    private int[] enclosingSyntaxSpanRange(int start, int end) {
        if (end <= start) return null;
        int firstLine = doc.lineForOffset(start);
        int lastLine = doc.lineForOffset(Math.max(start, end - 1));
        if (firstLine != lastLine) return null; // multi-lignes : pas de span simple

        List<StyledLine> styled = getStyledLines();
        if (firstLine >= styled.size()) return null;
        StyledLine sl = styled.get(firstLine);
        if (sl == null || sl.spans == null) return null;
        int lineStart = doc.lineStart(firstLine);
        for (LineSpan s : sl.spans) {
            TokenType t = s.type;
            if (t != TokenType.STRING && t != TokenType.CHAR
                    && t != TokenType.RAW_STRING && t != TokenType.COMMENT
                    && t != TokenType.DOC_COMMENT && t != TokenType.REGEXP
                    && t != TokenType.EMPHASIS) {
                continue;
            }
            int s0 = lineStart + s.startCol;
            int s1 = lineStart + s.endCol;
            if (s0 <= start && end <= s1) {
                return new int[]{s0, s1};
            }
        }
        return null;
    }

    /**
     * Recherche la paire de crochets englobante la plus interne couvrant
     * [start, end) — délimiteurs INCLUS dans la plage renvoyée. Les
     * paires cherchées sont {@code ()} {@code []} {@code {}} ; le scan
     * arrière est borné à 8192 caractères (commande interactive).
     * Renvoie {@code null} si aucune paire ne convient.
     */
    private int[] enclosingBracketRange(CharSequence cs, int start, int end) {
        final int MAX_BACKSCAN = 8192;
        int from = Math.max(0, start - MAX_BACKSCAN);
        for (int i = start - 1; i >= from; i--) {
            char c = cs.charAt(i);
            if (c != '(' && c != '[' && c != '{') continue;
            char close = (c == '(') ? ')' : (c == '[') ? ']' : '}';
            // Scan avant avec profondeur sur cette paire uniquement.
            int depth = 0;
            for (int j = i + 1, lim = Math.min(cs.length(), i + MAX_BACKSCAN); j < lim; j++) {
                char d = cs.charAt(j);
                if (d == c) depth++;
                else if (d == close) {
                    if (depth-- == 0) {
                        // Paire (i, j) : valable si elle couvre la sélection
                        // ET n'est pas déjà exactement sélectionnée.
                        if (j >= end - 1 && !(i == start && j + 1 == end)) {
                            return new int[]{i, j + 1};
                        }
                        break; // paire fermée avant la fin de la sélection → suivante
                    }
                }
            }
        }
        return null;
    }

    // ── Déplacement du curseur ─────────────────────────────────
    // Plan 02 : implémentations dans CursorMoves (collaborateur statique
    // package-privé) — la session garde de fins relais publics (zéro
    // rupture d'API), son plafond de lignes tient.

    public void moveHorizontal(int delta, boolean selecting) { CursorMoves.moveHorizontal(this, delta, selecting); }

    public void moveVertical(int lines, boolean selecting) { CursorMoves.moveVertical(this, lines, selecting); }

    public void moveLineStart(boolean selecting) { CursorMoves.moveLineStart(this, selecting); }

    public void moveLineEnd(boolean selecting) { CursorMoves.moveLineEnd(this, selecting); }

    public void moveDocBoundary(boolean toEnd, boolean selecting) { CursorMoves.moveDocBoundary(this, toEnd, selecting); }

    // ── Opérations sur les lignes ───────────────────────────────
    // Plan 01/02 : implémentations dans LineOperations (collaborateur
    // statique package-privé) — la session garde de fins relais
    // publics (zéro rupture d'API), son plafond de lignes tient.

    public void duplicateSelection() { LineOperations.duplicateSelection(this); }

    public void duplicateLine() { LineOperations.duplicateLine(this); }

    public void moveLines(boolean down) { LineOperations.moveLines(this, down); }

    public void deleteLines() { LineOperations.deleteLines(this); }

    /**
     * Déplace le BLOC de lignes couvert par la sélection d'une ligne
     * (P2, lot 2 J6) — implémentation dans {@code LineOperations}
     * (sémantique IntelliJ/VS Code : bloc entier, sélection
     * ré-épinglée, no-op aux bords, une étape d'undo).
     *
     * @param down true → vers le bas, false → vers le haut
     * @return true si le document a changé
     */
    public boolean moveSelectedLines(boolean down) {
        return LineOperations.moveSelectedLines(this, down);
    }

    /**
     * Déplace la plage de caractères [start, end) à l'offset
     * {@code target} — B13 (P1-2 drag-déposer de bloc) : une seule
     * étape d'annulation, sélection ré-épinglée sur le texte déplacé,
     * no-op (false) en lecture-seule, plage vide ou dépôt dans la
     * source. Moteur {@link LineOperations#moveRange}.
     */
    public boolean moveRange(int start, int end, int target) {
        return LineOperations.moveRange(this, start, end, target);
    }

    public void moveLineUp() { LineOperations.moveLineUp(this); }

    public void moveLineDown() { LineOperations.moveLineDown(this); }

    public void joinLines() { LineOperations.joinLines(this); }

    // ── Opérations de presse-papiers ────────────────────────────

    public String selectedText() {
        if (selection.isCursor()) return "";
        return doc.subText(selection.start, selection.end); // ★ B19
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

        // ★ B21f : via setSelection — le saut de diagnostic notifie l'IME.
        setSelection(Selection.cursor(best.start));
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
        // Lecture-seule : l'annulation ne doit RIEN modifier (et ne pas
        // consommer la pile — l'étape reste disponible si l'hôte déverrouille).
        if (readOnly) return false;
        // Le snippet actif ne survit pas à l'annulation : les offsets de
        // ses champs ne sont plus fiables (aucun mapping inverse).
        snippets.end();
        UndoStep step = recorder.manager().undo();
        if (step == null) return false;
        boolean wasComposing = ime.isComposing();
        // Appliquer les opérations inverses dans l'ORDRE INVERSE via le
        // pipeline commun (épissure des styles, décalage des
        // diagnostics/plis/composition) SANS réenregistrer sur la pile.
        // L'ancien chemin mutait doc directement : diagnostics, tokens
        // sémantiques, inlays et régions de pliage gardaient des offsets
        // périmés, la garde lecture-seule était contournée et les
        // listeners d'édition (pont LSP) n'étaient pas notifiés.
        for (int i = step.edits.size() - 1; i >= 0; i--) {
            EditOp op = step.edits.get(i).inverse();
            EditSpan span = doReplaceRange(op.start, op.start + op.removed.length(),
                    op.inserted, op.start + op.inserted.length(), false);
            notifyTextEditListeners(span, op.inserted);
        }
        selection = Selection.cursor(step.selBefore);
        restyleAllAsync();
        notifyImeAfterUndoRedo(wasComposing);
        return true;
    }

    public boolean redo() {
        // Lecture-seule : symétrique d'undo.
        if (readOnly) return false;
        // Symétrique d'undo : la session de snippet ne survit pas.
        snippets.end();
        UndoStep step = recorder.manager().redo();
        if (step == null) return false;
        boolean wasComposing = ime.isComposing();
        // Réappliquer les opérations dans l'ordre via le pipeline commun
        // (mêmes invariants qu'undo).
        for (EditOp op : step.edits) {
            EditSpan span = doReplaceRange(op.start, op.start + op.removed.length(),
                    op.inserted, op.start + op.inserted.length(), false);
            notifyTextEditListeners(span, op.inserted);
        }
        selection = Selection.cursor(step.selAfter);
        restyleAllAsync();
        notifyImeAfterUndoRedo(wasComposing);
        return true;
    }

    /** Notifie les listeners d'édition chaînés (pont LSP) d'une édition
     *  appliquée par undo/redo. */
    private void notifyTextEditListeners(EditSpan span, String insertion) {
        if (onTextEditListeners.isEmpty()) return;
        int s = span.start, e = span.start + span.removed;
        for (OnTextEditListener l : onTextEditListeners) {
            l.onTextEdit(s, e, insertion);
        }
    }

    /** Pousse l'état de sélection vers le listener IME après un
     *  undo/redo. Un undo multi-opérations peut toucher des plages non
     *  contiguës : le texte extrait est rafraîchi en entier. */
    private void notifyImeAfterUndoRedo(boolean wasComposing) {
        if (ime.listener() == null) return;
        ime.onTextChanged(new EditSpan(0, 0, 0));
        ime.notifySelectionChanged(selection.start, selection.end);
        if (wasComposing && !ime.listener().isSyncingExtractedText()) {
            ime.listener().onRestartInput();
        }
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
     * <p>Sans cet appel, le thread de restyle vivait autrefois aussi
     * longtemps que le process — une app ouvrant un éditeur par onglet
     * fuirait un thread (et sa file de tâches en attente) par session.
     * Depuis le correctif D-02 (audit 3.69.0), le thread expire de lui-
     * même après ~30 s d'inactivité (cœur expirable) : la fuite à vie
     * est impossible même si l'hôte oublie dispose(). L'appel reste le
     * moyen propre et immédiat d'arrêter : annulation du restyle en
     * vol, arrêt de l'exécuteur.
     *
     * <p><b>La libération est à la charge de l'HÔTE</b> — l'{@code EditorView}
     * NE l'appelle PAS pour vous (le détachement de vue est réversible,
     * une session survit à plusieurs attachements) ; le moment juste est
     * la destruction définitive de l'éditeur (p. ex. {@code onDestroy}
     * de l'activité, voir USAGE.md §5). Après {@code dispose()} : les
     * restyles asynchrones en attente sont annulés, l'exécuteur est
     * arrêté, et tout {@code setLanguage}/undo/redo ultérieur retombe sur
     * le chemin SYNCHRONE {@code restyleAll()} du moteur (correct,
     * simplement bloquant). Les éditions de texte restent pleinement
     * fonctionnelles.
     */
    public void dispose() {
        restyle.dispose();
    }

    /** Indique si {@link #dispose()} a été appelé. */
    public boolean isDisposed() {
        return restyle.isDisposed();
    }
}
