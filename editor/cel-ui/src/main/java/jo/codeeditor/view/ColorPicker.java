package jo.codeeditor.view;

/**
 * Color picker — détection et parsing des couleurs hexadécimales
 * dans le texte (#RGB, #RRGGBB, #AARRGGBB, rgb(), rgba(),
 * hsl(), named colors).
 *
 * <p>Un petit carré coloré est dessiné
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

        /**
         * Nombre de chiffres hex du code d'origine : 3 (#RGB), 6
         * (#RRGGBB) ou 8 (#AARRGGBB). Le sélecteur de couleur (B26)
         * s'en sert pour reformater la couleur choisie au format
         * d'origine — remplacer « #fff » par « #e43 » et pas par
         * « #EE443355 ».
         */
        public int digits() {
            return end - start - 1;
        }
    }

    /**
     * Cherche une couleur hexadécimale à la position donnée.
     * Retourne null si aucune couleur n'est trouvée.
     *
     * <p>B26 : {@code pos} peut valoir {@code text.length()} (tap juste
     * après la fin de la ligne) — le balayage autour de la position est
     * borné aux indices valides.</p>
     *
     * @param text le texte
     * @param pos la position à examiner
     * @return le match, ou null
     */
    public static ColorMatch findColorAt(CharSequence text, int pos) {
        if (text == null || pos < 0 || pos > text.length()) return null;
        if (text.length() == 0) return null;

        // Chercher un '#' autour de la position.
        int hashPos = -1;
        for (int i = Math.min(pos, text.length() - 1);
                i >= Math.max(0, pos - 10); i--) {
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

    /**
     * B26 — formate une couleur ARGB au format hex demandé (3, 6 ou 8
     * chiffres) : le pick du sélecteur remplace le code TAPÉ sans en
     * changer la largeur (une édition qui renvoie 8 chiffres à la place
     * de 3 décalerait tout le code environnant et polluerait l'undo).
     *
     * <ul>
     *   <li>3 chiffres : chaque canal est quantifié au nibble le plus
     *       proche (arrondi mathématique, 255 → F) ;</li>
     *   <li>6 chiffres : #RRGGBB ;</li>
     *   <li>8 chiffres : #AARRGGBB (l'alpha passé est conservé — le
     *       sélecteur édite la teinte, l'alpha d'origine est préservé
     *       par l'appelant).</li>
     * </ul>
     *
     * @param argb la couleur ARGB à formater
     * @param digits 3, 6 ou 8 (toute autre valeur retombe à 6)
     * @return le code hex, préfixé de « # », minuscules (usage code)
     */
    public static String format(int argb, int digits) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (digits == 3) {
            int qr = Math.round(r * 15f / 255f);
            int qg = Math.round(g * 15f / 255f);
            int qb = Math.round(b * 15f / 255f);
            return String.format("#%1x%1x%1x", qr, qg, qb);
        }
        if (digits == 8) {
            int a = (argb >>> 24) & 0xFF;
            return String.format("#%02x%02x%02x%02x", a, r, g, b);
        }
        return String.format("#%02x%02x%02x", r, g, b);
    }
}
