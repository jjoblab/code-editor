package jo.codeeditor.demo;

import jo.codeeditor.theme.ColorAttributes;
import jo.codeeditor.theme.EditorColorScheme;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Thèmes et scheme de la démo — « émeraude », assortis à l'habillage
 * Material 3 zinc/émeraude de l'application (voir res/values/themes.xml).
 *
 * <p>La construction passe par le Builder <em>par clés du registre</em>
 * ({@link EditorTheme#from(EditorTheme)}) plutôt qu'en positionnel :
 * plus lisible, et surtout ça permet de poser les 8 attributs VIRTUELS
 * du lot 4 (#26 — distinctions fines de tokens). Sans eux, ces types
 * retombent silencieusement sur la couleur de leur parent — rien ne les
 * sépare visuellement du reste du texte.</p>
 */
final class DemoTheme {

    /** Thème « nuit émeraude » — base sombre de la démo. */
    static EditorTheme emeraldNight() {
        return EditorTheme.from(EditorTheme.dark())
            .set("editor.background", 0xFF09090B)
            .set("gutter.background", 0xFF09090B)
            .set("gutter.text", 0xFF52525B)
            .set("gutter.border", 0xFF27272A)
            .set("caret", 0xFF34D399)
            .set("selection", 0xFF0A5B47)
            .set("editor.currentLine", 0xFF111114)
            .set("diagnostics.error", 0xFFF87171)
            .set("diagnostics.warning", 0xFFFBBF24)
            .set("diagnostics.info", 0xFF7DD3FC)
            .set("syntax.keyword", 0xFF34D399)
            .set("syntax.string", 0xFFD8A657)
            .set("syntax.comment", 0xFF7C8494)
            .set("syntax.number", 0xFFC4B5FD)
            .set("syntax.annotation", 0xFFD7BA7D)
            .set("syntax.func", 0xFFE5C890)
            .set("syntax.type", 0xFF5EEAD4)
            .set("syntax.punct", 0xFFD4D4D8)
            .set("syntax.operator", 0xFFD4D4D8)
            .set("syntax.escape", 0xFFD8A657)
            .set("syntax.label", 0xFFA7F3D0)
            .set("syntax.property", 0xFF93C5FD)
            .set("syntax.variable", 0xFF93C5FD)
            .set("syntax.constant", 0xFF7DD3FC)
            .set("syntax.regexp", 0xFFF0ABFC)
            .set("search.match", 0xFF5A4426)
            .set("search.current", 0xFF6B6B2A)
            .set("search.occurrence", 0xFF57572C)
            .set("chrome.indentGuide", 0xFF27272A)
            .set("chrome.composing", 0xFF3F3F46)
            .set("text.foreground", 0xFFE4E4E7)
            // ── Distinctions fines (lot 4 #26) ──────────────────
            .set("syntax.docComment", 0xFF8FB79F)      /** javadoc : jade doux */
            .set("syntax.keywordControl", 0xFF4ADE80)  // if/for/return : émeraude vif
            .set("syntax.keywordModifier", 0xFF5EEAD4) // public/static : teal
            .set("syntax.char", 0xFFE8C07D)            // 'a' : ambre clair
            .set("syntax.stringRaw", 0xFFDFAE6B)       // """raw""" : ambre orangé
            .set("syntax.namespace", 0xFF7DD3FC)       // ns XML : azur
            .set("syntax.entity", 0xFFF9A8D4)          // &amp; : rose
            .set("syntax.emphasis", 0xFFFDE68A)        // **gras** markdown : jaune doux
            .build();
    }

    /** Thème « jour émeraude » — pendant clair du toggle Thème. */
    static EditorTheme emeraldDay() {
        return EditorTheme.from(EditorTheme.light())
            .set("editor.background", 0xFFFAFAF9)
            .set("gutter.background", 0xFFFAFAF9)
            .set("gutter.text", 0xFFA1A1AA)
            .set("gutter.border", 0xFFE4E4E7)
            .set("caret", 0xFF059669)
            .set("selection", 0xFFA7F3D0)
            .set("editor.currentLine", 0xFFF4F4F5)
            .set("diagnostics.error", 0xFFDC2626)
            .set("diagnostics.warning", 0xFFB45309)
            .set("diagnostics.info", 0xFF0369A1)
            .set("syntax.keyword", 0xFF047857)
            .set("syntax.string", 0xFF92400E)
            .set("syntax.comment", 0xFF78716C)
            .set("syntax.number", 0xFF6D28D9)
            .set("syntax.annotation", 0xFF92670C)
            .set("syntax.func", 0xFF8F6400)
            .set("syntax.type", 0xFF0F766E)
            .set("syntax.punct", 0xFF57534E)
            .set("syntax.operator", 0xFF57534E)
            .set("syntax.escape", 0xFFB45309)
            .set("syntax.label", 0xFF065F46)
            .set("syntax.property", 0xFF1D4ED8)
            .set("syntax.variable", 0xFF1E3A8A)
            .set("syntax.constant", 0xFF0369A1)
            .set("syntax.regexp", 0xFFA21CAF)
            .set("search.match", 0xFFFDE68A)
            .set("search.current", 0xFFFCD34D)
            .set("search.occurrence", 0xFFE7E5E4)
            .set("chrome.indentGuide", 0xFFE7E5E4)
            .set("chrome.composing", 0xFFE7E5E4)
            .set("text.foreground", 0xFF1C1917)
            // ── Distinctions fines (lot 4 #26), teintes claires ─
            .set("syntax.docComment", 0xFF6B8F71)
            .set("syntax.keywordControl", 0xFF059669)
            .set("syntax.keywordModifier", 0xFF0D9488)
            .set("syntax.char", 0xFFA16207)
            .set("syntax.stringRaw", 0xFF854D0E)
            .set("syntax.namespace", 0xFF0369A1)
            .set("syntax.entity", 0xFFBE185D)
            .set("syntax.emphasis", 0xFFB45309)
            .build();
    }

    /**
     * Scheme « crépuscule ambré » (lot 4 #25) — preset de démonstration
     * de la cascade : sélection, caret, commentaires et fonctions
     * recolorés dans les DEUX modes, tout le reste suit le thème de
     * référence. Le scheme porte aussi une opinion sur un attribut
     * VIRTUEL ({@code syntax.docComment}) : un scheme peut distinguer
     * les tokens fins sans toucher au thème lui-même.
     */
    static EditorColorScheme duskScheme() {
        EditorColorScheme s = EditorColorScheme.create(
                "dusk", "Crépuscule ambré", "emerald-night");
        // Mode sombre — la sélection passe à l'ambre, le caret à l'or.
        s.setColor(ColorAttributes.KEY_SELECTION, 0xFF5C4216, true);
        s.setColor(ColorAttributes.KEY_CARET, 0xFFFBBF24, true);
        s.setColor(ColorAttributes.KEY_COMMENT, 0xFFA89678, true);
        s.setColor(ColorAttributes.KEY_FUNC, 0xFFFCD34D, true);
        s.setColor("syntax.docComment", 0xFFB5A642, true);
        // Mode clair — pendant de jour.
        s.setColor(ColorAttributes.KEY_SELECTION, 0xFFFDE9C8, false);
        s.setColor(ColorAttributes.KEY_CARET, 0xFFB45309, false);
        s.setColor(ColorAttributes.KEY_COMMENT, 0xFF92826B, false);
        s.setColor(ColorAttributes.KEY_FUNC, 0xFF92400E, false);
        s.setColor("syntax.docComment", 0xFF8A7A2E, false);
        return s;
    }

    private DemoTheme() {
    }
}
