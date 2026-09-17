package jo.codeeditor.view.chrome;

import jo.codeeditor.highlight.TokenType;

/**
 * Thème de couleurs complet de l'éditeur.
 *
 * <p>Palette syntaxique étendue (opérateur, échappement, label, propriété,
 * variable, constante, regexp) et constructeur en pattern Builder pour
 * gérer proprement les 32 champs de couleur.
 *
 * <p>Thèmes modernes à contraste WCAG AA, d'aspect professionnel :
 * GitHub Light, GitHub Dark, Nord.
 *
 * <p>Thèmes prédéfinis :
 * <ul>
 *   <li>{@link #dark()} — VS Code Dark+ (défaut)</li>
 *   <li>{@link #light()} — VS Code Light+</li>
 *   <li>{@link #dracula()} — Dracula</li>
 *   <li>{@link #oneDark()} — Atom One Dark</li>
 *   <li>{@link #monokai()} — Monokai Pro</li>
 *   <li>{@link #solarizedDark()} — Solarized Dark</li>
 *   <li>{@link #gitHubLight()} — GitHub Light</li>
 *   <li>{@link #gitHubDark()} — GitHub Dark Dimmed</li>
 *   <li>{@link #nord()} — Nord (palette arctique)</li>
 * </ul>
 */
public class EditorTheme {

    // ── Arrière-plan ─────────────────────────────────────────────
    public final int editorBg;
    public final int gutterBg;
    public final int gutterText;
    public final int gutterBorder;

    // ── Caret / sélection / ligne courante ───────────────────────
    public final int caret;
    public final int selection;
    public final int currentLine;

    // ── Diagnostics ──────────────────────────────────────────────
    public final int error;
    public final int warning;
    public final int info;

    // ── Couleurs syntaxiques (16 types) ──────────────────────────
    public final int keyword;
    public final int string;
    public final int comment;
    public final int number;
    public final int annotation;
    public final int func;
    public final int type;
    public final int punct;
    /** Opérateurs (+, -, *, /, =, !, ?, etc.) */
    public final int operator;
    /** Séquences d'échappement (\n, \t, uXXXX) */
    public final int escape;
    /** Labels (goto, case) */
    public final int label;
    /** Propriétés d'objet (foo.bar — le « bar ») */
    public final int property;
    /** Identifiants de variable */
    public final int variable;
    /** Constantes ALL_CAPS, valeurs d'enum */
    public final int constant;
    /** Littéraux regex */
    public final int regexp;

    // ── Recherche/remplacement ───────────────────────────────────
    public final int findMatch;
    public final int findCurrent;
    public final int occurrence;

    // ── Guides / composition ─────────────────────────────────────
    public final int indentGuide;
    public final int composing;

    // ── Texte (défaut) ───────────────────────────────────────────
    public final int textColor;

    // ── Couleurs des popups verre (pattern CodeAssist) ───────────
    /** Arrière-plan translucide des popups (alpha ~0.86). */
    public final int glassBg;
    /** Bordure translucide des popups (alpha ~0.10). */
    public final int glassBorder;

    // ── Constructeur ─────────────────────────────────────────────

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
        // Calcule les couleurs verre depuis le fond de l'éditeur.
        // Thèmes sombres : bordure blanc@0.10, remplissage fond@0.86.
        // Thèmes clairs : bordure noir@0.08, remplissage fond@0.88.
        boolean isDark = (editorBg & 0xFFFFFF) < 0x808080;

        // ★ Couleur « succès console » (log SUCCESS) :
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

    // ── Constructeur rétrocompatible (25 args) ───────────────────
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

    /** Couleur par défaut de VARIABLE — actuellement identique à la couleur du texte. */
    private static int variable(int textColor) {
        return textColor;
    }

    // ── Thèmes prédéfinis ────────────────────────────────────────

    /** VS Code Dark+ (thème sombre par défaut). */
    public static EditorTheme dark() {
        return new EditorTheme(
            0xFF1E1E1E, 0xFF1E1E1E, 0xFF858585, 0xFF323232,  // fond/gutter
            0xFFDCDCDC, 0xFF264F78, 0xFF2A2A2A,               // caret/sélect./ligne
            0xFFF44747, 0xFFCCA700, 0xFF0078D4,               // erreur/avert./info
            0xFF569CD6, 0xFFCE9178, 0xFF6A9955, 0xFFB5CEA8,   // mots-clés/chaînes/comment./nombres
            0xFFD7BA7D, 0xFFDCDCAA, 0xFF4EC9B0, 0xFFD4D4D4,   // annot./fonctions/types/ponctuation
            0xFFD4D4D4, 0xFFFFD700, 0xFF717171, 0xFF9CDCFE,   // opérateurs/échappements/labels/propriétés
            0xFF9CDCFE, 0xFF4FC1FF, 0xFFD16969,               // var/const/regexp
            0xFF515151, 0xFF6B6B2A, 0xFF57572C,               // recherche
            0xFF404040, 0xFFFFFFC8, 0xFFD4D4D4                 // guide/composition/texte
        );
    }

    /** VS Code Light+ (thème clair par défaut). */
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

    /** Thème Dracula. */
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

    /** Thème Atom One Dark. */
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

    /** Thème Monokai Pro. */
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

    /** Thème Solarized Dark. */
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

    // ── Thèmes supplémentaires (GitHub Light, GitHub Dark, Nord) ──

