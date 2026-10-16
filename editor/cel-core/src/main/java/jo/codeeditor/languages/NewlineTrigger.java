package jo.codeeditor.languages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Déclencheur d'indentation au saut de ligne (ADR-002, chantier C1 —
 * étape 3) : quand la ligne avant le caret <b>se termine</b> par un
 * suffixe donné, la ligne suivante est indentée d'un niveau de plus.
 *
 * <p>Règle déclarative immuable, portée par le
 * {@link LanguageProfile} ({@code builder.newlineTrigger(...)}) et
 * consommée par {@code SmartNewline} — le {@code switch} par nom de
 * langage canonique (python/yaml/shell/lua) vit désormais dans la
 * table :</p>
 * <ul>
 *   <li><b>Python</b> : {@code onSuffix(":", "def ", "class ", "if ", …)}
 *       — la ligne doit finir par {@code :} ET commencer par un
 *       mot-clé de bloc (évite les dictionnaires {@code {1: 2}}) ;</li>
 *   <li><b>YAML</b> : {@code onSuffix(":")} — une clé sans valeur
 *       ({@code key:}) attend un bloc imbriqué, pas
 *       {@code key: value} ;</li>
 *   <li><b>Shell / Lua</b> : {@code onSuffix("then")},
 *       {@code onSuffix("do")} — en-têtes de bloc par mots-clés.</li>
 * </ul>
 *
 * <p>La correspondance s'applique à la ligne <b>trimée</b> (comportement
 * historique du lot 2/3 de l'audit v3.41.0, repris octet pour octet).</p>
 */
public final class NewlineTrigger {

    /** Suffixe de fin de ligne qui déclenche l'indentation (jamais vide). */
    public final String lineSuffix;
    /**
     * Préfixes de tête de ligne requis (mots-clés de bloc), ou liste
     * vide quand aucun prérequis. La ligne doit commencer par l'un
     * d'eux pour que le déclencheur s'active.
     */
    public final List<String> linePrefixes;

    private NewlineTrigger(String lineSuffix, List<String> linePrefixes) {
        this.lineSuffix = lineSuffix;
        this.linePrefixes = Collections.unmodifiableList(linePrefixes);
    }

    /**
     * Déclencheur sur un suffixe de fin de ligne, sans prérequis de
     * tête de ligne (sémantique YAML : {@code key:}).
     */
    public static NewlineTrigger onSuffix(String lineSuffix) {
        return new NewlineTrigger(normalize(lineSuffix),
                Collections.emptyList());
    }

    /**
     * Déclencheur sur un suffixe de fin de ligne exigeant que la ligne
     * commence par l'un des préfixes donnés (sémantique Python :
     * {@code def foo():}, pas {@code d = {1: 2}}). Les préfixes vides
     * ou nuls sont ignorés.
     */
    public static NewlineTrigger onSuffix(String lineSuffix,
            String... linePrefixes) {
        List<String> prefixes = new ArrayList<>();
        if (linePrefixes != null) {
            for (String p : linePrefixes) {
                if (p != null && !p.isEmpty()) prefixes.add(p);
            }
        }
        return new NewlineTrigger(normalize(lineSuffix), prefixes);
    }

    /**
     * Vrai quand la ligne (déjà trimée) matche ce déclencheur : elle
     * se termine par {@link #lineSuffix} et, si des préfixes sont
     * déclarés, commence par l'un d'eux.
     */
    public boolean matches(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isEmpty()) return false;
        if (!trimmedLine.endsWith(lineSuffix)) return false;
        if (linePrefixes.isEmpty()) return true;
        for (String p : linePrefixes) {
            if (trimmedLine.startsWith(p)) return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NewlineTrigger)) return false;
        NewlineTrigger that = (NewlineTrigger) o;
        return lineSuffix.equals(that.lineSuffix)
                && linePrefixes.equals(that.linePrefixes);
    }

    @Override
    public int hashCode() {
        return 31 * lineSuffix.hashCode() + linePrefixes.hashCode();
    }

    @Override
    public String toString() {
        return "NewlineTrigger(suffix='" + lineSuffix
                + (linePrefixes.isEmpty() ? "" : "', prefixes=" + linePrefixes)
                + "')";
    }

    private static String normalize(String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException("lineSuffix is empty");
        }
        return s;
    }
}
