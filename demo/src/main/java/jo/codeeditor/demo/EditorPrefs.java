package jo.codeeditor.demo;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Préférences persistées de la démo ({@code SharedPreferences}) —
 * source unique de vérité entre l'écran Réglages et l'atelier.
 *
 * <p>Le contrat est simple : Réglages ÉCRIT, l'atelier RELIT
 * ({@code onResume} + application idempotente des setters de
 * l'éditeur) — jamais de push croisé. Les valeurs par défaut
 * reproduisent l'état initial de l'ancienne bande de bascules.</p>
 */
final class EditorPrefs {

    private static final String FILE = "demo_prefs";

    private static final String KEY_WRAP = "wrap";
    private static final String KEY_MINIMAP = "minimap";
    private static final String KEY_LIGATURES = "ligatures";
    private static final String KEY_NON_PRINTABLE = "non_printable";
    private static final String KEY_LIGHT = "light_theme";
    private static final String KEY_SCHEME = "scheme";
    private static final String KEY_GRAMMARS = "grammars_auto";
    private static final String KEY_LSP_AUTO = "lsp_auto";
    private static final String KEY_DROP_POLICY = "preview_drop_policy";
    private static final String KEY_DROP_SCALE = "preview_drop_scale";
    private static final String KEY_HISTORY = "completion_history";

    private final SharedPreferences prefs;

    EditorPrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    boolean wordWrap() {
        return prefs.getBoolean(KEY_WRAP, true);
    }

    boolean minimap() {
        return prefs.getBoolean(KEY_MINIMAP, false);
    }

    boolean ligatures() {
        return prefs.getBoolean(KEY_LIGATURES, false);
    }

    boolean nonPrintable() {
        return prefs.getBoolean(KEY_NON_PRINTABLE, false);
    }

    boolean lightTheme() {
        return prefs.getBoolean(KEY_LIGHT, false);
    }

    boolean scheme() {
        return prefs.getBoolean(KEY_SCHEME, false);
    }

    /** Activation automatique des grammaires TextMate quand elles existent. */
    boolean grammarsAuto() {
        return prefs.getBoolean(KEY_GRAMMARS, false);
    }

    /** Connexion automatique du moteur LSP sur les fichiers Java. */
    boolean lspAuto() {
        return prefs.getBoolean(KEY_LSP_AUTO, false);
    }

    /**
     * Politique de cohabitation goutte d'eau / popups de frappe —
     * l'ORIGINALE est le défaut (maquette d'origine). Valeurs
     * persistées : « original », « three_layers », « variant_b ».
     */
    String previewDropPolicy() {
        String v = prefs.getString(KEY_DROP_POLICY, "original");
        return "three_layers".equals(v) || "variant_b".equals(v) ? v : "original";
    }

    void setPreviewDropPolicy(String value) {
        prefs.edit().putString(KEY_DROP_POLICY, value).apply();
    }

    /** Taille de la goutte d'eau — facteur d'agrandissement (1 par défaut). */
    float previewDropScale() {
        return prefs.getFloat(KEY_DROP_SCALE, 1f);
    }

    void setPreviewDropScale(float value) {
        prefs.edit().putFloat(KEY_DROP_SCALE, value).apply();
    }

    void set(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    /**
     * La photographie JSON de l'historique d'apprentissage (4.6.0) —
     * null tant qu'aucune session n'a été sauvegardée.
     */
    String completionHistory() {
        return prefs.getString(KEY_HISTORY, null);
    }

    void setCompletionHistory(String json) {
        prefs.edit().putString(KEY_HISTORY, json).apply();
    }

    static String keyWrap() {
        return KEY_WRAP;
    }

    static String keyMinimap() {
        return KEY_MINIMAP;
    }

    static String keyLigatures() {
        return KEY_LIGATURES;
    }

    static String keyNonPrintable() {
        return KEY_NON_PRINTABLE;
    }

    static String keyLight() {
        return KEY_LIGHT;
    }

    static String keyScheme() {
        return KEY_SCHEME;
    }

    static String keyGrammars() {
        return KEY_GRAMMARS;
    }

    static String keyLspAuto() {
        return KEY_LSP_AUTO;
    }
}
