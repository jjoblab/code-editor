package jo.codeeditor.navigation;

import java.util.*;

/**
 * Types et filtres de la navigation go-to (déclaration, implémentation,
 * déclaration de type, super) et du go-to-symbol.
 *
 * <p>M-02 (audit 3.69.0) : la machinerie de PICKER instance (résolution,
 * navigation directe/multiple, sélection d'option) n'avait AUCUN
 * appelant — le popup de la vue ({@code EditorNavMenuPopup}) pilote ses
 * propres lignes à partir des types exposés ici. La classe est
 * désormais un porte-type utilitaire : symboles, cibles, options,
 * filtrage camel-hump — tout ce qui est réellement consommé.</p>
 */
public final class NavigationMenu {

    private NavigationMenu() {
        // Porte-type utilitaire : aucun état d'instance.
    }

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

    /**
     * Une option de navigation avec un libellé et ses cibles.
     *
     * <p>Le libellé est AFFICHÉ (et reste traduisible par l'hôte) ;
     * l'identifiant {@link #key} est STABLE — l'icône du menu de
     * navigation s'y accroche, jamais au libellé (une option au
     * libellé traduit gardait son icône par défaut : la clé faisait
     * défaut).</p>
     */
    public static final class NavOption {
        /** Clé stable : aller à la déclaration. */
        public static final String KEY_DECLARATION = "declaration";
        /** Clé stable : aller aux implémentations. */
        public static final String KEY_IMPLEMENTATIONS = "implementations";
        /** Clé stable : aller à la déclaration de type. */
        public static final String KEY_TYPE_DECLARATION = "typeDeclaration";
        /** Clé stable : aller au super (membre outrepassé / supertypes). */
        public static final String KEY_SUPER = "super";

        public final String label;
        /** Identifiant stable de l'option (cf. constantes KEY_*), vide si générique. */
        public final String key;
        public final List<NavTarget> targets;

        public NavOption(String label, List<NavTarget> targets) {
            this(label, "", targets);
        }

        public NavOption(String label, String key, List<NavTarget> targets) {
            this.label = label != null ? label : "";
            this.key = key != null ? key : "";
            this.targets = targets != null ? Collections.unmodifiableList(targets) : Collections.emptyList();
        }

        @Override
        public String toString() {
            return "NavOption(\"" + label + "\", key=" + key + ", targets=" + targets.size() + ")";
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
     *   <li>Une position de début de mot (majuscule ou après _ / . / :
     *       / / @ / espace),</li>
     *   <li>Ou immédiatement après le caractère correspondant précédent
     *       (run contigu).</li>
     * </ul>
     * Ainsi "gS" (camel-hump) et "get" (préfixe contigu) correspondent tous
     * deux à "getString". ★ 4.3.0 : la vérification est unifiée dans
     * {@link jo.codeeditor.completion.CompletionMatcher#camelHumpSubsequence(String, String)}
     * — cette méthode est le relais public historique (le code et la
     * Javadoc se contredisaient sur les runs contigus : la définition
     * canonique documentée ici fait foi, le code l'applique enfin).
     */
    public static boolean isCamelHumpSubsequence(String label, String prefix) {
        return jo.codeeditor.completion.CompletionMatcher.camelHumpSubsequence(label, prefix);
    }

    // ── État ───────────────────────────────────────────────────────
}
