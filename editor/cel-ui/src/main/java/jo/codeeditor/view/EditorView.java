package jo.codeeditor.view;

import jo.codeeditor.view.chrome.EditorFastScroller;
import jo.codeeditor.view.chrome.EditorTheme;
import jo.codeeditor.view.chrome.GutterView;
import jo.codeeditor.view.input.EditorBlockDragController;
import jo.codeeditor.view.input.EditorInputHandler;
import jo.codeeditor.view.input.EditorKeyHandler;
import jo.codeeditor.view.input.EditorKeymap;
import jo.codeeditor.view.input.EditorScrollManager;
import jo.codeeditor.view.input.EditorZoomController;
import jo.codeeditor.view.popup.EditorHoverQuickDoc;
import jo.codeeditor.view.popup.EditorPopupAnchors;
import jo.codeeditor.view.popup.EditorPopupManager;
import jo.codeeditor.view.preview.EditorPreviewController;
import jo.codeeditor.view.preview.EditorPreviewHost;
import jo.codeeditor.view.preview.EditorPreviewSheet;
import jo.codeeditor.view.render.CaretAnimator;
import jo.codeeditor.view.render.EditorErrorStripe;
import jo.codeeditor.view.render.EditorPainterHost;
import jo.codeeditor.view.render.EditorRenderer;
import jo.codeeditor.view.render.EditorShapedLayoutCache;

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.find.Match;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.PopupCoordinator;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;
import jo.codeeditor.view.chrome.BreadcrumbBar;
import jo.codeeditor.view.chrome.EditorTheme;
import jo.codeeditor.view.chrome.GutterView;

/**
 * Vue Android personnalisée pour l'édition de code avec rendu Canvas.
 *
 * <p>Architecture :
 * <ul>
 *   <li><b>Pont IME</b> — flag explicite {@code wantsKeyboard} positionné
 *       uniquement par un tap utilisateur ; {@code onCreateInputConnection}
 *       configure {@code EditorInfo} avec le bon inputType/imeOptions/initialSel
 *       et retourne une {@link EditorImeBridge.EditorInputConnection} qui surcharge
 *       chaque méthode d'opération texte, y compris {@code replaceText}
 *       (API 34), {@code closeConnection} (gardée par génération),
 *       {@code getExtractedText} (avec armement du monitor) et
 *       {@code beginBatchEdit}/{@code endBatchEdit} (mappés sur
 *       {@link EditorSession#beginBatch()}/{@link EditorSession#endBatch()}).</li>
 *   <li><b>Toucher</b> — défilement à un doigt + sélection par glissement
 *       avec désambiguïsation par slop, sélection de mot au long-press,
 *       word/line select au double/triple tap, zoom pinch sur un
 *       {@link ScaleGestureDetector} séparé.</li>
 *   <li><b>Défilement</b> — vertical + horizontal avec bornage à
 *       {@code [0, maxV()]} et {@code [0, maxH()]}, fling à inertie via
 *       {@link OverScroller}, auto-scroll du caret dans la vue à chaque
 *       édition.</li>
 *   <li><b>Rendu</b> — passe Canvas unique : fond → bande de ligne courante
 *       → gouttière → sélection → texte → soulignés → guides d'indentation
 *       → caret. Tous les offsets sont bornés ; le remplissage de la
 *       sélection est enveloppé dans un {@code try/catch} pour qu'un offset
 *       périmé d'une frame ne puisse jamais faire planter tout l'éditeur.</li>
 *   <li><b>Caret</b> — barre d'accent de 2px ; le clignotement passe en
 *       plein à chaque édition / déplacement du caret et ne reprend qu'après
 *       530ms d'inactivité.</li>
 *   <li><b>Touches matérielles</b> — surcharge complète de
 *       {@code onKeyDown} pour backspace, flèches, Entrée, Tab, Home/End,
 *       PageUp/Down, Ctrl+Z/Y/A/C/V/X, zoom Ctrl±/0.</li>
 *   <li><b>Presse-papiers</b> — {@link #copy()}, {@link #cut()},
 *       {@link #paste()} via le presse-papiers Android (délégué à EditorClipboard).</li>
 * </ul>
 */
public class EditorView extends View {

    static {
        // Première vue créée ⇒ journal Android installé pour toute la
        // bibliothèque (modules purs inclus, via EditorLog) — sauf si
        // l'hôte a déjà installé le sien : il a priorité.
        jo.codeeditor.util.EditorLog.installIfAbsent(
                new jo.codeeditor.view.log.AndroidEditorLogger());
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public EditorSession session;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorMetrics metrics;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public EditorTheme theme;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final GutterView gutterView;
    // ── B11 (feuille de route « écarts AS ») — navigation dans le ──
    // contenu : fast-scroller (retour visuel du drag de gouttière/bande)
    // et bande d'erreurs du bord droit (marqueurs de diagnostics + rect
    // du viewport). Les DEUX sont des collaborateurs de dessin possédés
    // par la vue, pilotés par EditorInputHandler/EditorTapResolver.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorFastScroller fastScroller;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorErrorStripe errorStripe;
    // ── B15 (P2-2) — actions de gouttière de l'HÔTE : l'API publique
    // GutterAction généralisée (fondations B9c). Le registre expose
    // add/remove/clear/actionForLine ; la gouttière dessine la pill de
    // chaque action sur SA ligne (même géométrie déterministe que
    // l'œil d'aperçu) et le tap exécute perform() sans toucher au
    // caret. Données d'HÔTE : elles survivent aux setSession (parité
    // actions de pill) — l'hôte vide le registre quand il change de
    // fichier.
    public final EditorGutterActions gutterActions;
    // ── B13 (P1-2) : contrôleur du drag-déposer de bloc (mode armé
    // par la pill ✥, moteur EditorSession.moveRange) — l'état du
    // geste vit ici, le rendu (carte flottante, caret fantôme, loupe)
    // le lit.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorBlockDragController blockDragCtl;

    // ── État de défilement ────────────────────────────────────────
    // Objet d'état extrait (plan 01, étape 5) : l'invariant « offsets
    // dans [0, max] » est possédé par ScrollState (clampTo après toute
    // mutation non bornée).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final jo.codeeditor.view.state.ScrollState scroll =
            new jo.codeeditor.view.state.ScrollState();

    // ── Zoom ───────────────────────────────────────────────────────
    // L'état de zoom (fontScale + mutations d'échelle) vit dans
    // EditorZoomController ; EditorView ne conserve que les wrappers
    // publics de délégation.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorZoomController zoom = new EditorZoomController(this);
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float BASE_TEXT_SIZE_SP = 14f;

    // ── B12 (P1-3) : clé de mémoire de zoom par type de fichier ──
    // Posée par l'HÔTE (extension, id de langage…) ; chaque mutation
    // d'échelle l'enregistre dans EditorZoomMemory, la repose
    // l'applique. Nulle (défaut) : aucun changement de comportement.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public String zoomMemoryKey;

    // ── Surlignages de recherche (décoration visuelle) ────────────
    // Renseignés par l'hôte (ex. barre Rechercher/Remplacer) — chaque
    // occurrence dans le viewport est teintée theme.findMatch, la
    // courante theme.findCurrent. Objet d'état extrait (plan 01,
    // étape 3) : l'invariant « index courant dans les bornes » est
    // possédé et testé par FindState.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final jo.codeeditor.view.state.FindState find =
            new jo.codeeditor.view.state.FindState();

    // ── Popup de complétion ────────────────────────────────────────
    // Complétion intégrée par mots-clés ; l'hôte peut piloter un
    // CompletionController externe via setCompletionItems(items,
    // tokenStart, prefix). L'état affichable (liste, sélection, fenêtre
    // de scroll, cache par token) vit dans l'objet d'état extrait —
    // plan 01, étape 2 : les invariants de sélection/scroll sont
    // possédés et testés là, la vue ne fait que l'héberger.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final jo.codeeditor.view.state.CompletionState completion =
            new jo.codeeditor.view.state.CompletionState();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public CompletionProvider completionProvider;

    // ── Retour à la ligne (word wrap) ──────────────────────────────
    // Les lignes longues se replient en rangées visuelles ; le modèle
    // cache les rangées par ligne et donne la correspondance O(log L)
    // ligne-doc ↔ rangée-visuelle (sommes préfixes).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean wordWrap = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public jo.codeeditor.wrap.WrapModel wrapModel;
    int wrapWidthPx = 0;

    /**
     * Étendue horizontale (coords CONTENU hors gouttière) de la chip
     * diagnostic la plus à droite, mesurée par la passe de dessin
     * (pattern {@code chipExtent}) : une chip qui déborde de sa ligne
     * étend la largeur scrollable (maxH).
     * 0 = aucune chip dessinée ce frame.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float chipExtentContentX = 0f;

    /**
     * Géométrie de repli d'UNE ligne en mode word-wrap (source unique
     * partagée par le rendu, le caret/tap, le scroll et les chips) :
     * 1re rangée pleine largeur à {@code textAreaLeft}, rangées de
     * continuation plus étroites indentées du leading-whitespace (cap à
     * la demi-largeur).
     *
     * <p>La formule de comptage ({@code ceil(len / maxCols)}) et le
     * découpage ({@code maxCols + (r-1)*colsPerCont}) doivent rester
     * cohérents : dès que l'indent de continuation > 0, toute divergence
     * laisserait la queue de la ligne (jusqu'à {@code wrapIndentCols}
     * caractères) jamais dessinée.</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final class WrapRows {
        final int maxColsPerRow;   // capacité de la 1re rangée
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public final int wrapIndentCols;  // indentation (cols) des rangées de continuation
        final int colsPerCont;     // capacité d'une rangée de continuation
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public final int rows;            // nombre total de rangées (≥1)

        WrapRows(int maxColsPerRow, int wrapIndentCols, int colsPerCont, int rows) {
            this.maxColsPerRow = maxColsPerRow;
            this.wrapIndentCols = wrapIndentCols;
            this.colsPerCont = colsPerCont;
            this.rows = rows;
        }

        /** Colonne de début de la rangée {@code r} dans la ligne brute. */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public int rowStartCol(int r) {
            return r <= 0 ? 0 : maxColsPerRow + (r - 1) * colsPerCont;
        }

        /** Colonne de fin (EXCLUSIVE, bornée à lineLen) de la rangée {@code r}. */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public int rowEndCol(int r, int lineLen) {
            return Math.min(rowStartCol(r) + (r == 0 ? maxColsPerRow : colsPerCont),
                    lineLen);
        }

        /** La rangée (base 0) contenant la colonne {@code col}. */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public int rowForCol(int col) {
            if (col < maxColsPerRow) return 0;
            return 1 + (col - maxColsPerRow) / colsPerCont;
        }
    }

    /**
     * Calcule la géométrie de pliage d'une ligne (word-wrap). Hors wrap,
     * retourne une rangée unique pleine largeur (délégué à
     * {@link EditorWrapGeometry}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public WrapRows wrapRowsFor(int line, int lineLen) {
        return wrapGeometry.wrapRowsFor(line, lineLen);
    }

    // ── Cache de rendu par ligne ─────────────────────────────────
    // Par ligne de doc : StyledLine + inlays filtrés + spans sém +
    // tables colonnes↔visuelles. Triple-stamp (rev texte+inlay+sém) :
    // une édition n'invalide que les lignes réellement changées.
    // Éviction LRU 512.
    final LineRenderCache renderCache = new LineRenderCache();

    // ── Index de pliage à sommes préfixes ──────────────────────────
    // Possédé par EditorFoldIndex (mémoïsation + requêtes O(log plis)) ;
    // isLineFoldedCached() / countHiddenLinesAbove() ci-dessous et
    // docLineForScreenY() consomment l'index via relais.
    final EditorFoldIndex foldIndex = new EditorFoldIndex(this);
    // Cache des blocs de code (paires d'accolades ou régions
    // d'indentation) pour les guides d'indentation — mémoïsé
    // par révision du document.
    final CodeBlockIndex codeBlockIndex = new CodeBlockIndex(this);
    // Détecteur de lignes sticky (en-têtes collés en haut pendant
    // le scroll, comme les éditeurs de code de bureau).
    final StickyLineDetector stickyLineDetector = new StickyLineDetector(this);
    // Sticky lines activées/désactivées (défaut : true).
    public boolean stickyLinesEnabled = true;
    // S4 (lot 2, J4) : nombre MAXIMAL de lignes sticky empilées en haut
    // (défaut mobile : 2 — classe + méthode ; 3-5 tenable sur tablette,
    // cf. setStickyMaxLines). Borné [1, 5].
    public int stickyMaxLines = 2;
    // Carrés colorés inline pour #RGB/#RRGGBB/#AARRGGBB (défaut : true).
    public boolean colorSwatchesEnabled = true;
    // B26 : tap sur un code couleur hex → sélecteur de couleur ancré
    // (défaut : true — les pastilles inline marquent la zone tappable).
    public boolean colorPickerEnabled = true;
    // Variable-specific colors : chaque variable reçoit une teinte
    // unique basée sur son nom (défaut : false — opt-in car
    // nécessite des semantic tokens LSP pour bien fonctionner).
    public boolean variableColorsEnabled = false;

    /** Lot B : accède au cache des blocs de code (package render). */
    public int[][] getCodeBlocks() {
        return codeBlockIndex.get();
    }

    /** Lot B : indique si le dernier calcul était en mode
     *  indentCountMode (non C_LIKE). */
    public boolean isIndentCountMode() {
        return codeBlockIndex.isIndentCountMode();
    }
    // Géométrie du word wrap (rangées, docLine↔Y, colonnes de rangée)
    // — possédée par EditorWrapGeometry.
    final EditorWrapGeometry wrapGeometry = new EditorWrapGeometry(this);
    // Mapping point écran ↔ offset document et position écran du caret
    // — possédé par EditorHitMapper.
    final EditorHitMapper hitMapper = new EditorHitMapper(this);
    // Construction du modèle de rangées du menu contextuel unifié
    // — possédée par EditorNavMenuRows.
    final EditorNavMenuRows navMenuRowsBuilder = new EditorNavMenuRows(this);

    /**
     * Remplacement en O(log plis) de {@code session.isLineFolded()} dans
     * les chemins de dessin / hit-test (même sémantique : union des
     * plages {@code (startLine, endLine]} des plis repliés).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean isLineFoldedCached(int docLine) {
        return foldIndex.get().isHidden(docLine);
    }

    // ── Cache de layouts façonnés adressé par contenu ─────────────
    // ~25 % des lignes réelles sont identiques octet pour octet ; LRU
    // dans EditorShapedLayoutCache mémoïsant le layout par CONTENU
    // (relais ci-dessous pour le dessin et les tests).
    final EditorShapedLayoutCache shapedCache = new EditorShapedLayoutCache(this);
    // Construction des entrées de cache par ligne + correspondance de
    // colonnes brute↔visuelle (inlays) — possédées par
    // EditorLineLayoutResolver.
    final EditorLineLayoutResolver lineLayouts = new EditorLineLayoutResolver(this);

    /**
     * Retourne un {@link android.text.StaticLayout} façonné pour le dessin
     * en mode ligatures de {@code lineText}, mémoïsé par adressage de
     * contenu (délégué à {@link EditorShapedLayoutCache#layoutFor}).
     *
     * <p>Sûreté de threads : appelé depuis le thread UI uniquement
     * (chemin de dessin).</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public android.text.StaticLayout shapedLayoutFor(String lineText, StyledLine styled,
                                              android.graphics.Paint paint) {
        return shapedCache.layoutFor(lineText, styled, paint);
    }

    /** Nombre de layouts façonnés mémoïsés (tests/diagnostics). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int shapedLayoutCacheSize() {
        return shapedCache.size();
    }

    private final EditorSession.OnLinesShiftedListener cacheShiftListener =
        new EditorSession.OnLinesShiftedListener() {
            @Override
            public void onLinesShifted(int fromLine, int delta) {
                renderCache.shiftKeys(fromLine, delta);
            }
            @Override
            public void onLinesReset() {
                // EditorSession.restyleAllAsync peut appeler ceci depuis
                // un thread en arrière-plan (le worker restyleExecutor).
                // renderCache.clear() mute une HashMap et doit s'exécuter
                // sur le thread UI ; invalidate() doit aussi être sur le
                // thread UI pour que la vue se redessine correctement.
                // Miroir du pattern multi-thread de notifyDiagnosticsChanged.
                if (getHandler() != null
                        && Thread.currentThread() != getHandler().getLooper().getThread()) {
                    getHandler().post(() -> {
                        renderCache.clear();
                        invalidate();
                    });
                } else {
                    renderCache.clear();
                    invalidate();
                }
            }
        };

    // ── Popup d'aide de signature ────────────────────────────────
    // Aide de signature au-dessus du caret dans un appel de fonction
    // (contrairement à la complétion, EN DESSOUS). Repli synthétique
    // « function(…) param N » sans résolveur (démo, langages sans
    // serveur).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final jo.codeeditor.completion.SignatureHelpController signatureHelpController =
        new jo.codeeditor.completion.SignatureHelpController();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean signatureHelpVisible = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public jo.codeeditor.completion.SignatureHelpController.SignatureHelp signatureHelpData;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public SignatureHelpResolver signatureHelpResolver;

    /**
     * Interface de résolveur pour l'aide de signature — l'hôte en branche
     * un pour alimenter des signatures conscientes du langage. Sans
     * résolveur, le popup se replie sur une indication synthétique
     * « function(…) param N ».
     */
    public interface SignatureHelpResolver {
        jo.codeeditor.completion.SignatureHelpController.SignatureHelp resolve(
            String text, int caret);
    }

    // ── Popup quick doc ───────────────────────────────────────────
    // Doc du symbole au caret/survol : survol > 500ms (desktop),
    // long-press (mobile) ; fermé par tap ailleurs, scroll, édition,
    // Échap.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public QuickDocResolver quickDocResolver;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean quickDocVisible = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public jo.codeeditor.doc.QuickDoc.QuickDocContent quickDocContent;
    // Provider de diagnostics + poussée débouncée vers la session et la
    // gouttière — possédés par EditorDiagnosticsPusher.
    final EditorDiagnosticsPusher diagnosticsPusher = new EditorDiagnosticsPusher(this);
    // Requêtes de diagnostics (groupes de chips, hit-tests, garde
    // ampoule) — possédées par EditorDiagnosticsLocator.
    final EditorDiagnosticsLocator diagnosticsLocator = new EditorDiagnosticsLocator(this);
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float quickDocX, quickDocY;
    /** Offset d'ancrage du quick doc : le popup est REPOSITIONNÉ à chaque frame depuis cet offset (il suit le texte au scroll) au lieu de rester figé en coordonnées écran. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int quickDocAnchorOffset;
    /** Scroll vertical du corps du quick doc (drag sur le popup). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float quickDocScrollY;
    // Survol souris → quick doc : dwell 500 ms + slop 20 px, possédés par
    // EditorHoverQuickDoc (enregistre le listener de survol à la
    // construction).
    final EditorHoverQuickDoc hoverQuickDoc = new EditorHoverQuickDoc(this);

    /**
     * Résolveur du popup quick doc. Retourne le texte brut du commentaire
     * de documentation (Javadoc/KDoc) ; la vue l'analyse via
     * {@link jo.codeeditor.doc.QuickDoc#parseQuickDoc}.
     */
    public interface QuickDocResolver {
        String resolve(String text, int offset);
    }

