package jo.codeeditor.navigation;

import java.util.*;

/**
 * Navigation go-to : déclaration, implémentation, déclaration de type,
 * super.
 * Résout les cibles de navigation et gère les résultats simples/multiples.
 * Reprend le design du module navigation de CodeAssist.
 */
public class NavigationMenu {

    // ── Enums & Types de données ─────────────────────────────────

    /** Type de cible de navigation. */
    public enum NavKind {
        DECLARATION,
        IMPLEMENTATION,
        TYPE_DECLARATION,
        SUPER
    }

    /** Un emplacement cible de navigation. */
    public static final class NavTarget {
        public final String path;
        public final int offset;
        public final String displayName;

        public NavTarget(String path, int offset, String displayName) {
            this.path = path != null ? path : "";
            this.offset = offset;
            this.displayName = displayName != null ? displayName : "";
        }

        public NavTarget(String path, int offset) {
            this(path, offset, "");
        }

        @Override
        public String toString() {
            return "NavTarget(\"" + path + ":" + offset + "\", \"" + displayName + "\")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NavTarget)) return false;
            NavTarget t = (NavTarget) o;
            return offset == t.offset && path.equals(t.path);
        }

        @Override
        public int hashCode() {
            return 31 * path.hashCode() + offset;
        }
    }

    /** Une option de navigation avec un libellé et ses cibles. */
    public static final class NavOption {
        public final String label;
        public final List<NavTarget> targets;

        public NavOption(String label, List<NavTarget> targets) {
            this.label = label != null ? label : "";
            this.targets = targets != null ? Collections.unmodifiableList(targets) : Collections.emptyList();
        }

        @Override
        public String toString() {
            return "NavOption(\"" + label + "\", targets=" + targets.size() + ")";
        }
    }

    /**
     * Un symbole de document pour la navigation go-to-symbol.
     * Reflète le DocumentSymbol LSP : un nom, un offset, un kind
     * (class / method / field / etc.) et le conteneur (classe déclarante
     * ou fichier).
     */
    public static final class Symbol {
        public final String name;
        public final int offset;
        public final String kind;
        public final String container;

        public Symbol(String name, int offset, String kind, String container) {
            this.name = name != null ? name : "";
            this.offset = offset;
            this.kind = kind != null ? kind : "";
            this.container = container != null ? container : "";
        }

        @Override
        public String toString() {
            return "Symbol(\"" + name + "\", offset=" + offset + ", kind=" + kind
                + ", container=" + container + ")";
        }
    }

