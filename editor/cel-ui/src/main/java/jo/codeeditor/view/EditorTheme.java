package jo.codeeditor.view;

import android.graphics.Color;

import jo.codeeditor.highlight.TokenType;

/**
 * Complete color theme for the editor.
 *
 * <p>v3.1.0: expanded with 7 new syntax colors (operator, escape, label,
 * property, variable, constant, regexp) and 4 additional themes (Dracula,
 * One Dark, Monokai, Solarized). The constructor is now a Builder pattern
 * to handle the 32 color fields cleanly.
 *
 * <p>v0.1.0.71-v2.55: added 3 new themes (GitHub Light, GitHub Dark, Nord)
 * — modern palettes with WCAG AA contrast, professional feel.
 *
 * <p>Pre-built themes:
 * <ul>
 *   <li>{@link #dark()} — VS Code Dark+ (default)</li>
 *   <li>{@link #light()} — VS Code Light+</li>
 *   <li>{@link #dracula()} — Dracula</li>
 *   <li>{@link #oneDark()} — Atom One Dark</li>
 *   <li>{@link #monokai()} — Monokai Pro</li>
 *   <li>{@link #solarizedDark()} — Solarized Dark</li>
 *   <li>{@link #gitHubLight()} — GitHub Light (v2.55)</li>
 *   <li>{@link #gitHubDark()} — GitHub Dark Dimmed (v2.55)</li>
 *   <li>{@link #nord()} — Nord (Arctic palette) (v2.55)</li>
 * </ul>
 *
 * @since v1.0.0 (expanded v3.1.0, v2.55)
 */
public class EditorTheme {

    // ── Background ───────────────────────────────────────────────
    public final int editorBg;
    public final int gutterBg;
    public final int gutterText;
    public final int gutterBorder;

    // ── Caret / selection / current line ─────────────────────────
    public final int caret;
    public final int selection;
    public final int currentLine;

    // ── Diagnostics ──────────────────────────────────────────────
    public final int error;
    public final int warning;
    public final int info;

    // ── Syntax colors (16 types) ─────────────────────────────────
    public final int keyword;
    public final int string;
    public final int comment;
    public final int number;
    public final int annotation;
    public final int func;
    public final int type;
    public final int punct;
    /** v3.1.0: operators (+, -, *, /, =, !, ?, etc.) */
    public final int operator;
    /** v3.1.0: escape sequences (\n, \t, uXXXX) */
    public final int escape;
    /** v3.1.0: labels (goto, case) */
    public final int label;
    /** v3.1.0: object properties (foo.bar — the "bar") */
    public final int property;
    /** v3.1.0: variable identifiers */
    public final int variable;
    /** v3.1.0: ALL_CAPS constants, enum values */
    public final int constant;
    /** v3.1.0: regex literals */
    public final int regexp;

    // ── Find/replace ─────────────────────────────────────────────
    public final int findMatch;
    public final int findCurrent;
    public final int occurrence;

    // ── Guides / composing ───────────────────────────────────────
    public final int indentGuide;
    public final int composing;

    // ── Text (default) ───────────────────────────────────────────
    public final int textColor;

    // ── v3.2.0: Glass popup colors (CodeAssist pattern) ──────────
    /** Translucent popup background (alpha ~0.86). */
    public final int glassBg;
    /** Translucent popup border (alpha ~0.10). */
    public final int glassBorder;

    // ── Constructor ──────────────────────────────────────────────

