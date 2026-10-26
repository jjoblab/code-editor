package jo.codeeditor.view.state;

import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.List;

/**
 * État du popup de complétion — objet d'état extrait d'EditorView
 * (plan 01 « Décomposer EditorView », étape 2).
 *
 * <p>Possède ce qu'EditorView exposait jusque-là comme champs publics
 * librement mutables : la liste affichée, la sélection, la fenêtre de
 * scroll, le token/préfixe courants, la visibilité et le cache de
 * filtrage par token. Les invariants autrefois dispersés entre le
 * popup, l'input handler et les painters vivent désormais ICI :</p>
 * <ul>
 *   <li>la fenêtre de scroll ({@code scrollOffset}) suit la sélection
 *       dans les deux bornes — jamais la sélection hors fenêtre ;</li>
 *   <li>au remplacement de liste, l'item sélectionné survit par
 *       libellé quand il est encore présent ;</li>
 *   <li>le drag de la liste déplace sélection et fenêtre ensemble,
 *       bornés aux extrémités de la liste ;</li>
 *   <li>visibilité et contenu restent cohérents (reset sur liste vide
 *       → invisible ; clear → cache de base vidé aussi).</li>
 * </ul>
 *
 * <p><b>Thread-safety</b> : mutabilité confinée au thread UI
 * (publication) ; les painters lisent l'état PENDANT onDraw — jamais
 * de mutation en cours de frame. La géométrie (ancrage, rangées
 * visibles selon la hauteur de vue) reste du ressort du popup et de
 * la vue : les méthodes qui en dépendent reçoivent
 * {@code rowsVisible} en paramètre.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class CompletionState {

    // ── Constantes de géométrie du popup (ex-EditorView) ─────────
    /** Nombre maximal de rangées affichées avant scroll interne. */
    public static final int MAX_ROWS = 8;
    /** Hauteur d'une rangée en dp. */
    public static final float ROW_HEIGHT_DP = 28f;
    /** Largeur du popup en dp. */
    public static final float WIDTH_DP = 280f;

    // ── Panneau de documentation (4.4.0) ──────────────────────
    /** Plafond de lignes du panneau de documentation (miroir de
     *  SIGNATURE_DOC_MAX_LINES). */
    public static final int DOC_PANEL_MAX_LINES = 6;
    /**
     * Largeur minimale de VUE (dp) pour afficher le panneau de
     * documentation — en dessous (écran partagé, multi-paneau), le
     * panneau ne tient pas de façon lisible et la documentation se
     * réduit au glyphe de rangée.
     */
    public static final float DOC_PANEL_MIN_VIEW_WIDTH_DP = 340f;

    // ── État affiché ─────────────────────────────────────────────
    /** Items affichés (après filtrage). */
    public final List<jo.codeeditor.completion.CompletionSession.Item> items = new ArrayList<>();
    /** Index de l'item sélectionné dans {@link #items}. */
    public int selected = 0;
    /** Première rangée visible (scroll interne du popup). */
    public int scrollOffset = 0;
    /** Début du token de complétion dans le document, -1 si aucun. */
    public int tokenStart = -1;
    /** Préfixe courant du token (jamais null). */
    public String prefix = "";
    /** Popup affiché ? */
    public boolean visible = false;

    /**
     * (4.4.0) Lignes plafonnées du panneau de documentation de l'item
     * SÉLECTIONNÉ — données de RENDU transitoires : recalculées par
     * l'ancrage (source unique de géométrie) à chaque frame, lues par
     * le peintre. Vide = pas de panneau (le peintre dessine alors le
     * glyphe de rangée si la vue est trop étroite).
     */
    public final List<String> docPanelLines = new ArrayList<>();

    // ── Cache de filtrage par token ──────────────────────────────
    // Le provider retourne un jeu de base pour un token ; les frappes
    // suivantes du MÊME token filtrent en cache par préfixe (insensible
    // casse + fuzzy) au lieu de re-requêter — réactif pendant qu'un
    // serveur lent rattrape.
    /** Jeu de base non filtré du token courant (réaffecté à chaque livraison). */
    public List<jo.codeeditor.completion.CompletionSession.Item> baseItems = new ArrayList<>();
    /** Token du cache de base, -1 si invalide. */
    public int baseTokenStart = -1;
    /**
     * La base du token courant a été signalée TRONQUÉE par le
     * fournisseur (backend qui plafonne ses réponses) : le filtrage
     * local travaille sur une liste partielle. Faux (défaut) = base
     * réputée complète, filtrage local pur.
     */
    public boolean baseIncomplete = false;

    /**
     * Remplacement complet du jeu affiché (voie
     * {@code EditorPopupManager.setCompletionItems} — pilotage hôte).
     */
    public void reset(List<jo.codeeditor.completion.CompletionSession.Item> newItems,
                      int newTokenStart, String newPrefix) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        tokenStart = newTokenStart;
        prefix = newPrefix == null ? "" : newPrefix;
        selected = 0;
        scrollOffset = 0;
        visible = !items.isEmpty();
    }

    /**
     * Remplacement de la liste filtrée du MÊME token : l'item
     * sélectionné survit par libellé, la fenêtre de scroll suit la
     * sélection, le popup devient visible.
     */
    public void refilter(List<jo.codeeditor.completion.CompletionSession.Item> filtered,
                         int newTokenStart, String newPrefix, int rowsVisible) {
        String prevSelectedLabel = (selected >= 0 && selected < items.size())
                ? items.get(selected).label : null;
        items.clear();
        items.addAll(filtered);
        tokenStart = newTokenStart;
        prefix = newPrefix;
        int newSel = 0;
        if (prevSelectedLabel != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).label.equals(prevSelectedLabel)) {
                    newSel = i;
                    break;
                }
            }
        }
        selected = newSel;
        followSelection(rowsVisible);
        visible = true;
    }

    /**
     * Fermeture complète : plus rien d'affiché ET cache de base vidé
     * (un dismiss invalide le token en cache — rouvrir doit re-requêter).
     */
    public void clear() {
        visible = false;
        items.clear();
        baseItems.clear();
        baseTokenStart = -1;
        baseIncomplete = false;
    }

    /**
     * Masque SANS casser le cache de base (livraison vide pour le token
     * courant : le token reste valide, seule la liste est vide).
     */
    public void softHide() {
        visible = false;
    }

    /**
     * Sélection +/- 1 avec fenêtre solidaire.
     *
     * @return true si la sélection a bougé (l'appelant décide du
     *         repaint), false aux bornes de la liste.
     */
    public boolean select(int delta, int rowsVisible) {
        int newSel = selected + delta;
        if (newSel < 0 || newSel >= items.size()) {
            return false;
        }
        selected = newSel;
        followSelection(rowsVisible);
        return true;
    }

    /**
     * Drag vertical de la liste : la fenêtre et la sélection bougent
     * ensemble, bornées aux extrémités.
     *
     * @return true si la fenêtre a bougé (repaint), false si le drag
     *         bute sur une borne.
     */
    public boolean scrollBy(int rowDelta, int rowsVisible) {
        int maxOffset = Math.max(0, items.size() - rowsVisible);
        int newOffset = Math.max(0, Math.min(scrollOffset + rowDelta, maxOffset));
        if (newOffset == scrollOffset) {
            return false;
        }
        scrollOffset = newOffset;
        selected = Math.max(0, Math.min(selected + rowDelta, items.size() - 1));
        return true;
    }

    /**
     * La fenêtre de scroll couvre toujours la sélection (les deux
     * bornes), sans dépasser la fin de liste.
     */
    public void followSelection(int rowsVisible) {
        if (selected < scrollOffset) {
            scrollOffset = selected;
        } else if (selected >= scrollOffset + rowsVisible) {
            scrollOffset = selected - rowsVisible + 1;
        }
        int maxOffset = Math.max(0, items.size() - rowsVisible);
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }
    }
}
