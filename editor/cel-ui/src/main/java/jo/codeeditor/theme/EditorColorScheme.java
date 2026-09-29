package jo.codeeditor.theme;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Scheme de couleurs modifiable par l'utilisateur (audit §1D lot 4 #25 —
 * port compact de l'approche amont <em>ab3e7d5d0</em>).
 *
 * <p><b>Principe</b> : un scheme ne décrit JAMAIS un thème complet — il
 * porte deux maps éparses (une pour le mode sombre, une pour le mode
 * clair) de clé de registre → couleur ARGB. Une clé ABSENTE signifie
 * « pas d'opinion » : la valeur du thème de référence s'applique. Un
 * scheme ne contient donc que ce que l'utilisateur a réellement
 * personnalisé, ce qui rend la sérialisation naturellement plate et
 * petite (export JSON des seuls overrides).</p>
 *
 * <p><b>Résolution en cascade</b> ({@link #resolve(EditorTheme, boolean)},
 * eager, une passe puisque le registre ordonne les parents avant les
 * enfants) :
 * <ol>
 *   <li>override du scheme (mode actif) ;</li>
 *   <li>sinon, si l'attribut a un parent ET que sa valeur de base est
 *       IDENTIQUE à celle du parent dans le thème de référence
 *       (l'enfant « héritait » déjà visuellement) → valeur résolue du
 *       parent ;</li>
 *   <li>sinon, valeur du thème de référence ;</li>
 *   <li>plancher garanti : {@code text.foreground} est présent dans le
 *       registre et sert de racine aux attributs non fixés.</li>
 * </ol></p>
 *
 * <p><b>Persistance</b> : chaîne JSON plate
 * {@code {schema:1, id, name, basedOn, dark:{key:"#AARRGGBB"}, light:{…}}}
 * — seuls les overrides figurent dans le document. L'hôte stocke cette
 * chaîne où il veut (SharedPreferences, fichier…).</p>
 */
public final class EditorColorScheme {

    /** Version du format de sérialisation. */
    public static final int SCHEMA = 1;

    private final String id;
    private String name;
    /** Identifiant du thème de référence (documentation + écran de réglages). */
    private String basedOn;
    /** Overrides mode sombre — clé de registre → ARGB. */
    private final Map<String, Integer> dark = new LinkedHashMap<>();
    /** Overrides mode clair — clé de registre → ARGB. */
    private final Map<String, Integer> light = new LinkedHashMap<>();

    private EditorColorScheme(String id, String name, String basedOn) {
        this.id = id;
        this.name = name;
        this.basedOn = basedOn;
    }

    // ── Construction ─────────────────────────────────────────────

    /** Scheme vierge basé sur un thème de référence donné (identifiant libre). */
    public static EditorColorScheme create(@NonNull String id, @NonNull String name,
                                           @NonNull String basedOnId) {
        return new EditorColorScheme(id, name, basedOnId);
    }

    /** Scheme vierge « sans opinion », basé sur le thème sombre par défaut. */
    public static EditorColorScheme createDefault() {
        return new EditorColorScheme("default", "Personnalisé", "dark");
    }

    // ── Opinion sur une case ─────────────────────────────────────

    /**
     * Pose une couleur pour la clé de registre donnée dans le mode indiqué.
     *
     * @throws IllegalArgumentException si la clé est inconnue du registre
     */
    public void setColor(@NonNull String key, int argb, boolean darkMode) {
        if (!ColorAttributes.isValidKey(key)) {
            throw new IllegalArgumentException("Clé de couleur inconnue : " + key);
        }
        (darkMode ? dark : light).put(key, argb);
    }

    /** Retire toute opinion sur la clé (retour à la valeur du thème de référence). */
    public void unsetColor(@NonNull String key, boolean darkMode) {
        (darkMode ? dark : light).remove(key);
    }

    /** Override actuel pour la clé (mode indiqué), ou null = pas d'opinion. */
    @Nullable
    public Integer getColor(@NonNull String key, boolean darkMode) {
        return (darkMode ? dark : light).get(key);
    }

    /** Clés personnalisées pour le mode indiqué (ordre d'insertion). */
    public List<String> overriddenKeys(boolean darkMode) {
        return new ArrayList<>((darkMode ? dark : light).keySet());
    }

    /** Nombre total d'overrides du mode indiqué. */
    public int overrideCount(boolean darkMode) {
        return (darkMode ? dark : light).size();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    /** Identifiant du thème de référence (ex. {@code "dark"}, {@code "nord"}). */
    public String getBasedOn() {
        return basedOn;
    }

    public void setBasedOn(@NonNull String basedOn) {
        this.basedOn = basedOn;
    }

    // ── Résolution ───────────────────────────────────────────────

    /**
     * Résout le scheme sur un thème de référence et produit le thème
     * effectif. Eager, une passe (les parents précèdent les enfants dans
     * le registre).
     *
     * @param base     thème de référence (celui que le scheme « décore »)
     * @param darkMode mode actif — sélectionne la map d'overrides
     */
    @NonNull
    public EditorTheme resolve(@NonNull EditorTheme base, boolean darkMode) {
        Map<String, Integer> overrides = darkMode ? dark : light;
        Map<String, Integer> resolved = new LinkedHashMap<>();
        EditorTheme.Builder builder = EditorTheme.from(base);

        for (ColorAttribute a : ColorAttributes.all()) {
            Integer override = overrides.get(a.key);
            if (override != null) {
                resolved.put(a.key, override);
            } else if (a.parent != null) {
                int baseVal = ColorAttributes.get(base, a.key);
                int parentBase = ColorAttributes.get(base, a.parent);
                // L'enfant suivait déjà visuellement le parent dans la base :
                // il suit sa valeur RÉSOLUE (recolorer le parent recolore
                // l'enfant). Sinon l'enfant garde sa propre valeur.
                Integer parentResolved = resolved.get(a.parent);
                resolved.put(a.key, baseVal == parentBase && parentResolved != null
                        ? parentResolved : baseVal);
            } else {
                resolved.put(a.key, ColorAttributes.get(base, a.key));
            }
        }

        // Appliquer la carte résolue au builder (une écriture par attribut).
        for (Map.Entry<String, Integer> e : resolved.entrySet()) {
            builder.set(e.getKey(), e.getValue());
        }
        return builder.build();
    }

    // ── Sérialisation (overrides seuls, format plat) ─────────────

    /** Sérialise le scheme — seuls les overrides figurent dans le document. */
    @NonNull
    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA);
            root.put("id", id);
            root.put("name", name);
            root.put("basedOn", basedOn);
            root.put("dark", mapToJson(dark));
            root.put("light", mapToJson(light));
            return root.toString();
        } catch (JSONException e) {
            // Impossible en pratique (clés/valeurs simples).
            throw new IllegalStateException("Sérialisation du scheme impossible", e);
        }
    }

    private static JSONObject mapToJson(Map<String, Integer> map) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            o.put(e.getKey(), colorToString(e.getValue()));
        }
        return o;
    }

    /**
     * Désérialise un scheme. Les clés inconnues du registre sont ignorées
     * (compatibilité avant/arrière) — jamais une exception pour une clé.
     *
     * @throws JSONException si le document n'est pas un JSON objet valide
     */
    @NonNull
    public static EditorColorScheme fromJson(@NonNull String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int schema = root.optInt("schema", SCHEMA);
        if (schema > SCHEMA) {
            // Format plus récent : on charge ce qui est compréhensible.
        }
        EditorColorScheme scheme = new EditorColorScheme(
                root.optString("id", "imported"),
                root.optString("name", "Importé"),
                root.optString("basedOn", "dark"));
        jsonToMap(root.optJSONObject("dark"), scheme.dark);
        jsonToMap(root.optJSONObject("light"), scheme.light);
        return scheme;
    }

    private static void jsonToMap(@Nullable JSONObject o, Map<String, Integer> out) {
        if (o == null) return;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String key = it.next();
            // Clés inconnues : ignorées silencieusement (tolérance de version).
            if (!ColorAttributes.isValidKey(key)) continue;
            Integer color = parseColor(o.optString(key, null));
            if (color != null) out.put(key, color);
        }
    }

    // ── Couleurs ↔ texte ─────────────────────────────────────────

    /** {@code #AARRGGBB} / {@code #RRGGBB} → ARGB, ou null si non analysable. */
    @Nullable
    public static Integer parseColor(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.charAt(0) != '#') return null;
            long v;
            switch (s.length()) {
                case 7: // #RRGGBB — alpha opaque implicite
                    v = 0xFF000000L | Long.parseLong(s.substring(1), 16);
                    break;
                case 9: // #AARRGGBB
                    v = Long.parseLong(s.substring(1), 16);
                    break;
                default:
                    return null;
            }
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ARGB → {@code #AARRGGBB} (toujours 9 caractères). */
    @NonNull
    public static String colorToString(int argb) {
        return String.format("#%08X", argb);
    }

    @NonNull
    @Override
    public String toString() {
        return "EditorColorScheme{" + id + " (" + name + "), dark=" + dark.size()
                + ", light=" + light.size() + "}";
    }

    // ── Presets en code (points de départ de l'utilisateur) ──────

    /**
     * Preset « Émeraude » : accents émeraude sur la base sombre —
     * démonstration du port : sélection, commentaires et fonctions
     * recolorés, tout le reste suit le thème de référence.
     */
    public static EditorColorScheme emeraldAccents() {
        EditorColorScheme s = new EditorColorScheme("emerald-accents",
                "Accents émeraude", "dark");
        s.setColor(ColorAttributes.KEY_SELECTION, 0xFF0A5B47, true);
        s.setColor(ColorAttributes.KEY_COMMENT, 0xFF5F9E7F, true);
        s.setColor(ColorAttributes.KEY_FUNC, 0xFFE5C890, true);
        s.setColor(ColorAttributes.KEY_CARET, 0xFF34D399, true);
        return s;
    }
}