    /**
     * Résolveur du tooltip d'un inlay hint (I2, lot 2 J3) — câblé par le
     * pont de langage quand le provider expose la résolution paresseuse
     * ({@code InlayHintProvider.resolveHint}). Appelé au PREMIER appui
     * long sur le hint, sur la voie « features » ; retourne null si le
     * serveur n'a rien (le hint reste muet, pas de requête en boucle).
     */
    public interface InlayTooltipResolver {
        /** Retourne le tooltip du hint identifié par (offset, texte), ou null. */
        String resolveInlayTooltip(CharSequence text, int offset, String hint);
    }

    /** Résolveur du tooltip des inlay hints (null = résolution absente). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public InlayTooltipResolver inlayTooltipResolver;

    // ── Ampoule des code actions ─────────────────────────────────
    // 💡 dans la gouttière des lignes de diagnostic ; tap → popup dont
    // le contenu est RÉSOLU À LA DEMANDE (plus de balayage pré-calculé).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public CodeActionsResolver codeActionsResolver;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final java.util.Map<Integer, List<CodeAction>> codeActionsByLine = new java.util.HashMap<>();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean codeActionsPopupVisible = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int codeActionsPopupLine = -1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int codeActionsSelected = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float CODE_ACTIONS_POPUP_WIDTH_DP = 260f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float CODE_ACTIONS_ROW_HEIGHT_DP = 28f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int CODE_ACTIONS_MAX_ROWS = 8;

    /** Résolveur des code actions — liste de {@link CodeAction}s par ligne. */
    public interface CodeActionsResolver {
        List<CodeAction> resolve(String text, int line);

        /** Résolution au POINT D'INVOCATION (sélection, sinon mot, sinon
         *  ligne) : contenu calculé à la demande, UNE requête par
         *  invocation. Repli : la méthode ligne (résolveurs hôtes inchangés). */
        default List<CodeAction> resolveAtCaret(String text, int caretOffset,
                int selectionStart, int selectionEnd) {
            if (text == null) return resolve("", 0);
            int line = 0;
            for (int i = 0, borne = Math.max(0, Math.min(caretOffset, text.length()));
                    i < borne; i++) {
                if (text.charAt(i) == '\n') line++;
            }
            return resolve(text, line);
        }
    }

    /** Pré-validation de renommage : nom proposé (placeholder) ou null
     *  (non renommable / repli local). Branché par le pont langage. */
    public interface RenamePrepareResolver {
        String prepareRename(String text, int offset);
    }

    /** Pré-validation de renommage (null = heuristique locale). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public RenamePrepareResolver renamePrepareResolver;

    /**
     * Une code action unitaire — titre, kind et un Runnable pour
     * l'appliquer.
     */
    public static final class CodeAction {
        public final String title;
        public final String kind; // "quickfix", "refactor", "source"
        /** Motif de désactivation (action affichée mais inerte), ou null. */
        public final String disabledReason;
        public final Runnable apply;
        public CodeAction(String title, String kind, Runnable apply) {
            this(title, kind, null, apply);
        }
        public CodeAction(String title, String kind, String disabledReason, Runnable apply) {
            this.title = title != null ? title : "";
            this.kind = kind != null ? kind : "quickfix";
            this.disabledReason = disabledReason;
            this.apply = apply;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié (NavMenu) — ouvert par « Actions ⋯ » de la
    // pill : sections GO TO / QUICK FIXES / INTENTIONS (si non-vides) +
    // MORE (débordement T1) ; pick GO TO multi-cibles → mode RESULTS.
    // ════════════════════════════════════════════════════════════════

    /** Le menu est-il visible ? */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean navMenuVisible = false;
    /** Mode picker : true = liste de cibles d'une option GO TO. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean navMenuResultsMode = false;
    /** Ligne d'ancrage du popup (sous la ligne du caret). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int navMenuLine = -1;
    /** Offset caret de résolution (l'extrémité START de la sélection). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int navMenuCaretOffset = -1;
    /** Options GO TO applicables (mode Menu) — Declaration /
     *  Implementations / Type declaration / Super (ordre NavKind). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<NavigationMenu.NavOption> navMenuOptions = new ArrayList<>();
    /** Quick fixes (kind quickfix) de la ligne du caret (mode Menu). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<CodeAction> navMenuQuickFixes = new ArrayList<>();
    /** Intentions (kind != quickfix) de la ligne du caret (mode Menu). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<CodeAction> navMenuIntentions = new ArrayList<>();
    /** Cibles du picker (mode Results). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<NavigationMenu.NavTarget> navMenuTargets = new ArrayList<>();
    /** Index de rangée pressée (-1 = aucune) — feedback press. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int navMenuPressedIdx = -1;
    /** Décalage vertical de scroll du contenu (pixels), borné. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float navMenuScrollY = 0;

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float NAV_MENU_ROW_HEIGHT_DP = 40f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float NAV_MENU_HEADER_HEIGHT_DP = 26f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float NAV_MENU_MAX_HEIGHT_DP = 360f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    // B25 : plancher abaissé 240 → 168 — la largeur suit le contenu
    // (navMenuContentWidth), un débordement de 8 actions courtes n'a
    // plus à occuper 240dp.
    public static final float NAV_MENU_MIN_WIDTH_DP = 168f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float NAV_MENU_MAX_WIDTH_DP = 320f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float NAV_MENU_GAP_DP = 6f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float NAV_MENU_MARGIN_DP = 8f;

    /**
     * Une rangée du menu contextuel unifié. SOURCE UNIQUE du
     * rendu et du hit-test (pattern SelectionToolbarMetrics).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final class NavMenuRow {
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public static final int TYPE_HEADER = 0;
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public static final int TYPE_OPTION = 1;
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public static final int TYPE_ACTION = 2;
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public static final int TYPE_TARGET = 3;
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public static final int TYPE_NOTHING = 4;
        /** Section de la rangée (mode Menu) : 0 = GO TO, 1 = QUICK FIXES, 2 = INTENTIONS. */
        static final int SECTION_GO_TO = 0;
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public static final int SECTION_QUICK_FIXES = 1;
        static final int SECTION_INTENTIONS = 2;
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public final int type;
        /** Index dans la liste de section (option / action / target). */
        final int index;
        /** La section d'une rangée TYPE_ACTION (SECTION_*). */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public final int section;
        /** NavigationMenu.NavOption (TYPE_OPTION) ou CodeAction (TYPE_ACTION). */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public final Object ref;
        NavMenuRow(int type, int index, int section, Object ref) {
            this.type = type;
            this.index = index;
            this.section = section;
            this.ref = ref;
        }
    }

    // ── Popup go-to-symbol ────────────────────────────────────────
    // Popup centré en haut avec un champ de filtre et une liste de
    // symboles scrollable. Le filtre est un préfixe insensible à la
    // casse + camel-hump (NavigationMenu.filter).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public SymbolResolver symbolResolver;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean goToSymbolVisible = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public String goToSymbolFilter = "";
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<NavigationMenu.Symbol> goToSymbolAll = new ArrayList<>();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<NavigationMenu.Symbol> goToSymbolFiltered = new ArrayList<>();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int goToSymbolSelected = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int goToSymbolScrollOffset = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float GO_TO_SYMBOL_WIDTH_DP = 320f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float GO_TO_SYMBOL_ROW_HEIGHT_DP = 26f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int GO_TO_SYMBOL_MAX_ROWS = 10;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float GO_TO_SYMBOL_RADIUS_DP = 6f;

    /**
     * Résolveur du popup go-to-symbol. Retourne TOUS les symboles du
     * document ; la vue filtre via {@link NavigationMenu#filter} à chaque
     * frappe dans le champ de filtre du popup.
     */
    public interface SymbolResolver {
        List<NavigationMenu.Symbol> resolve(String text);
    }

    // ── Câblage des providers LSP ─────────────────────────────────
    // Les résolveurs pontent les providers du SPI Language vers les
    // chemins UI/dessin ; setLanguage() les branche quand le provider
    // retourne non-null.

    /** Résolveur du go-to-definition. Retourne une liste de localisations cibles. */
    public interface DefinitionResolver {
        List<jo.codeeditor.lang.model.DefinitionLocation> resolve(String text, int offset);
    }
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DefinitionResolver definitionResolver;

    /**
     * Resolver pour le go-to-TYPE-declaration (le type du symbole au
     * caret, {@code Foo x = …} → {@code class Foo}). Nourrit la section
     * GO TO du menu contextuel unifié (toolbar de sélection → Actions ⋯).
     */
    public interface TypeDefinitionResolver {
        List<jo.codeeditor.lang.model.DefinitionLocation> resolve(String text, int offset);
    }
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public TypeDefinitionResolver typeDefinitionResolver;

    /**
     * Resolver pour le go-to-IMPLEMENTATIONS (les héritiers DIRECTS du
     * type en contexte au caret — une référence de type, sinon la classe
     * englobante). Section GO TO du menu contextuel unifié
     * ({@code implementationTargets} — icône layers).
     */
    public interface ImplementationsResolver {
        List<jo.codeeditor.lang.model.DefinitionLocation> resolve(String text, int offset);
    }
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public ImplementationsResolver implementationsResolver;

    /**
     * Resolver pour le go-to-SUPER (le membre outrepassé dans chaque
     * supertype, sinon les supertypes DIRECTS du type en contexte). Section
     * GO TO du menu contextuel unifié ({@code superTargets} — icône pin).
     */
    public interface SuperResolver {
        List<jo.codeeditor.lang.model.DefinitionLocation> resolve(String text, int offset);
    }
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public SuperResolver superResolver;

    /** Résolveur du find-references. Retourne une liste de localisations d'usage. */
    public interface ReferencesResolver {
        List<jo.codeeditor.lang.model.DefinitionLocation> resolve(String text, int offset);
    }

    /**
     * Résolveur des document highlights (occurrences du symbole sous le
     * caret). Retourne une liste de paires [start, end] (offsets).
     */
    public interface DocumentHighlightResolver {
        /** Retourne des paires int[]{start, end} de plages surlignées. */
        List<int[]> resolve(String text, int offset);
    }
    DocumentHighlightResolver documentHighlightResolver;
    /** Plages document-highlight en cache ; invalidées au déplacement du caret + édition. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final List<int[]> documentHighlights = new ArrayList<>();

    // ════════════════════════════════════════════════════════════════
    // Surlignage de l'appariement de parenthèses
    // ════════════════════════════════════════════════════════════════

    /**
     * La paire de parenthèses actuellement surlignée, comme offsets
     * document des caractères OUVRANT et FERMANT — ou {@code null} quand
     * le caret n'est pas adjacent à une parenthèse (ou qu'elle est non
     * appariée). Recalculée de façon synchrone à chaque déplacement du
     * caret + édition par {@link #updateBracketPair()} — le balayage est
     * borné ({@link #BRACKET_SCAN_LIMIT}) pour qu'une parenthèse non
     * appariée dans un énorme fichier ne coûte pas O(N) par frappe.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int[] bracketPair;

    /**
     * Plafond du balayage d'appariement de parenthèses
     * ({@code BRACKET_SCAN_LIMIT = 50_000}) pour qu'une parenthèse non
     * appariée dans un énorme fichier ne produise simplement aucun
     * surlignage au lieu de parcourir tout le document.
     */
    static final int BRACKET_SCAN_LIMIT = 50_000;

    /**
     * Trouve la parenthèse appariée pour celle immédiatement AVANT ou SUR
     * {@code caret}, comme {@code [openOffset, closeOffset]}, ou null
     * (délégué à {@link EditorBracketMatcher}).
     */
    static int[] matchingBracket(CharSequence text, int caret) {
        return EditorBracketMatcher.matchingBracket(text, caret);
    }

    /**
     * Lot B.10 : profondeur d'imbrication des accolades à la
     * position {@code caret} (délégué à
     * {@link EditorBracketMatcher#braceScopeLevel}). Utilisé par
     * les guides d'indentation pour ancrer la portée active sur
     * la paire d'accolades pour les langages C_LIKE.
     */
    public static int braceScopeLevel(CharSequence text, int caret) {
        return EditorBracketMatcher.braceScopeLevel(text, caret);
    }

    /**
     * Recalcule {@link #bracketPair} depuis la sélection courante.
     * Appelé de façon synchrone aux déplacements du caret, éditions et
     * changements de langage/session — le balayage est borné et s'arrête
     * typiquement à l'appariement, donc c'est sûr de l'exécuter sur le
     * thread UI.
     */
    void updateBracketPair() {
        if (session == null) {
            bracketPair = null;
            return;
        }
        Selection sel = session.getSelection();
        // ★ B19 : balayage borné sur la ROPE (charAt O(log n)) — getText()
        // matérialisait le document entier à chaque déplacement de caret.
        bracketPair = matchingBracket(session.getDocument().charSequence(), sel.start);
    }

    /** Résolveur du rename. Retourne le nouveau texte complet après renommage. */
    public interface RenameResolver {
        /** Retourne le nouveau texte, ou null si le rename a échoué. */
        String rename(String text, int offset, String newName);
    }
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public RenameResolver renameResolver;

    /** Résolveur du formatage. Retourne le nouveau texte complet. */
    public interface FormatterResolver {
        String format(String text);
    }
    FormatterResolver formatterResolver;

    // Inlay hints (débouncés), document highlights (débouncés) et
    // génération d'annulation — possédés par EditorLanguageBridge ;
    // scheduleDocumentHighlights() reste un relais package-private
    // (appelé par EditorImeBridge).
    void scheduleDocumentHighlights() {
        languageBridge.scheduleDocumentHighlights();
    }

    // État du popup references (réutilise l'UI go-to-symbol) — possédé par
    // EditorReferencesController (visibilité, filtre, listes, sélection,
    // scroll, génération d'annulation).
    final EditorReferencesController referencesController = new EditorReferencesController(this);

    // Affichage des caractères non imprimables (espaces, tabulations,
    // sauts de ligne).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean showNonPrintable = false;

    // Bascule de visibilité du caret. Défaut true. Découple « cacher le
    // caret » de setReadOnly(true) (qui bloquerait AUSSI les mutations
    // programmatiques — la console resterait vide à vie).
    // ConsoleLogView : setFocusable(false) + setCaretVisible(false),
    // sans setReadOnly. Voir EditorRenderer.drawCaret.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean caretVisible = true;

    // État de la loupe — active pour le drag DE POIGNÉE et le
    // DRAG-SELECT AU DOIGT (dragHandle ET applyDragSelect alimentent X/Y ;
    // UP/CANCEL/changement de session/détachement/perte de focus
    // désactivent). La bulle custom est dessinée par
    // EditorChromePainter.drawMagnifier (60dp, zoom réglable M2, ±3
    // lignes, consciente des inlays) ; la bulle NATIVE (M3, opt-in
    // API 28+) est portée par le widget plateforme.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean magnifierActive = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float magnifierX = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float magnifierY = 0;
    // M2 (lot 2, J5) : facteur de zoom de la bulle custom (×1.5–×3,
    // défaut ×2) — piloté par setMagnifierZoom(float).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float magnifierZoom = 2f;
    // M3 (lot 2, J5) : drapeau de la loupe native (opt-in API 28+,
    // défaut faux = bulle custom) — piloté par
    // setNativeMagnifierEnabled(boolean).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean nativeMagnifierEnabled = false;

    // Bascule des ligatures de police. Quand activée, l'éditeur dessine
    // chaque ligne en un seul appel drawText (au lieu de par span) pour
    // que les ligatures comme ->, =>, ==, !=, >=, <=, &&, ||, :: se
    // forment correctement. La coloration syntaxique est désactivée quand
    // les ligatures sont actives (compromis : ligatures vs couleurs).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean fontLigatures = false;

    // Minimap — rendu miniature du fichier entier, dessiné
    // dans une bande étroite sur le bord droit. Montre la structure du
    // document d'un coup d'œil + indique le rectangle du viewport courant.
    // Tap/drag pour scroller. La géométrie (constantes + bornes) vit dans
    // EditorChromePainter, qui dessine la bande.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean minimapEnabled = false;