    /**
     * Thème GitHub Light.
     *
     * <p>Palette claire moderne inspirée du thème clair officiel Primer
     * de GitHub (github-light). Fort contraste, faible éblouissement,
     * optimisée pour les longues sessions de lecture. Ratios de contraste
     * WCAG AA vérifiés sur la palette syntaxique face au fond #ffffff.
     *
     * <ul>
     *   <li>Fond : blanc pur (#ffffff) — comme l'éditeur GitHub</li>
     *   <li>Mots-clés : rouge vif (#cf222e) — ancre syntaxique principale</li>
     *   <li>Chaînes : vert (#0a3069 → #116329) — lisible sur blanc</li>
     *   <li>Commentaires : vert-gris (#6e7781) — s'efface sans disparaître</li>
     *   <li>Nombres : bleu (#0550ae) — distingué des chaînes</li>
     *   <li>Types/Fonctions : violet et bleu (#8250df, #6639ba) —
     *       hiérarchie cohérente</li>
     * </ul>
     */
    public static EditorTheme gitHubLight() {
        return new EditorTheme(
            0xFFFFFFFF, 0xFFF6F8FA, 0xFF8C959F, 0xFFD0D7DE,  // fond/gutter
            0xFF1F2328, 0xFFDDF4FF, 0xFFF6F8FA,                // caret/sélect./ligne
            0xFFCF222E, 0xFFBF8700, 0xFF0969DA,                // erreur/avert./info
            0xFFCF222E, 0xFF0A3069, 0xFF6E7781, 0xFF0550AE,  // mots-clés/chaînes/comment./nombres
            0xFF8250DF, 0xFF8250DF, 0xFF116329, 0xFF1F2328,   // annot./fonctions/types/ponctuation
            0xFF1F2328, 0xFF0550AE, 0xFF6E7781, 0xFF6639BA,   // opérateurs/échappements/labels/propriétés
            0xFF0550AE, 0xFF0550AE, 0xFFA4371F,               // var/const/regexp
            0xFFFFD70A, 0xFFFFA657, 0xFFFFEB8C,               // recherche
            0xFFD8DEE4, 0xFFFFEB8C, 0xFF1F2328                // guide/composition/texte
        );
    }

    /**
     * Thème GitHub Dark Dimmed.
     *
     * <p>La palette officielle « dark dimmed » de GitHub (fond #22272e) —
     * plus douce que le noir pur, plus reposante pour coder la nuit.
     * Utilisée par GitHub.com en mode sombre avec la variante atténuée.
     *
     * <ul>
     *   <li>Fond : #22272e — gris sombre légèrement désaturé</li>
     *   <li>Mots-clés : #ff7b72 — rouge corail, distinctif sans éblouir</li>
     *   <li>Chaînes : #a5d6ff — bleu ciel pâle</li>
     *   <li>Commentaires : #7d8590 — vert-gris neutre</li>
     *   <li>Nombres : #79c0ff — bleu-cyan vif</li>
     *   <li>Types : #ffa657 — orange chaud, se distingue des mots-clés</li>
     *   <li>Fonctions : #d2a8ff — violet pâle, cohérent avec GitHub</li>
     * </ul>
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
     * Thème Nord.
     *
     * <p>Palette arctique aux teintes bleu-nord — le Nord officiel
     * (arcticicestudio/nord). Combine des bleus-gris froids pour les fonds
     * avec les accents signature nord-aurora (cyan, vert, violet, rouge).
     * L'un des thèmes sombres les plus populaires de la communauté des
     * éditeurs.
     *
     * <ul>
     *   <li>Fond : #2e3440 (nord0) — ardoise profonde</li>
     *   <li>Mots-clés : #81a1c1 (nord9) — bleu ciel doux</li>
     *   <li>Chaînes : #a3be8c (nord14) — vert mousse naturel</li>
     *   <li>Commentaires : #616e88 (nord3) — gris-bleu atténué</li>
     *   <li>Nombres : #b48ead (nord15) — magenta</li>
     *   <li>Types : #8fbcbb (nord7) — cyan givré</li>
     *   <li>Fonctions : #88c0d0 (nord8) — givre plus clair</li>
     *   <li>Annotations : #d08770 (nord12) — accent orange chaud</li>
     * </ul>
     */
    public static EditorTheme nord() {
        return new EditorTheme(
            0xFF2E3440, 0xFF2E3440, 0xFFD8DEE9, 0xFF3B4252,  // fond/gutter
            0xFFD8DEE9, 0xFF434C5E, 0xFF3B4252,               // caret/sélect./ligne
            0xFFBF616A, 0xFFEBCB8B, 0xFF88C0D0,                // erreur/avert./info
            0xFF81A1C1, 0xFFA3BE8C, 0xFF616E88, 0xFFB48EAD,   // mots-clés/chaînes/comment./nombres
            0xFFD08770, 0xFF88C0D0, 0xFF8FBCBB, 0xFFECEFF4,   // annot./fonctions/types/ponctuation
            0xFF81A1C1, 0xFFEBCB8B, 0xFF5E81AC, 0xFF8FBCBB,   // opérateurs/échappements/labels/propriétés
            0xFFD8DEE9, 0xFFD08770, 0xFFBF616A,               // var/const/regexp
            0xFF4C566A, 0xFFD08770, 0xFF5E81AC,               // recherche
            0xFF434C5E, 0xFFFFEB8C, 0xFFECEFF4                // guide/composition/texte
        );
    }

    // ── Recherche de couleur par jeton (source unique de vérité) ─

    /**
     * Renvoie la couleur du type de jeton donné. Source unique de
     * vérité — tous les chemins de rendu passent par cette méthode.
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
            // ★ Coloration des logs (styleur « log »).
            case ERROR:      return error;
            case WARNING:    return warning;
            case INFO:       return info;
            case SUCCESS:    return logSuccess;
            case PLAIN:
            default:         return textColor;
        }
    }

    // ── Accesseurs de champs historiques ─────────────────────────
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
