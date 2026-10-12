package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la résolution de la syntaxe de commentaire pilotée par le
 * langage :
 * <ul>
 *   <li>chaque famille de langages résout vers les préfixes attendus
 *       (famille C, langages à dièse, bloc seul pour la famille XML, aucun
 *       pour JSON, Lua, SQL) ;</li>
 *   <li>les alias (py, js, ts, rs, rb, sh, kt…) résolvent comme leurs formes
 *       longues ;</li>
 *   <li>les identifiants inconnus/nuls/vides retombent sur le défaut
 *       famille C (conserve le comportement historique Java-first) ;</li>
 *   <li>la normalisation est sûre au sens Locale.ROOT (le İ turc ne casse
 *       pas la mise en minuscules de "PYTHON").</li>
 * </ul>
 */
class CommentSyntaxTest {

    // ── Résolution par famille ────────────────────────────────────

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
                "swift", "dart", "groovy", "gradle", "scala"};
        // Lot 1 (audit v3.41.0) : CSS retiré de cette liste — il
        // n'a pas de commentaire de ligne //, seulement des
        // commentaires de bloc /* … */ (cf. css_hasBlockCommentOnly_noLineComment).
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

    // ── Replis & normalisation ───────────────────────────────────

    @Test
    void nullAndUnknownLanguagesFallBackToCStyle() {
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage(null));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage(""));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("   "));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("brainfuck"));
    }

    @Test
    void languageIdsAreTrimmedAndLowercasedWithRootLocale() {
        // Piège du locale turc : "PYTHON".toLowerCase(new Locale("tr"))
        // produirait "pythOn" (problème de i sans point) et raterait la
        // table — Locale.ROOT garde la résolution déterministe.
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

    // ── Lot 1 (audit v3.41.0) — CSS, log, texte brut ───────────────

    /**
     * CSS ne reconnaît pas les commentaires de ligne {@code //}.
     * {@code Ctrl+/} dans un fichier CSS doit insérer
     * {@code /* … *\/} (bloc sur une ligne) et non {@code // …}.
     */
    @Test
    void css_hasBlockCommentOnly_noLineComment() {
        CommentSyntax css = CommentSyntax.forLanguage("css");
        assertFalse(css.hasLine(),
                "CSS ne supporte pas les commentaires de ligne // — "
                        + "Ctrl+/ ne doit pas insérer de // en CSS");
        assertTrue(css.hasBlock(),
                "CSS supporte les commentaires de bloc /* … */");
        assertEquals("/*", css.blockStart);
        assertEquals("*/", css.blockEnd);
        assertNull(css.lineComment,
                "CSS n'a pas de préfixe de commentaire de ligne");
    }

    /**
     * Les fichiers de log ne sont pas édités comme du code — il n'y
     * a ni commentaire de ligne ni commentaire de bloc à insérer.
     * {@code Ctrl+/} sur un fichier .log doit être un no-op.
     */
    @Test
    void log_hasNoCommentSyntax() {
        CommentSyntax log = CommentSyntax.forLanguage("log");
        assertFalse(log.hasLine(),
                "Les fichiers de log n'ont pas de commentaire de ligne");
        assertFalse(log.hasBlock(),
                "Les fichiers de log n'ont pas de commentaire de bloc");
        assertEquals(CommentSyntax.NONE, log);
    }

    /**
     * Le texte brut ({@code text}, {@code plaintext}) ne supporte
     * aucune syntaxe de commentaire de code. {@code Ctrl+/} doit
     * être un no-op.
     */
    @Test
    void plainText_hasNoCommentSyntax() {
        CommentSyntax text = CommentSyntax.forLanguage("text");
        CommentSyntax plaintext = CommentSyntax.forLanguage("plaintext");
        assertEquals(CommentSyntax.NONE, text,
                "text n'a pas de syntaxe de commentaire");
        assertEquals(CommentSyntax.NONE, plaintext,
                "plaintext n'a pas de syntaxe de commentaire");
        assertFalse(text.hasLine());
        assertFalse(text.hasBlock());
        assertFalse(plaintext.hasLine());
        assertFalse(plaintext.hasBlock());
    }
}
