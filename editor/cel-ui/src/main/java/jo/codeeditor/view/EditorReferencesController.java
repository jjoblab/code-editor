package jo.codeeditor.view;

import jo.codeeditor.navigation.NavigationMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Contrôleur du popup « find references » — état et logique du popup de
 * références (réutilise la mise en page du go-to-symbol).
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li>état du popup : visibilité, filtre, listes complète/filtrée de
 *       {@link NavigationMenu.Symbol}, index sélectionné, scroll ;</li>
 *   <li>résolution asynchrone : la requête LSP (jusqu'à 15 s !) est
 *       déportée sur {@code EditorPopupManager.FEATURE_EXECUTOR}, annulable
 *       par génération ; le résultat est rappliqué sur le thread UI via le
 *       handler de la vue ;</li>
 *   <li>navigation : l'acceptation d'une référence saute au même fichier
 *       (avec expansion du pli éventuel) ou délègue l'ouverture
 *       inter-fichiers au listener d'hôte
 *       ({@code EditorView.OnNavigateToFileListener}).</li>
 * </ul>
 *
 * <p>EditorView ne conserve que les wrappers publics de délégation —
 * l'état n'est lu par AUCUN autre collaborateur (peintres, popup manager,
 * tests), d'où son déplacement intégral ici.</p>
 */
class EditorReferencesController {

    private final EditorView view;

    /** Résolveur des références (ponté par l'hôte ou le SPI Language). */
    EditorView.ReferencesResolver resolver;

    /** Le popup est-il visible ? */
    private boolean popupVisible = false;
    /** Filtre courant du popup (préfixe insensible à la casse + camel-hump). */
    private String filter = "";
    /** Toutes les références résolues, converties en symboles navigables. */
    private final List<NavigationMenu.Symbol> all = new ArrayList<>();
    /** Sous-liste filtrée affichée. */
    private final List<NavigationMenu.Symbol> filtered = new ArrayList<>();
    /** Index de la rangée sélectionnée. */
    private int selected = 0;
    /** Décalage vertical de scroll de la liste (pixels). */
    private int scrollOffset = 0;

    /**
     * Génération de la recherche de références (annule les résolutions
     * obsolètes).
     */
    private volatile int generation = 0;

    EditorReferencesController(EditorView view) {
        this.view = view;
    }

    /** Branche le résolveur des références. */
    void setResolver(EditorView.ReferencesResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Déclenche le find-references au caret courant. Appelle le resolver
     * et affiche les résultats dans un popup (réutilise la mise en page du
     * go-to-symbol).
     *
     * <p>La résolution LSP (jusqu'à 15 s !) est déportée hors du thread UI,
     * annulable par génération.</p>
     */
    void show() {
        if (resolver == null || view.session == null) return;
        final jo.codeeditor.document.Selection sel = view.session.getSelection();
        final String text = view.session.getText().toString();
        final int gen = ++generation;
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            List<jo.codeeditor.lang.model.DefinitionLocation> targets = null;
            try {
                targets = resolver.resolve(text, sel.start);
            } catch (Exception ignored) {
            }
            final List<jo.codeeditor.lang.model.DefinitionLocation> resolved = targets;
            Runnable apply = () -> {
                if (gen != generation || view.session == null) return;
                if (resolved == null || resolved.isEmpty()) return;
                // Convertit en NavigationMenu.Symbol pour réutiliser l'UI du popup de symboles.
                all.clear();
                for (jo.codeeditor.lang.model.DefinitionLocation loc : resolved) {
                    String label = loc.displayName != null && !loc.displayName.isEmpty()
                        ? loc.displayName : loc.path;
                    all.add(new NavigationMenu.Symbol(label, loc.offset, "reference", loc.path));
                }
                filter = "";
                filtered.clear();
                filtered.addAll(all);
                selected = 0;
                scrollOffset = 0;
                popupVisible = true;
                view.invalidate();
            };
            android.os.Handler h = view.getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /** Referme le popup de références. */
    void dismiss() {
        popupVisible = false;
        view.invalidate();
    }

    /** Vrai pendant que le popup de références est affiché. */
    boolean isVisible() {
        return popupVisible;
    }

    /** Déplace la sélection du popup de références de delta. */
    boolean select(int delta) {
        if (!popupVisible || filtered.isEmpty()) return false;
        selected = Math.max(0,
            Math.min(filtered.size() - 1, selected + delta));
        view.invalidate();
        return true;
    }

    /** Accepte la référence sélectionnée et y navigue. */
    boolean accept() {
        if (!popupVisible || filtered.isEmpty()) return false;
        NavigationMenu.Symbol s = filtered.get(selected);
        // Si l'usage pointe vers un AUTRE fichier, on délègue la
        // navigation inter-fichiers à l'hôte (ouverture du fichier cible
        // au bon offset). Sinon saut direct dans le fichier courant.
        dismiss();
        String container = s.container;
        boolean sameFile = container == null || container.isEmpty()
            || container.equals(view.currentFilePath)
            || container.equals("file:///" + view.currentFilePath)
            || container.equals("file://" + view.currentFilePath);
        if (!sameFile && view.navigateToFileListener != null) {
            view.navigateToFileListener.navigateToFile(container, s.offset);
        } else {
            view.session.expandFoldAt(s.offset);
            view.session.setSelection(s.offset);
            view.scrollManager.scrollCaretIntoView();
        }
        view.invalidate();
        return true;
    }

    /** Met à jour le filtre du popup (réutilise NavigationMenu.filter). */
    void setFilter(String newFilter) {
        filter = newFilter != null ? newFilter : "";
        filtered.clear();
        filtered.addAll(NavigationMenu.filter(all, filter));
        if (selected >= filtered.size()) {
            selected = Math.max(0, filtered.size() - 1);
        }
        view.invalidate();
    }
}