    /**
     * Lot B.11 (audit v3.41.0) — style des guides d'indentation :
     * <ul>
     *   <li>{@link IndentGuideStyle#OFF} — aucun guide.</li>
     *   <li>{@link IndentGuideStyle#INDENT} — guides d'indentation
     *       seuls (couleur de base, pas de mise en évidence de la
     *       portée active).</li>
     *   <li>{@link IndentGuideStyle#INDENT_WITH_SCOPE} (défaut) —
     *       guides d'indentation + mise en évidence de la portée
     *       active (bloc contenant le caret) en couleur d'accent.</li>
     * </ul>
     * Piloté par {@link #setIndentGuideStyle(IndentGuideStyle)} /
     * {@link #getIndentGuideStyle()}.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public IndentGuideStyle indentGuideStyle = IndentGuideStyle.INDENT_WITH_SCOPE;

    // ── Couches de surcharge ─────────────────────────────────────
    // Overlays Canvas : chips, pill de sélection, go-to-line/rename
    // (vrais PopupWindow + EditText — le Canvas seul ne reçoit pas la
    // saisie), feuille de diagnostic.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean diagnosticChipsEnabled = true;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean selectionToolbarVisible = false;
    // ── Toolbar de sélection ─────────────────────────────────────
    // La toolbar fonctionne aussi en mode REPLIÉ (re-tap sur le caret →
    // Coller/Sélectionner tout + boutons icônes uniquement), avec feedback
    // de pression et animation d'entrée (entrancePop + cascade par item).
    /** uptimeMillis du dernier affichage — pilote l'animation d'entrée. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public long selectionToolbarShownAt = 0L;
    /** Index de l'item pressé (feedback de pression), -1 = aucun. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int selectionToolbarPressedIdx = -1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_COPY = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_CUT = 1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_PASTE = 2;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_SELECT_ALL = 3;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_DOCS = 4;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_ACTIONS = 5;
    // L'état des popups go-to-line et rename (visibilité, texte, offsets,
    // PopupWindow) vit dans EditorGoToLinePopup / EditorRenamePopup —
    // leurs seuls consommateurs.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean diagnosticSheetVisible = false;
    // Popup par diagnostic (pattern DiagnosticSheet).
    // Affiche le message COMPLET + quick-fixes d'un seul diagnostic tapé.
    // Redessiné en bottom sheet (scrim + panneau arrondi + en-tête avec
    // libellé de sévérité + fermeture × + rangées de quick-fixes).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean diagnosticPopupVisible = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DiagnosticShift.Diagnostic diagnosticPopupItem = null;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int diagnosticPopupOffset = -1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int diagnosticSheetScroll = 0;

    // ── Géométrie de la feuille de diagnostic ────────────────────
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_SHEET_HEADER_DP = 46f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_SHEET_MSG_LINE_DP = 19f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int DIAG_SHEET_MAX_MSG_LINES = 6;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_SHEET_ACTION_ROW_DP = 44f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_SHEET_ACTIONS_BLOCK_DP = 36f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_SHEET_BOTTOM_PAD_DP = 10f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_SHEET_RADIUS_DP = 16f;

    // ── Chip de diagnostic ──
    // Une pastille par ligne — la plus sévère Error/Warning — placée après
    // la fin de ligne ; la taper ouvre la feuille de diagnostic.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_CHIP_GAP_CHARS = 3f;

    // ── Feuille de liste de diagnostics groupée ──────────────────
    // Par ligne de départ : une ligne à PLUSIEURS diagnostics → badge de
    // compte sur la chip + feuille groupée (une rangée par diagnostic,
    // tap → popup de détail + quick fixes). Sinon la chip/gouttière ne
    // montrerait que le plus sévère (avertissement masqué par erreur).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int diagnosticListSheetLine = -1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_LIST_ROW_DP = 44f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int DIAG_LIST_MAX_ROWS = 8;

    // ── Popup ancré de squiggle (D1) ─────────────────────────────
    // Niveau 1 de lecture : tap au cœur d'un squiggle → fiche compacte
    // ancrée, non modale (re-tap → caret) ; multi → mini-liste ;
    // « See all » → fiche basse. Logique : EditorSquigglePopups ;
    // géométrie : EditorPopupAnchors.squigglePopupMetrics.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean squigglePopupVisible = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DiagnosticShift.Diagnostic squigglePopupItem = null;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<DiagnosticShift.Diagnostic> squigglePopupChoices = new ArrayList<>();

    // ── Sélecteur de couleur (B26) ───────────────────────────────
    // Tap au cœur d'un code couleur hex (les pastilles inline du
    // renderer marquent la zone) → carte ancrée non modale : champ
    // saturation/valeur + bande de teinte + palette ; le pick
    // remplace le code en conservant format (3/6/8 chiffres) et
    // alpha d'origine, via le pipeline d'édition (annulable d'un
    // undo). Logique : EditorColorPickerPopup ; géométrie :
    // EditorPopupAnchors.colorPickerMetrics ; dessin :
    // EditorColorPickerPainter.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean colorPickerVisible = false;
    /** Début du code tapé dans le document (-1 = refermé). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int colorPickerStart = -1;
    /** Fin (exclusive) du code tapé. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int colorPickerEnd = -1;
    /** ARGB d'ORIGINE du code tapé (référence + alpha conservé). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int colorPickerOriginal = 0;
    /** ARGB en cours d'édition (vivant pendant le drag). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int colorPickerCurrent = 0;
    /** Format du code d'origine : 3, 6 ou 8 chiffres hex. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int colorPickerDigits = 6;
    /** Élément pressé : 0 aucun, 1 champ SV, 2 bande de teinte. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int colorPickerPressed = 0;

    // ── Poignées de sélection (mobile) ────────────────────────────
    // Après un long-press ou un double-tap, deux poignées déplaçables
    // apparaissent au début et à la fin de la sélection. Glisser une
    // poignée déplace cette extrémité de la sélection.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean handlesVisible = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int handleDragMode = 0; // 0=aucune, 1=début, 2=fin, 3=caret replié
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float HANDLE_RADIUS_DP = 8f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float HANDLE_TAP_RADIUS_DP = 16f;

    // ── État IME ───────────────────────────────────────────────────
    /**
     * Positionné UNIQUEMENT par un tap utilisateur explicite. Le focus
     * seul ne lève jamais le clavier — ouvrir un fichier, changer
     * d'onglet, revenir d'une feuille ne doit pas faire surgir l'IME.
     * La perte de focus efface le flag pour qu'un refocus passif reste
     * silencieux.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean wantsKeyboard = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorImeBridge imeBridge = new EditorImeBridge(this);
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorScrollManager scrollManager;
    final EditorViewportPrefetcher viewportPrefetcher = new EditorViewportPrefetcher(this);
    int connectionGeneration = 0;

    // ── Clignotement + glissement du caret ─────────────────────────
    // TOUT l'état du caret vit dans caretAnim, y compris l'horodatage
    // de dernière activité (lastEditTime) : le renderer et le painter
    // de surlignage le lisent via la référence à l'animateur.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final CaretAnimator caretAnim = new CaretAnimator(this);

    // ── Souligné ondulé de diagnostic ────────────────────────────

    // ── Guides d'indentation ──────────────────────────────────────

    // ── Plafond de fenêtre du texte extrait (sûr pour le Binder) ──
    static final int MAX_EXTRACT_CHARS = 100_000;
    int extractedTextMonitorToken = -1;
    // R6 (plan 09) : fenêtre demandée par la dernière requête d'extraction
    // (hintMaxChars) — les mises à jour du moniteur gardent cette taille.
    int extractedTextMaxChars = MAX_EXTRACT_CHARS;

    // ── Infos d'ancre du curseur (G9i, API 21+) ───────────────────
    // Certains IME (japonais/chinois) demandent des mises à jour de
    // l'ancre du curseur pour positionner leur fenêtre de candidats.
    // 0 = pas de monitoring ; l'IME le règle via
    // InputConnection.requestCursorUpdates.
    int cursorAnchorMonitorMode = 0;

    // ── Peintures réutilisables (évite le GC dans onDraw) ──────────
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final Paint bgPaint = new Paint();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final Paint selPaint = new Paint();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final Paint caretPaint = new Paint();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final Paint squigglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final Paint guidePaint = new Paint();

    // Tout le dessin Canvas est délégué à ce renderer. EditorView garde
    // ses champs d'état, son API publique, la gestion des entrées, l'IME,
    // etc. — seules les méthodes de dessin ont bougé. Le renderer accède
    // aux champs package-private d'EditorView via la référence `view`.
    private final EditorRenderer renderer;
    /**
     * B7 — fondus d'apparition/disparition des popups Canvas (entrées par
     * front montant dans le pipeline de rendu, sorties par photographie au
     * dismiss). Public restreint : les popups du même groupe y appellent
     * {@code snuff} juste avant de nettoyer leur état.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final jo.codeeditor.view.popup.EditorPopupFade popupFade;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorInputHandler inputHandler;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorPopupManager popupManager;
    private final EditorKeyHandler keyHandler;
    final EditorClipboard clipboard = new EditorClipboard(this);
    // Actions de document asynchrones (go-to-definition, formatage,
    // organize imports) — possédées par EditorDocumentActions.
    final EditorDocumentActions documentActions = new EditorDocumentActions(this);

    /**
     * Le keymap piloté par les données, re-bindable.
     * {@link EditorKeyHandler} résout chaque événement de touche matérielle
     * à travers cette table ; remplacez-la via {@link #setKeymap(EditorKeymap)}
     * pour re-binder des commandes à l'exécution (voir {@link EditorCommands}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public EditorKeymap keymap = EditorKeymap.defaults();

    /**
     * Remplace le keymap des touches matérielles (re-binding souple,
     * voir {@link EditorKeymap#defaults}). Passer null restaure la table
     * par défaut. Les changements s'appliquent au prochain événement de
     * touche.
     */
    public void setKeymap(EditorKeymap keymap) {
        this.keymap = keymap != null ? keymap : EditorKeymap.defaults();
    }

    /** Le keymap actif (mutable — re-binder directement si besoin). */
    public EditorKeymap getKeymap() {
        return keymap;
    }

    /**
     * L'hôte des painters de plugin. Enregistrez des
     * {@link EditorDecorationPainter}s pour ajouter des décorations de
     * texte, des marques de gouttière et des inlays fantômes sans toucher
     * aux couches propres de l'éditeur. Un painter qui lève est retiré
     * plutôt que de faire planter l'éditeur.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorPainterHost painterHost = new EditorPainterHost();

    /** L'hôte des painters de plugin (enregistrer/désenregistrer des painters). */
    public EditorPainterHost getPainterHost() {
        return painterHost;
    }

    // ── SPI Language ──────────────────────────────────────────────
    // L'instance Language fournit toute l'intelligence de langage
    // (complétion, hover, aide de signature, diagnostics, etc.) via des
    // méthodes provider optionnelles. Une fois définie, elle remplace les
    // résolveurs par fonctionnalité.
    /** Accès package pour EditorPopupManager (trigger chars). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public Language language;
    /** Coordonne le z-order + la fermeture de tous les popups de l'éditeur. */
    private final PopupCoordinator popupCoordinator = new PopupCoordinator();

    // Pont SPI Language → résolveurs UI + rafraîchissements débouncés
    // (code actions, inlay hints, document highlights) + StyleReceiver de
    // l'analyzer — possédés par EditorLanguageBridge.
    final EditorLanguageBridge languageBridge = new EditorLanguageBridge(this);

