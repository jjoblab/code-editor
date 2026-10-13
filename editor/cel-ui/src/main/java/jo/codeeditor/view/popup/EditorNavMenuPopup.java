package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import jo.codeeditor.navigation.NavigationMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu contextuel unifié : résolution
 * des options GO TO hors thread UI (Declaration, Implementations, Type
 * declaration, Super), tri quick-fixes/intentions de la ligne du
 * caret, navigation directe ou via picker en mode RESULTS. Corps
 * déplacés d'EditorPopupManager à l'identique (adaptation des accès
 * délégués).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorNavMenuPopup {

    private final EditorView view;

    EditorNavMenuPopup(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié
    // ════════════════════════════════════════════════════════════════

    /** Génération du menu contextuel unifié (annulation async). */
    private volatile int navMenuGeneration = 0;

    /**
     * Ouvre le menu contextuel unifié (toolbar de sélection → Actions ⋯).
     * Le menu contextuel : les options GO TO
     * applicables au caret sont résolues HORS thread UI dans l'ordre des
     * les types d'entrée — Declaration (definitionResolver),
     * Implementations (implementationsResolver), Type declaration
     * (typeDefinitionResolver), Super (superResolver) — puis les
     * quick-fixes/intentions : cache de la ligne s'il est FRAIS (une
     * résolution récente l'a peuplé), sinon résolution À LA DEMANDE au
     * caret dans la même tâche (le balayage par ligne n'existe plus).
     * Le menu apparaît quand tout est prêt ; une nouvelle
     * demande invalide la livraison (génération).
     */
    void showNavMenu(int line, int offset) {
        if (view.session == null) return;
        // UX-1 (J3) : la section « MORE » (actions injectées non
        // affichées) n'est peuplé QUE par la métrique de la pill — depuis
        // la pill, la métrique vient d'être calculée au tap (source
        // unique, fraîche) ; depuis toute AUTRE entrée (raccourci clavier,
        // API), un débordement résiduel d'un affichage précédent serait
        // PÉRIMÉ → purge.
        if (!view.selectionToolbarVisible) {
            view.popupManager.selActions.overflow.clear();
        }
        final String text = view.session.getText();
        final int gen = ++navMenuGeneration;
        final int fLine = line;
        final int fOffset = offset;
        // Cache-frais d'abord (peuplé par une résolution récente au caret).
        final List<EditorView.CodeAction> lineActions =
                view.codeActionsByLine.containsKey(fLine)
                        ? new ArrayList<>(view.codeActionsByLine.get(fLine))
                        : null;
        final EditorView.DefinitionResolver defResolver = view.definitionResolver;
        final EditorView.ImplementationsResolver implResolver =
                view.implementationsResolver;
        final EditorView.TypeDefinitionResolver typeDefResolver = view.typeDefinitionResolver;
        final EditorView.SuperResolver superResolver = view.superResolver;
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            // QUICK FIXES / INTENTIONS — résolution à la demande au caret
            // quand le cache frais est absent (rien d'autre ne peuple la map).
            List<EditorView.CodeAction> actions = lineActions;
            if (actions == null && view.codeActionsResolver != null) {
                try {
                    actions = view.codeActionsResolver.resolveAtCaret(
                            text, fOffset, fOffset, fOffset);
                } catch (Exception e) {
                    jo.codeeditor.util.EditorLog.warn("EditorNavMenu",
                        "résolution des actions de code échouée", e);
                }
            }
            List<NavigationMenu.NavOption> options = new ArrayList<>();
            // GO TO — Declaration (textDocument/definition) — NavKind #1.
            if (defResolver != null) {
                List<jo.codeeditor.lang.model.DefinitionLocation> targets = null;
                try {
                    targets = defResolver.resolve(text, fOffset);
                } catch (Exception e) {
                    jo.codeeditor.util.EditorLog.warn("EditorNavMenu",
                        "résolution des définitions échouée", e);
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Declaration", navTargets));
                }
            }
            // GO TO — Implementations (textDocument/implementation)
            // — NavKind #2 : les héritiers DIRECTS du type en contexte.
            if (implResolver != null) {
                List<jo.codeeditor.lang.model.DefinitionLocation> targets = null;
                try {
                    targets = implResolver.resolve(text, fOffset);
                } catch (Exception e) {
                    jo.codeeditor.util.EditorLog.warn("EditorNavMenu",
                        "résolution des implémentations échouée", e);
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Implementations", navTargets));
                }
            }
            // GO TO — Type declaration (textDocument/typeDefinition)
            // — NavKind #3.
            if (typeDefResolver != null) {
                List<jo.codeeditor.lang.model.DefinitionLocation> targets = null;
                try {
                    targets = typeDefResolver.resolve(text, fOffset);
                } catch (Exception e) {
                    jo.codeeditor.util.EditorLog.warn("EditorNavMenu",
                        "résolution des déclarations de type échouée", e);
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Type declaration", navTargets));
                }
            }
            // GO TO — Super (textDocument/superDefinition) —
            // NavKind #4 : le membre outrepassé / les supertypes DIRECTS.
            if (superResolver != null) {
                List<jo.codeeditor.lang.model.DefinitionLocation> targets = null;
                try {
                    targets = superResolver.resolve(text, fOffset);
                } catch (Exception e) {
                    jo.codeeditor.util.EditorLog.warn("EditorNavMenu",
                        "résolution des super-membres échouée", e);
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Super", navTargets));
                }
            }
            // QUICK FIXES vs INTENTIONS — kind « quickfix » vs le reste
            // (parité NavigationMenu.kt : UiActionKind.QUICK_FIX / autres).
            final List<EditorView.CodeAction> quickFixes = new ArrayList<>();
            final List<EditorView.CodeAction> intentions = new ArrayList<>();
            if (actions != null) {
                for (EditorView.CodeAction a : actions) {
                    if (a == null) continue;
                    if (a.kind != null && a.kind.startsWith("quickfix")) {
                        quickFixes.add(a);
                    } else {
                        intentions.add(a);
                    }
                }
            }
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != navMenuGeneration || view.session == null) return;
                view.navMenuLine = fLine;
                view.navMenuCaretOffset = fOffset;
                view.navMenuOptions = options;
                view.navMenuQuickFixes = quickFixes;
                view.navMenuIntentions = intentions;
                view.navMenuResultsMode = false;
                view.navMenuTargets = new ArrayList<>();
                view.navMenuScrollY = 0;
                view.navMenuPressedIdx = -1;
                view.navMenuVisible = true;
                view.invalidate();
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /**
     * DefinitionLocation → NavTarget. Le libellé picker : le
     * {@code displayName} s'il est SIGNIFICATIF (les providers
     * Implementations/Super transportent « Simple  ·  pkg » /
     * « name  ·  Super »), sinon le nom COURT du fichier — un displayName
     * qui EST le chemin/URI (le provider definition l'envoie brut) tombe
     * sur le nom court, plus lisible que
     * « file:///storage/emulated/0/… ».
     */
    private static List<NavigationMenu.NavTarget> toNavTargets(
            List<jo.codeeditor.lang.model.DefinitionLocation> targets) {
        List<NavigationMenu.NavTarget> out = new ArrayList<>();
        if (targets == null) return out;
        for (jo.codeeditor.lang.model.DefinitionLocation loc : targets) {
            if (loc == null) continue;
            String label = loc.displayName != null && !loc.displayName.isEmpty()
                    ? loc.displayName : shortFileName(loc.path);
            if (label.startsWith("file://") || label.startsWith("/")
                    || label.equals(loc.path)) {
                label = shortFileName(loc.path);
            }
            out.add(new NavigationMenu.NavTarget(loc.path, loc.offset, label));
        }
        return out;
    }

    /** Le dernier segment d'un chemin/URI (« MainActivity.java »). */
    static String shortFileName(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Referme le menu contextuel unifié. */
    void dismissNavMenu() {
        navMenuGeneration++;
        // B7 : photographie le menu AVANT de nettoyer son état — le
        // fantôme s'efface en 110 ms (fondus d'apparition/disparition).
        view.popupFade.snuff(jo.codeeditor.view.popup.EditorPopupFade.Kind.NAV_MENU);
        view.navMenuVisible = false;
        view.navMenuPressedIdx = -1;
        view.invalidate();
    }

    /**
     * Ouvre le menu en mode DÉBORDEMENT de la pill (T1) : liste
     * SYNCHRONE des actions injectées non affichées (section « MORE »
     * de {@code EditorNavMenuRows.navMenuRows()}, alimentée par la
     * métrique de la pill — la source unique de layout), SANS résolution
     * GO TO/quick-fixes. Invalide toute livraison async en cours
     * (génération).
     */
    void showSelectionOverflow(int line, int offset) {
        if (view.session == null) return;
        if (line < 0) return;
        navMenuGeneration++;
        view.navMenuLine = line;
        view.navMenuCaretOffset = offset;
        view.navMenuOptions = new ArrayList<>();
        view.navMenuQuickFixes = new ArrayList<>();
        view.navMenuIntentions = new ArrayList<>();
        view.navMenuResultsMode = false;
        view.navMenuTargets = new ArrayList<>();
        view.navMenuScrollY = 0;
        view.navMenuPressedIdx = -1;
        view.navMenuVisible = true;
        view.invalidate();
    }

    /**
     * Pick d'une option GO TO : cible unique → navigation directe ;
     * plusieurs cibles → bascule en mode RESULTS (picker, parité
     * {@code NavMenuState.Results}).
     */
    void navMenuPickOption(NavigationMenu.NavOption option) {
        if (option == null) return;
        if (option.targets.size() == 1) {
            navMenuNavigate(option.targets.get(0));
            return;
        }
        view.navMenuTargets = new ArrayList<>(option.targets);
        view.navMenuResultsMode = true;
        view.navMenuScrollY = 0;
        view.navMenuPressedIdx = -1;
        view.invalidate();
    }

    /** Pick d'une action (quick fix ou intention) : applique + ferme. */
    void navMenuPickAction(EditorView.CodeAction action) {
        if (action == null) return;
        dismissNavMenu();
        if (action.apply != null) action.apply.run();
    }

    /**
     * Pick d'une cible (mode RESULTS) : navigation directe (parité
     * {@code onPick}).
     */
    void navMenuPickTarget(NavigationMenu.NavTarget target) {
        if (target == null) return;
        navMenuNavigate(target);
    }

    /**
     * Navigue vers une cible : même fichier → saut du caret +
     * scroll ; autre fichier → {@code definitionListener} (l'hôte ouvre le
     * fichier — via le resolver de définitions).
     */
    private void navMenuNavigate(NavigationMenu.NavTarget target) {
        dismissNavMenu();
        if (view.session == null || target == null) return;
        String path = target.path != null ? target.path : "";
        boolean sameFile = path.isEmpty()
                || path.equals(view.currentFilePath)
                || path.equals("file:///" + view.currentFilePath);
        if (sameFile) {
            view.session.setSelection(Math.max(0, Math.min(target.offset,
                    view.session.getDocument().length())));
            view.scrollManager.scrollCaretIntoView();
            view.invalidate();
        } else if (view.definitionListener != null) {
            List<jo.codeeditor.lang.model.DefinitionLocation> locations = new ArrayList<>();
            locations.add(new jo.codeeditor.lang.model.DefinitionLocation(
                    target.path, target.offset, target.displayName));
            view.definitionListener.onDefinitionRequested(locations);
        }
    }
}
