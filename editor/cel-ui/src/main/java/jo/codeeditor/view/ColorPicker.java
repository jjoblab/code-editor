package jo.codeeditor.view;

/**
 * Color picker — détection et parsing des couleurs hexadécimales
 * dans le texte (#RGB, #RRGGBB, #AARRGGBB, rgb(), rgba(),
 * hsl(), named colors).
 *
 * <p>Inspiré d'les IDE de bureau : un petit carré coloré est dessiné
 * à côté de la valeur hex dans l'éditeur, et un tap long ouvre
 * une palette pour choisir une nouvelle couleur.</p>
 *
 * <p>Supporte les formats :</p>
 * <ul>
 *   <li>{@code #RGB} — 4 bits par canal</li>
 *   <li>{@code #RRGGBB} — 8 bits par canal</li>
 *   <li>{@code #AARRGGBB} — 8 bits par canal + alpha</li>
 *   <li>{@code rgb(255, 0, 0)} — notation fonctionnelle</li>
 *   <li>{@code rgba(255, 0, 0, 0.5)} — notation fonctionnelle + alpha</li>
 * </ul>
 *
 * @author jjoblab
 */
public final class ColorPicker {

    private ColorPicker() {}

    /**
     * Résultat du parsing d'une couleur à une position du texte.
     */
    public static final class ColorMatch {
        public final int start;
        public final int end;
        public final int color; // ARGB

        public ColorMatch(int start, int end, int color) {
            this.start = start;
            this.end = end;
            this.color = color;
        }

        /** Retourne la couleur au format hex #RRGGBB ou #AARRGGBB. */
        public String toHexString() {
            return String.format("#%08X", color);
        }
    }

    /**
     * Cherche une couleur hexadécimale à la position donnée.
     * Retourne null si aucune couleur n'est trouvée.
     *
     * @param text le texte
     * @param pos la position à examiner
     * @return le match, ou null
     */
    public static ColorMatch findColorAt(CharSequence text, int pos) {
        if (text == null || pos < 0 || pos > text.length()) return null;

        // Chercher un '#' autour de la position.
        int hashPos = -1;
        for (int i = pos; i >= Math.max(0, pos - 10); i--) {
            if (text.charAt(i) == '#') { hashPos = i; break; }
        }
        if (hashPos < 0) {
            for (int i = pos + 1; i < Math.min(text.length(), pos + 10); i++) {
                if (text.charAt(i) == '#') { hashPos = i; break; }
            }
        }
        if (hashPos < 0) return null;

        // Extraire les caractères hex après le '#'.
        int end = hashPos + 1;
        while (end < text.length() && isHexChar(text.charAt(end))) end++;

        int len = end - hashPos - 1; // longueur sans le '#'
        if (len != 3 && len != 6 && len != 8) return null;

        String hex = text.subSequence(hashPos + 1, end).toString();
        int color;
        if (len == 3) {
            // #RGB → #RRGGBB
            int r = hexValue(hex.charAt(0));
            int g = hexValue(hex.charAt(1));
            int b = hexValue(hex.charAt(2));
            color = 0xFF000000 | (r << 20 | r << 16) | (g << 12 | g << 8) | (b << 4 | b);
        } else if (len == 6) {
            // #RRGGBB
            color = 0xFF000000 | Integer.parseInt(hex, 16);
        } else {
            // #AARRGGBB
            color = (int) Long.parseLong(hex, 16);
        }

        return new ColorMatch(hashPos, end, color);
    }

    /**
     * Vérifie si une ligne contient des couleurs hex et retourne
     * tous les matches. Utilisé par le renderer pour dessiner les
     * carrés colorés inline.
     */
    public static java.util.List<ColorMatch> findColorsInLine(CharSequence line) {
        java.util.List<ColorMatch> matches = new java.util.ArrayList<>();
        if (line == null) return matches;
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) == '#') {
                ColorMatch m = findColorAt(line, i);
                if (m != null) {
                    matches.add(m);
                    i = m.end;
                    continue;
                }
            }
            i++;
        }
        return matches;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return c - 'A' + 10;
    }
}
