package jo.codeeditor.theme;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Registre des attributs de couleur — source unique décrivant TOUTES les
 * cases réglables du thème ({@link EditorTheme}).
 *
 * <p>L'ordre de déclaration est significatif : un parent figure TOUJOURS
 * avant ses enfants, ce qui permet la résolution itérative en une seule
 * passe ({@link EditorColorScheme#resolve(EditorTheme, boolean)}).</p>
 *
 * <p>Le registre expose aussi les accesseurs thème-clé (lecture) et les
 * écriteurs clé-builder (écriture) — aucune réflexion : chaque attribut
 * est câblé une fois pour toutes vers son champ d'{@link EditorTheme}.</p>
 */
public final class ColorAttributes {

    // ── Fond ─────────────────────────────────────────────────────
    public static final String KEY_EDITOR_BG = "editor.background";
    public static final String KEY_CURRENT_LINE = "editor.currentLine";
    public static final String KEY_GUTTER_BG = "gutter.background";
    public static final String KEY_GUTTER_BORDER = "gutter.border";
    public static final String KEY_GUTTER_TEXT = "gutter.text";

    // ── Curseur / sélection ──────────────────────────────────────
    public static final String KEY_CARET = "caret";
    public static final String KEY_SELECTION = "selection";

    // ── Diagnostics ──────────────────────────────────────────────
    public static final String KEY_ERROR = "diagnostics.error";
    public static final String KEY_WARNING = "diagnostics.warning";
    public static final String KEY_INFO = "diagnostics.info";

    // ── Syntaxe ──────────────────────────────────────────────────
    public static final String KEY_KEYWORD = "syntax.keyword";
    public static final String KEY_STRING = "syntax.string";
    public static final String KEY_COMMENT = "syntax.comment";
    public static final String KEY_NUMBER = "syntax.number";
    public static final String KEY_ANNOTATION = "syntax.annotation";
    public static final String KEY_FUNC = "syntax.func";
    public static final String KEY_TYPE = "syntax.type";
    public static final String KEY_PUNCT = "syntax.punct";
    public static final String KEY_OPERATOR = "syntax.operator";
    public static final String KEY_ESCAPE = "syntax.escape";
    public static final String KEY_LABEL = "syntax.label";
    public static final String KEY_PROPERTY = "syntax.property";
    public static final String KEY_VARIABLE = "syntax.variable";
    public static final String KEY_CONSTANT = "syntax.constant";
    public static final String KEY_REGEXP = "syntax.regexp";

    // ── Recherche ────────────────────────────────────────────────
    public static final String KEY_FIND_MATCH = "search.match";
    public static final String KEY_FIND_CURRENT = "search.current";
    public static final String KEY_OCCURRENCE = "search.occurrence";

    // ── Chrome / composition ─────────────────────────────────────
    public static final String KEY_INDENT_GUIDE = "chrome.indentGuide";
    public static final String KEY_COMPOSING = "chrome.composing";

    // ── Texte (plancher) ─────────────────────────────────────────
    public static final String KEY_TEXT = "text.foreground";

    /** Registre ordonné — un parent précède toujours ses enfants. */
    private static final List<ColorAttribute> ATTRIBUTES = new ArrayList<>();

    /** Lecture : clé → champ d'EditorTheme. */
    private static final Map<String, ToIntFunction<EditorTheme>> GETTERS = new LinkedHashMap<>();

    /** Clés VIRTUELLES : attributs du registre sans champ dédié dans
     *  EditorTheme (distinctions fines du lot 4 #26) — leur couleur vit
     *  dans la carte d'extras du thème et retombe sur le parent. */
    private static final Map<String, Boolean> VIRTUAL = new LinkedHashMap<>();

    private static void reg(ColorAttribute a, ToIntFunction<EditorTheme> getter) {
        ATTRIBUTES.add(a);
        GETTERS.put(a.key, getter);
    }

    /** Enregistre un attribut VIRTUEL — la lecture délègue au parent. */
    private static void regVirtual(ColorAttribute a) {
        ATTRIBUTES.add(a);
        VIRTUAL.put(a.key, true);
        GETTERS.put(a.key, t -> get(t, a.parent));
    }

    static {
        // Le texte est le PLANCHER de plusieurs chaînes d'héritage
        // (gutter.text, variable) — il doit donc figurer tôt dans
        // l'ordre du registre, avant ses enfants.
        reg(new ColorAttribute(KEY_TEXT, "Texte (couleur par défaut)", ColorAttribute.GROUP_TEXT, null),
            t -> t.textColor);

        reg(new ColorAttribute(KEY_EDITOR_BG, "Fond de l'éditeur", ColorAttribute.GROUP_BACKGROUND, null),
            t -> t.editorBg);
        reg(new ColorAttribute(KEY_CURRENT_LINE, "Ligne courante", ColorAttribute.GROUP_BACKGROUND, KEY_EDITOR_BG),
            t -> t.currentLine);
        reg(new ColorAttribute(KEY_GUTTER_BG, "Fond de gouttière", ColorAttribute.GROUP_GUTTER, KEY_EDITOR_BG),
            t -> t.gutterBg);
        reg(new ColorAttribute(KEY_GUTTER_BORDER, "Bordure de gouttière", ColorAttribute.GROUP_GUTTER, KEY_GUTTER_BG),
            t -> t.gutterBorder);
        reg(new ColorAttribute(KEY_GUTTER_TEXT, "Numéros de ligne", ColorAttribute.GROUP_GUTTER, KEY_TEXT),
            t -> t.gutterText);

        reg(new ColorAttribute(KEY_CARET, "Curseur", ColorAttribute.GROUP_CARET, null),
            t -> t.caret);
        reg(new ColorAttribute(KEY_SELECTION, "Sélection", ColorAttribute.GROUP_CARET, null),
            t -> t.selection);

        reg(new ColorAttribute(KEY_ERROR, "Erreur", ColorAttribute.GROUP_DIAGNOSTICS, null),
            t -> t.error);
        reg(new ColorAttribute(KEY_WARNING, "Avertissement", ColorAttribute.GROUP_DIAGNOSTICS, null),
            t -> t.warning);
        reg(new ColorAttribute(KEY_INFO, "Information", ColorAttribute.GROUP_DIAGNOSTICS, null),
            t -> t.info);

        reg(new ColorAttribute(KEY_KEYWORD, "Mots-clés", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.keyword);
        reg(new ColorAttribute(KEY_LABEL, "Labels (case, goto)", ColorAttribute.GROUP_SYNTAX, KEY_KEYWORD),
            t -> t.label);
        reg(new ColorAttribute(KEY_STRING, "Chaînes", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.string);
        reg(new ColorAttribute(KEY_ESCAPE, "Séquences d'échappement", ColorAttribute.GROUP_SYNTAX, KEY_STRING),
            t -> t.escape);
        reg(new ColorAttribute(KEY_REGEXP, "Expressions régulières", ColorAttribute.GROUP_SYNTAX, KEY_STRING),
            t -> t.regexp);
        reg(new ColorAttribute(KEY_COMMENT, "Commentaires", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.comment);
        reg(new ColorAttribute(KEY_NUMBER, "Nombres", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.number);
        reg(new ColorAttribute(KEY_CONSTANT, "Constantes (ALL_CAPS, enum)", ColorAttribute.GROUP_SYNTAX, KEY_NUMBER),
            t -> t.constant);
        reg(new ColorAttribute(KEY_ANNOTATION, "Annotations", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.annotation);
        reg(new ColorAttribute(KEY_FUNC, "Fonctions", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.func);
        reg(new ColorAttribute(KEY_TYPE, "Types", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.type);
        reg(new ColorAttribute(KEY_PUNCT, "Ponctuation", ColorAttribute.GROUP_SYNTAX, null),
            t -> t.punct);
        reg(new ColorAttribute(KEY_OPERATOR, "Opérateurs", ColorAttribute.GROUP_SYNTAX, KEY_PUNCT),
            t -> t.operator);
        reg(new ColorAttribute(KEY_VARIABLE, "Variables", ColorAttribute.GROUP_SYNTAX, KEY_TEXT),
            t -> t.variable);
        reg(new ColorAttribute(KEY_PROPERTY, "Propriétés d'objet", ColorAttribute.GROUP_SYNTAX, KEY_VARIABLE),
            t -> t.property);

        reg(new ColorAttribute(KEY_FIND_MATCH, "Résultat de recherche", ColorAttribute.GROUP_SEARCH, null),
            t -> t.findMatch);
        reg(new ColorAttribute(KEY_FIND_CURRENT, "Résultat courant", ColorAttribute.GROUP_SEARCH, null),
            t -> t.findCurrent);
        reg(new ColorAttribute(KEY_OCCURRENCE, "Occurrences du symbole", ColorAttribute.GROUP_SEARCH, null),
            t -> t.occurrence);

        reg(new ColorAttribute(KEY_INDENT_GUIDE, "Guides d'indentation", ColorAttribute.GROUP_CHROME, KEY_GUTTER_BORDER),
            t -> t.indentGuide);
        reg(new ColorAttribute(KEY_COMPOSING, "Texte en composition (IME)", ColorAttribute.GROUP_CHROME, null),
            t -> t.composing);

        // ── Distinctions fines (lot 4 #26) — attributs VIRTUELS :
        // aucune couleur propre (délèguent au parent) — rien ne change
        // visuellement tant qu'un scheme ne les sépare pas.
        regVirtual(new ColorAttribute("syntax.docComment", "Commentaires de documentation",
                ColorAttribute.GROUP_SYNTAX, KEY_COMMENT));
        regVirtual(new ColorAttribute("syntax.keywordControl", "Contrôle de flux (if, for, return…)",
                ColorAttribute.GROUP_SYNTAX, KEY_KEYWORD));
        regVirtual(new ColorAttribute("syntax.keywordModifier", "Modificateurs (public, static…)",
                ColorAttribute.GROUP_SYNTAX, KEY_KEYWORD));
        regVirtual(new ColorAttribute("syntax.char", "Caractères ('a')",
                ColorAttribute.GROUP_SYNTAX, KEY_STRING));
        regVirtual(new ColorAttribute("syntax.stringRaw", "Chaînes brutes (\"\"\"…\"\"\")",
                ColorAttribute.GROUP_SYNTAX, KEY_STRING));
        regVirtual(new ColorAttribute("syntax.namespace", "Espaces de nom XML",
                ColorAttribute.GROUP_SYNTAX, KEY_TYPE));
        regVirtual(new ColorAttribute("syntax.entity", "Entités XML (&amp;…)",
                ColorAttribute.GROUP_SYNTAX, KEY_ESCAPE));
        regVirtual(new ColorAttribute("syntax.emphasis", "Emphase Markdown (**gras**, _italique_)",
                ColorAttribute.GROUP_SYNTAX, KEY_ANNOTATION));
    }

    private ColorAttributes() {
    }

    /** Registre complet (non modifiable), parents avant enfants. */
    public static List<ColorAttribute> all() {
        return Collections.unmodifiableList(ATTRIBUTES);
    }

    /** Attribut par clé, ou null. */
    @Nullable
    public static ColorAttribute byKey(String key) {
        if (key == null) return null;
        for (ColorAttribute a : ATTRIBUTES) {
            if (a.key.equals(key)) return a;
        }
        return null;
    }

    /** Attributs d'un groupe, dans l'ordre du registre. */
    public static List<ColorAttribute> byGroup(String group) {
        List<ColorAttribute> out = new ArrayList<>();
        for (ColorAttribute a : ATTRIBUTES) {
            if (a.group.equals(group)) out.add(a);
        }
        return out;
    }

    /** Valide qu'une clé appartient bien au registre. */
    public static boolean isValidKey(String key) {
        return GETTERS.containsKey(key);
    }

    /** Clé VIRTUELLE (sans champ dédié dans EditorTheme) ? */
    public static boolean isVirtual(String key) {
        return VIRTUAL.containsKey(key);
    }

    /** Toutes les clés (itérable stable). */
    public static List<String> keys() {
        return new ArrayList<>(GETTERS.keySet());
    }

    /** Lecture de la valeur brute d'un attribut sur un thème. Les clés
     *  virtuelles délèguent à leur parent (couleur héritée). */
    public static int get(EditorTheme theme, String key) {
        ToIntFunction<EditorTheme> g = GETTERS.get(key);
        if (g == null) throw new IllegalArgumentException("Clé inconnue : " + key);
        return g.applyAsInt(theme);
    }
}
