package jo.codeeditor.theme;

import androidx.annotation.Nullable;

/**
 * Attribut de couleur du registre — une « case » réglable du thème.
 *
 * <p>Port du scheme de couleurs modifiable (audit §1D lot 4 #25, approche
 * amont <em>ab3e7d5d0</em> en version Java compacte) : chaque attribut a
 * une clé stable (sérialisée telle quelle dans le JSON du scheme), un
 * libellé (écran de réglages), un groupe (navigation de l'écran) et un
 * <b>parent</b> optionnel exprimant l'héritage visuel.</p>
 *
 * <p>Sémantique du parent : si un scheme ne donne pas d'opinion sur
 * l'enfant ET que la valeur de base de l'enfant est IDENTIQUE à celle de
 * son parent dans le thème de référence (l'enfant « héritait » déjà
 * visuellement), alors l'enfant suit la valeur résolue du parent. Sinon
 * il garde sa propre valeur. Exemple : {@code operator} (parent
 * {@code syntax.punct}) — dans un thème où opérateurs et ponctuation
 * partagent la même couleur, recolorer {@code syntax.punct} recolore
 * aussi les opérateurs sauf opinion contraire du scheme.</p>
 */
public final class ColorAttribute {

    /** Groupes d'attributs (navigation de l'écran de réglages). */
    public static final String GROUP_BACKGROUND = "fond";
    public static final String GROUP_GUTTER = "gouttière";
    public static final String GROUP_CARET = "curseur";
    public static final String GROUP_DIAGNOSTICS = "diagnostics";
    public static final String GROUP_SYNTAX = "syntaxe";
    public static final String GROUP_SEARCH = "recherche";
    public static final String GROUP_CHROME = "chrome";
    public static final String GROUP_TEXT = "texte";

    /** Clé stable de l'attribut (ex. {@code "syntax.keyword"}). */
    public final String key;
    /** Libellé lisible (écran de réglages). */
    public final String title;
    /** Groupe d'appartenance (une des constantes GROUP_*). */
    public final String group;
    /** Clé de l'attribut parent (héritage visuel), ou null. */
    @Nullable
    public final String parent;

    public ColorAttribute(String key, String title, String group, @Nullable String parent) {
        this.key = key;
        this.title = title;
        this.group = group;
        this.parent = parent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColorAttribute)) return false;
        return key.equals(((ColorAttribute) o).key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "ColorAttribute{" + key + "}";
    }
}