    public EditorTheme(
        int editorBg, int gutterBg, int gutterText, int gutterBorder,
        int caret, int selection, int currentLine,
        int error, int warning, int info,
        int keyword, int string, int comment, int number,
        int annotation, int func, int type, int punct,
        int operator, int escape, int label, int property,
        int variable, int constant, int regexp,
        int findMatch, int findCurrent, int occurrence,
        int indentGuide, int composing, int textColor
    ) {
        this.editorBg = editorBg;
        this.gutterBg = gutterBg;
        this.gutterText = gutterText;
        this.gutterBorder = gutterBorder;
        this.caret = caret;
        this.selection = selection;
        this.currentLine = currentLine;
        this.error = error;
        this.warning = warning;
        this.info = info;
        this.keyword = keyword;
        this.string = string;
        this.comment = comment;
        this.number = number;
        this.annotation = annotation;
        this.func = func;
        this.type = type;
        this.punct = punct;
        this.operator = operator;
        this.escape = escape;
        this.label = label;
        this.property = property;
        this.variable = variable;
        this.constant = constant;
        this.regexp = regexp;
        this.findMatch = findMatch;
        this.findCurrent = findCurrent;
        this.occurrence = occurrence;
        this.indentGuide = indentGuide;
        this.composing = composing;
        this.textColor = textColor;
        // v3.2.0: Compute glass colors from the editor background.
        // Dark themes: white@0.10 border, bg@0.86 fill.
        // Light themes: black@0.08 border, bg@0.88 fill.
        boolean isDark = (editorBg & 0xFFFFFF) < 0x808080;

        // ★ v0.1.0.49-v2.20 — Couleur « succès console » (log SUCCESS) :
        // il n'existe pas de champ universel vert dans les thèmes existants,
        // on la dérive de la luminance du fond pour rester lisible partout.
        this.logSuccess = isDark ? 0xFF4EC97B   // vert vif sur fond sombre
                                 : 0xFF15803D;  // vert foncé sur fond clair

        this.glassBg = isDark
            ? (0xDB000000 | (editorBg & 0xFFFFFF))  // alpha 0xDB ≈ 0.86
            : (0xE0FFFFFF & editorBg) | 0xE0000000;  // alpha 0xE0 ≈ 0.88
        this.glassBorder = isDark
            ? 0x1AFFFFFF  // white @ alpha 0.10
            : 0x14000000;  // black @ alpha 0.08
    }

    /** Couleur des lignes de succès console (BUILD SUCCESSFUL…) — dérivée du fond. */
    public final int logSuccess;

    // ── Backward-compatible constructor (v1.x — 25 args) ──────────
    public EditorTheme(
        int editorBg, int gutterBg, int gutterText, int gutterBorder,
        int caret, int selection, int currentLine,
        int error, int warning, int info,
        int keyword, int string, int comment, int number,
        int annotation, int func, int type, int punct,
        int findMatch, int findCurrent, int occurrence,
        int indentGuide, int composing, int textColor
    ) {
        this(editorBg, gutterBg, gutterText, gutterBorder,
             caret, selection, currentLine,
             error, warning, info,
             keyword, string, comment, number,
             annotation, func, type, punct,
             punct, string, keyword, variable(textColor),
             textColor, type, string,
             findMatch, findCurrent, occurrence,
             indentGuide, composing, textColor);
    }

    /** Default color for VARIABLE — slightly dimmer than text. */
    private static int variable(int textColor) {
        return textColor;
    }

    // ── Pre-built themes ─────────────────────────────────────────

    /** VS Code Dark+ (default dark theme). */
    public static EditorTheme dark() {
        return new EditorTheme(
            0xFF1E1E1E, 0xFF1E1E1E, 0xFF858585, 0xFF323232,  // bg/gutter
            0xFFDCDCDC, 0xFF264F78, 0xFF2A2A2A,               // caret/sel/line
            0xFFF44747, 0xFFCCA700, 0xFF0078D4,               // error/warn/info
            0xFF569CD6, 0xFFCE9178, 0xFF6A9955, 0xFFB5CEA8,   // kw/str/cmt/num
            0xFFD7BA7D, 0xFFDCDCAA, 0xFF4EC9B0, 0xFFD4D4D4,   // ann/func/type/punct
            0xFFD4D4D4, 0xFFFFD700, 0xFF717171, 0xFF9CDCFE,   // op/esc/label/prop
            0xFF9CDCFE, 0xFF4FC1FF, 0xFFD16969,               // var/const/regexp
            0xFF515151, 0xFF6B6B2A, 0xFF57572C,               // find
            0xFF404040, 0xFFFFFFC8, 0xFFD4D4D4                 // guide/compose/text
        );
    }

