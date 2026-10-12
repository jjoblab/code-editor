package jo.codeeditor.view;

import java.util.ArrayList;
import java.util.List;

/**
 * Modèle de rangées du menu contextuel unifié (NavMenu).
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>navMenuRows</b> — construit la liste ordonnée des rangées du
 *       menu (pattern NavigationMenu.kt) : sections en majuscules affichées
 *       seulement si non-vides, ou « Nothing found in source. » ; en mode
 *       picker (RESULTS), la liste des cibles de l'option GO TO ;</li>
 *   <li><b>navMenuActionAt</b> — l'action d'une rangée TYPE_ACTION,
 *       résolue depuis sa section ;</li>
 *   <li><b>navMenuContentHeight</b> — hauteur totale du CONTENU (sans
 *       clamp viewport), source du clamp de scroll.</li>
 * </ul>
 *
 * <p>L'état du menu (options, quick fixes, intentions, cibles, mode
 * results) reste sur {@link EditorView} : il est peuplé par
 * {@code EditorPopupManager}, lu par le painter du chrome et le hit-tester
 * — EditorView conserve les relais package-privés. Le type
 * {@link EditorView.NavMenuRow} reste imbriqué dans la vue (référencé par
 * les tests et les collaborateurs).</p>
 */
class EditorNavMenuRows {

    private final EditorView view;

    EditorNavMenuRows(EditorView view) {
        this.view = view;
    }

    /**
     * Construit la liste ordonnée des rangées du menu (pattern
     * NavigationMenu.kt) : sections en majuscules affichées seulement si
     * non-vides, ou « Nothing found in source. ».
     */
    List<EditorView.NavMenuRow> navMenuRows() {
        List<EditorView.NavMenuRow> rows = new ArrayList<>();
        if (view.navMenuResultsMode) {
            if (view.navMenuTargets.isEmpty()) {
                rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_NOTHING, 0, 0, null));
            } else {
                for (int i = 0; i < view.navMenuTargets.size(); i++) {
                    rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_TARGET, i, 0,
                            view.navMenuTargets.get(i)));
                }
            }
            return rows;
        }
        boolean empty = view.navMenuOptions.isEmpty()
                && view.navMenuQuickFixes.isEmpty()
                && view.navMenuIntentions.isEmpty();
        if (empty) {
            rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_NOTHING, 0, 0, null));
            return rows;
        }
        if (!view.navMenuOptions.isEmpty()) {
            rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_HEADER, 0,
                    EditorView.NavMenuRow.SECTION_GO_TO, "GO TO"));
            for (int i = 0; i < view.navMenuOptions.size(); i++) {
                rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_OPTION, i,
                        EditorView.NavMenuRow.SECTION_GO_TO, view.navMenuOptions.get(i)));
            }
        }
        if (!view.navMenuQuickFixes.isEmpty()) {
            rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_HEADER, 0,
                    EditorView.NavMenuRow.SECTION_QUICK_FIXES, "QUICK FIXES"));
            for (int i = 0; i < view.navMenuQuickFixes.size(); i++) {
                rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_ACTION, i,
                        EditorView.NavMenuRow.SECTION_QUICK_FIXES, view.navMenuQuickFixes.get(i)));
            }
        }
        if (!view.navMenuIntentions.isEmpty()) {
            rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_HEADER, 0,
                    EditorView.NavMenuRow.SECTION_INTENTIONS, "INTENTIONS"));
            for (int i = 0; i < view.navMenuIntentions.size(); i++) {
                rows.add(new EditorView.NavMenuRow(EditorView.NavMenuRow.TYPE_ACTION, i,
                        EditorView.NavMenuRow.SECTION_INTENTIONS, view.navMenuIntentions.get(i)));
            }
        }
        return rows;
    }

    /**
     * L'action d'une rangée TYPE_ACTION, résolue depuis sa section.
     */
    EditorView.CodeAction navMenuActionAt(EditorView.NavMenuRow row) {
        return row.ref instanceof EditorView.CodeAction ? (EditorView.CodeAction) row.ref : null;
    }

    /**
     * Hauteur totale du CONTENU du menu (sans clamp viewport) en pixels :
     * headers + rangées. Source du clamp de scroll.
     */
    float navMenuContentHeight() {
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = EditorView.NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = EditorView.NAV_MENU_HEADER_HEIGHT_DP * density;
        float h = 0;
        for (EditorView.NavMenuRow r : navMenuRows()) {
            h += r.type == EditorView.NavMenuRow.TYPE_HEADER ? headerH : rowH;
        }
        return h;
    }
}