    /**
     * Filtre une liste de symboles par préfixe, par correspondance de
     * préfixe insensible à la casse OU sous-séquence camel-hump
     * (ex. "gS" correspond à "getString"). Renvoie une nouvelle liste,
     * en préservant l'ordre d'entrée pour un classement stable.
     *
     * <p>Java pur et testable unitairement. Le popup go-to-symbol de la
     * vue appelle ceci à chaque frappe dans le champ de filtre.
     *
     * @param symbols la liste complète des symboles
     * @param prefix  le préfixe de filtre tapé (peut être vide → renvoie tout)
     * @return une nouvelle liste des symboles correspondants
     */
    public static List<Symbol> filter(List<Symbol> symbols, String prefix) {
        if (symbols == null) return Collections.emptyList();
        if (prefix == null || prefix.isEmpty()) return new ArrayList<>(symbols);
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        List<Symbol> out = new ArrayList<>();
        for (Symbol s : symbols) {
            if (s.name == null || s.name.isEmpty()) continue;
            String lowerName = s.name.toLowerCase(java.util.Locale.ROOT);
            if (lowerName.startsWith(lowerPrefix)
                || isCamelHumpSubsequence(s.name, prefix)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Renvoie true si chaque caractère de {@code prefix} correspond à un
     * caractère de {@code label}, dans l'ordre, chaque correspondance étant :
     * <ul>
     *   <li>Le premier caractère du préfixe (à toute position du libellé),</li>
     *   <li>Une position de début de mot (majuscule ou après _ / . / espace),</li>
     *   <li>Ou immédiatement après le caractère correspondant précédent
     *       (run contigu).</li>
     * </ul>
     * Ainsi "gS" (camel-hump) et "get" (préfixe contigu) correspondent tous
     * deux à "getString". L'algorithme est identique à
     * {@link jo.codeeditor.completion.CompletionSession#isCamelHumpSubsequence}.
     */
    public static boolean isCamelHumpSubsequence(String label, String prefix) {
        if (prefix == null || prefix.isEmpty()) return true;
        if (label == null || label.isEmpty()) return false;
        int pi = 0;
        boolean prevMatched = false;
        for (int li = 0; li < label.length() && pi < prefix.length(); li++) {
            char lc = label.charAt(li);
            char pc = prefix.charAt(pi);
            if (Character.toLowerCase(lc) == Character.toLowerCase(pc)) {
                boolean isWordStart = Character.isUpperCase(lc)
                    || li == 0
                    || (li > 0 && (label.charAt(li - 1) == '_'
                        || label.charAt(li - 1) == '.'
                        || Character.isWhitespace(label.charAt(li - 1))));
                if (pi == 0 || isWordStart || !prevMatched) {
                    pi++;
                    prevMatched = true;
                    continue;
                }
            }
            prevMatched = false;
        }
        return pi == prefix.length();
    }

    // ── État ───────────────────────────────────────────────────────

    /** Options de navigation courantes. */
    private List<NavOption> options = new ArrayList<>();

    /** Résolveur de navigation. */
    private NavigationResolver resolver;

    /** Listener de résultats de navigation. */
    private NavigationListener listener;

    /** Option courante sélectionnée (pour le picker). */
    private int selectedOption = 0;

    /** Indique si le picker est ouvert. */
    private boolean pickerOpen = false;

    /**
     * Interface de résolution des cibles de navigation depuis le serveur
     * de langage.
     */
    public interface NavigationResolver {
        /**
         * Résout les cibles de navigation du kind donné au caret.
         */
        List<NavTarget> resolve(NavKind kind, String filePath, int offset);
    }

    /**
     * Interface des événements de résultat de navigation.
     */
    public interface NavigationListener {
        /** Appelé quand une cible unique doit recevoir la navigation. */
        void onNavigate(NavTarget target);
        /** Appelé quand plusieurs cibles nécessitent un picker. */
        void onShowPicker(List<NavTarget> targets);
    }

    // ── Constructeurs ─────────────────────────────────────────────

    public NavigationMenu() {
        this(null, null);
    }

    public NavigationMenu(NavigationResolver resolver, NavigationListener listener) {
        this.resolver = resolver;
        this.listener = listener;
    }

    // ── Accesseurs ────────────────────────────────────────────────

    public List<NavOption> getOptions() { return Collections.unmodifiableList(options); }
    public boolean isPickerOpen() { return pickerOpen; }
    public int getSelectedOption() { return selectedOption; }

    public void setResolver(NavigationResolver resolver) { this.resolver = resolver; }
    public void setListener(NavigationListener listener) { this.listener = listener; }

    // ── Navigation ────────────────────────────────────────────────

    /**
     * Lance la navigation du kind donné à la position courante.
     * Si une seule cible est trouvée, navigue directement.
     * Si plusieurs cibles, ouvre un picker.
     *
     * @param kind     kind de navigation
     * @param filePath chemin du fichier courant
     * @param offset   offset courant du caret
     */
    public void runNav(NavKind kind, String filePath, int offset) {
        if (resolver == null) return;

        List<NavTarget> targets = resolver.resolve(kind, filePath, offset);
        if (targets == null || targets.isEmpty()) return;

        if (targets.size() == 1) {
            // Cible unique : navigation directe
            if (listener != null) {
                listener.onNavigate(targets.get(0));
            }
        } else {
            // Cibles multiples : affiche un picker
            if (listener != null) {
                listener.onShowPicker(targets);
            }
            openPicker(targets);
        }
    }

    /**
     * Ouvre le picker de navigation avec les cibles données.
     */
    private void openPicker(List<NavTarget> targets) {
        options = new ArrayList<>();
        for (NavTarget t : targets) {
            String label = t.displayName.isEmpty()
                ? t.path + ":" + t.offset
                : t.displayName;
            options.add(new NavOption(label, Collections.singletonList(t)));
        }
        pickerOpen = true;
        selectedOption = 0;
    }

    /**
     * Ferme le picker.
     */
    public void closePicker() {
        pickerOpen = false;
        selectedOption = 0;
    }

    /**
     * Déplace la sélection du picker.
     */
    public void movePickerSelection(int dir) {
        if (!pickerOpen || options.isEmpty()) return;
        selectedOption = Math.max(0, Math.min(options.size() - 1, selectedOption + dir));
    }

    /**
     * Sélectionne l'option courante du picker et navigue.
     */
    public void selectPickerOption() {
        if (!pickerOpen || selectedOption < 0 || selectedOption >= options.size()) return;
        NavOption option = options.get(selectedOption);
        closePicker();
        if (listener != null && !option.targets.isEmpty()) {
            listener.onNavigate(option.targets.get(0));
        }
    }

    /**
     * Navigue vers une option précise par index.
     */
    public void navigateToOption(int index) {
        if (index < 0 || index >= options.size()) return;
        NavOption option = options.get(index);
        closePicker();
        if (listener != null && !option.targets.isEmpty()) {
            listener.onNavigate(option.targets.get(0));
        }
    }

    // ── Réinitialisation ──────────────────────────────────────────

    public void reset() {
        options = new ArrayList<>();
        pickerOpen = false;
        selectedOption = 0;
    }
}