    // ── Listeners ──────────────────────────────────────────────────
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public OnSelectionChangedListener selectionListener;
    // Listeners de sélection additionnels (BreadcrumbBar, etc.) qui ne
    // remplacent pas le primaire. setOnSelectionChangedListener règle le
    // primaire ; addOnSelectionChangedListener ajoute un secondaire.
    final java.util.List<OnSelectionChangedListener> extraSelectionListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int line, int col, boolean isCursor);
    }

    /**
     * R9 (plan 09, E9) — personnalisation de l'{@link android.view.inputmethod.EditorInfo}
     * par l'application hôte : appelé à CHAQUE construction de connexion,
     * APRÈS les valeurs par défaut de la bibliothèque (inputType verrou code,
     * imeOptions, sélection initiale, capitales réelles). L'hôte a le
     * dernier mot — il peut par exemple ajouter
     * {@link android.view.inputmethod.EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING},
     * une action d'entrée ({@code imeActionLabel/actionId}) ou un
     * {@code inputType} dédié sans toucher à la bibliothèque.
     */
    public interface OnEditorInfoListener {
        /** Reçoit l'{@code EditorInfo} à muter en place (dernier mot). */
        void onBuildEditorInfo(android.view.inputmethod.EditorInfo info);
    }

    /**
     * R9 (plan 09, E10) — commande privée d'un clavier : certaines IME
     * propriétaires étendent le protocole via
     * {@code InputConnection.performPrivateCommand(action, data)}. Sans
     * ce routage, les commandes tombaient dans le vide ; l'hôte peut
     * maintenant y répondre (retourner vrai si traitée).
     */
    public interface OnImePrivateCommandListener {
        /** @return vrai si la commande est traitée. */
        boolean onImePrivateCommand(String action, android.os.Bundle data);
    }

    // ════════════════════════════════════════════════════════════════
    // Construction
    // ════════════════════════════════════════════════════════════════

    public EditorView(Context context) {
        this(context, null);
    }

    /**
     * Constructeur de layout XML — requis quand l'EditorView est déclarée
     * dans un fichier de layout XML. Le LayoutInflater d'Android l'appelle
     * avec le Context et l'AttributeSet du tag XML.
     */
    public EditorView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        this.metrics = new EditorMetrics();
        this.theme = EditorTheme.dark();
        this.gutterView = new GutterView(metrics, theme);
        // ★ B15 (P2-2) : le registre d'actions de gouttière de l'hôte —
        // posé UNE fois, la gouttière consulte la liste à chaque rendu
        // (add/remove/clear → invalidate → redraw, aucun re-pose).
        this.gutterActions = new EditorGutterActions(this);
        this.gutterView.setHostActions(this.gutterActions);
        // Passe la densité d'écran pour que le point de diagnostic soit
        // dimensionné en dp.
        this.gutterView.setDensity(getResources().getDisplayMetrics().density);
        // Branche la conscience des plis de la gouttière sur le
        // isLineFolded() de la session pour que les numéros de ligne
        // restent alignés quand des plis se replient.
        this.gutterView.setHiddenLineChecker(line -> {
            EditorSession s = session;
            // Recherche dans l'index de plis en O(log plis) au lieu du
            // parcours de session en O(plis) (la gouttière vérifie chaque
            // ligne visible à chaque dessin).
            return s != null && isLineFoldedCached(line);
        });
        // ★ B11+B20 : la gouttière s'aligne sur la géométrie de la vue
        // (docLineToY / docLineForScreenY — conscients du wrap ET des
        // plis) et démarre son itération à la première ligne visible.
        this.gutterView.setHostGeometry(new GutterView.HostGeometry() {
            @Override
            public float docLineToY(int docLine) {
                return EditorView.this.docLineToY(docLine);
            }
            @Override
            public int docLineForScreenY(float screenY) {
                return EditorView.this.docLineForScreenY(screenY);
            }
        });
        this.session = new EditorSession();
        this.session.setImeListener(imeBridge.listener);
        this.session.setOnLinesShiftedListener(cacheShiftListener);
        // ★ B11 : fast-scroller (drag de gouttière/bande) + bande
        // d'erreurs du bord droit — instanciés AVANT le renderer, qui
        // les dessine à la fin du pipeline.
        this.fastScroller = new EditorFastScroller(this);
        this.errorStripe = new EditorErrorStripe(this);
        // ★ B13 (P1-2) : contrôleur du drag-déposer de bloc — armé par
        // la pill (✥ Move), possédé par la vue (rendu + hôte), piloté
        // par EditorInputHandler.
        this.blockDragCtl = new EditorBlockDragController(this);
        // B7 : contrôleur des fondus d'apparition/disparition des popups
        // Canvas — créé AVANT le renderer, qui y enregistre ses peintres à
        // sa construction.
        this.popupFade = new jo.codeeditor.view.popup.EditorPopupFade(this);
        // Instancie le renderer qui possède toutes les méthodes draw*.
        this.renderer = new EditorRenderer(this);
        this.inputHandler = new EditorInputHandler(this);
        this.popupManager = new EditorPopupManager(this);
        this.scrollManager = new EditorScrollManager(this);
        this.keyHandler = new EditorKeyHandler(this);

        // Focalisabilité par défaut pour que le framework route les
        // touches ici.
        setFocusable(true);
        setFocusableInTouchMode(true);

        // Survol souris → quick doc (desktop / ChromeOS / DeX) : dwell de
        // 500 ms conscient du slop, possédé par EditorHoverQuickDoc.
        hoverQuickDoc.attach();

        // Ré-applique les métriques avec la densité correcte.
        metrics.setTextSize(spToPx(BASE_TEXT_SIZE_SP));
    }

    /** Branche la session et enregistre le pont IME. */
    public void setSession(EditorSession session) {
        // Changement de fichier/onglet : la loupe et le drag de poignée en
        // cours n'ont plus de sens — les faire retomber pour ne pas
        // laisser une bulle figée sur le nouveau document (M3 : la bulle
        // native est un fenêtrage système, refermée explicitement).
        dismissMagnifier();
        handleDragMode = 0;
        // ★ B13 (P1-2) : un drag de bloc armé sur l'ANCIEN document
        // n'a plus de sens — le mode retombe (document intact).
        blockDragCtl.cancel();
        // Détache de la session précédente.
        if (this.session != null) {
            this.session.setImeListener(null);
            this.session.setOnLinesShiftedListener(null);
        }
        // ★ B21e : setSession(null) détache proprement la session sans
        // NPE (les chemins de dessin/tactiles vérifient déjà session ==
        // null) ; une nouvelle session se rattache ensuite normalement.
        this.session = session;
        if (session != null) {
            session.setImeListener(imeBridge.listener);
            session.setOnLinesShiftedListener(cacheShiftListener);
        }
        renderCache.clear();
        // Réinitialise le glissement du caret pour que le premier dessin
        // dans la nouvelle session s'affiche d'un coup.
        caretAnim.reset();
        // Réinitialise le scroll pour qu'un offset périmé du document
        // précédent ne laisse pas le viewport échoué.
        scroll.reset();
        // ★ B8 (retour terrain n°5) : le modèle de wrap décrit l'ANCIEN
        // document — sans reconstruction, il survit au changement de
        // session et WrapModel.topRow() clampe toute ligne au-delà de
        // l'ANCIEN compte de lignes sur le MÊME total : toutes les lignes
        // de queue se dessinent alors au même Y (« lignes empilées » en
        // bas, d'autant plus visibles que le nouveau fichier est long).
        // Même compte de lignes ≠ sauvetage : les rangées par ligne sont
        // comptées sur les longueurs de l'ancien document. Sans session,
        // le modèle meurt avec son document (parité setWordWrap(false)).
        if (wordWrap) {
            if (session != null) rebuildWrapModel();
            else wrapModel = null;
        }
        // ★ B10 : les surlignages de recherche décrivent l'ANCIEN
        // document — leurs offsets sont périmés sur le nouveau (le
        // contrôleur de recherche recalcule et repousse les siens via
        // sa méthode refresh ; sans purge, des bandes pouvaient rester
        // dessinées aux offsets hasardeux du fichier précédent).
        find.clear();
        // ★ B11 : les marqueurs de la bande d'erreurs décrivent
        // l'ANCIEN document (le pusher les repoussera aux diagnostics
        // de la nouvelle session) et le pouce du fast-scroller n'a plus
        // de raison d'être — purge immédiate, sans fondu.
        errorStripe.setDiagnostics(new int[0]);
        fastScroller.cancel();
        // ★ B27 : la carte « goutte d'eau » pointe un œil GELÉ sur une
        // rangée de l'ANCIEN document — l'ancre ne survit pas au
        // changement d'onglet (miroir des purges loupe/drag/wrap).
        preview.onSessionChanged();
        // ★ R1 (plan 09, E1) : le miroir IME décrit l'ANCIEN document
        // (texte extrait, sélection, composition en cours) — purge de
        // l'état IME (génération, jeton d'extraction, mode ancre) et
        // restart : le clavier reconstruit son miroir sur le NOUVEAU
        // document au lieu de rejouer le périmé.
        imeBridge.purgeForSessionChange();
        invalidate();
    }

    public EditorSession getSession() { return session; }

    /**
     * Étend la sélection courante d'un cran (lot 4 #27) : curseur → mot →
     * syntagme (span syntaxique ou crochets englobants) → ligne(s).
     * Voir {@link EditorSession#expandSelection()} pour la cascade exacte.
     *
     * @return {@code true} si la sélection a changé.
     */
    public boolean expandSelection() {
        return session != null && session.expandSelection();
    }

    public void setTheme(EditorTheme theme) {
        this.theme = theme;
        // Les layouts façonnés ont les couleurs de thème cuites dans leurs
        // spans — un changement de thème invalide tous les StaticLayout
        // en cache.
        shapedCache.clear();
        // Le LineRenderCache cuit AUSSI les couleurs du thème dans ses
        // SemSpan (overlays sémantiques par ligne) : sans cette purge, les
        // lignes en cache gardaient les couleurs sémantiques de l'ancien
        // thème jusqu'à leur prochaine édition.
        renderCache.clear();
        gutterView.setTheme(theme);
        invalidate();
    }

    // ── Scheme de couleurs modifiable (lot 4 #25) ────────────────

    /** Scheme actif (peut être null = thème brut), et son mode. */
    private jo.codeeditor.theme.EditorColorScheme colorScheme;
    private boolean schemeDarkMode = true;
    /** Thème de référence sur lequel le scheme est résolu. */
    private EditorTheme schemeBaseTheme;

    /**
     * Pose le thème de référence du scheme. C'est CE thème que le scheme
     * « décore » : les clés sans opinion suivent ses valeurs (et suivent
     * le parent quand elles héritaient déjà visuellement de lui).
     * Réapplique immédiatement le scheme actif.
     */
    public void setSchemeBaseTheme(EditorTheme base) {
        this.schemeBaseTheme = base;
        applyColorScheme();
    }

    /**
     * Active un scheme de couleurs modifiable (null = revenir au thème
     * brut). La résolution en cascade produit un thème effectif appliqué
     * via {@link #setTheme(EditorTheme)} — purge de caches incluse.
     */
    public void setColorScheme(@Nullable jo.codeeditor.theme.EditorColorScheme scheme) {
        this.colorScheme = scheme;
        applyColorScheme();
    }

    /** Scheme actif, ou null si aucun. */
    @Nullable
    public jo.codeeditor.theme.EditorColorScheme getColorScheme() {
        return colorScheme;
    }

    /** Mode du scheme (true = sombre). Recalcule le thème effectif. */
    public void setSchemeDarkMode(boolean dark) {
        this.schemeDarkMode = dark;
        applyColorScheme();
    }

    public boolean isSchemeDarkMode() {
        return schemeDarkMode;
    }

    /** Recalcule le thème effectif depuis le scheme actif (si présent). */
    private void applyColorScheme() {
        if (colorScheme == null) {
            if (schemeBaseTheme != null) setTheme(schemeBaseTheme);
            return;
        }
        EditorTheme base = schemeBaseTheme != null ? schemeBaseTheme : theme;
        setTheme(colorScheme.resolve(base, schemeDarkMode));
    }

    /**
     * Définit le {@link Language} qui fournit toute l'intelligence de
     * langage (complétion, hover, aide de signature, diagnostics, etc.).
     *
     * <p>Ponte de TOUS les providers du SPI vers les chemins de rendu UI.
     * Quand un {@link Language} est défini, l'éditeur :
     * <ul>
     *   <li>Crée des wrappers adaptateurs qui délèguent chaque résolveur
     *       ({@code completionProvider}, {@code signatureHelpResolver},
     *       {@code quickDocResolver}, {@code codeActionsResolver},
     *       {@code symbolResolver}) vers la méthode
     *       {@code language.getXxxProvider()} correspondante</li>
     *   <li>Réinitialise l'analyzer avec le texte du document courant</li>
     *   <li>Invalide la vue pour déclencher un redraw</li>
     * </ul>
     *
     * <p>Si un provider retourne {@code null} (non supporté par ce
     * langage), le résolveur correspondant est mis à {@code null} aussi —
     * la fonctionnalité est simplement désactivée.
     *
     * @param language le langage, ou {@code null} pour détacher
     */
    public void setLanguage(Language language) {
        languageBridge.setLanguage(language);
    }

    /**
     * Hook public pour les producteurs de diagnostics externes (ex. le
     * client LSP dans :cel-lsp appelant {@code session.setDiagnostics(...)}
     * directement) pour notifier l'EditorView que les diagnostics ont
     * changé. L'EditorView reconstruit alors le tableau de sévérité par
     * ligne et le pousse vers le GutterView pour que les points de
     * diagnostic rouge/jaune restent synchronisés.
     *
     * <p><b>Sûreté de threads :</b> peut être appelé depuis n'importe quel
     * thread (typiquement le thread lecteur JSON-RPC du LSP). Si l'appel
     * n'est pas sur le thread UI, le travail réel (poussée vers la
     * gouttière + {@link #invalidate()}, dans
     * {@link EditorDiagnosticsPusher}) est posté vers le
     * {@link android.os.Handler} de la vue. Cela évite les courses où la
     * liste de diagnostics est mutée en plein rendu.</p>
     *
     * <p>Les appelants externes (LspEditor) devraient l'appeler après
     * {@code session.setDiagnostics(...)}.
     */
    public void notifyDiagnosticsChanged() {
        diagnosticsPusher.notifyChanged();
    }

    /** Hôte écoutant les publications de diagnostics LSP. */
    public interface OnDiagnosticsPublishedListener {
        void onDiagnosticsPublished(List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> diagnostics);
    }

    public void setOnDiagnosticsPublishedListener(OnDiagnosticsPublishedListener listener) {
        diagnosticsPusher.setListener(listener);
    }

    /**
     * Retourne le {@link Language} courant, ou {@code null} si aucun n'est
     * défini.
     */
    public Language getLanguage() {
        return language;
    }

    /**
     * Retourne le {@link PopupCoordinator} qui gère le z-order et la
     * fermeture de tous les popups de l'éditeur.
     */
    public PopupCoordinator getPopupCoordinator() {
        return popupCoordinator;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener l) {
        this.selectionListener = l;
    }

    /** R9 (plan 09, E9) : règle l'écouteur de personnalisation de l'{@code EditorInfo}. */
    public void setOnEditorInfoListener(OnEditorInfoListener l) {
        imeBridge.editorInfoListener = l;
    }

    /** R9 (plan 09, E10) : règle l'écouteur des commandes privées de l'IME. */
    public void setOnImePrivateCommandListener(OnImePrivateCommandListener l) {
        imeBridge.imePrivateCommandListener = l;
    }

    /**
     * Listener de position de scroll vertical.
     *
     * <p>Notifié après chaque mutation du {@code vOffset} : drag doigt,
     * fling, scroll programmatique ({@code scrollToLine/Offset/By}) et
     * frames d'inertie de {@code computeScroll()}.</p>
     *
     * <p>Usage principal : le sticky-bottom des consoles log embarquées
     * (ConsoleLogView côté app CodeIDE) — le consommateur compare
     * {@code vOffset} à {@code maxVOffset} pour détecter « collé en bas ».</p>
     */
    public interface OnScrollPositionListener {
        void onScrollPosition(float vOffset, float maxVOffset);
    }

    private OnScrollPositionListener scrollPositionListener;

    public void setOnScrollPositionListener(OnScrollPositionListener l) {
        this.scrollPositionListener = l;
    }

    /** Notifie le listener (package-private — appelé depuis les managers). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void notifyScrollPositionChanged() {
        OnScrollPositionListener l = scrollPositionListener;
        if (l != null) {
            float max = maxV();
            l.onScrollPosition(scroll.vOffset, max);
        }
        // Plan 11 : hint de viewport au régime progressif — la passe
        // d'arrière-plan priorise le franchissement de la plage visible
        // et notifie la montée en couche (intégré → grammaires) pour un
        // rafraîchissement immédiat. Sans effet sur les petits
        // documents (régime eager).
        float lineHeight = metrics.getLineHeight();
        if (lineHeight > 0 && session != null
                && session.getDocument().lineCount() > 0) {
            int first = Math.max(0,
                    (int) Math.floor(scroll.vOffset / lineHeight) - 1);
            int last = Math.min(session.getDocument().lineCount() - 1,
                    (int) Math.ceil((scroll.vOffset + getHeight()) / lineHeight) + 1);
            session.hintViewport(first, last);
        }
        // Préfetch du viewport au repos — ré-armé à chaque scroll pour que
        // la tâche ne se déclenche qu'après 150 ms de scroll CALME (jamais
        // pendant un fling — préfetcher en plein fling ne fait que le
        // travail de la frame suivante une frame trop tôt, pour rien).
        viewportPrefetcher.schedule();
        // I5 (lot 2, J6) : re-requête des inlay hints au repos du scroll
        // (provider à plage viewport uniquement — no-op sinon).
        languageBridge.scheduleInlayViewportRefresh();
    }

    // ── Préfetch du viewport au repos ─────────────────────────────
    // Délégué à EditorViewportPrefetcher (réchauffe le LineRenderCache
    // autour du viewport après 150 ms de scroll calme).

    /** Ajoute un listener de sélection SANS remplacer le primaire. */
    public void addOnSelectionChangedListener(OnSelectionChangedListener l) {
        if (l != null) extraSelectionListeners.add(l);
    }

    /**
     * Retire un listener de sélection secondaire précédemment ajouté.
     * Utile pour BreadcrumbBar / les hôtes d'overlay qui doivent nettoyer
     * au détachement pour éviter de fuiter le listener (et la vue hôte
     * qu'il capture).
     *
     * @param l le listener à retirer ; null est un no-op
     */
    public void removeOnSelectionChangedListener(OnSelectionChangedListener l) {
        if (l != null) extraSelectionListeners.remove(l);
    }

    public EditorMetrics getMetrics() { return metrics; }
    public EditorTheme getTheme() { return theme; }
    public float getFontScale() { return zoom.fontScale; }
    public float getVOffset() { return scroll.vOffset; }
    public float getHOffset() { return scroll.hOffset; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Le modèle de wrap dépend de la largeur du viewport — reconstruit
        // au changement de taille.
        if (wordWrap) rebuildWrapModel();
        // Re-borne le scroll au nouveau max.
        scroll.clampTo(maxV(), maxH());
        // Redimensionne la feuille d'aperçu pour correspondre aux nouvelles
        // bornes de l'éditeur. La feuille est ancrée à notre position de
        // fenêtre ; on lui demande de ré-évaluer sa taille en ré-appliquant
        // le mode courant.
        preview.handleSizeChanged();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // ★ B21g : rejoue un schedule de diagnostics différé faute de
        // Handler — l'attach initial (setLanguage avant addView) ne doit
        // plus exécuter la tâche en synchrone.
        diagnosticsPusher.onAttachedToWindow();
        // ★ B21h : repose les listeners session→vue — l'attach peut venir
        // APRÈS setSession (session posée avant addView, recyclage de
        // ViewHolder) : sans repos, une vue ré-attachée ne recevrait plus
        // ni IME ni décalages de cache de sa session.
        if (session != null) {
            session.setImeListener(imeBridge.listener);
            session.setOnLinesShiftedListener(cacheShiftListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // La loupe ne doit pas survivre au détachement de la vue (M3 :
        // la bulle native aussi — fenêtrage système).
        dismissMagnifier();
        // Ferme toute feuille d'aperçu ouverte pour ne pas fuiter un popup
        // pointant vers un éditeur détaché (ce qui planterait au toucher).
        preview.onDetachedFromWindow();
        // ★ B15 : ferme aussi les PopupWindow go-to-line / rename /
        // références — un PopupWindow vivant après le détachement de sa
        // vue hôte lève WindowLeaked et fuit l'Activity à la rotation.
        popupManager.dismissGoToLine();
        popupManager.dismissRename();
        referencesController.dismiss();
        // Nettoyage complet des callbacks en attente (préfetch au repos,
        // hover tap-hold, fermeture multi-tap) : une vue détachée avec des
        // runnables encore postés resterait accessible depuis son Handler
        // et chaque callback pourrait toucher un état déjà démonté.
        if (getHandler() != null) {
            viewportPrefetcher.cancel();
            languageBridge.cancelPending();
        }
        if (inputHandler != null) {
            inputHandler.cancelPendingCallbacks();
        }
        // Annule tout ValueAnimator de glissement du caret en vol — un
        // animator actif sur une vue détachée garde une boucle
        // postInvalidateOnAnimation vivante (et la vue accessible) jusqu'à
        // sa fin. Le clignotement lui-même est basé sur le temps et piloté
        // par le chemin de dessin, donc il meurt avec la vue.
        caretAnim.cancelGlide();
        // ★ B21h : purge les listeners session→vue — une session qui
        // survit au détachement (hôte recyclant les sessions) ne doit
        // plus retenir la vue (sinon tout son graphe fuie jusqu'à la
        // mort de la session). onAttachedToWindow repose.
        if (session != null) {
            session.setImeListener(null);
            session.setOnLinesShiftedListener(null);
        }
    }

    /**
     * Retourne la coordonnée Y (espace contenu, avant vOffset) du HAUT de
     * la ligne doc, consciente du wrap et des plis repliés (délégué à
     * {@link EditorWrapGeometry}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float docLineToY(int docLine) {
        return wrapGeometry.docLineToY(docLine);
    }

    /**
     * Retourne le nombre de lignes de document AU-DESSUS de {@code docLine}
     * actuellement cachées par un pli replié. Utilisé par
     * {@link #docLineToY(int)} et {@link #docLineForScreenY(float)} pour
     * faire la correspondance entre l'espace ligne-doc et l'espace
     * rangée-visuelle quand des plis sont repliés.
     * <p>Répond en O(log plis) via l'index de plis mémoïsé.
     */
    int countHiddenLinesAbove(int docLine) {
        // O(log plis) via les sommes préfixes du FoldIndex mémoïsé — au
        // lieu de O(plis × log lignes) par appel, payé une fois par ligne
        // VISIBLE dans le chemin de dessin (docLineToY).
        return foldIndex.get().hiddenAbove(docLine);
    }

    /** Nombre TOTAL de lignes masquées par les plis repliés — pour les
     *  calculs de hauteur de contenu (scroll) sensibles aux plis. */
    /**
     * Nombre TOTAL de rangées wrap cachées par les plis repliés (mode
     * word-wrap) — utilisé par le gestionnaire de scroll pour borner
     * maxV() : les lignes cachées n'occupent aucune rangée visuelle.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public long totalHiddenWrapRows() {
        if (session == null) return 0;
        return wrapGeometry.hiddenWrapRowsAbove(session.getDocument().lineCount());
    }

    public int totalHiddenLines() {
        if (session == null) return 0;
        return countHiddenLinesAbove(session.getDocument().lineCount());
    }

    /**
     * Retourne le nombre de rangées visuelles occupées par la ligne doc
     * donnée (≥ 1 ; délégué à {@link EditorWrapGeometry}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int rowsForDocLine(int docLine) {
        return wrapGeometry.rowsForDocLine(docLine);
    }

    /**
     * Fait correspondre une coordonnée Y écran vers une ligne de document,
     * en tenant compte des rangées repliées ET des plis repliés
     * (délégué à {@link EditorWrapGeometry}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int docLineForScreenY(float screenY) {
        return wrapGeometry.docLineForScreenY(screenY);
    }

    /**
     * Colonne dans la ligne doc pour un offset de rangée repliée,
     * consciente des rangées de continuation (délégué à
     * {@link EditorWrapGeometry}).
     */
    int wrappedColFor(int docLine, int rowInLine, int colInRow) {
        return wrapGeometry.wrappedColFor(docLine, rowInLine, colInRow);
    }

    /**
     * Définit les surlignages d'occurrences de recherche dessinés dans le
     * viewport. Passer une liste vide pour effacer. {@code currentIndex}
     * est l'index (dans la liste) de l'occurrence « courante » — dessinée
     * avec {@code theme.findCurrent} au lieu de {@code theme.findMatch}.
     */
    public void setFindHighlights(List<Match> matches, int currentIndex) {
        find.set(matches, currentIndex);
        invalidate();
    }

    /**
     * Scrolle l'éditeur pour que l'offset de document donné soit visible.
     * Utilisé par la navigation Rechercher/Remplacer, go-to-def, etc.
     */
    public void scrollToOffset(int offset) {
        scrollManager.scrollToOffset(offset);
    }

    /**
     * Bascule le retour à la ligne. Quand activé, les lignes longues se
     * replient sur plusieurs rangées visuelles dans la zone de texte ; le
     * scroll horizontal est désactivé.
     */
    public void setWordWrap(boolean enabled) {
        if (this.wordWrap == enabled) return;
        this.wordWrap = enabled;
        if (enabled) {
            scroll.hOffset = 0;
            rebuildWrapModel();
        } else {
            this.wrapModel = null;
        }
        invalidate();
    }

    public boolean isWordWrap() { return wordWrap; }

    // ════════════════════════════════════════════════════════════════
    // Minimap
    // ════════════════════════════════════════════════════════════════

    /**
     * Active ou désactive la minimap — un petit aperçu du fichier entier
     * rendu dans une bande sur le bord droit. La minimap réutilise le
     * {@link jo.codeeditor.cache.LineRenderCache} existant pour dessiner la
     * structure colorée par token de chaque ligne à une échelle minuscule
     * (2.5 px par ligne doc, 60 dp de large). Un rectangle façon scrollbar
     * indique le viewport courant.
     *
     * @param enabled {@code true} pour montrer la minimap, {@code false} pour la cacher
     */
    public void setMinimapEnabled(boolean enabled) {
        if (this.minimapEnabled == enabled) return;
        this.minimapEnabled = enabled;
        invalidate();
    }

    /** @return {@code true} si la minimap est actuellement visible. */
    public boolean isMinimapEnabled() { return minimapEnabled; }

    // ════════════════════════════════════════════════════════════════
    // Lot B11 (P1-1) — bande d'erreurs du bord droit
    // ════════════════════════════════════════════════════════════════

    /**
     * B11 (P1-1) — active/désactive la bande d'erreurs du bord droit :
     * marqueurs de diagnostics (erreur/avertissement/info, tout le
     * fichier même hors écran) + rectangle du viewport façon scrollbar.
     * Tap sur un marqueur = centrer la ligne + ouvrir la fiche ; drag
     * sur la bande = défilement absolu (pouce + bulle du fast-scroller).
     * Activée par défaut (parité Android Studio).
     *
     * @param enabled {@code true} pour montrer la bande, {@code false} pour la cacher
     * @since 3.65.0
     */
    public void setErrorStripeEnabled(boolean enabled) {
        if (errorStripe.isEnabled() == enabled) return;
        errorStripe.setEnabled(enabled);
        invalidate();
    }

    /** @return {@code true} si la bande d'erreurs est visible.
     * @since 3.65.0 */
    public boolean isErrorStripeEnabled() { return errorStripe.isEnabled(); }

    // ════════════════════════════════════════════════════════════════
    // Lot B.11 (audit v3.41.0) — Style des guides d'indentation
    // ════════════════════════════════════════════════════════════════

    /**
     * Définit le style d'affichage des guides d'indentation.
     *
     * <p>Voir {@link IndentGuideStyle} pour les options. Le style
     * par défaut est {@link IndentGuideStyle#INDENT_WITH_SCOPE}
     * (guides d'indentation + portée active mise en évidence,
     * cohérent avec les éditeurs de code de bureau).</p>
     *
     * @param style le style à appliquer ; {@code null} est
     *              silencieusement traité comme
     *              {@link IndentGuideStyle#OFF}
     */
    public void setIndentGuideStyle(IndentGuideStyle style) {
        IndentGuideStyle s = (style != null) ? style : IndentGuideStyle.OFF;
        if (this.indentGuideStyle == s) return;
        this.indentGuideStyle = s;
        invalidate();
    }

    /** @return le style courant des guides d'indentation. */
    public IndentGuideStyle getIndentGuideStyle() { return indentGuideStyle; }

    // ════════════════════════════════════════════════════════════
    // Loupe (M2/M3, lot 2 J5)
    // ════════════════════════════════════════════════════════════

    /**
     * M2 (lot 2, J5) — facteur de zoom de la loupe de sélection
     * (la bulle affichée pendant le drag d'une poignée ou le
     * drag-select au doigt).
     *
     * <p>Plage ×1.5 – ×3 : en dessous de ×1.5 la bulle n'apporte plus
     * assez de grossissement pour régler la borne au caractère près ;
     * au-dessus de ×3, la fenêtre de ±3 lignes ne montre plus assez de
     * contexte. Les valeurs hors plage sont ramenées à la borne la
     * plus proche (clamp silencieux documenté). Le défaut reste ×2
     * (inchangé depuis l'origine) ; utile pour la lecture des grands
     * caractères.</p>
     *
     * @param zoom le facteur voulu (ramené dans [1.5, 3])
     * @since 3.56.0
     */
    public void setMagnifierZoom(float zoom) {
        float z = Math.max(1.5f, Math.min(3f, zoom));
        if (this.magnifierZoom == z) return;
        this.magnifierZoom = z;
        invalidate();
    }

    /**
     * M2 — @return le facteur de zoom courant de la loupe (dans
     * [1.5, 3], défaut 2).
     * @since 3.56.0
     */
    public float getMagnifierZoom() { return magnifierZoom; }

    /**
     * M3 (lot 2, J5) — active la <b>loupe native</b> de la plateforme
     * (android.widget.Magnifier, API 28+) en remplacement de la bulle
     * custom dessinée sur le canvas.
     *
     * <p>Compromis documenté : le widget plateforme rend la VRAIE frame
     * de la vue (overlays système compris, fidélité maximale) mais son
     * style n'est pas maîtrisé — la bulle suit les règles système
     * (taille, forme, position du fermoir). La bulle custom (défaut,
     * {@code false}) offre l'inverse : rendu reconstruit (±3 lignes,
     * bande de sélection + squiggle + caret — M1) et style maîtrisé.
     * Sous API 28, le drapeau est sans effet (repli custom).
     * {@link #setMagnifierZoom(float)} ne s'applique qu'à la bulle
     * custom.</p>
     *
     * @param enabled {@code true} pour la loupe native (défaut :
     *                {@code false} = custom)
     * @since 3.56.0
     */
    public void setNativeMagnifierEnabled(boolean enabled) {
        if (this.nativeMagnifierEnabled == enabled) return;
        this.nativeMagnifierEnabled = enabled;
        invalidate();
    }

    /**
     * M3 — @return vrai si la loupe native est activée (opt-in API
     * 28+ ; sous API 28, la bulle custom reste utilisée).
     * @since 3.56.0
     */
    public boolean isNativeMagnifierEnabled() { return nativeMagnifierEnabled; }

    /**
     * M3 — éteint la loupe (custom et native) : relâchement du geste,
     * changement de session, détachement de la vue, perte de focus.
     * La bulle custom cesse d'être dessinée (flag) ; la bulle native
     * est refermée explicitement (fenêtrage système qui survit au
     * canvas).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void dismissMagnifier() {
        magnifierActive = false;
        renderer.dismissNativeMagnifier();
    }

    // ════════════════════════════════════════════════════════════════
    // Sticky lines (en-têtes collés en haut pendant le scroll)
    // ════════════════════════════════════════════════════════════════

    /**
     * Active ou désactive les sticky lines (en-têtes de bloc de
     * code qui restent visibles en haut pendant le scroll).
     *
     * <p>Sur mobile, garde au plus
     * {@link #setStickyMaxLines(int) stickyMaxLines} lignes sticky
     * (défaut : 2 — classe + méthode) pour ne pas consommer
     * trop d'espace.</p>
     *
     * @param enabled {@code true} pour activer (défaut), {@code false}
     *                pour désactiver
     */
    public void setStickyLinesEnabled(boolean enabled) {
        if (this.stickyLinesEnabled == enabled) return;
        this.stickyLinesEnabled = enabled;
        invalidate();
    }

    /** @return {@code true} si les sticky lines sont activées. */
    public boolean isStickyLinesEnabled() { return stickyLinesEnabled; }

    /**
     * S4 (lot 2, J4) — nombre MAXIMAL de lignes sticky empilées en
     * haut pendant le scroll (1 à 5, défaut mobile : 2 — classe +
     * méthode ; 3 à 5 est tenable sur tablette où l'espace vertical
     * est moins rare).
     *
     * <p>Le plafond s'applique au rendu ET au hit-test de la bande
     * ({@link #activeStickyLines()}) — une seule source de vérité.
     * Les valeurs hors plage [1, 5] sont proprement refusées : la
     * valeur courante est conservée (pas de crash, pas de clamp
     * silencieux).</p>
     *
     * @param max le plafond voulu (1 à 5)
     * @since 3.55.0
     */
    public void setStickyMaxLines(int max) {
        if (max < 1 || max > 5) return;
        if (this.stickyMaxLines == max) return;
        this.stickyMaxLines = max;
        invalidate();
    }

    /**
     * S4 — @return le nombre maximal de lignes sticky (1 à 5,
     * défaut 2).
     * @since 3.55.0
     */
    public int getStickyMaxLines() { return stickyMaxLines; }

    /** Lot B : détecte les lignes sticky du document (package render). */
    public java.util.List<int[]> detectStickyLines(EditorDocument doc) {
        return stickyLineDetector.detect(doc);
    }

    /**
     * S3 (lot 2, J4) — les lignes sticky <b>actives</b> (effectivement
     * empilées en haut) : celles scrollées AU-DESSUS du viewport et
     * non repliées, en ordre d'empilement (extérieure → intérieure),
     * plafonnées à {@link #setStickyMaxLines(int) stickyMaxLines}.
     *
     * <p>Source unique partagée par le rendu
     * ({@code EditorRenderer.drawStickyLines}) et le hit-test de la
     * bande ({@code EditorTapResolver}) — le tap sur la bande navigue
     * vers la ligne d'origine (S3). Chaque élément est la paire
     * {@code [docLine, yContenu]} du détecteur.</p>
     *
     * @return la liste (vide si rien n'est collé) ; jamais null
     * @since 3.55.0
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public java.util.List<int[]> activeStickyLines() {
        if (!stickyLinesEnabled || session == null) {
            return java.util.Collections.emptyList();
        }
        EditorDocument doc = session.getDocument();
        if (doc == null) return java.util.Collections.emptyList();
        float paddingTop = metrics.getPadTop();
        java.util.List<int[]> sticky = stickyLineDetector.detect(doc);
        java.util.List<int[]> active = new java.util.ArrayList<>();
        for (int i = sticky.size() - 1; i >= 0; i--) {
            int[] pair = sticky.get(i);
            float lineY = docLineToY(pair[0]) - scroll.vOffset;
            // Active = scrollée au-dessus de la zone de texte, non
            // repliée (même règle que le rendu, avant factorisation).
            if (lineY < paddingTop - 1f && !isLineFoldedCached(pair[0])) {
                active.add(0, pair);
                if (active.size() >= stickyMaxLines) break;
            }
        }
        return active;
    }

    /**
     * D3 (lot 2, J4) — copie un texte arbitraire au presse-papiers
     * (message de diagnostic à l'appui long sur un squiggle) avec le
     * même plafond Binder que copy() (200 000 caractères, la fin
     * conservée). Relai vers {@code EditorClipboard}.
     *
     * @since 3.55.0
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void copyToClipboard(String text) {
        clipboard.copyText(text);
    }

    /**
     * B26 — active/désactive le sélecteur de couleur (au tap d'un code
     * couleur hex, cf. les carrés colorés inline). Désactivé, un tap
     * sur un code couleur hex place le caret (comportement historique).
     *
     * @param enabled {@code true} = le tap ouvre le sélecteur
     */
    public void setColorPickerEnabled(boolean enabled) {
        this.colorPickerEnabled = enabled;
        if (!enabled && colorPickerVisible) dismissColorPicker();
    }

    /** B26 — le sélecteur de couleur est-il actif au tap ? */
    public boolean isColorPickerEnabled() {
        return colorPickerEnabled;
    }

    /** B26 — referme le sélecteur de couleur (sans rien appliquer). */
    public void dismissColorPicker() {
        popupManager.dismissColorPicker();
    }

    /**
     * Active ou désactive les carrés colorés inline pour les
     * couleurs hexadécimales (#RGB, #RRGGBB, #AARRGGBB).
     *
     * @param enabled {@code true} pour activer (défaut), {@code false}
     *                pour désactiver
     */
    public void setColorSwatchesEnabled(boolean enabled) {
        if (this.colorSwatchesEnabled == enabled) return;
        this.colorSwatchesEnabled = enabled;
        invalidate();
    }

    /** @return {@code true} si les carrés colorés sont activés. */
    public boolean isColorSwatchesEnabled() { return colorSwatchesEnabled; }

    /**
     * Active ou désactive les couleurs par variable (variable-specific
     * colors). Chaque variable reçoit une teinte unique basée sur son
     * nom.
     *
     * <p>Nécessite des semantic tokens (LSP) pour fonctionner — sans
     * LSP, les variables ne sont pas identifiées et la fonction n'a
     * aucun effet.</p>
     *
     * @param enabled {@code true} pour activer, {@code false} (défaut)
     *                pour désactiver
     */
    public void setVariableColorsEnabled(boolean enabled) {
        if (this.variableColorsEnabled == enabled) return;
        this.variableColorsEnabled = enabled;
        // Invalider le cache de rendu pour recalculer les couleurs
        // des spans sémantiques.
        renderCache.clear();
        invalidate();
    }

    /** @return {@code true} si les couleurs par variable sont activées. */
    public boolean isVariableColorsEnabled() { return variableColorsEnabled; }

    /** @return {@code true} si le thème courant est sombre. */
    public boolean isDarkTheme() { return schemeDarkMode; }

    // ════════════════════════════════════════════════════════════════
    // Mode aperçu
    // ════════════════════════════════════════════════════════════════

    /**
     * Mode aperçu pour les fichiers Markdown/HTML. Quand activé, l'éditeur
     * réserve une portion de sa largeur pour un panneau d'aperçu (rendu par
     * l'hôte via un overlay WebView). L'éditeur dessine une ligne de
     * séparation et rogne son texte à la largeur restante.
     *
     * <p>L'hôte crée un WebView comme vue sœur (dans un FrameLayout), le
     * positionne à {@link #getPreviewLeft()} et appelle
     * {@link #getPreviewWidth()} pour la largeur. L'hôte écoute aussi
     * {@link OnPreviewModeChangedListener} pour savoir quand montrer/
     * cacher/redimensionner le WebView.
     *
     * <p>{@link #SHEET_SPLIT} et {@link #SHEET_FULL} : au lieu de réserver
     * de l'espace inline, l'éditeur ouvre une feuille popup en overlay
     * ({@link EditorPreviewSheet}) ancrée à la vue éditeur. La zone de
     * texte de l'éditeur reste pleine largeur — la feuille flotte au-
     * dessus. Ce mode est utilisé pour les fichiers {@code .md}/
     * {@code .html} où l'hôte fournit un corps WebView via
     * {@link EditorPreviewHost#onCreatePreviewView}.
     */
    public enum PreviewMode {
        /** Pas d'aperçu — l'éditeur prend toute la largeur. */
        NONE,
        /** Split — éditeur à gauche, aperçu à droite (~50 % de largeur chacun). */
        SPLIT,
        /** Full — l'aperçu prend toute la largeur, éditeur caché. */
        FULL,
        /**
         * Feuille popup arrimée à la moitié droite — overlay d'aperçu,
         * la zone de texte de l'éditeur reste pleine largeur en dessous.
         * Utilisé pour les fichiers {@code .md}/{@code .html} où l'hôte
         * fournit une View (typiquement un WebView) pour le corps de la
         * feuille.
         */
        SHEET_SPLIT,
        /**
         * Feuille popup couvrant toute la zone éditeur — overlay d'aperçu,
         * la zone de texte de l'éditeur reste pleine largeur en dessous.
         * Utilisé pour les fichiers {@code .md}/{@code .html}.
         */
        SHEET_FULL,
        /**
         * B27 — carte d'aperçu « goutte d'eau » : popup ancré à l'ŒIL de
         * gouttière, non modal, dessiné sur le canvas de l'éditeur. La
         * zone de texte reste pleine largeur en dessous ; la carte
         * esquive le bloc de frappe (caret ± 1 ligne) et se compacte
         * (échelle bornée) quand l'éditeur est trop petit. Utilisé pour
         * les aperçus <em>canvas</em> (layout XML gonflé, arbre simulé)
         * des hôtes qui répondent vrai à
         * {@link EditorPreviewHost#prefersDropPreview()}.
         */
        DROP;

        /** Vrai pour les modes feuille-overlay (popup) vs modes inline. */
        public boolean isSheet() {
            return this == SHEET_SPLIT || this == SHEET_FULL;
        }
    }

    /**
     * Politique de cohabitation de la carte d'aperçu « goutte d'eau »
     * (B27) avec les popups de frappe (complétion, doc rapide) — trois
     * réglages au choix du propriétaire, l'ORIGINALE est le défaut.
     *
     * <ul>
     *   <li>{@link #ORIGINAL} — la maquette validée d'origine : la
     *       carte vit au premier plan (dessinée après les popups, têtes
     *       de cascade tactile) et ne connaît que la bande de frappe
     *       (caret ± 1 ligne) — aveugle aux popups, elle peut les
     *       recouvrir ;</li>
     *   <li>{@link #THREE_LAYERS} — le retour terrain B27 : esquive par
     *       obstacles (creux libre, latéral grand écran, compactage),
     *       voile d'eau translucide quand rien ne tient, et priorité des
     *       taps à la surface de frappe ;</li>
     *   <li>{@link #VARIANT_B} — la lisibilité brute : z-order inversé,
     *       la complétion se dessine AU-DESSUS de la carte (et gagne les
     *       taps) — la carte disparaît derrière un verre opaque, plus
     *       simple, plus lisible.</li>
     * </ul>
     */
    public enum PreviewDropPolicy {
        /** Maquette d'origine : premier plan, aveugle aux popups (DÉFAUT). */
        ORIGINAL,
        /** Retour terrain : esquive + voile d'eau + priorité des taps. */
        THREE_LAYERS,
        /** Variante B : la complétion passe au-dessus de la carte. */
        VARIANT_B;

        /** Vrai quand la complétion précède la carte (taps + z-order). */
        public boolean completionFirst() {
            return this != ORIGINAL;
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public PreviewMode previewMode = PreviewMode.NONE;

    // Icônes d'aperçu dessinées au Canvas (coin haut-droit).
    // Quand previewable = true, deux petites icônes sont dessinées :
    // - icône d'aperçu split (à gauche de la paire)
    // - icône d'aperçu full (à droite de la paire)
    // Taper une icône bascule le mode aperçu.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean previewable = false;
    // B5 (retour terrain post-J6) : les icônes d'aperçu du coin haut-
    // droit peuvent se RELOCALISER dans la barre de fil d'Ariane
    // ({@code BreadcrumbBar.addPreviewAction}) — la barre dessine l'œil
    // parmi ses actions et coupe celles du canvas (une seule entrée
    // d'interaction, au même endroit que les autres boutons).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean previewIconsVisible = true;
    // Tout le reste de l'état d'aperçu (hôte, feuille popup, carte
    // « goutte d'eau », nom de fichier, listener de mode) vit dans
    // EditorPreviewController ; la vue ne conserve que des relais
    // publics. (Public restreint : les painters du pipeline de rendu —
    // paquet render — consultent la carte et le hôte à chaque frame.)
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorPreviewController preview = new EditorPreviewController(this);

    /**
     * Enregistre le hôte d'aperçu qui fournit le rendu d'aperçu.
     *
     * <p>Sans hôte, l'éditeur ne peut prévisualiser aucun fichier. Le hôte
     * est typiquement défini une fois dans le onCreate() de l'Activity
     * hôte :
     * <pre>{@code
     * editorView.setPreviewHost(new XmlPreviewHost(this));
     * }</pre>
     *
     * @param host le hôte d'aperçu, ou null pour désactiver l'aperçu
     */
    public void setPreviewHost(EditorPreviewHost host) {
        preview.setHost(host);
    }

    public EditorPreviewHost getPreviewHost() {
        return preview.getHost();
    }

    /**
     * B5 (retour terrain post-J6) — montre ou cache les icônes d'aperçu du
     * coin haut-droit de l'éditeur. La barre de fil d'Ariane
     * ({@code BreadcrumbBar.addPreviewAction()}) les relocalise : elle
     * dessine l'icône dans sa zone d'actions et coupe celles du canvas —
     * le geste d'aperçu vit avec les autres boutons, en haut à droite.
     */
    public void setPreviewIconsVisible(boolean visible) {
        preview.setPreviewIconsVisible(visible);
    }

    /**
     * Définit le nom de fichier courant pour que l'éditeur puisse détecter
     * si l'aperçu est disponible (.md, .markdown, .html, .htm, layout .xml).
     * Quand prévisualisable, l'éditeur dessine les icônes d'aperçu dans le
     * coin haut-droit.
     */
    public void setFileName(String fileName) {
        preview.setFileName(fileName);
    }

    /**
     * Retourne le dernier nom de fichier défini via {@link #setFileName(String)},
     * ou la chaîne vide si aucun n'a été défini. Utilisé par la feuille
     * d'aperçu pour afficher le nom de fichier dans son en-tête.
     */
    public String getFileName() {
        return preview.getFileName();
    }

    /**
     * Hit-teste les icônes d'aperçu. Retourne :
     * 0 = aucun hit
     * 1 = hit icône d'aperçu split
     * 2 = hit icône d'aperçu full
     *
     * <p>Les modes SHEET_* cachent les badges (le chrome de la feuille
     * possède l'interaction de fermeture). La feuille elle-même peut
     * basculer entre SHEET_SPLIT et SHEET_FULL via son toggle d'en-tête.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int hitTestPreviewIcons(float x, float y) {
        return preview.hitTestIcons(x, y);
    }

    /**
     * B9c (retour terrain n°6) — hit-teste l'icône d'aperçu de la
     * GOUTTIÈRE (œil sur la rangée du caret, façon Android Studio).
     *
     * <p>Vrai quand le fichier courant est prévisualisable ET que le
     * point tombe dans la pill de l'icône. Le rectangle est recalculé
     * DÉTERMINISTEMENT (même formule que le dessin —
     * {@code GutterView.previewActionRect}) : aucun état mutable partagé
     * entre le rendu et le hit-test. Le tap de bascule vit dans
     * EditorTapResolver (parité avec l'œil du fil d'Ariane B6 :
     * ouvert → closePreviewSheet, fermé → openPreview).</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean hitTestGutterPreviewAction(float x, float y) {
        if (!previewable || session == null || session.getDocument() == null) {
            return false;
        }
        EditorDocument doc = session.getDocument();
        if (doc.lineCount() <= 0) return false;
        // ★ B27 : carte « goutte d'eau » OUVERTE — l'œil est GELÉ sur la
        // rangée d'ancre (il ne suit plus le caret) : le hit-test vise
        // l'œil DESSINÉ, pas la rangée du caret (sinon un tap sur l'œil
        // visible ne basculerait plus la carte dès que le caret bouge).
        int eyeLine;
        if (previewMode == PreviewMode.DROP && preview.drop().isOpen()) {
            eyeLine = preview.drop().getAnchorLine();
        } else {
            Selection sel = clampSelection(session.getSelection(), doc);
            eyeLine = clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        }
        if (eyeLine < 0 || eyeLine >= doc.lineCount()) return false;
        float rowTop = docLineToY(eyeLine) - scroll.vOffset;
        float lineHeight = metrics.getLineHeight();
        // Rangée hors écran (repliée au-dessus ou en dessous du viewport) :
        // l'icône n'est pas visible — pas de zone tactile fantôme.
        if (rowTop + lineHeight < 0 || rowTop > getHeight()) return false;
        return jo.codeeditor.view.chrome.GutterView.previewActionContains(
                metrics, getResources().getDisplayMetrics().density,
                rowTop, lineHeight, x, y);
    }

    /**
     * Listener des changements de mode d'aperçu. Le hôte l'implémente
     * pour montrer/cacher/redimensionner son overlay WebView.
     */
    public interface OnPreviewModeChangedListener {
        void onPreviewModeChanged(PreviewMode mode, int previewLeft, int previewWidth);
    }

    public void setOnPreviewModeChangedListener(OnPreviewModeChangedListener listener) {
        preview.setModeListener(listener);
    }

    /**
     * Définit le mode d'aperçu. En SPLIT ou FULL, l'éditeur réserve de
     * l'espace pour le panneau d'aperçu et notifie le listener avec les
     * bornes de l'aperçu.
     *
     * <p>Quand le mode est {@link PreviewMode#SHEET_SPLIT} ou
     * {@link PreviewMode#SHEET_FULL}, l'éditeur ouvre une feuille popup en
     * overlay (voir {@link EditorPreviewSheet}) au lieu de réserver de
     * l'espace inline. La zone de texte reste pleine largeur et la feuille
     * flotte au-dessus.
     */
    public void setPreviewMode(PreviewMode mode) {
        preview.setPreviewMode(mode);
    }

    /**
     * Point d'entrée pratique invoqué quand l'utilisateur tape un des
     * badges d'aperçu dessinés par {@link EditorRenderer#drawPreviewIcons}.
     *
     * <p>Pour les fichiers {@code .md}/{@code .html} (où le hôte fournit
     * typiquement un corps WebView via
     * {@link EditorPreviewHost#onCreatePreviewView}), ceci ouvre une
     * feuille popup en overlay ({@link PreviewMode#SHEET_SPLIT} ou
     * {@link PreviewMode#SHEET_FULL}) pour que la zone de texte de
     * l'éditeur reste pleine largeur et que la feuille flotte au-dessus.
     *
     * <p>Pour les fichiers de layout {@code .xml} (où le hôte rend via
     * {@link EditorPreviewHost#drawPreview(Canvas, float, float)} dans le
     * canvas de l'éditeur), ceci ouvre le mode inline
     * {@link PreviewMode#SPLIT} ou {@link PreviewMode#FULL}.
     *
     * @param full true pour le badge plein écran, false pour le badge split
     */
    public void openPreview(boolean full) {
        preview.openPreview(full);
    }

    /**
     * Ferme la feuille popup active (s'il y en a une) et revient à
     * {@link PreviewMode#NONE}. Appelé quand l'utilisateur tape le bouton
     * fermer de la feuille, tape hors de la feuille, ou presse Retour.
     */
    public void closePreviewSheet() {
        preview.closePreviewSheet();
    }

    /**
     * Retourne la feuille popup active, ou null quand aucun mode SHEET_*
     * n'est actif. Exposé pour que {@link EditorRenderer} et le harnais de
     * test puissent interroger l'état de la feuille sans passer par les
     * champs package-private.
     */
    public EditorPreviewSheet getPreviewSheet() {
        return preview.getPreviewSheet();
    }

    /**
     * B27 — ouvre la carte d'aperçu « goutte d'eau » ancrée à la ligne
     * donnée (l'œil de gouttière se GÈLE sur cette rangée, la carte
     * pointe vers lui, non modale, esquivant le bloc de frappe).
     *
     * <p>Ne fait rien si le fichier courant n'est pas prévisualisable ou
     * si aucun hôte n'est enregistré. Pour un hôte qui ne répond pas
     * vrai à {@link EditorPreviewHost#prefersDropPreview()}, la carte
     * reste disponible : c'est le choix du rendu, pas une exclusion.</p>
     *
     * @param line la ligne de document d'ancre (0-based, clampée)
     */
    public void openPreviewDropAt(int line) {
        preview.openPreviewDropAt(line);
    }

    /**
     * B27 — referme la carte d'aperçu « goutte d'eau » et revient à
     * {@link PreviewMode#NONE}. Appelé par le ✕ de la carte, le re-tap
     * sur l'œil, le changement de session/fichier, ou le détachement.
     */
    public void closePreviewDrop() {
        preview.closePreviewDrop();
    }

    /**
     * B27 — bascule la carte « goutte d'eau » : l'ouvre ancrée à la
     * rangée du caret (comme le ferait le tap sur l'œil), ou la referme
     * si elle est déjà ouverte.
     */
    public void togglePreviewDrop() {
        preview.togglePreviewDrop();
    }

    /**
     * Politique de cohabitation de la carte « goutte d'eau » avec les
     * popups de frappe — voir {@link PreviewDropPolicy}. Le changement
     * prend effet au frame suivant (le voile d'eau se réinitialise :
     * une carte ouverte repart opaque).
     *
     * <p>Le réglage est du choix du propriétaire : l'ORIGINALE (premier
     * plan, aveugle aux popups) est le défaut ; TROIS couches ajoute
     * l'esquive par obstacles + le voile d'eau + la priorité des taps ;
     * la variante B inverse le z-order (la complétion recouvre la
     * carte).</p>
     */
    public void setPreviewDropPolicy(PreviewDropPolicy policy) {
        preview.setDropPolicy(policy);
    }

    /** Politique courante de cohabitation goutte d'eau / popups. */
    public PreviewDropPolicy getPreviewDropPolicy() {
        return preview.dropPolicy();
    }

    /**
     * Taille de la carte « goutte d'eau » : facteur d'agrandissement
     * appliqué à toute la carte (chrome + mini-téléphone), borné dans
     * [1, 1,5] — 1 = taille de la maquette. Le placement (clamp aux
     * bords, esquive, compactage) se recalcule sur la carte agrandie.
     */
    public void setPreviewDropScale(float scale) {
        preview.setDropScale(scale);
    }

    /** Facteur d'agrandissement courant de la carte (1 par défaut). */
    public float getPreviewDropScale() {
        return preview.dropScale();
    }

    /**
     * Pousse le texte courant de l'éditeur vers le hôte d'aperçu.
     * Appelé (débouncé) après les changements de texte quand l'aperçu est
     * actif.
     *
     * <p>Pousse aussi le contenu vers le corps de la feuille popup (s'il y
     * en a une) pour que le WebView du hôte puisse re-rendre le Markdown/
     * HTML en live pendant la frappe.
     */
    /**
     * Retourne vrai si le hôte d'aperçu a du contenu prêt à dessiner
     * <em>sur le canvas de l'éditeur</em> (aperçu inline SPLIT/FULL de
     * layout XML).
     *
     * <p>Les modes SHEET_* ne dessinent jamais sur le canvas de l'éditeur
     * (la feuille a sa propre surface), donc ceci retourne faux pour eux.
     */
    public boolean isXmlPreviewActive() {
        return preview.isXmlPreviewActive();
    }

    public PreviewMode getPreviewMode() { return previewMode; }

    /**
     * Retourne la coordonnée X où commence le panneau d'aperçu.
     *
     * <p>Les modes SHEET_* retournent les bornes de la feuille — mais
     * l'éditeur ne rétrécit PAS sa zone de texte pour les modes feuille
     * (la feuille est un overlay). Ces valeurs sont passées au hôte pour
     * qu'il sache où vit le corps de la feuille.</p>
     */
    public int getPreviewLeft() {
        return preview.previewLeft();
    }

    /**
     * Retourne la largeur du panneau d'aperçu.
     *
     * <p>Pour les modes SHEET_*, c'est la largeur du corps de la feuille —
     * passée au hôte pour qu'il connaisse les dimensions du canvas (quand
     * {@link EditorPreviewHost#drawPreview} sert de repli).</p>
     */
    public int getPreviewWidth() {
        return preview.previewWidth();
    }

    /**
     * Retourne la largeur effective de la zone de texte disponible pour
     * le rendu du texte. Quand l'aperçu est actif, elle est réduite pour
     * laisser place à l'aperçu (délégué à
     * {@link EditorPreviewController#effectiveTextWidth()}).
     *
     * <p>Les modes SHEET_* ne réduisent PAS la largeur de la zone de
     * texte — la feuille flotte au-dessus de l'éditeur, donc le texte se
     * replit comme si aucun aperçu n'était actif (l'utilisateur peut
     * toujours voir et éditer le texte en fermant la feuille).</p>
     */
    int getEffectiveTextWidth() {
        return preview.effectiveTextWidth();
    }

    /** Applique un multiplicateur d'alpha à une couleur ARGB (pour les icônes d'aperçu). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static int applyAlphaToColor(int color, float alpha) {
        int a = (color >>> 24) & 0xFF;
        int newA = (int) (a * alpha);
        return (newA << 24) | (color & 0x00FFFFFF);
    }

    /** Reconstruit le modèle de wrap depuis le document courant + la largeur du viewport (délégué à {@link EditorWrapGeometry}). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void rebuildWrapModel() {
        wrapGeometry.rebuildWrapModel();
    }

    /** Point d'entrée public « montrer le clavier maintenant » (bouton toolbar / FAB).
     * R8 (plan 09) : passe par la politique unifiée — lecture seule ou
     * clavier matériel exposé → le clavier logiciel ne monte pas. */
    public void showSoftKeyboard() {
        requestFocus();
        imeBridge.showSoftInputIfEditable();
    }

    /** Point d'entrée public « cacher le clavier maintenant ». */
    public void hideSoftKeyboard() {
        wantsKeyboard = false;
        InputMethodManager imm = imm();
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Dessin
    // ════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Tout le dessin est délégué à EditorRenderer.
        renderer.draw(canvas);
    }
    /** Retourne la région de pli repliée qui COMMENCE à {@code docLine}, ou null (délégué à {@link EditorLineLayoutResolver}). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DiagnosticShift.FoldRegion collapsedFoldStartingAtLine(int docLine) {
        return lineLayouts.collapsedFoldStartingAtLine(docLine);
    }

    /**
     * Superpose les tokens sémantiques par-dessus les spans lexicaux de
     * coloration syntaxique (délégué à
     * {@link EditorLineLayoutResolver}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static jo.codeeditor.highlight.TokenType semanticTypeToTokenType(int semType) {
        return EditorLineLayoutResolver.semanticTypeToTokenType(semType);
    }

    /**
     * Retourne le layout par ligne en cache (StyledLine + inlays filtrés +
     * spans sémantiques filtrés + tables colonnes brutes↔visuelles),
     * validé par triple-stamp (délégué à
     * {@link EditorLineLayoutResolver}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public LineRenderCache.LineCacheEntry layoutForLine(int lineNum, String lineText) {
        return lineLayouts.layoutForLine(lineNum, lineText);
    }

    /** Retourne la StyledLine en cache pour la ligne donnée, ou null (délégué à {@link EditorLineLayoutResolver}). */
    private StyledLine cachedStyledFor(int lineNum, String lineText) {
        return lineLayouts.cachedStyledFor(lineNum, lineText);
    }

    // ════════════════════════════════════════════════════════════════
    // Correspondance de colonnes consciente des inlays (rawToVisual/visualToRaw)
    // ════════════════════════════════════════════════════════════════

    /**
     * Colonne brute du document → colonne VISUELLE (tissée d'inlays)
     * pour la ligne donnée, avec repli sur l'identité (délégué à
     * {@link EditorLineLayoutResolver}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int visualColFor(int line, int rawCol) {
        return lineLayouts.visualColFor(line, rawCol);
    }

    /**
     * Colonne VISUELLE (tissée d'inlays) → colonne brute du document
     * (délégué à {@link EditorLineLayoutResolver}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int rawColFor(int line, int visualCol) {
        return lineLayouts.rawColFor(line, visualCol);
    }

    // ════════════════════════════════════════════════════════════════
    // Géométrie feuille / chip de diagnostic (partagée par dessin + hit-test)
    // ════════════════════════════════════════════════════════════════

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int countWrappedLines(String msg, float maxW) {
        return EditorPopupAnchors.countWrappedLines(this, msg, maxW);
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] diagnosticSheetMetrics() {
        return EditorPopupAnchors.diagnosticSheetMetrics(this);
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] diagnosticListSheetMetrics() {
        return EditorPopupAnchors.diagnosticListSheetMetrics(this);
    }

    /**
     * Ouvre la feuille de diagnostics groupée pour {@code line} (API
     * publique hôte).
     */
    public void showDiagnosticListSheet(int line) {
        popupManager.showDiagnosticListSheet(line);
    }

    /** Ferme la feuille de diagnostics groupée (API publique hôte). */
    public void dismissDiagnosticListSheet() {
        popupManager.dismissDiagnosticListSheet();
    }

    /** Vrai pendant que la feuille de diagnostics groupée est affichée. */
    public boolean isDiagnosticListSheetVisible() {
        return diagnosticListSheetLine >= 0;
    }

    /**
     * Le {@code DiagnosticsProvider} branché (accès package pour
     * {@link OpenTabDiagnosticsSweep}), ou null quand le langage courant
     * n'en a pas.
     */
    jo.codeeditor.lang.provider.DiagnosticsProvider getDiagnosticsProviderSpi() {
        return diagnosticsPusher.getProvider();
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public EditorPopupAnchors.SelectionToolbarMetrics selectionToolbarMetrics() {
        return EditorPopupAnchors.selectionToolbarMetrics(this);
    }

    /**
     * T4 (lot 2, J5) — offset d'ancrage de la pill de sélection : la
     * borne MOBILE pendant un drag (poignée ou drag-select au doigt —
     * la pill suit le geste), {@code sel.end} au repos. Relais vers
     * l'état du geste ({@code EditorSelectionGestures.pillAnchorOffset}).
     *
     * @return l'offset d'ancrage, ou -1 (repli sur {@code sel.end})
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int selectionToolbarAnchorOffset() {
        return inputHandler.pillAnchorOffset();
    }

    

    // ════════════════════════════════════════════════════════════════
    // NavMenu — métriques du menu contextuel unifié
    // ════════════════════════════════════════════════════════════════

    /**
     * Construit la liste ordonnée des rangées du menu (délégué à
     * {@link EditorNavMenuRows}) : sections en majuscules affichées
     * seulement si non-vides, ou « Nothing found in source. ».
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<NavMenuRow> navMenuRows() {
        return navMenuRowsBuilder.navMenuRows();
    }

    /**
     * L'action d'une rangée TYPE_ACTION, résolue depuis sa section
     * (délégué à {@link EditorNavMenuRows}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public CodeAction navMenuActionAt(NavMenuRow row) {
        return navMenuRowsBuilder.navMenuActionAt(row);
    }

    /**
     * Hauteur totale du CONTENU du menu (sans clamp viewport) en pixels :
     * headers + rangées (délégué à {@link EditorNavMenuRows}). Source du
     * clamp de scroll.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float navMenuContentHeight() {
        return navMenuRowsBuilder.navMenuContentHeight();
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] navMenuMetrics() {
        return EditorPopupAnchors.navMenuMetrics(this);
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] diagnosticChipMetrics(DiagnosticShift.Diagnostic d, int line, int badgeCount) {
        return EditorPopupAnchors.diagnosticChipMetrics(this, d, line, badgeCount);
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] diagnosticChipMetrics(DiagnosticShift.Diagnostic d, int line) {
        return EditorPopupAnchors.diagnosticChipMetrics(this, d, line);
    }

    /**
     * Les diagnostics Error/Warning dont le début se trouve sur
     * {@code line}, le plus sévère d'abord — le groupe de la chip
     * (délégué à {@link EditorDiagnosticsLocator}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<DiagnosticShift.Diagnostic> chipDiagnosticsForLine(int line) {
        return diagnosticsLocator.chipDiagnosticsForLine(line);
    }

    /**
     * Un hit de chip — la ligne, son groupe Error/Warning (le plus sévère
     * d'abord) et le diagnostic primaire affiché dans la pastille.
     * Remplace le retour mono-diagnostic de {@link #findDiagnosticChipAt}
     * pour le flux de tap : une ligne avec plusieurs diagnostics ouvre la
     * feuille groupée au lieu de sauter directement au plus sévère.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final class DiagnosticChipHit {
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public final int line;
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public final List<DiagnosticShift.Diagnostic> diagnostics;

        DiagnosticChipHit(int line, List<DiagnosticShift.Diagnostic> diagnostics) {
            this.line = line;
            this.diagnostics = diagnostics;
        }

        /** Le diagnostic le plus sévère du groupe (jamais null une fois construit). */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public DiagnosticShift.Diagnostic primary() {
            return diagnostics.get(0);
        }
    }

    /**
     * La chip de diagnostic (avec son groupe) sous le point écran (x, y),
     * ou null — pilote l'interaction tap chip → feuille groupée → popup
     * de détail (délégué à {@link EditorDiagnosticsLocator}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DiagnosticChipHit findDiagnosticChipHitAt(float x, float y) {
        return diagnosticsLocator.findDiagnosticChipHitAt(x, y);
    }

        /**
     * Le diagnostic Error/Warning le plus sévère dont le début se trouve
     * sur {@code line} — celui qui a une chip (délégué à
     * {@link EditorDiagnosticsLocator}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DiagnosticShift.Diagnostic chipDiagnosticForLine(int line) {
        return diagnosticsLocator.chipDiagnosticForLine(line);
    }

        /**
     * La chip de diagnostic sous le point écran (x, y), ou null — pilote
     * l'interaction tap chip → feuille (délégué à
     * {@link EditorDiagnosticsLocator}).
     *
     * <p>Conservé pour compatibilité (tests + chemin rapide
     * mono-diagnostic) — le flux de tap utilise désormais
     * {@link #findDiagnosticChipHitAt} qui porte tout le groupe.</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DiagnosticShift.Diagnostic findDiagnosticChipAt(float x, float y) {
        return diagnosticsLocator.findDiagnosticChipAt(x, y);
    }

    /**
     * Aide conservée pour les appelants qui passaient auparavant par
     * EditorView (EditorScrollManager, etc.). Délègue à
     * {@link CaretAnimator#cancelGlide()}.
     */
    void cancelCaretAnimation() {
        caretAnim.cancelGlide();
    }

    // ════════════════════════════════════════════════════════════════
    // Intégration IME
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onCheckIsTextEditor() {
        // R8 (plan 09, E8) : en lecture seule, la vue n'est plus un champ
        // d'édition pour le système — le tap ne fait pas monter un clavier
        // dont les frappes seraient silencieusement ignorées.
        return session != null && !session.isReadOnly();
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
        return imeBridge.onCreateInputConnection(outAttrs);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            // Ne fait pas remonter le clavier si l'utilisateur est parti
            // ailleurs. On garde wantsKeyboard armé pour pouvoir re-montrer
            // au retour, mais on ne prend pas activement le focus.
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) {
            // La perte de focus efface le flag de demande explicite de
            // clavier — un refocus passif (fermeture de feuille, retour
            // d'un autre écran) reste silencieux.
            wantsKeyboard = false;
            hideSoftKeyboard();
            // La perte de focus éteint aussi la loupe (un drag interrompu
            // par un vol de focus ne doit pas laisser la bulle affichée —
            // M3 : la native aussi, fenêtrage système).
            dismissMagnifier();
            // La perte de focus referme le hover/quick doc.
            if (quickDocVisible) dismissQuickDoc();
            // … et le sélecteur de couleur (B26).
            if (colorPickerVisible) dismissColorPicker();
        } else if (wantsKeyboard) {
            // R8 (plan 09) : la politique unifiée décide (lecture seule /
            // clavier matériel) — le flag n'est armé que par elle.
            imeBridge.showSoftInputIfEditable();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // R8 (plan 09, E7) : un clavier matériel vient d'être connecté ou
        // exposé → masquer le clavier logiciel (l'utilisateur tape
        // désormais dessus) et désarmer la demande de remontée.
        if (EditorImeBridge.isHardwareKeyboardVisible(newConfig)) {
            hideSoftKeyboard();
        }
    }

    /**
     * Hook public pour que le hôte signale qu'une édition programmatique
     * a été appliquée à la session (ex. via {@link EditorSession#replaceRange}
     * hors du chemin IME). Rafraîchit le clignotement du caret, le
     * scroll-en-vue, le popup de complétion et l'aide de signature — la
     * même comptabilité que le chemin IME fait automatiquement.
     */
    public void notifyTextChanged() {
        onTextChanged();
        // B7 : le rafraîchissement des actions de la barre de fil d'Ariane
        // vit dans onTextChanged() — le chemin IME doit les rafraîchir au
        // même titre qu'une édition programmatique (retour terrain : les
        // boutons annuler/rétablir restaient grisés après la frappe).
    }

    /** Appelé par l'InputConnection après chaque édition pour rafraîchir
     *  le timer plein du caret et scroller le caret en vue. Rafraîchit
     *  aussi le popup de complétion (si visible). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void onTextChanged() {
        // Point d'entrée unique — CaretAnimator.onEditOrMove() met à jour
        // son horodatage de dernière activité, réinitialise la bascule de
        // clignotement, rend le caret visible et annule tout glissement
        // en vol.
        caretAnim.onEditOrMove();
        // Reconstruit le modèle de wrap — le nombre de lignes ou le contenu
        // du document a pu changer, ce qui affecte les comptes de rangées
        // de wrap par ligne.
        if (wordWrap) rebuildWrapModel();
        scrollManager.scrollCaretIntoView();
        refreshCompletion();
        refreshSignatureHelp();
        // Relance les diagnostics (débouncés) pour que les soulignés se
        // mettent à jour pendant la frappe.
        diagnosticsPusher.schedule();
        // Le cache d'actions ne survit pas à l'édition (périmé).
        codeActionsByLine.clear();
        // Rafraîchit les inlay hints (débouncés) — les annotations de type
        // peuvent changer pendant la frappe (ex. var x = ...).
        languageBridge.scheduleInlayHints();
        // Rafraîchit les document highlights (débouncés) — les occurrences
        // du symbole sous le caret ont pu changer.
        scheduleDocumentHighlights();
        // Surlignage d'appariement de parenthèses — l'édition a pu
        // ajouter ou retirer la parenthèse au caret, recalcul synchrone.
        updateBracketPair();
        // Met à jour le contenu d'aperçu (débouncé via postDelayed).
        preview.scheduleContentUpdate();
        // Quick doc + code actions + toolbar de sélection se ferment à
        // l'édition.
        if (quickDocVisible) dismissQuickDoc();
        if (codeActionsPopupVisible) dismissCodeActions();
        if (diagnosticPopupVisible) dismissDiagnosticPopup();
        // Le menu contextuel unifié se referme aussi sur édition.
        if (navMenuVisible) dismissNavMenu();
        // Le popup ancré de squiggle aussi : les plages diagnostiquées
        // bougent à l'édition.
        if (squigglePopupVisible) popupManager.dismissSquigglePopup();
        // Le sélecteur de couleur (B26) aussi : le code tapé (plage +
        // valeur d'origine) devient périmé à la première frappe.
        if (colorPickerVisible) popupManager.dismissColorPicker();
        // Referme la toolbar de sélection à l'édition — la sélection a pu
        // bouger ; annule aussi le tap-dismiss différé (un caret tapé
        // AVANT l'insert serait périmé après).
        popupFade.snuff(jo.codeeditor.view.popup.EditorPopupFade.Kind.SELECTION_TOOLBAR);
        selectionToolbarVisible = false;
        selectionToolbarPressedIdx = -1;
        handlesVisible = false;
        inputHandler.cancelPendingTapDismiss();
        // B7 : rafraîchit les actions de la barre de fil d'Ariane sœur
        // (annuler/rétablir/aperçu — états activé/visible/actif). Vit ICI
        // et pas seulement dans notifyTextChanged() : le chemin IME
        // (commitText → onTextChanged) est le chemin de frappe réel sur
        // appareil — sans ceci, les boutons restaient grisés après une
        // édition clavier (retour terrain n°4 : « ils ne font rien »).
        BreadcrumbBar.invalidateActionsIn(getParent());
        invalidate();
    }

    // ════════════════════════════════════════════════════════════════
    // Toucher & gestes
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return inputHandler.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        inputHandler.computeScroll();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /**
     * G9h : le clic droit (bouton secondaire de la souris) ouvre un menu
     * contextuel avec Copier / Couper / Coller / Sélectionner tout /
     * Annuler / Rétablir. Desktop / ChromeOS / DeX distribuent les clics
     * droits comme {@code ACTION_BUTTON_PRESS} avec
     * {@code getActionButton() == BUTTON_SECONDARY}.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (inputHandler.onGenericMotionEvent(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    /**
     * Construit et montre un {@link android.widget.PopupMenu} Android avec
     * les actions standard de l'éditeur. Utilisé par G9h (clic droit) et
     * peut être appelé directement par le hôte pour une touche menu
     * matérielle.
     */
    public void showEditorContextMenu(float anchorX, float anchorY) {
        inputHandler.showEditorContextMenu(anchorX, anchorY);
    }

    /**
     * Retourne les coordonnées écran du caret de l'offset donné,
     * utilisées pour positionner les poignées de sélection (délégué à
     * {@link EditorHitMapper}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] caretScreenPos(int offset) {
        return hitMapper.caretScreenPos(offset);
    }

    /**
     * Mappe un point écran (x, y) en offset document, borné à la fin de
     * ligne, conscient du wrap, des plis repliés et des inlays (délégué
     * à {@link EditorHitMapper}).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int offsetAt(float x, float y) {
        return hitMapper.offsetAt(x, y);
    }

    // ════════════════════════════════════════════════════════════════
    // Touches matérielles
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (session == null) return super.onKeyDown(keyCode, event);
        if (keyHandler.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ════════════════════════════════════════════════════════════════
    // Presse-papiers
    // ════════════════════════════════════════════════════════════════

    /** Copie la sélection courante (ne fait rien en mode curseur). */
    public void copy() {
        clipboard.copy();
    }

    /** Coupe la sélection courante vers le presse-papiers (ne fait rien en mode curseur). */
    public void cut() {
        clipboard.cut();
    }

    /** Colle le presse-papiers au caret (en remplaçant la sélection). */
    public void paste() {
        clipboard.paste();
    }

    // ════════════════════════════════════════════════════════════════
    // Défilement
    // ════════════════════════════════════════════════════════════════

    /** Fait défiler pour rendre le caret visible après chaque édition / déplacement du caret. */
    void scrollCaretIntoView() {
        scrollManager.scrollCaretIntoView();
    }

    /**
     * B9 (retour terrain n°6) — vrai pendant qu'un drag de sélection
     * (poignée traînée ou drag-select au doigt) possède le viewport.
     *
     * <p>Consulté par l'IME bridge pour suspendre le recentrage du
     * caret pendant le geste : chaque application de sélection pendant
     * le drag déclenchait {@code scrollCaretIntoView()}, qui TIRAIT le
     * viewport vers le caret pendant que l'auto-scroll UX-2 du drag le
     * poussait dans la direction du doigt — les deux luttant au rythme
     * des MOVE, les lignes « secouaient » pendant le drag (retour
     * terrain : « les lignes ont tendance à secouer »). Pendant le
     * drag, le geste et son auto-scroll dirigent SEULS le viewport.</p>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean isSelectionDragActive() {
        return inputHandler.isSelectionDragActive();
    }

    public void scrollToLine(int line) {
        scrollManager.scrollToLine(line);
    }

    public void scrollBy(float dy) {
        scrollManager.scrollBy(dy);
    }

    public void scrollHorizontallyBy(float dx) {
        scrollManager.scrollHorizontallyBy(dx);
    }

    // ════════════════════════════════════════════════════════════════
    // État d'instance (rotation / recreation) — B17
    // ════════════════════════════════════════════════════════════════

    /**
     * Sauvegarde l'état VISUEL de l'éditeur : défilement (vOffset/hOffset),
     * zoom (fontScale) et bascule du retour à la ligne. B17 : sans cela,
     * une rotation de l'activité réinitialisait scroll au sommet, zoom à
     * ×1 et wrap à la valeur par défaut — l'hôte ne peut pas les restaurer
     * lui-même (champs internes). Le CONTENU du document reste de la
     * responsabilité de l'hôte (la session ne vit pas dans la vue).
     */
    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        EditorSavedState ss = new EditorSavedState(superState);
        ss.vOffset = scroll.vOffset;
        ss.hOffset = scroll.hOffset;
        ss.fontScale = zoom.fontScale;
        ss.wordWrap = wordWrap;
        return ss;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof EditorSavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        EditorSavedState ss = (EditorSavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        // Zoom d'abord : setFontScale reconstruit le modèle de wrap (B13).
        if (ss.fontScale != zoom.fontScale) zoom.setFontScale(ss.fontScale);
        // Wrap ensuite — setWordWrap rebuild aussi ; ne rebuild PAS si
        // identique (état déjà cohérent).
        if (wordWrap != ss.wordWrap) setWordWrap(ss.wordWrap);
        // Scroll en dernier, borné à la géométrie RESTAURÉE. Sans session
        // encore posée, maxV()/maxH() valent 0 : garder les valeurs brutes
        // — elles seront bornées au premier usage (scroll/setSession).
        if (session != null) {
            scroll.restore(ss.vOffset, ss.hOffset, maxV(), maxH());
        } else {
            scroll.restoreUnclamped(ss.vOffset, ss.hOffset);
        }
        invalidate();
    }


    // ════════════════════════════════════════════════════════════════
    // Défilement — bornes
    // ════════════════════════════════════════════════════════════════

    /** Défilement vertical max : hauteur du contenu moins hauteur du viewport, au moins 0. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float maxV() {
        return scrollManager.maxV();
    }

    /** Défilement horizontal max : largeur de la ligne la plus longue moins largeur de la zone de texte, au moins 0. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float maxH() {
        return scrollManager.maxH();
    }

    // ════════════════════════════════════════════════════════════════
    // Zoom
    // ════════════════════════════════════════════════════════════════

        public void setFontScale(float scale) {
        zoom.setFontScale(scale);
    }

        public void applyPinchScale(float scaleFactor) {
        zoom.applyPinchScale(scaleFactor);
    }

    /**
     * B9 (retour terrain n°6) — pincement avec POINT FOCAL : l'ancre
     * prioritaire reste le caret (visible), de repli le contenu sous le
     * milieu des deux doigts (lecture seule, caret hors écran). Voir
     * {@link jo.codeeditor.view.input.EditorZoomController#applyPinchScale(float, float, float)}.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void applyPinchScale(float scaleFactor, float focusX, float focusY) {
        zoom.applyPinchScale(scaleFactor, focusX, focusY);
    }

    // Méthodes de commodité pour la taille de police +/- depuis les icônes Canvas.
        public void increaseFontSize() {
        zoom.increaseFontSize();
    }

        public void decreaseFontSize() {
        zoom.decreaseFontSize();
    }

    /**
     * B12 (P1-3) — pose la clé de mémoire de zoom par type de fichier.
     *
     * <p>Sémantique « un zoom par type » : l'échelle courante est
     * d'abord enregistrée sous l'ANCIENNE clé, puis si la nouvelle clé
     * est non nulle, l'échelle mémorisée pour ELLE est appliquée (1 par
     * défaut — un type jamais zoomé repart à l'échelle de base, il
     * n'hérite PAS du zoom du type précédent). Clé nulle : mémoire
     * désactivée, l'échelle courante est conservée telle quelle.</p>
     *
     * <p>Appelée par l'hôte à chaque changement de fichier/onglet — la
     * granularité de la clé lui appartient (extension, langage, projet).
     * La rotation reste portée par l'état d'instance B17, plus précis :
     * {@code onRestoreInstanceState} s'exécute APRÈS le câblage de
     * l'hôte, l'échelle exacte de CETTE vue l'emporte donc sur la
     * mémoire du type.</p>
     *
     * @param key clé du type de fichier, ou null pour désactiver
     */
    public void setZoomMemoryKey(String key) {
        String old = zoomMemoryKey;
        if (java.util.Objects.equals(old, key)) return;
        if (old != null) EditorZoomMemory.remember(old, zoom.fontScale);
        zoomMemoryKey = key;
        if (key != null) zoom.setFontScale(EditorZoomMemory.recall(key));
    }

    // ── B13 (P1-2) : drag-déposer de bloc ──────────────────────

    /**
     * B13 (P1-2) — arme le mode « déplacer par glisser-déposer » pour
     * la sélection courante : le GESTE suivant appartient au bloc
     * (drag = déplacement avec loupe + caret fantôme, tap =
     * annulation). Le dépôt s'exécute en une étape d'annulation et
     * ré-épingle la sélection sur le texte déplacé.
     *
     * <p>C'est le perform de la pill
     * {@code BuiltinSelectionActions.blockDrag(view)} — l'hôte peut
     * aussi l'appeler directement (barre d'outils, raccourci).</p>
     *
     * @return true si armé (sélection non réduite au caret, session
     *         vivante et éditable) ; false sinon (rien ne change)
     */
    public boolean armBlockDrag() {
        return inputHandler.armBlockDrag();
    }

    /** B13 — vrai tant que le mode drag-déposer est armé (avant le geste). */
    public boolean isBlockDragArmed() {
        return inputHandler.isBlockDragArmed();
    }

    /** B13 — vrai pendant un déplacement engagé (doigt au-delà du slop). */
    public boolean isBlockDragActive() {
        return inputHandler.isBlockDragActive();
    }

    /** B13 — annule le mode drag-déposer (document intact, sélection d'origine conservée). */
    public void cancelBlockDrag() {
        inputHandler.cancelBlockDrag();
    }

    // Bascule d'affichage des caractères non imprimables.
    public void setShowNonPrintable(boolean show) {
        this.showNonPrintable = show;
        invalidate();
    }
    public boolean isShowNonPrintable() { return showNonPrintable; }

    /**
     * B12 (P1-4) — mode d'affichage des numéros de ligne de la
     * gouttière (ABSOLUTE par défaut, RELATIVE = distance au caret,
     * HYBRID = numéro réel au caret + distances autour).
     *
     * <p>Relais public vers la gouttière : l'hôte n'a pas à connaître
     * les collaborateurs internes de rendu. {@code null} retombe sur
     * ABSOLUTE.</p>
     *
     * @param style le mode voulu
     */
    public void setLineNumberStyle(
            jo.codeeditor.view.chrome.LineNumberStyle style) {
        gutterView.setLineNumberStyle(style);
        invalidate();
    }

    /** Mode courant des numéros de gouttière (jamais null). */
    public jo.codeeditor.view.chrome.LineNumberStyle getLineNumberStyle() {
        return gutterView.getLineNumberStyle();
    }

    // ★ Bascule de visibilité du caret. Défaut : true (visible).
    // Cache ou affiche le caret clignotant indépendamment de EditorSession.readOnly.
    // Sert aux surfaces en lecture-seule qui veulent quand même muter le
    // document programmatiquement (ex : ConsoleLogView.appendLine) sans
    // montrer de point d'insertion à l'utilisateur.
    public void setCaretVisible(boolean visible) {
        this.caretVisible = visible;
        // Réinitialise l'état du blink pour éviter un caret figé dans
        // l'état précédent au moment du toggle.
        caretAnim.onEditOrMove();
        invalidate();
    }
    public boolean isCaretVisible() { return caretVisible; }

    // Bascule des ligatures de police.
    public void setFontLigatures(boolean enabled) {
        this.fontLigatures = enabled;
        invalidate();
    }
    public boolean isFontLigatures() { return fontLigatures; }

    /**
     * Bascule du survol par appui long maintenu.
     *
     * <p>Activé, un maintien de 500 ms sans mouvement ni relâchement affiche
     * le popup quick doc à l'offset touché — SANS déclencher la sélection
     * classique de l'appui long (pas de poignées, pas de toolbar, pas de
     * déplacement du caret). L'appui long classique (400 ms → sélection +
     * poignées + toolbar + quick doc) est mutuellement exclusif avec ce
     * mode : quand touchHover est actif, le long-press du GestureDetector
     * est supprimé pour la durée de chaque geste.</p>
     *
     * <p>Défaut : {@code false} (conserve le comportement historique où
     * l'appui long déclenche à la fois sélection et quick doc).</p>
     *
     * @param enabled true pour activer le survol par appui maintenu, false pour l'appui long classique
     */
    public void setTouchHoverEnabled(boolean enabled) {
        this.touchHoverEnabled = enabled;
    }
    /** Indique si le survol par appui maintenu est activé. */
    public boolean isTouchHoverEnabled() { return touchHoverEnabled; }
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean touchHoverEnabled = false;

    // Hit-test des icônes de toolbar (A+, A-, ¶, lig) en haut à droite —
    // possédé par EditorPopupAnchors (appelé par EditorTapResolver).

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static float clampFontScale(float s) {
        return EditorZoomController.clampFontScale(s);
    }

    // ════════════════════════════════════════════════════════════════
    // Utilitaires
    // ════════════════════════════════════════════════════════════════

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public InputMethodManager imm() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
            getResources().getDisplayMetrics());
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static Selection clampSelection(Selection sel, EditorDocument doc) {
        int start = clamp(sel.start, 0, doc.length());
        int end = clamp(sel.end, 0, doc.length());
        return new Selection(start, end);
    }

    /** Listener optionnel pour que l'Activity hôte réagisse à Ctrl+F. */
    public interface OnFindRequestedListener {
        void onFindRequested();
    }

    /** Listener optionnel pour que l'Activity hôte réagisse à Ctrl+S. */
    public interface OnSaveRequestedListener {
        void onSaveRequested();
    }

    // ════════════════════════════════════════════════════════════════
    // Popup de complétion
    // ════════════════════════════════════════════════════════════════

    /** Fournisseur de complétion optionnel — sans fournisseur, repli sur
     * la complétion par mots-clés intégrée (java/kotlin/xml/markdown). */
    public interface CompletionProvider {
        /**
         * @param text     le texte complet du document
         * @param caret    l'offset du caret
         * @param tokenStart l'offset où l'identifiant en cours de saisie a commencé
         * @param prefix   le préfixe tapé jusqu'ici (peut être vide)
         * @return une liste d'items de complétion (liste vide si aucune complétion)
         */
        List<jo.codeeditor.completion.CompletionSession.Item> provide(
            String text, int caret, int tokenStart, String prefix);

        /** Variante avec le caractère déclencheur (null = invocation
         * explicite) — délègue par défaut à la variante 4 paramètres.
         * @since 3.51.0 */
        default List<jo.codeeditor.completion.CompletionSession.Item> provide(
            String text, int caret, int tokenStart, String prefix,
            Character triggerCharacter) {
            return provide(text, caret, tokenStart, prefix);
        }
    }

    public void setCompletionProvider(CompletionProvider provider) {
        this.completionProvider = provider;
    }

    /**
     * Branche un resolver d'aide de signature. Sans resolver, le popup
     * retombe sur un indice synthétique « function(…) param N » dérivé d'un
     * scan local du contexte d'appel.
     */
    public void setSignatureHelpResolver(SignatureHelpResolver resolver) {
        this.signatureHelpResolver = resolver;
        signatureHelpController.setListener(resolver != null
            ? (offset, callOpen) -> resolver.resolve(session.getText(), offset)
            : null);
    }

    /**
     * Point d'entrée public pour que l'hôte déclenche explicitement l'aide
     * de signature (ex. bouton « Sig » dans la toolbar ou Ctrl+P).
     */
    public void refreshSignatureHelpFromHost() {
        popupManager.refreshSignatureHelpFromHost();
    }

    /** Masque le popup d'aide de signature. */
    public void dismissSignatureHelp() {
        popupManager.dismissSignatureHelp();
    }

    /**
     * Fait défiler la signature active (surcharge) de {@code +1} (Bas)
     * ou {@code -1} (Haut), avec bouclage. Appelé par le gestionnaire clavier
     * quand l'utilisateur presse Haut/Bas pendant que le popup d'aide de
     * signature est ouvert.
     *
     * <p>La surcharge choisie persiste à travers les rafraîchissements de
     * {@link #refreshSignatureHelp} au sein du même appel — l'utilisateur
     * peut continuer à taper des arguments et le popup reste sur la
     * signature choisie. Quand le caret passe à un autre appel, la priorité
     * manuelle est effacée.
     *
     * @param direction {@code +1} pour la surcharge suivante, {@code -1} pour la précédente
     */
    public void cycleSignatureHelp(int direction) {
        int newIdx = signatureHelpController.cycleActiveSignature(direction);
        if (newIdx >= 0) {
            invalidate();
        }
    }

    /**
     * Retourne l'index de signature active effectif, en tenant compte de la
     * priorité utilisateur (définie via {@link #cycleSignatureHelp}). Les
     * renderers doivent lire ceci plutôt que {@code signatureHelpData.activeSignature}
     * pour que la navigation clavier Haut/Bas soit reflétée dans le popup.
     *
     * @return l'index de signature active, ou {@code -1} si aucune aide n'est disponible
     */
    public int getEffectiveActiveSignature() {
        return signatureHelpController.getEffectiveActiveSignature();
    }

    /**
     * Déclenche l'aide de signature au caret courant. Appelé sur changements
     * de sélection et après édition — le contrôleur décide en interne de
     * garder le popup ouvert (caret toujours dans le même appel) ou de le
     * masquer (caret sorti de l'appel ou rejeté par l'utilisateur).
     */
    void refreshSignatureHelp() {
        popupManager.refreshSignatureHelp();
    }

    /** Déclencheur explicite Ctrl+P — efface l'état « rejeté » et re-résout. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void triggerSignatureHelp() {
        popupManager.triggerSignatureHelp();
    }

    // ════════════════════════════════════════════════════════════════
    // Popup quick doc
    // ════════════════════════════════════════════════════════════════

    /** Branche un resolver quick doc. */
    public void setQuickDocResolver(QuickDocResolver resolver) {
        this.quickDocResolver = resolver;
    }

    /** Branche un resolver de tooltip d'inlay hints (I2, lot 2 J3). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void setInlayTooltipResolver(InlayTooltipResolver resolver) {
        this.inlayTooltipResolver = resolver;
    }

    /**
     * Affiche le popup quick doc pour le symbole à l'offset donné. Si un
     * resolver est branché, l'appelle pour obtenir le texte brut de la
     * doc ; sinon le popup ne fait rien.
     */
    public void showQuickDoc(int offset) {
        popupManager.showQuickDoc(offset);
    }

    /** Masque le popup quick doc. */
    public void dismissQuickDoc() {
        popupManager.dismissQuickDoc();
    }

    // ════════════════════════════════════════════════════════════════
    // Ampoule des actions de code
    // ════════════════════════════════════════════════════════════════

    /** Branche un resolver d'actions de code. */
    public void setCodeActionsResolver(CodeActionsResolver resolver) {
        this.codeActionsResolver = resolver;
    }

    /**
     * Ouvre le popup d'actions de code pour la ligne donnée — contenu
     * RÉSOLU À LA DEMANDE (ancre = début du diagnostic de la ligne, sinon
     * début de ligne) ; le popup n'apparaît que si des actions existent.
     */
    public void showCodeActions(int line) {
        popupManager.showCodeActions(line);
    }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié
    // ════════════════════════════════════════════════════════════════

    /** Branche le resolver go-to-type-declaration. */
    public void setTypeDefinitionResolver(TypeDefinitionResolver resolver) {
        this.typeDefinitionResolver = resolver;
    }

    /** Branche le resolver go-to-implementations. */
    public void setImplementationsResolver(ImplementationsResolver resolver) {
        this.implementationsResolver = resolver;
    }

    /** Branche le resolver go-to-super. */
    public void setSuperResolver(SuperResolver resolver) {
        this.superResolver = resolver;
    }

    /**
     * Ouvre le menu contextuel unifié (le bouton Actions ⋯ de la toolbar
     * de sélection) : sections GO TO / QUICK FIXES / INTENTIONS. La
     * résolution (definition + typeDefinition + quick-fixes de la ligne)
     * est asynchrone — le menu apparaît quand le contenu est prêt.
     *
     * @param line   la ligne d'ancrage (celle du caret)
     * @param offset l'offset caret de résolution
     */
    public void showNavMenu(int line, int offset) {
        popupManager.showNavMenu(line, offset);
    }

    /** Referme le menu contextuel unifié. */
    public void dismissNavMenu() {
        popupManager.dismissNavMenu();
    }

    /**
     * Injecte une action d'hôte dans la pill de sélection (T1, lot 2) :
     * libellé entre les canoniques d'édition et les icônes Docs/Actions,
     * uniquement avec une vraie sélection ; au-delà de la largeur de vue
     * les suivantes basculent dans « ⋯ » (NavMenu, section « MORE »).
     * Un tap exécute {@code perform} puis referme la pill (contrat
     * Copy/Cut/Paste) ; {@code enabled} à false → estompée, tap rejeté.
     */
    public void addSelectionAction(SelectionAction action) {
        popupManager.selActions.add(action);
        invalidate();
    }

    /** Retire la première action injectée d'identifiant {@code id}. */
    public void removeSelectionAction(String id) {
        popupManager.selActions.remove(id);
        invalidate();
    }

    /**
     * Retourne true si la ligne donnée porte un diagnostic Error/Warning
     * (délégué à {@link EditorDiagnosticsLocator}). Utilisé par le dessin
     * de l'ampoule + le hit-test pour réserver l'ampoule aux lignes de
     * diagnostic uniquement.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean lineHasDiagnostic(int line) {
        return diagnosticsLocator.lineHasDiagnostic(line);
    }

    /** Referme le popup d'actions de code. */
    public void dismissCodeActions() {
        popupManager.dismissCodeActions();
    }

    /** Applique l'action de code actuellement sélectionnée. */
    public boolean applySelectedCodeAction() {
        return popupManager.applySelectedCodeAction();
    }

    // ════════════════════════════════════════════════════════════════
    // Popup go-to-symbol
    // ════════════════════════════════════════════════════════════════

    /** Branche un resolver de symboles. */
    public void setSymbolResolver(SymbolResolver resolver) {
        this.symbolResolver = resolver;
    }

    // ════════════════════════════════════════════════════════════════
    // Definition / References / DocumentHighlight / Rename / Formatter
    // ════════════════════════════════════════════════════════════════

    /** Branche un resolver de définition pour le go-to-definition. */
    public void setDefinitionResolver(DefinitionResolver resolver) {
        this.definitionResolver = resolver;
    }

    /**
     * Déclenche le go-to-definition au caret courant. Si le resolver
     * retourne une cible unique dans le MÊME fichier, y navigue directement.
     * Si cibles multiples ou inter-fichiers, retourne la liste via le
     * {@link OnDefinitionRequestedListener} (l'hôte décide de l'affichage).
     *
     * <p>La résolution LSP quitte le thread UI — l'hôte peut afficher un
     * feedback « recherche… » immédiat et le résultat est appliqué dès
     * qu'il arrive.</p>
     */
    public void jumpToDefinition() {
        documentActions.jumpToDefinition();
    }

    /** Listener invoqué quand le go-to-definition produit des cibles multiples ou une cible inter-fichiers. */
    public interface OnDefinitionRequestedListener {
        void onDefinitionRequested(List<jo.codeeditor.lang.model.DefinitionLocation> targets);
    }
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public OnDefinitionRequestedListener definitionListener;
    public void setOnDefinitionRequestedListener(OnDefinitionRequestedListener l) {
        this.definitionListener = l;
    }

    /**
     * Optionnel : l'hôte définit ce chemin pour que jumpToDefinition
     * reconnaisse les URI du même fichier.
     * Package-private — navMenuNavigate (EditorPopupManager) teste le même-fichier.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public String currentFilePath = "";
    public void setCurrentFilePath(String path) {
        this.currentFilePath = path != null ? path : "";
    }

    /** Branche un resolver de références pour le find-references. */
    public void setReferencesResolver(ReferencesResolver resolver) {
        referencesController.setResolver(resolver);
    }

    /**
     * Déclenche le find-references au caret courant. Appelle le resolver
     * et affiche les résultats dans un popup (réutilise la mise en page du
     * go-to-symbol).
     *
     * <p>La résolution LSP (jusqu'à 15 s !) est déportée hors du thread UI,
     * annulable par génération.</p>
     */
    public void showReferences() {
        referencesController.show();
    }

    /**
     * Navigation inter-fichiers. L'hôte (ProjectActivity) branche ce
     * listener pour ouvrir le fichier cible à l'offset donné. Utilisé par
     * {@link #referencesAccept()} quand l'usage sélectionné se trouve dans
     * un autre fichier.
     */
    public interface OnNavigateToFileListener {
        void navigateToFile(String pathOrUri, int offset);
    }
    OnNavigateToFileListener navigateToFileListener;

    public void setOnNavigateToFileListener(OnNavigateToFileListener listener) {
        this.navigateToFileListener = listener;
    }

    /** Referme le popup de références. */
    public void dismissReferences() {
        referencesController.dismiss();
    }

    public boolean isReferencesVisible() { return referencesController.isVisible(); }

    /** Déplace la sélection du popup de références de delta. */
    public boolean referencesSelect(int delta) {
        return referencesController.select(delta);
    }

    /** Accepte la référence sélectionnée et y navigue. */
    public boolean referencesAccept() {
        return referencesController.accept();
    }

    /** Met à jour le filtre du popup de références (réutilise NavigationMenu.filter). */
    public void setReferencesFilter(String filter) {
        referencesController.setFilter(filter);
    }

    /** Branche un resolver de surlignage document (document-highlight). */
    public void setDocumentHighlightResolver(DocumentHighlightResolver resolver) {
        this.documentHighlightResolver = resolver;
    }

    /** Branche un resolver de renommage (remplace le fallback par correspondance de sous-chaîne). */
    public void setRenameResolver(RenameResolver resolver) {
        this.renameResolver = resolver;
    }

    /** Branche un resolver de formatage. */
    public void setFormatterResolver(FormatterResolver resolver) {
        this.formatterResolver = resolver;
    }

    /**
     * Formate tout le document via le resolver de formatage : requête
     * LSP hors du thread UI, résultat appliqué en un seul step d'undo.
     *
     * @param callback thread UI : TRUE si le texte a changé, FALSE sinon,
     *                 null si aucun formatteur. Peut être null.
     */
    public void formatDocument(java.util.function.Consumer<Boolean> callback) {
        documentActions.formatDocument(callback);
    }

    /** Ancienne API synchrone conservée pour compatibilité — retourne
     * toujours false (le formatage est désormais asynchrone). */
    public boolean formatDocument() {
        return false;
    }

    /** Ouvre le popup go-to-symbol (résolution + filtrage). */
    public void showGoToSymbol() {
        popupManager.showGoToSymbol();
    }

    /**
     * « source.organizeImports » (resp. « source.fixAll » via
     * {@link #performFixAll}) : UNE requête par document avec le filtre
     * de famille dans le contexte LSP, résolution en arrière-plan.
     *
     * @param callback thread UI : TRUE si action trouvée+exécutée, FALSE sinon.
     */
    public void performOrganizeImports(java.util.function.Consumer<Boolean> callback) {
        documentActions.performOrganizeImports(callback);
    }

    /** {@link #performOrganizeImports} pour « source.fixAll ». @since 3.51.0 */
    public void performFixAll(java.util.function.Consumer<Boolean> callback) {
        documentActions.performFixAll(callback);
    }

    /** Referme le popup go-to-symbol. */
    public void dismissGoToSymbol() {
        popupManager.dismissGoToSymbol();
    }

    /** Met à jour le filtre et re-filtre la liste de symboles. */
    public void setGoToSymbolFilter(String filter) {
        popupManager.setGoToSymbolFilter(filter);
    }

    /** Déplace la sélection go-to-symbol du delta donné. */
    public boolean goToSymbolSelect(int delta) {
        return popupManager.goToSymbolSelect(delta);
    }

    /** Accepte le symbole sélectionné et y navigue. */
    public boolean goToSymbolAccept() {
        return popupManager.goToSymbolAccept();
    }

    /** Entrée publique pour une source de complétion externe (ex. CompletionController). */
    public void setCompletionItems(List<jo.codeeditor.completion.CompletionSession.Item> items,
                                    int tokenStart, String prefix) {
        popupManager.setCompletionItems(items, tokenStart, prefix);
    }

    public boolean isCompletionVisible() { return popupManager.isCompletionVisible(); }

    public void dismissCompletion() {
        popupManager.dismissCompletion();
    }

    /** Monte la sélection dans le popup de complétion. Retourne true si traité. */
    public boolean completionSelectUp() {
        return popupManager.completionSelectUp();
    }

    /** Descend la sélection dans le popup de complétion. Retourne true si traité. */
    public boolean completionSelectDown() {
        return popupManager.completionSelectDown();
    }

    /** Accepte la complétion sélectionnée. Retourne true si une complétion a été acceptée. */
    public boolean completionAccept() {
        return popupManager.completionAccept();
    }

    /**
     * Rafraîchit le popup de complétion selon le contexte courant du caret.
     * <p>Utilise un filtrage côté client :
     * <ol>
     *   <li>Si le caret est toujours sur le MÊME token que l'ensemble de
     *       base en cache, filtre le cache par préfixe (insensible à la
     *       casse + sous-séquence floue) — AUCUN aller-retour provider.
     *       Le popup reste réactif à chaque frappe même avec un serveur
     *       LSP lent.</li>
     *   <li>Si le caret a bougé vers un NOUVEAU token (ou pas de cache),
     *       interroge le provider et met le résultat en cache comme
     *       nouvel ensemble de base.</li>
     * </ol>
     * <p>Le cache est invalidé quand :
     * <ul>
     *   <li>Le caret passe à un token différent (le préfixe ne commence
     *       pas au même offset)</li>
     *   <li>Le préfixe devient vide (token terminé)</li>
     *   <li>L'utilisateur accepte ou referme le popup</li>
     * </ul>
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void refreshCompletion() {
        popupManager.refreshCompletion();
    }

    /**
     * Point d'entrée public pour que l'hôte déclenche explicitement la
     * complétion (ex. bouton « Cmplt » dans la toolbar).
     */
    public void refreshCompletionFromHost() {
        popupManager.refreshCompletionFromHost();
    }

    /**
     * Calcule l'ancre (haut-gauche) du popup de complétion en coordonnées
     * écran. Partagé par {@link #drawCompletionPopup} et
     * {@link #hitTestCompletionPopup} pour qu'ils soient toujours d'accord
     * sur la position du popup.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] completionPopupAnchor() {
        return popupManager.completionPopupAnchor();
    }

    /**
     * Géométrie du popup quick doc : {anchorX, anchorY, popupW,
     * popupH, contentH, rowH} ou null. Source unique rendu (renderer) /
     * hit-test (input handler) — le popup suit le texte au scroll.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float[] quickDocMetrics() {
        return renderer.quickDocMetrics();
    }

    // ════════════════════════════════════════════════════════════════
    // Mode éditeur par blocs + EditorOverlayLayers
    // ════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════
    // EditorOverlayLayers
    // ════════════════════════════════════════════════════════════════

    /** Bascule les chips de diagnostic en ligne. */
    public void setDiagnosticChipsEnabled(boolean enabled) {
        this.diagnosticChipsEnabled = enabled;
        invalidate();
    }

    public boolean isDiagnosticChipsEnabled() { return diagnosticChipsEnabled; }

    /**
     * Ouvre le popup go-to-line. Utilise un vrai
     * {@link android.widget.PopupWindow} Android avec un
     * {@link android.widget.EditText} pour que l'utilisateur puisse saisir
     * un numéro de ligne via l'IME. Accepte {@code line} ou
     * {@code line:column} (base 1). Entrée navigue, Échap annule.
     */
    public void showGoToLine() {
        popupManager.showGoToLine();
    }

    /** Analyse « line » ou « line:col » (base 1) et navigue. */
    void acceptGoToLineInput(String input) {
        popupManager.acceptGoToLineInput(input);
    }

    public void dismissGoToLine() {
        popupManager.dismissGoToLine();
    }

    public boolean isGoToLineVisible() { return popupManager.isGoToLineVisible(); }

    /** @deprecated Utiliser plutôt le {@link #showGoToLine()} basé popup. */
    @Deprecated
    public void setGoToLineText(String text) {
        popupManager.setGoToLineText(text);
    }

    /** @deprecated Utiliser plutôt le {@link #showGoToLine()} basé popup. */
    @Deprecated
    public boolean acceptGoToLine() {
        return popupManager.acceptGoToLine();
    }

    /**
     * Ouvre le popup de renommage. Utilise un vrai
     * {@link android.widget.PopupWindow} Android avec un
     * {@link android.widget.EditText} pré-rempli avec l'identifiant au
     * caret. Entrée renomme toutes les occurrences, Échap annule.
     */
    public void showRename() {
        popupManager.showRename();
    }

    /** Applique le renommage : remplace toutes les occurrences identiques de l'identifiant. */
    boolean acceptRenameInput(String newName) {
        return popupManager.acceptRenameInput(newName);
    }

    public void dismissRename() {
        popupManager.dismissRename();
    }

    public boolean isRenameVisible() { return popupManager.isRenameVisible(); }

    /** @deprecated Utiliser plutôt le {@link #showRename()} basé popup. */
    @Deprecated
    public void setRenameText(String text) {
        popupManager.setRenameText(text);
    }

    /** @deprecated Utiliser plutôt le {@link #showRename()} basé popup. */
    @Deprecated
    public boolean acceptRename() {
        return popupManager.acceptRename();
    }

    /** Ouvre la feuille de diagnostics — une bottom sheet listant tous les diagnostics. */
    public void showDiagnosticSheet() {
        popupManager.showDiagnosticSheet();
    }

    public void dismissDiagnosticSheet() {
        popupManager.dismissDiagnosticSheet();
    }

    public boolean isDiagnosticSheetVisible() { return popupManager.isDiagnosticSheetVisible(); }

    /** True quand la feuille par-diagnostic est ouverte. */
    public boolean isDiagnosticPopupVisible() { return diagnosticPopupVisible; }

    /**
     * Affiche la toolbar flottante de sélection (Copier/Couper/Coller/Tout
     * sélectionner) — une pastille dépolie ancrée au-dessus de l'extrémité
     * ACTIVE de la sélection (la toolbar suit le doigt
     * jusqu'à l'endroit où l'utilisateur a fini de sélectionner).
     */
    public void showSelectionToolbar() {
        inputHandler.showSelectionToolbar();
    }

    public void dismissSelectionToolbar() {
        inputHandler.dismissSelectionToolbar();
    }

    /**
     * Gère un tap sur la toolbar de sélection. Retourne true si le tap a
     * été consommé. Conforme à la mise en page en pastille — mesure la
     * largeur réelle de chaque bouton au lieu de supposer des tailles
     * égales.
     */
    public boolean handleSelectionToolbarTap(float x, float y) {
        return inputHandler.handleSelectionToolbarTap(x, y);
    }

    /** Convertit dp en px. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Trouve le diagnostic à l'offset donné, ou null (délégué à
     * {@link EditorDiagnosticsLocator}). N'est plus utilisé par handleTap —
     * le soulignement ondulé n'est pas tapable (seule
     * la chip et le point de gouttière ouvrent la feuille). Conservé pour
     * les tests et d'éventuelles intégrations appui long / quick doc.
     */
    DiagnosticShift.Diagnostic findDiagnosticAt(int offset) {
        return diagnosticsLocator.findDiagnosticAt(offset);
    }

    /**
     * Trouve le premier diagnostic qui COMMENCE sur la ligne document
     * donnée, ou null (délégué à {@link EditorDiagnosticsLocator}).
     * Utilisé par handleTap pour ouvrir le popup de diagnostic quand
     * l'utilisateur tape n'importe où sur une ligne qui porte un diagnostic
     * (pas seulement sur la plage du soulignement).
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public DiagnosticShift.Diagnostic findDiagnosticAtLine(int line) {
        return diagnosticsLocator.findDiagnosticAtLine(line);
    }

    /**
     * Affiche un popup par diagnostic (motif fiche).
     * Montre le message COMPLET du diagnostic + les éventuelles quick-fixes
     * du resolver d'actions de code. Ancré au-dessus de la ligne du diagnostic.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void showDiagnosticPopup(DiagnosticShift.Diagnostic diag, int offset) {
        popupManager.showDiagnosticPopup(diag, offset);
    }

    /** Referme le popup par-diagnostic. */
    public void dismissDiagnosticPopup() {
        popupManager.dismissDiagnosticPopup();
    }

}
