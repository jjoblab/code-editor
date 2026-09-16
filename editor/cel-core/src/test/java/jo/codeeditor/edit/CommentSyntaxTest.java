package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.35.0 (roadmap item 1 / bug B11) — tests of the language-driven comment
 * syntax resolution:
 * <ul>
 *   <li>every language family resolves to the expected prefixes (C-family,
 *       hash languages, XML-family block-only, JSON none, Lua, SQL);</li>
 *   <li>aliases (py, js, ts, rs, rb, sh, kt…) resolve like their long
 *       forms;</li>
 *   <li>unknown/null/blank ids fall back to the C-family default (preserves
 *       the pre-v3.35.0 Java-first behavior);</li>
 *   <li>normalization is Locale.ROOT-safe (Turkish İ does not break the
 *       lowercase of "PYTHON").</li>
 * </ul>
 */
class CommentSyntaxTest {

    // ── Family resolution ────────────────────────────────────────────

    @Test
    void javaResolvesToCStyle() {
        CommentSyntax cs = CommentSyntax.forLanguage("java");
        assertEquals("//", cs.lineComment);
        assertEquals("/*", cs.blockStart);
        assertEquals("*/", cs.blockEnd);
        assertTrue(cs.hasLine());
        assertTrue(cs.hasBlock());
        assertSame(CommentSyntax.C_STYLE, cs);
    }

    @Test
    void cFamilyLanguagesAllResolveToSlashSyntax() {
        String[] langs = {"java", "kotlin", "kt", "c", "h", "cpp", "cc", "hpp",
                "cxx", "go", "rust", "javascript", "typescript", "php",
                "swift", "dart", "groovy", "gradle", "scala", "css"};
        for (String lang : langs) {
            CommentSyntax cs = CommentSyntax.forLanguage(lang);
            assertEquals("//", cs.lineComment, lang);
            assertEquals("/*", cs.blockStart, lang);
            assertEquals("*/", cs.blockEnd, lang);
        }
    }

    @Test
    void pythonResolvesToHashWithoutBlock() {
        CommentSyntax cs = CommentSyntax.forLanguage("python");
        assertEquals("#", cs.lineComment);
        assertNull(cs.blockStart);
        assertNull(cs.blockEnd);
        assertTrue(cs.hasLine());
        assertFalse(cs.hasBlock());
        // Alias
        assertEquals(cs, CommentSyntax.forLanguage("py"));
    }

    @Test
    void hashLanguagesResolveToHash() {
        String[] langs = {"python", "py", "ruby", "rb", "shell", "bash", "sh",
                "toml", "properties", "smali", "yaml", "yml"};
        for (String lang : langs) {
            CommentSyntax cs = CommentSyntax.forLanguage(lang);
            assertEquals("#", cs.lineComment, lang);
            assertFalse(cs.hasBlock(), lang + " ne doit pas avoir de bloc");
        }
    }

    @Test
    void xmlFamilyIsBlockOnly() {
        for (String lang : new String[]{"xml", "html", "htm", "svg", "markdown", "md"}) {
            CommentSyntax cs = CommentSyntax.forLanguage(lang);
            assertNull(cs.lineComment, lang + " n'a pas de commentaire de ligne");
            assertEquals("<!--", cs.blockStart, lang);
            assertEquals("-->", cs.blockEnd, lang);
            assertFalse(cs.hasLine());
            assertTrue(cs.hasBlock());
        }
    }

    @Test
    void jsonHasNoCommentSyntaxAtAll() {
        CommentSyntax cs = CommentSyntax.forLanguage("json");
        assertNull(cs.lineComment);
        assertNull(cs.blockStart);
        assertNull(cs.blockEnd);
        assertFalse(cs.hasLine());
        assertFalse(cs.hasBlock());
        assertSame(CommentSyntax.NONE, cs);
    }

    @Test
    void luaResolvesToDashAndLongBracket() {
        CommentSyntax cs = CommentSyntax.forLanguage("lua");
        assertEquals("--", cs.lineComment);
        assertEquals("--[[", cs.blockStart);
        assertEquals("]]", cs.blockEnd);
    }

    @Test
    void sqlResolvesToDashLineAndCStyleBlock() {
        CommentSyntax cs = CommentSyntax.forLanguage("sql");
        assertEquals("--", cs.lineComment);
        assertEquals("/*", cs.blockStart);
        assertEquals("*/", cs.blockEnd);
    }

    // ── Fallbacks & normalization ───────────────────────────────────

    @Test
    void nullAndUnknownLanguagesFallBackToCStyle() {
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage(null));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage(""));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("   "));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("brainfuck"));
    }

    @Test
    void languageIdsAreTrimmedAndLowercasedWithRootLocale() {
        // Turkish locale pitfall: "PYTHON".toLowerCase(new Locale("tr"))
        // would produce "pythOn" (dotless i issues) and miss the table —
        // Locale.ROOT keeps the resolution deterministic.
        assertEquals(CommentSyntax.forLanguage("python"),
                CommentSyntax.forLanguage("  PYTHON "));
        assertEquals(CommentSyntax.forLanguage("xml"),
                CommentSyntax.forLanguage("XML"));
        assertEquals(CommentSyntax.forLanguage("json"),
                CommentSyntax.forLanguage(" Json "));
    }

    @Test
    void aliasesResolveLikeLongForms() {
        assertEquals(CommentSyntax.forLanguage("python"), CommentSyntax.forLanguage("py"));
        assertEquals(CommentSyntax.forLanguage("javascript"), CommentSyntax.forLanguage("js"));
        assertEquals(CommentSyntax.forLanguage("typescript"), CommentSyntax.forLanguage("ts"));
        assertEquals(CommentSyntax.forLanguage("rust"), CommentSyntax.forLanguage("rs"));
        assertEquals(CommentSyntax.forLanguage("ruby"), CommentSyntax.forLanguage("rb"));
        assertEquals(CommentSyntax.forLanguage("kotlin"), CommentSyntax.forLanguage("kt"));
        assertEquals(CommentSyntax.forLanguage("markdown"), CommentSyntax.forLanguage("md"));
    }

    @Test
    void equalityIsValueBased() {
        assertEquals(new CommentSyntax("#", null, null),
                CommentSyntax.forLanguage("python"));
        assertEquals(new CommentSyntax(null, "<!--", "-->"),
                CommentSyntax.forLanguage("xml"));
        assertEquals(new CommentSyntax("//", "/*", "*/"),
                CommentSyntax.forLanguage("go"));
        assertFalse(CommentSyntax.forLanguage("python")
                .equals(CommentSyntax.forLanguage("java")));
    }
}
