package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ★ Tests du badge de type du popup de complétion
 * ({@link CompletionKindBadge}, portage du KindBadge de CodeAssist).
 *
 * <p>Chaque kind LSP moderne doit produire le BON glyphe — « K » pour un
 * mot-clé (le badge rend visible le type des suggestions), « C » classe,
 * « I » interface, « E » enum, « M » méthode, « F » champ, « v » variable,
 * « p » package, « {} » snippet, « T » paramètre de type, « # » constante
 * d'enum, « @ » annotation (via le tag data), « R » record.</p>
 *
 * @author jo@Dev
 */
class CompletionKindBadgeTest {

    private static final int ACCENT = 0xFFABCDEF;

    private static String glyph(int kind) {
        return CompletionKindBadge.meta(kind, null, null, true, ACCENT).glyph;
    }

    private static String glyph(int kind, String tag) {
        return CompletionKindBadge.meta(kind, tag, null, true, ACCENT).glyph;
    }

    @Test
    void keywordShowsK() {
        assertEquals("K", glyph(14)); // LSP Keyword
    }

    @Test
    void classInterfaceEnumGlyphs() {
        assertEquals("C", glyph(7));  // Class
        assertEquals("I", glyph(8));  // Interface
        assertEquals("E", glyph(13)); // Enum
        assertEquals("#", glyph(20)); // EnumMember
        assertEquals("T", glyph(25)); // TypeParameter
    }

    @Test
    void memberGlyphs() {
        assertEquals("M", glyph(2));  // Method
        assertEquals("M", glyph(3));  // Function
        assertEquals("M", glyph(4));  // Constructor
        assertEquals("F", glyph(5));  // Field
        assertEquals("F", glyph(10)); // Property
        assertEquals("F", glyph(21)); // Constant
        assertEquals("v", glyph(6));  // Variable
        assertEquals("v", glyph(12)); // Value
    }

    @Test
    void packageModuleFolderShowP() {
        assertEquals("p", glyph(9));  // Module (les packages lspjava)
        assertEquals("p", glyph(19)); // Folder
    }

    @Test
    void snippetShowsBraces() {
        assertEquals("{}", glyph(15));
    }

    @Test
    void kindTagsOverrideGlyph() {
        // Annotation Java : voyage en Class + data="annotation" → « @ »
        assertEquals("@", glyph(7, "annotation"));
        // Record : voyage en Class + data="record" → « R »
        assertEquals("R", glyph(7, "record"));
        // Package explicite → « p » même si le kind est Class
        assertEquals("p", glyph(7, "package"));
    }

    @Test
    void legacyIconFallbackWhenKindUnknown() {
        // Providers sans kindCode : le badge dérive de l'icône string.
        assertEquals("K", CompletionKindBadge.meta(0, null, "k", true, ACCENT).glyph);
        assertEquals("M", CompletionKindBadge.meta(0, null, "m", true, ACCENT).glyph);
        assertEquals("F", CompletionKindBadge.meta(0, null, "f", true, ACCENT).glyph);
        assertEquals("C", CompletionKindBadge.meta(0, null, "c", true, ACCENT).glyph);
        assertEquals("I", CompletionKindBadge.meta(0, null, "i", true, ACCENT).glyph);
        assertEquals("E", CompletionKindBadge.meta(0, null, "e", true, ACCENT).glyph);
        assertEquals("p", CompletionKindBadge.meta(0, null, "p", true, ACCENT).glyph);
    }

    @Test
    void methodUsesThemeAccent() {
        assertEquals(ACCENT,
                CompletionKindBadge.meta(2, null, null, true, ACCENT).color);
        assertEquals(ACCENT,
                CompletionKindBadge.meta(4, null, null, false, ACCENT).color);
    }

    @Test
    void keywordKeepsCodeAssistPurple() {
        assertEquals(0xFFCD7EE0,
                CompletionKindBadge.meta(14, null, null, true, ACCENT).color);
    }

    @Test
    void darkThemeKeepsPaletteLightThemeDarkens() {
        int darkClass = CompletionKindBadge.meta(7, null, null, true, ACCENT).color;
        int lightClass = CompletionKindBadge.meta(7, null, null, false, ACCENT).color;
        assertEquals(0xFFE6C178, darkClass);       // palette CodeAssist telle quelle
        assertTrue(lightClass < darkClass,         // assombrie sur fond clair
                "la couleur badge doit être assombrie pour un thème clair");
        // L'alpha est préservé.
        assertEquals(0xFF000000 & lightClass, 0xFF000000);
    }

    @Test
    void glyphSizeFactorShrinksMultiCharGlyph() {
        assertEquals(0.56f, CompletionKindBadge.glyphSizeFactor("K"));
        assertEquals(0.42f, CompletionKindBadge.glyphSizeFactor("{}"));
    }

    @Test
    void unknownKindFallsBackToVariable() {
        assertEquals("v", glyph(0));
        assertEquals("v", glyph(16)); // Color
        assertEquals("v", glyph(17)); // File
        assertEquals("v", glyph(23)); // Event
    }
}