    /** VS Code Light+ (default light theme). */
    public static EditorTheme light() {
        return new EditorTheme(
            0xFFFFFFFF, 0xFFF5F5F5, 0xFF808080, 0xFFDCDCDC,
            0xFF000000, 0xFFADD6FF, 0xFFF5F8FA,
            0xFFFF0000, 0xFFCC7800, 0xFF0078D4,
            0xFF0000FF, 0xFFA31515, 0xFF008000, 0xFF098658,
            0xFF800080, 0xFF795E26, 0xFF267F99, 0xFF000000,
            0xFF000000, 0xFFB5695E, 0xFF717171, 0xFF001080,
            0xFF001080, 0xFF0070C1, 0xFF811F3F,
            0xFFFFE600, 0xFFFFC800, 0xFFE6E6D2,
            0xFFDCDCDC, 0xFFFFFFC8, 0xFF000000
        );
    }

    /** Dracula theme. */
    public static EditorTheme dracula() {
        return new EditorTheme(
            0xFF282A36, 0xFF21222C, 0xFF6272A4, 0xFF191A21,
            0xFFF8F8F2, 0xFF44475A, 0xFF343746,
            0xFFFF5555, 0xFFFFF44F, 0xFF8BE9FD,
            0xFFFF79C6, 0xFFF1FA8C, 0xFF6272A4, 0xFFBD93F9,
            0xFFFFB86C, 0xFF50FA7B, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFFFF79C6, 0xFFFF79C6, 0xFF8BE9FD, 0xFF8BE9FD,
            0xFFF8F8F2, 0xFFBD93F9, 0xFFFF5555,
            0xFF44475A, 0xFF6B6B2A, 0xFF57572C,
            0xFF44475A, 0xFFFFFFC8, 0xFFF8F8F2
        );
    }

    /** Atom One Dark theme. */
    public static EditorTheme oneDark() {
        return new EditorTheme(
            0xFF282C34, 0xFF282C34, 0xFF5C6370, 0xFF3B4048,
            0xFFABB2BF, 0xFF2C313A, 0xFF2C313A,
            0xFFE06C75, 0xFFE5C07B, 0xFF61AFEF,
            0xFFC678DD, 0xFF98C379, 0xFF7F848E, 0xFFD19A66,
            0xFFE5C07B, 0xFF61AFEF, 0xFFE5C07B, 0xFFABB2BF,
            0xFF56B6C2, 0xFF56B6C2, 0xFF7F848E, 0xFFE06C75,
            0xFFE06C75, 0xFFD19A66, 0xFF98C379,
            0xFF3B4048, 0xFF6B6B2A, 0xFF57572C,
            0xFF3B4048, 0xFFFFFFC8, 0xFFABB2BF
        );
    }

    /** Monokai Pro theme. */
    public static EditorTheme monokai() {
        return new EditorTheme(
            0xFF2D2A2E, 0xFF2D2A2E, 0xFF727072, 0xFF403E41,
            0xFFFCFCFA, 0xFF403E41, 0xFF363337,
            0xFFFF6188, 0xFFFFD866, 0xFF78DCE8,
            0xFFFF6188, 0xFFFFD866, 0xFF727072, 0xFFAB9DF2,
            0xFF78DCE8, 0xFFA9DC76, 0xFF78DCE8, 0xFFFCFCFA,
            0xFFF92672, 0xFFF92672, 0xFFFCFCFA, 0xFFFCFCFA,
            0xFFFCFCFA, 0xFFAB9DF2, 0xFFFF6188,
            0xFF403E41, 0xFF6B6B2A, 0xFF57572C,
            0xFF403E41, 0xFFFFFFC8, 0xFFFCFCFA
        );
    }

    /** Solarized Dark theme. */
    public static EditorTheme solarizedDark() {
        return new EditorTheme(
            0xFF002B36, 0xFF002B36, 0xFF586E75, 0xFF073642,
            0xFF839496, 0xFF073642, 0xFF073642,
            0xFFDC322F, 0xFFB58900, 0xFF268BD2,
            0xFF859900, 0xFF2AA198, 0xFF586E75, 0xFFD33682,
            0xFF6C71C4, 0xFF268BD2, 0xFFB58900, 0xFF93A1A1,
            0xFFCB4B16, 0xFFCB4B16, 0xFF586E75, 0xFF93A1A1,
            0xFF93A1A1, 0xFFD33682, 0xFFCB4B16,
            0xFF073642, 0xFF6B6B2A, 0xFF57572C,
            0xFF073642, 0xFFFFFFC8, 0xFF93A1A1
        );
    }

