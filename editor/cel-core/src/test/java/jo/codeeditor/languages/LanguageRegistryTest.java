package jo.codeeditor.languages;

import jo.codeeditor.edit.CommentSyntax;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.highlight.TokenType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3.36.0 — Tests du registre de langages contribuables (roadmap item 7,
 * portage {@code EditorLanguageRegistry} de CodeAssist v3.20).
 *
 * <p>Couvrent : les lookups (nom canonique, alias, casse, extensions), la
 * table intégrée (familles + mots-clés identiques à v3.35.0), le routage
 * tokenizer du highlighter via la famille (y compris les corrections
 * d'alias py/md/svg/htm/ini/kt/rs), la résolution des commentaires, et le
 * comportement contribuable : register/unregister/override + listeners.</p>
 *
 * @since v3.36.0
 */
public class LanguageRegistryTest {

    @AfterEach
    void cleanup() {
        // Reinstall the pristine built-in table — some tests override
        // built-ins (register replaces mappings in place).
        LanguageRegistry.resetToBuiltins();
    }

    // ── Lookups ───────────────────────────────────────────────────

    @Test
    public void forName_resolvesCanonicalAndAliases() {
        assertNotNull(LanguageRegistry.forName("python"));
        assertNotNull(LanguageRegistry.forName("py"));
        assertNotNull(LanguageRegistry.forName("PYTHON"));   // case-insensitive
        assertNotNull(LanguageRegistry.forName("  Kotlin ")); // trimmed
        assertNotNull(LanguageRegistry.forName("kt"));
        assertNotNull(LanguageRegistry.forName("rs"));
        assertNotNull(LanguageRegistry.forName("gradle"));
        assertNotNull(LanguageRegistry.forName("md"));
        assertNotNull(LanguageRegistry.forName("htm"));
        // Unknown ids resolve to nothing — callers keep their fallback.
        assertNull(LanguageRegistry.forName("pascal"));
        assertNull(LanguageRegistry.forName(""));
        assertNull(LanguageRegistry.forName(null));
    }

    @Test
    public void forExtension_resolvesWithAndWithoutDot() {
        assertEquals("python", LanguageRegistry.forExtension("py").name);
        assertEquals("python", LanguageRegistry.forExtension(".py").name);
        assertEquals("python", LanguageRegistry.forExtension("PY").name);
        assertEquals("kotlin", LanguageRegistry.forExtension("kt").name);
        assertEquals("xml", LanguageRegistry.forExtension("html").name);
        assertEquals("shell", LanguageRegistry.forExtension("sh").name);
        assertNull(LanguageRegistry.forExtension("xyzzy"));
        assertNull(LanguageRegistry.forExtension(null));
    }

    @Test
    public void builtins_haveExpectedFamiliesAndKeywords() {
        assertEquals(SyntaxFamily.PYTHON, LanguageRegistry.forName("python").family);
        assertEquals(SyntaxFamily.XML, LanguageRegistry.forName("svg").family);
        assertEquals(SyntaxFamily.MARKDOWN, LanguageRegistry.forName("md").family);
        assertEquals(SyntaxFamily.PROPERTIES, LanguageRegistry.forName("ini").family);
        assertEquals(SyntaxFamily.C_LIKE, LanguageRegistry.forName("java").family);
        assertEquals(SyntaxFamily.C_LIKE, LanguageRegistry.forName("kotlin").family);
        assertEquals(SyntaxFamily.SHELL, LanguageRegistry.forName("bash").family);

        // Keyword sets are the v3.35.0 tables (moved verbatim).
        assertTrue(LanguageRegistry.forName("java").keywords.contains("instanceof"));
        assertTrue(LanguageRegistry.forName("kotlin").keywords.contains("fun"));
        assertTrue(LanguageRegistry.forName("rust").keywords.contains("fn"));
        assertFalse(LanguageRegistry.forName("java").keywords.contains("fn"));
        // Scala kept its historical Java-keyword fallback (v3.35.0 behavior).
        assertTrue(LanguageRegistry.forName("scala").keywords.contains("instanceof"));
        // Hash languages still have their keyword sets.
        assertTrue(LanguageRegistry.forName("python").keywords.contains("def"));
        assertTrue(LanguageRegistry.forName("shell").keywords.contains("esac"));
    }

    @Test
    public void registeredNames_containsTheBuiltins() {
        var names = LanguageRegistry.registeredNames();
        assertTrue(names.contains("java"));
        assertTrue(names.contains("python"));
        assertTrue(names.contains("markdown"));
        assertTrue(names.contains("javascript"));
        assertTrue(names.contains("typescript"));
        assertTrue(names.contains("groovy"));
        assertTrue(names.contains("scala"));
        assertTrue(names.contains("log"));
        assertTrue(names.contains("css"));
        // Aliases are NOT canonical names.
        assertFalse(names.contains("py"));
        assertFalse(names.contains("js"));
    }

    // ── Highlighter routing through the registry ──────────────────

    @Test
    public void highlighter_familyRouting_coversAliases() {
        SyntaxHighlighter hl = new SyntaxHighlighter();

        // "py" now reaches the PYTHON tokenizer: '#' is a comment.
        StyledLine py = hl.styleLine("x = 1  # note", 0, "py");
        assertTrue(hasToken(py, TokenType.COMMENT), "'py' alias must use the Python tokenizer (# comment)");

        // "md" now reaches the Markdown tokenizer (heading recognized).
        StyledLine md = hl.styleLine("# Title", 0, "md");
        assertTrue(!tokens(md).isEmpty(), "'md' alias must use the Markdown tokenizer");

        // "svg"/"htm" now reach the XML tokenizer: '<' starts a tag.
        StyledLine svg = hl.styleLine("<shape/>", 0, "svg");
        assertTrue(!tokens(svg).isEmpty(), "'svg' alias must use the XML tokenizer");

        // Canonical names unchanged.
        StyledLine java = hl.styleLine("int x = 42;", 0, "java");
        assertTrue(hasToken(java, TokenType.KEYWORD));
        StyledLine unknown = hl.styleLine("int x = 42;", 0, "pascal");
        assertTrue(hasToken(unknown, TokenType.KEYWORD),
                "unknown ids keep the Java-keyword fallback");
    }

    @Test
    public void highlighter_keywordsForAliases() {
        SyntaxHighlighter hl = new SyntaxHighlighter();
        // "kt" now resolves Kotlin keywords (v3.35.0: fell back to Java's).
        StyledLine kt = hl.styleLine("fun main() {}", 0, "kt");
        assertTrue(hasToken(kt, TokenType.KEYWORD), "'kt' must use Kotlin keywords");
        // "rs" resolves Rust keywords.
        StyledLine rs = hl.styleLine("fn main() {}", 0, "rs");
        assertTrue(hasToken(rs, TokenType.KEYWORD), "'rs' must use Rust keywords");
    }

    // ── Comment syntax resolution through the registry ────────────

    @Test
    public void commentSyntax_tableMatchesV3350Behavior() {
        // Hash languages
        for (String id : new String[]{"python", "py", "ruby", "rb", "shell", "bash",
                "sh", "toml", "properties", "ini", "smali", "yaml", "yml"}) {
            CommentSyntax cs = CommentSyntax.forLanguage(id);
            assertEquals("#", cs.lineComment, id + " keeps its # line comment");
            assertFalse(cs.hasBlock(), id + " has no block comment");
        }
        // XML family: block only
        for (String id : new String[]{"xml", "html", "htm", "svg", "markdown", "md"}) {
            CommentSyntax cs = CommentSyntax.forLanguage(id);
            assertFalse(cs.hasLine(), id + " has no line comment");
            assertEquals("<!--", cs.blockStart, id + " keeps its XML block comment");
            assertEquals("-->", cs.blockEnd);
        }
        // JSON: no comments at all
        assertSame(CommentSyntax.NONE, CommentSyntax.forLanguage("json"));
        // Lua / SQL specifics
        assertEquals("--", CommentSyntax.forLanguage("lua").lineComment);
        assertEquals("--[[", CommentSyntax.forLanguage("lua").blockStart);
        assertEquals("]]", CommentSyntax.forLanguage("lua").blockEnd);
        assertEquals("--", CommentSyntax.forLanguage("sql").lineComment);
        assertEquals("/*", CommentSyntax.forLanguage("sql").blockStart);
        assertEquals("*/", CommentSyntax.forLanguage("sql").blockEnd);
        // C-family + unknown
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("java"));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("kotlin"));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("css"));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("log"));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("pascal"));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage(null));
        assertSame(CommentSyntax.C_STYLE, CommentSyntax.forLanguage("  "));
    }

    // ── Contributable: register / unregister / override / listeners ──

    @Test
    public void register_customLanguage_isUsedByHighlighterAndComments() {
        LanguageProfile mylang = LanguageProfile.builder("mylang")
                .family(SyntaxFamily.C_LIKE)
                .alias("ml")
                .extension("ml")
                .keywords("if", "else", "repeat", "until")
                .commentSyntax(new CommentSyntax("#", null, null))
                .build();
        LanguageRegistry.register(mylang);

        // Lookup by name and alias.
        assertSame(mylang, LanguageRegistry.forName("mylang"));
        assertSame(mylang, LanguageRegistry.forName("ml"));
        assertSame(mylang, LanguageRegistry.forExtension("ml"));

        // The highlighter honors the custom keyword set.
        SyntaxHighlighter hl = new SyntaxHighlighter();
        StyledLine line = hl.styleLine("repeat x = 1", 0, "mylang");
        assertTrue(hasToken(line, TokenType.KEYWORD), "custom keywords must highlight");

        // The comment toggle honors the custom syntax.
        assertEquals("#", CommentSyntax.forLanguage("mylang").lineComment);
        assertEquals("#", CommentSyntax.forLanguage("ml").lineComment);
    }

    @Test
    public void register_customPythonFamilyLanguage_routesToPythonTokenizer() {
        LanguageRegistry.register(LanguageProfile.builder("testlang")
                .family(SyntaxFamily.PYTHON)
                .keywords("foo")
                .commentSyntax(new CommentSyntax("#", null, null))
                .build());
        SyntaxHighlighter hl = new SyntaxHighlighter();
        StyledLine line = hl.styleLine("x = 1  # note", 0, "testlang");
        assertTrue(hasToken(line, TokenType.COMMENT),
                "a custom PYTHON-family language must use the Python tokenizer");
    }

    @Test
    public void register_sameIdTwice_replaces() {
        LanguageRegistry.register(LanguageProfile.builder("mylang")
                .family(SyntaxFamily.C_LIKE).keywords("old").build());
        LanguageRegistry.register(LanguageProfile.builder("mylang")
                .family(SyntaxFamily.PYTHON).keywords("new").build());
        assertEquals(SyntaxFamily.PYTHON, LanguageRegistry.forName("mylang").family);
    }

    @Test
    public void override_builtin_replacesItsMapping() {
        LanguageProfile overridden = LanguageProfile.builder("sql")
                .family(SyntaxFamily.SQL)
                .keywords("SELECT2")
                .commentSyntax(CommentSyntax.C_STYLE)
                .build();
        LanguageRegistry.register(overridden);
        assertSame(overridden, LanguageRegistry.forName("sql"));
        assertTrue(LanguageRegistry.forName("sql").keywords.contains("SELECT2"));

        // restore the built-in for the other tests
        LanguageRegistry.unregister("sql");
        // The override REPLACED the built-in mapping — unregistering drops
        // "sql" entirely until the built-in table is reinstalled (the
        // @AfterEach resetToBuiltins does exactly that).
        assertNull(LanguageRegistry.forName("sql"));
    }

    @Test
    public void unregister_removesAliasesAndExtensions() {
        LanguageRegistry.register(LanguageProfile.builder("mylang")
                .alias("ml").extension("ml")
                .family(SyntaxFamily.C_LIKE).build());
        assertNotNull(LanguageRegistry.forName("mylang"));
        assertNotNull(LanguageRegistry.forName("ml"));
        assertTrue(LanguageRegistry.unregister("mylang"));
        assertNull(LanguageRegistry.forName("mylang"));
        assertNull(LanguageRegistry.forName("ml"));
        assertNull(LanguageRegistry.forExtension("ml"));
        assertFalse(LanguageRegistry.unregister("mylang")); // already gone
        assertFalse(LanguageRegistry.unregister("never-registered"));
    }

    @Test
    public void listeners_fireOnRegisterAndUnregister() {
        AtomicInteger added = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        List<LanguageProfile> seenAdded = new ArrayList<>();
        LanguageRegistry.Listener l = new LanguageRegistry.Listener() {
            @Override
            public void onLanguagesChanged(LanguageProfile p, boolean wasRemoved) {
                if (wasRemoved) removed.incrementAndGet(); else {
                    added.incrementAndGet();
                    seenAdded.add(p);
                }
            }
        };
        LanguageRegistry.addListener(l);
        try {
            LanguageProfile p = LanguageProfile.builder("mylang").build();
            LanguageRegistry.register(p);
            assertEquals(1, added.get());
            assertSame(p, seenAdded.get(0));
            LanguageRegistry.unregister("mylang");
            assertEquals(1, removed.get());
        } finally {
            LanguageRegistry.removeListener(l);
        }
        // After removal, no more notifications.
        LanguageRegistry.register(LanguageProfile.builder("mylang").build());
        assertEquals(1, added.get());
        LanguageRegistry.unregister("mylang");
        assertEquals(1, removed.get());
    }

    @Test
    public void profile_builderNormalizesIds() {
        LanguageProfile p = LanguageProfile.builder("  MyLang ")
                .alias(" ML ", "ml2")
                .extension(" .ML ")
                .build();
        assertEquals("mylang", p.name);
        assertTrue(p.aliases.contains("ml"));
        assertTrue(p.aliases.contains("ml2"));
        assertTrue(p.extensions.contains("ml"));
        assertTrue(p.answersTo("MYLANG"));
        assertTrue(p.answersTo("ml"));
        assertFalse(p.answersTo("other"));
        // Default family + comment syntax
        assertEquals(SyntaxFamily.C_LIKE, p.family);
        assertSame(CommentSyntax.C_STYLE, p.commentSyntax);
        // null / empty name rejected
        assertThrows(IllegalArgumentException.class, () -> LanguageProfile.builder(null));
        assertThrows(IllegalArgumentException.class, () -> LanguageProfile.builder("  "));
    }

    // ── helpers ───────────────────────────────────────────────────

    private static List<LineSpan> tokens(StyledLine line) {
        return line != null && line.spans != null ? line.spans : new ArrayList<>();
    }

    private static boolean hasToken(StyledLine line, TokenType type) {
        for (LineSpan s : tokens(line)) {
            if (s.type == type) return true;
        }
        return false;
    }
}
