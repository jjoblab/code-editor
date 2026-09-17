package jo.codeeditor.completion;

/**
 * Badge de type pour le popup de complétion.
 *
 * <p>Portage Java du {@code KindBadge} de CodeAssist
 * ({@code ide-ui/.../components/Badges.kt}) : chaque suggestion porte un
 * glyphe (lettre ou symbole) dans un carré arrondi teinté par la nature du
 * candidat — « K » violet pour un mot-clé, « C » doré pour une classe,
 * « I » cyan pour une interface, « E » orange pour un enum, « M » pour une
 * méthode, « F » bleu pour un champ, « v » pour une variable, « p » gris
 * pour un package, « {} » vert pour un snippet, « @ » pour une annotation,
 * « # » pour une constante d'enum, « T » pour un paramètre de type.</p>
 *
 * <p>La couleur du badge « M » (méthode/constructeur) est laissée à
 * l'appelant (couleur d'accent du thème de l'éditeur — CodeAssist utilise
 * {@code MaterialTheme.colorScheme.primary}). Les autres couleurs sont la
 * palette fixe de CodeAssist, assombries automatiquement pour les thèmes
 * clairs (elles ont été calibrées pour un fond glass sombre).</p>
 *
 * <p>Classe pure (aucune dépendance Android) : le glyphe et la couleur
 * ARGB sont calculables et testables côté core ; seul le dessin (Canvas,
 * Paint) reste dans le renderer UI.</p>
 *
 * @author jo@Dev
 */
public final class CompletionKindBadge {

    private CompletionKindBadge() {}

    /** Métadonnées du badge : le glyphe affiché et sa couleur ARGB. */
    public static final class Meta {
        public final String glyph;
        public final int color;
        public Meta(String glyph, int color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    // ── Palette CodeAssist (Badges.kt) — calibrée fond sombre ──────────
    private static final int GOLD = 0xFFE6C178;      // Class, Record, Annotation, TypeParameter
    private static final int INTERFACE = 0xFF57B6C2; // Interface
    private static final int ENUM = 0xFFD9A066;      // Enum, EnumConstant
    private static final int FIELD = 0xFF61AFEF;     // Field, Property, Constant
    private static final int VARIABLE = 0xFF5CCFE6;  // Variable, Parameter
    private static final int PACKAGE = 0xFFA0A1AA;   // Package, Module, Folder, Word
    private static final int KEYWORD = 0xFFCD7EE0;   // Keyword
    private static final int SNIPPET = 0xFF98C97A;   // Snippet

    /**
     * Assombrit une couleur pour un thème clair (la palette CodeAssist est
     * calibrée pour un fond glass sombre ; sur fond blanc les teintes
     * moyennes manquent de contraste). Mélange vers le noir à 38 %.
     */
    private static int darken(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >>> 16) & 0xFF) * 0.62f);
        int g = (int) (((argb >>> 8) & 0xFF) * 0.62f);
        int b = (int) ((argb & 0xFF) * 0.62f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * @param lspKind la valeur LSP moderne du kind (1-25), 0 = inconnu
     * @param kindTag raffinement transporté par le champ {@code data} LSP
     *                (« annotation », « package », « record »), ou null
     * @param iconFallback l'icône string historique (« k », « m », « f »,
     *                     « c », « v »…) quand le kind LSP est absent
     *                     — sert de secours au glyphe
     * @param darkTheme true pour un thème sombre (palette telle quelle),
     *                  false pour un thème clair (palette assombrie)
     * @param methodAccent couleur d'accent du thème pour le badge « M »
     *                     (méthode/constructeur) — CodeAssist y met sa
     *                     couleur primaire
     */
    public static Meta meta(int lspKind, String kindTag, String iconFallback,
                            boolean darkTheme, int methodAccent) {
        // ── 1. Raffinements data (le kind LSP seul ne distingue pas) ──
        if ("annotation".equals(kindTag)) {
            return new Meta("@", tone(GOLD, darkTheme));
        }
        if ("package".equals(kindTag)) {
            return new Meta("p", tone(PACKAGE, darkTheme));
        }
        if ("record".equals(kindTag)) {
            return new Meta("R", tone(GOLD, darkTheme));
        }
        if ("parameter".equals(kindTag)) {
            return new Meta("v", tone(VARIABLE, darkTheme));
        }

        // ── 2. Kinds LSP modernes ──
        switch (lspKind) {
            case 2: case 3: case 4:                    // Method, Function, Constructor
                return new Meta("M", methodAccent);
            case 5: case 10: case 21:                  // Field, Property, Constant
                return new Meta("F", tone(FIELD, darkTheme));
            case 6: case 12:                           // Variable, Value
                return new Meta("v", tone(VARIABLE, darkTheme));
            case 7: case 22:                           // Class, Struct
                return new Meta("C", tone(GOLD, darkTheme));
            case 8:                                    // Interface
                return new Meta("I", tone(INTERFACE, darkTheme));
            case 9: case 19:                           // Module, Folder → package
                return new Meta("p", tone(PACKAGE, darkTheme));
            case 13:                                   // Enum
                return new Meta("E", tone(ENUM, darkTheme));
            case 14:                                   // Keyword
                return new Meta("K", tone(KEYWORD, darkTheme));
            case 15:                                   // Snippet
                return new Meta("{}", tone(SNIPPET, darkTheme));
            case 20:                                   // EnumMember
                return new Meta("#", tone(ENUM, darkTheme));
            case 25:                                   // TypeParameter
                return new Meta("T", tone(GOLD, darkTheme));
            default: break;                            // Text, Unit, Color, File… → fallback
        }

        // ── 3. Secours icône string historique ──
        if (iconFallback != null && !iconFallback.isEmpty()) {
            switch (iconFallback) {
                case "k": return new Meta("K", tone(KEYWORD, darkTheme));
                case "m": return new Meta("M", methodAccent);
                case "f": return new Meta("F", tone(FIELD, darkTheme));
                case "c": return new Meta("C", tone(GOLD, darkTheme));
                case "i": return new Meta("I", tone(INTERFACE, darkTheme));
                case "e": return new Meta("E", tone(ENUM, darkTheme));
                case "p": return new Meta("p", tone(PACKAGE, darkTheme));
                case "@": return new Meta("@", tone(GOLD, darkTheme));
                case "v": default: break;
            }
        }
        return new Meta("v", tone(VARIABLE, darkTheme));
    }

    private static int tone(int color, boolean darkTheme) {
        return darkTheme ? color : darken(color);
    }

    /**
     * Taille de police du glyphe relativement à la taille du badge —
     * un glyphe long (« {} ») se réduit pour tenir dans le carré.
     * Portage de {@code fontSize = (if (ch.length > 1) size*0.42 else size*0.56)}.
     */
    public static float glyphSizeFactor(String glyph) {
        return glyph != null && glyph.length() > 1 ? 0.42f : 0.56f;
    }
}