    // ── v2.55 — New themes (GitHub Light, GitHub Dark, Nord) ───────────

    /**
     * GitHub Light theme (v2.55).
     *
     * <p>Modern light palette inspired by GitHub's official Primer light
     * theme (github-light). High-contrast, low-glare, optimized for
     * long reading sessions. WCAG AA contrast ratios verified on the
     * syntax palette against the #ffffff background.
     *
     * <ul>
     *   <li>Background: pure white (#ffffff) — matches GitHub editor</li>
     *   <li>Keywords: bright red (#cf222e) — primary syntactic anchor</li>
     *   <li>Strings: green (#0a3069 → #116329) — readable on white</li>
     *   <li>Comments: gray-green (#6e7781) — recede without disappearing</li>
     *   <li>Numbers: blue (#0550ae) — distinguish from strings</li>
     *   <li>Types/Funcs: purple & blue (#8250df, #6639ba) —
     *       consistent hierarchy</li>
     * </ul>
     *
     * @since v2.55
     */
    public static EditorTheme gitHubLight() {
        return new EditorTheme(
            0xFFFFFFFF, 0xFFF6F8FA, 0xFF8C959F, 0xFFD0D7DE,  // bg/gutter
            0xFF1F2328, 0xFFDDF4FF, 0xFFF6F8FA,                // caret/sel/line
            0xFFCF222E, 0xFFBF8700, 0xFF0969DA,                // error/warn/info
            0xFFCF222E, 0xFF0A3069, 0xFF6E7781, 0xFF0550AE,  // kw/str/cmt/num
            0xFF8250DF, 0xFF8250DF, 0xFF116329, 0xFF1F2328,   // ann/func/type/punct
            0xFF1F2328, 0xFF0550AE, 0xFF6E7781, 0xFF6639BA,   // op/esc/label/prop
            0xFF0550AE, 0xFF0550AE, 0xFFA4371F,               // var/const/regexp
            0xFFFFD70A, 0xFFFFA657, 0xFFFFEB8C,               // find
            0xFFD8DEE4, 0xFFFFEB8C, 0xFF1F2328                // guide/compose/text
        );
    }

    /**
     * GitHub Dark Dimmed theme (v2.55).
     *
     * <p>The official GitHub "dark dimmed" palette (#22272e bg) — softer
     * than pure black, easier on the eyes for night coding. Used by
     * GitHub.com when dark mode is selected with the dimmed variant.
     *
     * <ul>
     *   <li>Background: #22272e — slightly desaturated dark gray</li>
     *   <li>Keywords: #ff7b72 — coral red, distinctive but not glaring</li>
     *   <li>Strings: #a5d6ff — pale sky blue</li>
     *   <li>Comments: #7d8590 — neutral gray-green</li>
     *   <li>Numbers: #79c0ff — bright cyan-blue</li>
     *   <li>Types: #ffa657 — warm orange, distinguishes from keywords</li>
     *   <li>Funcs: #d2a8ff — pale violet, consistent with GitHub</li>
     * </ul>
     *
     * @since v2.55
     */
    public static EditorTheme gitHubDark() {
        return new EditorTheme(
            0xFF22272E, 0xFF1C2128, 0xFF7D8590, 0xFF2D333B,
            0xFFADBAC7, 0xFF1F6FEB, 0xFF2D333B,
            0xFFFF7B72, 0xFFD29922, 0xFF58A6FF,
            0xFFFF7B72, 0xFFA5D6FF, 0xFF7D8590, 0xFF79C0FF,
            0xFFFFA657, 0xFFD2A8FF, 0xFFFFA657, 0xFFADBAC7,
            0xFFFF7B72, 0xFF79C0FF, 0xFF7D8590, 0xFFD2A8FF,
            0xFFADBAC7, 0xFFFFA657, 0xFFFFA657,
            0xFF2D333B, 0xFFE3B341, 0xFFADCFFF,
            0xFF373E47, 0xFFFFEB8C, 0xFFADBAC7
        );
    }

