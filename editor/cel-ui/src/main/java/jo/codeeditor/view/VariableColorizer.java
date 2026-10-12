package jo.codeeditor.view;

import android.graphics.Color;

/**
 * Attribue une teinte unique à chaque nom de variable. Chaque
 * variable reçoit une couleur HSL dont
 * la teinte (hue) est calculée par un hash du nom — ainsi
 * {@code userName} est toujours bleu, {@code password} est
 * toujours vert, etc.
 *
 * <p>L'algorithme :</p>
 * <ol>
 *   <li>Calcule un hash stable du nom de variable (FNV-1a).</li>
 *   <li>Le hash est mappé sur un hue HSL (0-360°).</li>
 *   <li>La saturation et la luminosité sont fixées pour
 *       garantir la lisibilité sur fond clair ET sombre :
 *       saturation 65%, luminosité 42% (sombre) ou 35% (clair).</li>
 * </ol>
 *
 * <p>Les noms de moins de 2 caractères ne reçoivent pas de
 * couleur spécifique (trop de collisions de hash pour des noms
 * comme {@code i}, {@code x}, {@code y}).</p>
 *
 * @author jjoblab
 */
public final class VariableColorizer {

    private VariableColorizer() {}

    /**
     * Calcule une couleur ARGB pour un nom de variable.
     *
     * @param name le nom de la variable
     * @param isDark true si le thème est sombre (ajuste la luminosité)
     * @return la couleur ARGB, ou 0 si le nom est trop court
     */
    public static int colorForVariable(String name, boolean isDark) {
        if (name == null || name.length() < 2) return 0;

        // FNV-1a hash — stable, rapide, bonne distribution.
        int hash = 0x811C9DC5;
        for (int i = 0; i < name.length(); i++) {
            hash ^= name.charAt(i);
            hash *= 0x01000193;
        }

        // Mapper le hash sur un hue (0-360).
        float hue = Math.abs(hash % 360);
        // Saturation modérée pour ne pas être trop flashu.
        float saturation = 0.65f;
        // Luminosité : plus sombre sur fond clair, plus clair sur
        // fond sombre — garantie de contraste.
        float lightness = isDark ? 0.65f : 0.42f;

        return hslToRgb(hue, saturation, lightness);
    }

    /**
     * Convertit HSL → ARGB.
     *
     * @param h hue en degrés (0-360)
     * @param s saturation (0-1)
     * @param l luminosity (0-1)
     * @return couleur ARGB opaque (0xFF000000 | RGB)
     */
    private static int hslToRgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = l - c / 2;

        float r, g, b;
        if (h < 60)       { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }

        int ri = Math.round((r + m) * 255);
        int gi = Math.round((g + m) * 255);
        int bi = Math.round((b + m) * 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }
}
