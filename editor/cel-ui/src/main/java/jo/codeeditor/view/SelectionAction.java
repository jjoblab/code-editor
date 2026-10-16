package jo.codeeditor.view;

import java.util.function.BooleanSupplier;

import jo.codeeditor.view.icons.EditorIcons;

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
 * <p><b>Icônes (B16)</b> — la pill reste en libellés (place comptée) ;
 * {@link #icon} est l'icône vectorielle de l'action DANS le menu de
 * débordement « ⋯ » (section « MORE » du menu contextuel unifié) :
 * sans icône, la rangée retombe sur l'ampoule générique — d'où le
 * retour terrain « toutes les icônes se ressemblent ». Les actions
 * intégrées ({@code BuiltinSelectionActions}) portent chacune la
 * leur ; une action d'hôte peut la choisir dans
 * {@link EditorIcons.Icon}.</p>
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
    /**
     * Facultatif (B16) : icône de la rangée de débordement « ⋯ » —
     * {@code null} retombe sur l'ampoule générique du menu.
     */
    public final EditorIcons.Icon icon;

    public SelectionAction(String id, String label, Runnable perform) {
        this(id, label, perform, null, null);
    }

    public SelectionAction(String id, String label, Runnable perform,
            BooleanSupplier enabled) {
        this(id, label, perform, enabled, null);
    }

    /**
     * Constructeur complet (B16) : {@code icon} est l'icône de la
     * rangée dans le menu de débordement « ⋯ » (null = ampoule).
     *
     * @param id      identifiant stable (retrait ciblé par
     *                {@code removeSelectionAction})
     * @param label   libellé affiché dans la pill
     * @param perform exécuté au tap (thread UI), puis la pill se referme
     * @param enabled facultatif : {@code false} → item estompé, tap rejeté
     * @param icon    facultatif : icône du débordement (null = ampoule)
     */
    public SelectionAction(String id, String label, Runnable perform,
            BooleanSupplier enabled, EditorIcons.Icon icon) {
        this.id = id != null ? id : "";
        this.label = label != null ? label : "";
        this.perform = perform;
        this.enabled = enabled;
        this.icon = icon;
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
