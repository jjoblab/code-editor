package jo.codeeditor.view;

import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * État des <b>actions de sélection injectées par l'hôte</b> (T1, lot 2) :
 * la pill canonique reste courte, le reste est injectable.
 *
 * <p>Porteur de l'état + constantes d'action — délibérément HORS
 * d'EditorView (budget de champs/lignes tenu) : une seule instance vit
 * sur {@code EditorPopupManager} ({@code view.popupManager.selActions}),
 * accessible des packages view/popup/input/render via ses champs
 * {@code @RestrictTo} publics. L'API hôte est
 * {@link EditorView#addSelectionAction(SelectionAction)} /
 * {@link EditorView#removeSelectionAction(String)} (ordre stable = ordre
 * d'injection).</p>
 *
 * <ul>
 *   <li>{@link #actions} — la liste injectée, ordre d'injection ;</li>
 *   <li>{@link #overflow} — les actions NON affichées dans la pill
 *       (calculées par {@code EditorPopupAnchors.selectionToolbarMetrics}
 *       — la source unique de layout), listées par le menu contextuel
 *       unifié (section « MORE ») après un tap sur « ⋯ ».</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorSelectionActions {

    /** Code d'action d'un item de pill injecté par l'hôte. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_INJECTED = 6;
    /** Code d'action de l'item « ⋯ » (débordement → NavMenu « MORE »). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SEL_ACT_OVERFLOW = 7;

    /** Les actions injectées, en ordre stable d'injection. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final List<SelectionAction> actions = new ArrayList<>();

    /**
     * Les actions non affichées dans la pill (débordement) — peuplée par
     * la métrique partagée de la pill, consommée par la section « MORE »
     * du NavMenu.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public final List<SelectionAction> overflow = new ArrayList<>();

    /** Ajoute une action (null ignoré). */
    void add(SelectionAction action) {
        if (action != null) actions.add(action);
    }

    /** Retire la première action d'identifiant {@code id} (rien si absent). */
    void remove(String id) {
        if (id == null) return;
        for (Iterator<SelectionAction> it = actions.iterator(); it.hasNext(); ) {
            if (id.equals(it.next().id)) {
                it.remove();
                return;
            }
        }
    }
}
