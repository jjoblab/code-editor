package jo.codeeditor.view;

import java.util.function.BooleanSupplier;

/**
 * Une action injectée par l'hôte dans la barre flottante de sélection
 * (pill) de l'éditeur.
 *
 * <p>La pill canonique offre Cut / Copy / Paste / Select all et les
 * icônes Docs / Actions. Ce type permet à l'application hôte d'ajouter
 * ses propres actions d'édition contextuelles (commenter, dupliquer,
 * changer la casse, partager…) via
 * {@link EditorView#addSelectionAction(SelectionAction)} /
 * {@link EditorView#removeSelectionAction(String)} (ordre stable = ordre
 * d'injection) :</p>
 *
 * <ul>
 *   <li>les actions injectées n'apparaissent que lorsqu'une vraie
 *       sélection (non réduite au caret) existe ;</li>
 *   <li>elles se placent après les actions canoniques d'édition et
 *       avant les icônes Docs / Actions ;</li>
 *   <li>au-delà de ce qui tient dans la largeur de la vue, les actions
 *       restantes basculent dans le débordement « ⋯ » : le tap ouvre le
 *       menu contextuel unifié (NavMenu) qui les liste dans sa section
 *       « MORE » (zéro nouveau chrome) ;</li>
 *   <li>un tap exécute {@link #perform} puis referme la pill et masque
 *       les poignées — même contrat que Copy/Cut/Paste ;</li>
 *   <li>{@link #enabled} facultatif : l'action désactivée est dessinée
 *       estompée et son tap est rejeté sans refermer la pill.</li>
 * </ul>
 *
 * <p>Libellés seuls pour J1 (la pill canonique elle-même est en
 * libellés) ; icônes optionnelles différées à un chantier P2.</p>
 *
 * @author jjoblab
 */
public final class SelectionAction {

    /** Identifiant stable de l'action (retrait ciblé éventuel). */
    public final String id;
    /** Libellé affiché dans la pill (texte, mesuré pour la largeur). */
    public final String label;
    /** Exécuté au tap (thread UI). */
    public final Runnable perform;
    /** Facultatif : {@code false} → item estompé, tap rejeté. */
    public final BooleanSupplier enabled;

    public SelectionAction(String id, String label, Runnable perform) {
        this(id, label, perform, null);
    }

    public SelectionAction(String id, String label, Runnable perform,
            BooleanSupplier enabled) {
        this.id = id != null ? id : "";
        this.label = label != null ? label : "";
        this.perform = perform;
        this.enabled = enabled;
    }

    /** @return {@code true} sauf fournisseur d'activation à {@code false}. */
    public boolean isEnabled() {
        return enabled == null || enabled.getAsBoolean();
    }

    @Override
    public String toString() {
        return "SelectionAction(\"" + label + "\", id=" + id + ")";
    }
}