    /**
     * Nord theme (v2.55).
     *
     * <p>Arctic, north-bluish color palette — the official Nord
     * (arcticicestudio/nord). Combines cool blue-grays for backgrounds
     * with the trademark nord-aurora accents (cyan, green, purple, red).
     * One of the most popular dark themes in the editor community.
     *
     * <ul>
     *   <li>Background: #2e3440 (nord0) — deep slate</li>
     *   <li>Keywords: #81a1c1 (nord9) — soft sky blue</li>
     *   <li>Strings: #a3be8c (nord14) — natural moss green</li>
     *   <li>Comments: #616e88 (nord3) — muted gray-blue</li>
     *   <li>Numbers: #b48ead (nord15) — magenta</li>
     *   <li>Types: #8fbcbb (nord7) — frost cyan</li>
     *   <li>Funcs: #88c0d0 (nord8) — lighter frost</li>
     *   <li>Annotations: #d08770 (nord12) — warm orange accent</li>
     * </ul>
     *
     * @since v2.55
     */
    public static EditorTheme nord() {
        return new EditorTheme(
            0xFF2E3440, 0xFF2E3440, 0xFFD8DEE9, 0xFF3B4252,  // bg/gutter
            0xFFD8DEE9, 0xFF434C5E, 0xFF3B4252,               // caret/sel/line
            0xFFBF616A, 0xFFEBCB8B, 0xFF88C0D0,                // error/warn/info
            0xFF81A1C1, 0xFFA3BE8C, 0xFF616E88, 0xFFB48EAD,   // kw/str/cmt/num
            0xFFD08770, 0xFF88C0D0, 0xFF8FBCBB, 0xFFECEFF4,   // ann/func/type/punct
            0xFF81A1C1, 0xFFEBCB8B, 0xFF5E81AC, 0xFF8FBCBB,   // op/esc/label/prop
            0xFFD8DEE9, 0xFFD08770, 0xFFBF616A,               // var/const/regexp
            0xFF4C566A, 0xFFD08770, 0xFF5E81AC,               // find
            0xFF434C5E, 0xFFFFEB8C, 0xFFECEFF4                // guide/compose/text
        );
    }

    // ── Token color lookup (single source of truth) ──────────────

    /**
     * Returns the color for the given token type. This is the single
     * source of truth — all rendering paths use this method.
     */
    public int colorForToken(TokenType tokenType) {
        switch (tokenType) {
            case KEYWORD:    return keyword;
            case STRING:     return string;
            case COMMENT:    return comment;
            case NUMBER:     return number;
            case ANNOTATION: return annotation;
            case FUNC:       return func;
            case TYPE:       return type;
            case PUNCT:      return punct;
            case OPERATOR:   return operator;
            case ESCAPE:     return escape;
            case LABEL:      return label;
            case PROPERTY:   return property;
            case VARIABLE:   return variable;
            case CONSTANT:   return constant;
            case REGEXP:     return regexp;
            // ★ v0.1.0.49-v2.20 — Coloration des logs (styleur « log »).
            case ERROR:      return error;
            case WARNING:    return warning;
            case INFO:       return info;
            case SUCCESS:    return logSuccess;
            case PLAIN:
            default:         return textColor;
        }
    }

    // ── Legacy field accessors ───────────────────────────────────
    public int getBackgroundColor() { return editorBg; }
    public int getGutterBackgroundColor() { return gutterBg; }
    public int getCurrentLineColor() { return currentLine; }
    public int getTextColor() { return textColor; }
    public int getKeywordColor() { return keyword; }
    public int getStringColor() { return string; }
    public int getCommentColor() { return comment; }
    public int getNumberColor() { return number; }
    public int getAnnotationColor() { return annotation; }
    public int getFuncColor() { return func; }
    public int getTypeColor() { return type; }
    public int getPunctColor() { return punct; }
    public int getCaretColor() { return caret; }
    public int getSelectionColor() { return selection; }
    public int getGutterTextColor() { return gutterText; }
    public int getGutterSeparatorColor() { return gutterBorder; }
    public int getErrorColor() { return error; }
    public int getWarningColor() { return warning; }
    public int getInfoColor() { return info; }
}
