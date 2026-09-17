package jo.codeeditor.view;

import jo.codeeditor.view.chrome.EditorTheme;
import jo.codeeditor.view.chrome.GutterView;
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
import jo.codeeditor.view.render.EditorPainterHost;
import jo.codeeditor.view.render.EditorRenderer;
import jo.codeeditor.view.render.EditorShapedLayoutCache;

import androidx.annotation.RestrictTo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
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

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public EditorSession session;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorMetrics metrics;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public EditorTheme theme;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final GutterView gutterView;

    // ── État de défilement ────────────────────────────────────────
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float vOffset = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float hOffset = 0;

    // ── Zoom ───────────────────────────────────────────────────────
    // L'état de zoom (fontScale + mutations d'échelle) vit dans
    // EditorZoomController ; EditorView ne conserve que les wrappers
    // publics de délégation.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final EditorZoomController zoom = new EditorZoomController(this);
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float BASE_TEXT_SIZE_SP = 14f;

    // ── Surlignages de recherche (décoration visuelle) ────────────
    // Renseignés par l'hôte (ex. barre Rechercher/Remplacer) — chaque
    // occurrence dans le viewport est teintée theme.findMatch, la
    // courante theme.findCurrent.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final List<Match> findHighlights = new ArrayList<>();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int findCurrentIndex = -1;

    // ── Popup de complétion ────────────────────────────────────────
    // Complétion simple par mots-clés intégrée à la vue. L'hôte peut
    // aussi piloter en externe un CompletionController plus riche et
    // alimenter les items via setCompletionItems(items, tokenStart,
    // prefix).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final List<jo.codeeditor.completion.CompletionSession.Item> completionItems = new ArrayList<>();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int completionSelected = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int completionScrollOffset = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int completionTokenStart = -1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public String completionPrefix = "";
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean completionVisible = false;
    // Cache côté client pour le filtrage de complétion.
    // Quand le provider retourne des items pour un token, on les met en
    // cache comme jeu de « base ». Aux frappes suivantes qui étendent le
    // même token, on filtre le jeu en cache par préfixe (insensible à la
    // casse + fuzzy) au lieu de re-requêter le provider — le popup reste
    // ainsi réactif et stable pendant qu'un serveur LSP lent rattrape
    // son retard.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<jo.codeeditor.completion.CompletionSession.Item> completionBaseItems = new ArrayList<>();
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int completionBaseTokenStart = -1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int COMPLETION_MAX_ROWS = 8;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float COMPLETION_ROW_HEIGHT_DP = 28f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float COMPLETION_WIDTH_DP = 280f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public CompletionProvider completionProvider;

    // ── Retour à la ligne (word wrap) ──────────────────────────────
    // Quand activé, les lignes longues se replient sur plusieurs rangées
    // visuelles dans la zone de texte. Le modèle de wrap met en cache le
    // nombre de rangées par ligne et fournit la correspondance
    // O(log L) ligne-doc ↔ rangée-visuelle via sommes préfixes.
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

    // ── Cache de rendu par ligne ──────────────────────────────────
    // Met en cache, par ligne de doc, la StyledLine plus les pièces
    // d'inlay filtrées par ligne + les spans sémantiques + les tables
    // de conversion colonnes brutes↔visuelles. Validé par un
    // triple-stamp (rev texte + rev inlay + rev sém) pour qu'une
    // édition n'invalide que les lignes dont le texte a réellement
    // changé — pas tout le viewport. Éviction LRU à 512 entrées.
    final LineRenderCache renderCache = new LineRenderCache();

    // ── Index de pliage à sommes préfixes ──────────────────────────
    // Possédé par EditorFoldIndex (mémoïsation + requêtes O(log plis)) ;
    // isLineFoldedCached() / countHiddenLinesAbove() ci-dessous et
    // docLineForScreenY() consomment l'index via relais.
    final EditorFoldIndex foldIndex = new EditorFoldIndex(this);
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
    // ~25 % des lignes d'un fichier réel sont identiques octet pour
    // octet ; cette LRU vit dans EditorShapedLayoutCache et mémoïse le
    // layout façonné par CONTENU de ligne (relais ci-dessous pour le
    // chemin de dessin et les tests).
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
    // Affiche l'aide de signature de fonction au-dessus du caret quand
    // le caret est dans un appel de fonction. Ancré AU-DESSUS de la ligne
    // du caret (contrairement à la complétion, qui est EN DESSOUS).
    // Repli sur un popup synthétique « function(…) param N » quand aucun
    // résolveur n'est branché — utile pour la démo et pour les langages
    // sans serveur de langage.
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
    // Affiche la documentation du symbole à la position caret / survol.
    // Desktop : un survol > 500ms déclenche. Mobile : un long-press sur
    // le symbole déclenche. Fermé par un tap ailleurs, scroll, édition
    // ou Échap.
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

    // ── Ampoule des code actions ─────────────────────────────────
    // Affiche une 💡 dans la gouttière pour chaque ligne visible qui a
    // au moins une code action. Tap sur l'ampoule → popup avec la liste
    // d'actions. Tap sur une action → apply.run() (le résolveur fournit
    // un Runnable).
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

    /**
     * Résolveur des code actions. Retourne une liste de
     * {@link CodeAction}s pour le texte de document + la ligne donnés.
     */
    public interface CodeActionsResolver {
        List<CodeAction> resolve(String text, int line);
    }

    /**
     * Une code action unitaire — titre, kind et un Runnable pour
     * l'appliquer.
     */
    public static final class CodeAction {
        public final String title;
        public final String kind; // "quickfix", "refactor", "source"
        public final Runnable apply;
        public CodeAction(String title, String kind, Runnable apply) {
            this.title = title != null ? title : "";
            this.kind = kind != null ? kind : "quickfix";
            this.apply = apply;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié (NavMenu)
    // ════════════════════════════════════════════════════════════════
    // Le bouton « Actions ⋯ » de la toolbar de sélection ouvre le menu
    // contextuel unifié de l'éditeur : sections GO TO (options de navigation
    // applicables au caret), QUICK FIXES (kind quickfix) et INTENTIONS
    // (autres kinds), chacune affichée seulement si non-vide — ou le note
    // « Nothing found in source. » quand tout est vide. Un pick GO TO
    // multi-cibles bascule en mode RESULTS (picker de cibles).

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
    public static final float NAV_MENU_MIN_WIDTH_DP = 240f;
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
    // Les résolveurs suivants pontent les providers du SPI Language
    // (definition, references, document highlights, rename, formatter,
    // inlay hints) vers les chemins UI / dessin de l'éditeur.
    // setLanguage() les branche automatiquement quand le provider SPI
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
        bracketPair = matchingBracket(session.getText(), sel.start);
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

    // Bascule de visibilité du caret. Défaut true (caret visible).
    // Découple « cacher le caret » de EditorSession.setReadOnly(boolean) :
    // setReadOnly(true) bloque AUSSI les mutations programmatiques
    // (replaceRange dans appendLine/setContent/…) → l'utiliser pour
    // empêcher la frappe ET cacher le caret laisserait la console
    // vide à vie.
    // ConsoleLogView passe donc setFocusable(false) (bloque l'IME)
    // + setCaretVisible(false) (cache le caret) sans toucher à setReadOnly
    // (le programme peut muter le document). Voir EditorRenderer.drawCaret.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean caretVisible = true;

    // État de la loupe — actif pour le DRAG DE POIGNÉE uniquement
    // (dragHandle alimente X/Y ; UP/CANCEL désactivent). La bulle
    // elle-même est dessinée par EditorRenderer.drawMagnifier (60dp,
    // zoom 2x, ±3 lignes, consciente des inlays). Jamais active pendant
    // le scroll / drag-select — pour ne pas interférer avec la sélection.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean magnifierActive = false;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float magnifierX = 0;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public float magnifierY = 0;

    // Bascule des ligatures de police. Quand activée, l'éditeur dessine
    // chaque ligne en un seul appel drawText (au lieu de par span) pour
    // que les ligatures comme ->, =>, ==, !=, >=, <=, &&, ||, :: se
    // forment correctement. La coloration syntaxique est désactivée quand
    // les ligatures sont actives (compromis : ligatures vs couleurs).
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean fontLigatures = false;

    // Minimap — rendu miniature à la VS Code du fichier entier, dessiné
    // dans une bande étroite sur le bord droit. Montre la structure du
    // document d'un coup d'œil + indique le rectangle du viewport courant.
    // Tap/drag pour scroller. La géométrie (constantes + bornes) vit dans
    // EditorChromePainter, qui dessine la bande.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public boolean minimapEnabled = false;

    // ── Couches de surcharge ─────────────────────────────────────
    // Overlays légers dessinés au Canvas : chips de diagnostic, toolbar
    // de sélection, popup go-to-line, popup rename, feuille de diagnostic.
    // go-to-line et rename utilisent un vrai PopupWindow Android +
    // EditText (les versions Canvas seul ne pouvaient pas recevoir la
    // saisie clavier).
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
    // Regroupement par ligne de départ (diagnosticsByStartLine) : quand
    // une ligne porte PLUSIEURS diagnostics, sa chip affiche un badge de
    // compte et son tap ouvre d'abord cette feuille groupée — chaque
    // diagnostic commençant sur la ligne a une rangée (point de sévérité +
    // message) ; taper une rangée ouvre le popup par diagnostic avec les
    // quick fixes. Sans cela, la chip/gouttière ne montrerait que le
    // diagnostic le PLUS sévère de la ligne : un avertissement caché
    // derrière une erreur sur la même ligne serait inatteignable.
    // -1 = caché.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int diagnosticListSheetLine = -1;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float DIAG_LIST_ROW_DP = 44f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int DIAG_LIST_MAX_ROWS = 8;

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
        this.session = new EditorSession();
        this.session.setImeListener(imeBridge.listener);
        this.session.setOnLinesShiftedListener(cacheShiftListener);
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
        // Détache de la session précédente.
        if (this.session != null) {
            this.session.setImeListener(null);
            this.session.setOnLinesShiftedListener(null);
        }
        this.session = session;
        this.session.setImeListener(imeBridge.listener);
        this.session.setOnLinesShiftedListener(cacheShiftListener);
        renderCache.clear();
        // Réinitialise le glissement du caret pour que le premier dessin
        // dans la nouvelle session s'affiche d'un coup.
        caretAnim.reset();
        // Réinitialise le scroll pour qu'un offset périmé du document
        // précédent ne laisse pas le viewport échoué.
        vOffset = 0;
        hOffset = 0;
        invalidate();
    }

    public EditorSession getSession() { return session; }

    public void setTheme(EditorTheme theme) {
        this.theme = theme;
        // Les layouts façonnés ont les couleurs de thème cuites dans leurs
        // spans — un changement de thème invalide tous les StaticLayout
        // en cache.
        shapedCache.clear();
        gutterView.setTheme(theme);
        invalidate();
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
            l.onScrollPosition(vOffset, max);
        }
        // Préfetch du viewport au repos — ré-armé à chaque scroll pour que
        // la tâche ne se déclenche qu'après 150 ms de scroll CALME (jamais
        // pendant un fling — préfetcher en plein fling ne fait que le
        // travail de la frame suivante une frame trop tôt, pour rien).
        viewportPrefetcher.schedule();
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
    public float getVOffset() { return vOffset; }
    public float getHOffset() { return hOffset; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Le modèle de wrap dépend de la largeur du viewport — reconstruit
        // au changement de taille.
        if (wordWrap) rebuildWrapModel();
        // Re-borne le scroll au nouveau max.
        vOffset = clamp(vOffset, 0, maxV());
        hOffset = clamp(hOffset, 0, maxH());
        // Redimensionne la feuille d'aperçu pour correspondre aux nouvelles
        // bornes de l'éditeur. La feuille est ancrée à notre position de
        // fenêtre ; on lui demande de ré-évaluer sa taille en ré-appliquant
        // le mode courant.
        preview.handleSizeChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Ferme toute feuille d'aperçu ouverte pour ne pas fuiter un popup
        // pointant vers un éditeur détaché (ce qui planterait au toucher).
        preview.onDetachedFromWindow();
        // Nettoyage complet des callbacks en attente : une vue détachée
        // avec des callbacks runnables encore postés (préfetch au repos,
        // rafraîchissement débouncé des code actions, hover tap-hold,
        // fermeture multi-tap) continuerait à les exécuter contre une vue
        // DÉTACHÉE — la vue resterait fortement accessible depuis son
        // Handler jusqu'à ce que chaque callback ait tiré, et chaque
        // callback pourrait toucher un état que l'hôte a déjà démonté.
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
        findHighlights.clear();
        if (matches != null) findHighlights.addAll(matches);
        findCurrentIndex = currentIndex;
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
            this.hOffset = 0;
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
        SHEET_FULL;

        /** Vrai pour les modes feuille-overlay (popup) vs modes inline. */
        public boolean isSheet() {
            return this == SHEET_SPLIT || this == SHEET_FULL;
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
    // Tout le reste de l'état d'aperçu (hôte, feuille popup, nom de
    // fichier, listener de mode) vit dans EditorPreviewController ; la
    // vue ne conserve que des relais publics.
    final EditorPreviewController preview = new EditorPreviewController(this);

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

    /** Point d'entrée public « montrer le clavier maintenant » (bouton toolbar / FAB). */
    public void showSoftKeyboard() {
        wantsKeyboard = true;
        requestFocus();
        InputMethodManager imm = imm();
        if (imm != null) {
            imm.showSoftInput(this, 0);
        }
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
        return true;
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
            // La perte de focus referme le hover/quick doc.
            if (quickDocVisible) dismissQuickDoc();
        } else if (wantsKeyboard) {
            InputMethodManager imm = imm();
            if (imm != null) imm.showSoftInput(this, 0);
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
        // Invalide le EditorBarTools parent pour que les états
        // activé/désactivé des boutons undo/redo se mettent à jour après
        // chaque édition.
        EditorBarTools.invalidateButtonsIn(getParent());
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
        // Rafraîchit les code actions (débouncées) — un appel depuis le
        // chemin de dessin à chaque frame bloquerait l'UI sur les appels
        // LSP.
        languageBridge.scheduleCodeActionsRefresh();
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
        // Referme la toolbar de sélection à l'édition — la sélection a pu
        // bouger ou être remplacée. Réinitialise aussi l'item pressé
        // (feedback) + les poignées, et annule le tap-dismiss différé —
        // committer un caret tapé AVANT l'édition déplacerait le caret
        // après l'insert.
        selectionToolbarVisible = false;
        selectionToolbarPressedIdx = -1;
        handlesVisible = false;
        inputHandler.cancelPendingTapDismiss();
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

    public void scrollToLine(int line) {
        scrollManager.scrollToLine(line);
    }

    public void scrollBy(float dy) {
        scrollManager.scrollBy(dy);
    }

    public void scrollHorizontallyBy(float dx) {
        scrollManager.scrollHorizontallyBy(dx);
    }

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

    // Méthodes de commodité pour la taille de police +/- depuis les icônes Canvas.
        public void increaseFontSize() {
        zoom.increaseFontSize();
    }

        public void decreaseFontSize() {
        zoom.decreaseFontSize();
    }

    // Bascule d'affichage des caractères non imprimables.
    public void setShowNonPrintable(boolean show) {
        this.showNonPrintable = show;
        invalidate();
    }
    public boolean isShowNonPrintable() { return showNonPrintable; }

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
     * Bascule du survol par appui long maintenu (parité Sora
     * "alwaysShowOnTouchHover").
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

    static int countLeadingWhitespace(String s) {
        int n = 0;
        while (n < s.length()) {
            char c = s.charAt(n);
            if (c == ' ' || c == '\t') n++;
            else break;
        }
        return n;
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

    /**
     * Fournisseur de complétion optionnel — l'hôte en branche un pour
     * alimenter des complétions conscientes du langage. Sans fournisseur,
     * la vue retombe sur la complétion par mots-clés intégrée pour
     * java/kotlin/xml/markdown.
     */
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
     * Rafraîchit le cache d'actions de code par ligne. Appelé depuis
     * {@link #onTextChanged} et après les grands déplacements de sélection.
     * Parcourt la plage de lignes visibles, appelle le resolver pour chacune
     * et stocke le résultat dans {@link #codeActionsByLine}.
     */
    void refreshCodeActions(int firstVisible, int lastVisible) {
        popupManager.refreshCodeActions(firstVisible, lastVisible);
    }

    /** Ouvre le popup d'actions de code pour la ligne donnée. */
    public void showCodeActions(int line) {
        popupManager.showCodeActions(line);
    }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié (portage NavMenu de CodeAssist)
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
     * Retourne true si la ligne donnée porte un diagnostic Error/Warning
     * (délégué à {@link EditorDiagnosticsLocator}). Utilisé par le dessin
     * de l'ampoule + le hit-test pour réserver l'ampoule aux lignes de
     * diagnostic uniquement (comportement aligné sur CodeAssist).
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
     * Formate tout le document via le resolver de formatage branché.
     * La requête LSP formatting quitte le thread UI ; le résultat est
     * appliqué en un seul step d'undo quand il arrive.
     *
     * @param callback exécuté sur le thread UI après application :
     *                 {@code Boolean.TRUE} si le texte a changé, FALSE sinon,
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

    /**
     * Ouvre le popup go-to-symbol. Appelle le resolver pour obtenir la
     * liste complète des symboles, puis filtre selon le texte de filtre
     * courant.
     */
    public void showGoToSymbol() {
        popupManager.showGoToSymbol();
    }

    /**
     * Exécute l'action « source.organizeImports » via le fournisseur
     * d'actions LSP (l'action est reconstruite par le serveur à la demande
     * et applique son WorkspaceEdit localement). Résout en arrière-plan —
     * le thread UI n'attend jamais.
     *
     * @param callback thread UI : TRUE si une action a été trouvée+exécutée,
     *                 FALSE sinon (aucun provider / action absente).
     */
    public void performOrganizeImports(java.util.function.Consumer<Boolean> callback) {
        documentActions.performOrganizeImports(callback);
    }

    /** Referme le popup go-to-symbol. */
    public void dismissGoToSymbol() {
        popupManager.dismissGoToSymbol();
    }

    /**
     * Met à jour le texte de filtre et re-filtre la liste de symboles.
     * Appelé par l'hôte à chaque frappe dans le champ de filtre du popup.
     */
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
     * <p>Utilise un filtrage côté client (motif CodeAssist) :
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

    /** True quand la feuille par-diagnostic (style CodeAssist) est ouverte. */
    public boolean isDiagnosticPopupVisible() { return diagnosticPopupVisible; }

    /**
     * Affiche la toolbar flottante de sélection (Copier/Couper/Coller/Tout
     * sélectionner) — une pastille dépolie ancrée au-dessus de l'extrémité
     * ACTIVE de la sélection (UX CodeAssist : la toolbar suit le doigt
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
     * le soulignement ondulé n'est pas tapable (parité CodeAssist : seule
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
     * Affiche un popup par diagnostic (motif DiagnosticSheet de CodeAssist).
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
