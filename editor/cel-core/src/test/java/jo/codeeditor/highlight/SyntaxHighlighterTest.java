package jo.codeeditor.highlight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the incremental per-line syntax highlighter.
 */
class SyntaxHighlighterTest {

    private final SyntaxHighlighter hl = new SyntaxHighlighter();

    // ── Java keywords ─────────────────────────────────────────────

    @Test
    void javaKeywords_recognized() {
        StyledLine line = hl.styleLine("public class Foo", LexState.NORMAL, "java");
        assertFalse(line.spans.isEmpty());
        // "public" and "class" should be KEYWORD
        boolean hasPublic = line.spans.stream().anyMatch(s ->
            s.type == TokenType.KEYWORD);
        assertTrue(hasPublic, "Expected KEYWORD token for 'public' or 'class'");
    }

    @Test
    void javaIdentifier_notKeyword() {
        StyledLine line = hl.styleLine("myVariable", LexState.NORMAL, "java");
        assertEquals(1, line.spans.size());
        assertEquals(TokenType.PLAIN, line.spans.get(0).type);
    }

    @Test
    void javaTypeName_recognized() {
        StyledLine line = hl.styleLine("String name", LexState.NORMAL, "java");
        // "int" should be TYPE
    }

    // ── Strings ───────────────────────────────────────────────────

    @Test
    void doubleQuotedString() {
        StyledLine line = hl.styleLine("String s = \"hello\";", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    @Test
    void singleQuotedChar() {
        StyledLine line = hl.styleLine("char c = 'x';", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    @Test
    void stringWithEscape() {
        StyledLine line = hl.styleLine("\"hello\\nworld\"", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
        String stringSpan = line.spans.stream()
            .filter(s -> s.type == TokenType.STRING)
            .map(s -> "\"hello\\nworld\"".substring(s.startCol, s.endCol))
            .findFirst().orElse("");
        assertEquals("\"hello\\nworld\"", stringSpan);
    }

    // ── Comments ──────────────────────────────────────────────────

    @Test
    void lineComment() {
        StyledLine line = hl.styleLine("// this is a comment", LexState.NORMAL, "java");
        assertEquals(1, line.spans.size());
        assertEquals(TokenType.COMMENT, line.spans.get(0).type);
    }

    @Test
    void inlineComment() {
        StyledLine line = hl.styleLine("int x = 5; // comment", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
        // "int" should be TYPE
        // "int" is TYPE, "x" could be VARIABLE, "// comment" is COMMENT
    }

    @Test
    void blockComment_singleLine() {
        StyledLine line = hl.styleLine("/* block */", LexState.NORMAL, "java");
        assertEquals(1, line.spans.size());
        assertEquals(TokenType.COMMENT, line.spans.get(0).type);
    }

    @Test
    void blockComment_multiLine_start() {
        StyledLine line = hl.styleLine("/* start", LexState.NORMAL, "java");
        assertEquals(1, line.spans.size());
        assertEquals(TokenType.COMMENT, line.spans.get(0).type);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void blockComment_multiLine_middle() {
        StyledLine line = hl.styleLine("middle", LexState.BLOCK_COMMENT, "java");
        assertEquals(1, line.spans.size());
        assertEquals(TokenType.COMMENT, line.spans.get(0).type);
        assertEquals(LexState.BLOCK_COMMENT, line.exitState);
    }

    @Test
    void blockComment_multiLine_end() {
        StyledLine line = hl.styleLine("end */", LexState.BLOCK_COMMENT, "java");
        assertEquals(1, line.spans.size());
        assertEquals(TokenType.COMMENT, line.spans.get(0).type);
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── Numbers ───────────────────────────────────────────────────

    @Test
    void integerLiteral() {
        StyledLine line = hl.styleLine("int x = 42;", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER));
    }

    @Test
    void floatLiteral() {
        StyledLine line = hl.styleLine("double d = 3.14;", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER));
    }

    @Test
    void hexLiteral() {
        StyledLine line = hl.styleLine("int x = 0xFF;", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER));
    }

    // ── Annotations ───────────────────────────────────────────────

    @Test
    void annotation() {
        StyledLine line = hl.styleLine("@Override", LexState.NORMAL, "java");
        assertEquals(1, line.spans.size());
        assertEquals(TokenType.ANNOTATION, line.spans.get(0).type);
    }

    @Test
    void annotationWithValue() {
        StyledLine line = hl.styleLine("@SuppressWarnings(\"unchecked\")", LexState.NORMAL, "java");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.ANNOTATION));
    }

    // ── Functions ─────────────────────────────────────────────────

    @Test
    void functionCall() {
        StyledLine line = hl.styleLine("foo(bar)", LexState.NORMAL, "java");
        // "foo" should be FUNC (followed by '(')
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.FUNC));
    }

    // ── Kotlin ────────────────────────────────────────────────────

    @Test
    void kotlinKeywords() {
        StyledLine line = hl.styleLine("fun main() {", LexState.NORMAL, "kotlin");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD));
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.FUNC));
    }

    @Test
    void kotlinRawString() {
        StyledLine line = hl.styleLine("val s = \"\"\"", LexState.NORMAL, "kotlin");
        assertEquals(LexState.KT_RAW_STRING, line.exitState);
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    @Test
    void kotlinRawString_end() {
        StyledLine line = hl.styleLine("text\"\"\"", LexState.KT_RAW_STRING, "kotlin");
        assertEquals(LexState.NORMAL, line.exitState);
    }

    // ── XML ───────────────────────────────────────────────────────

    @Test
    void xmlTagAndAttribute() {
        StyledLine line = hl.styleLine("<TextView android:text=\"hello\" />", LexState.NORMAL, "xml");
        // "int" should be TYPE
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.PROPERTY));
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    // ── Punctuation ───────────────────────────────────────────────

    @Test
    void punctuation() {
        StyledLine line = hl.styleLine("{ } ;", LexState.NORMAL, "java");
        long punctCount = line.spans.stream().filter(s -> s.type == TokenType.PUNCT).count();
        assertTrue(punctCount >= 3, "Expected at least 3 PUNCT tokens, got " + punctCount);
    }

    // ── Exit state continuity ─────────────────────────────────────

    @Test
    void initialState_normal() {
        StyledLine line = hl.styleLine("hello", LexState.NORMAL, "java");
        assertEquals(LexState.NORMAL, line.exitState);
    }

    @Test
    void blockCommentSpanningThreeLines() {
        StyledLine l1 = hl.styleLine("/* start", LexState.NORMAL, "java");
        assertEquals(LexState.BLOCK_COMMENT, l1.exitState);

        StyledLine l2 = hl.styleLine("middle", l1.exitState, "java");
        assertEquals(LexState.BLOCK_COMMENT, l2.exitState);

        StyledLine l3 = hl.styleLine("end */", l2.exitState, "java");
        assertEquals(LexState.NORMAL, l3.exitState);
    }

    // ── v2.44 — HTML fallback + TextMate enable/disable toggle ────

    /**
     * v2.44 — When TextMate is disabled (no tokenizer registered, OR
     * globally disabled), HTML files fall through to the built-in XML
     * tokenizer, which recognizes tags, attributes, strings, and comments.
     * This guarantees HTML files get reasonable coloring even when
     * TextMate is unavailable (e.g. large files).
     */
    @Test
    void htmlFallsBackToXmlTokenizer_whenNoTextMateRegistered() {
        // Default state: no TextMate tokenizer registered.
        SyntaxHighlighter.setTextMateTokenizer(null);
        SyntaxHighlighter.setTextMateEnabled(true);
        try {
            StyledLine line = hl.styleLine(
                "<div class=\"container\">",
                LexState.NORMAL, "html");
            // Should produce at least one TYPE (the tag name), one PROPERTY
            // (the attribute), and one STRING (the attribute value).
            assertTrue(!line.spans.isEmpty(),
                "HTML fallback should produce some spans");
            assertTrue(
                line.spans.stream().anyMatch(s -> s.type == TokenType.TYPE),
                "HTML tag should be TYPE");
            assertTrue(
                line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
                "HTML attribute value should be STRING");
        } finally {
            // Reset global state for other tests.
            SyntaxHighlighter.setTextMateTokenizer(null);
            SyntaxHighlighter.setTextMateEnabled(true);
        }
    }

    /**
     * v2.44 — The textMateEnabled toggle is a circuit breaker: when
     * disabled, even if a TextMate tokenizer IS registered, styleLine
     * should fall through to the built-in tokenizer.
     */
    @Test
    void textMateEnabledToggle_disablesDelegation() {
        // Register a fake TextMate tokenizer that would delegate to itself
        // (causing infinite recursion if not bypassed).
        TextMateTokenizer fake = new TextMateTokenizer() {
            @Override
            public boolean isAvailable(String language) {
                return "java".equals(language);
            }
            @Override
            public StyledLine tokenize(String line, int entryState, String language) {
                throw new AssertionError(
                    "TextMate should be bypassed when textMateEnabled=false");
            }
        };
        SyntaxHighlighter.setTextMateTokenizer(fake);
        SyntaxHighlighter.setTextMateEnabled(false);
        try {
            assertTrue(!SyntaxHighlighter.isTextMateEnabled(),
                "toggle should report disabled");
            // Should NOT call fake.tokenize — should use built-in Java
            // tokenizer and produce a KEYWORD token for "public".
            StyledLine line = hl.styleLine("public class Foo",
                LexState.NORMAL, "java");
            assertTrue(
                line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
                "built-in Java tokenizer should produce KEYWORD");
        } finally {
            SyntaxHighlighter.setTextMateTokenizer(null);
            SyntaxHighlighter.setTextMateEnabled(true);
        }
    }

    /**
     * v2.44 — When textMateEnabled=true AND a TextMate tokenizer IS
     * registered AND it reports isAvailable(language)=true, styleLine
     * delegates to it. Verified via a stub that returns a sentinel span.
     */
    @Test
    void textMateEnabledToggle_delegatesWhenEnabled() {
        TextMateTokenizer stub = new TextMateTokenizer() {
            @Override
            public boolean isAvailable(String language) {
                return "java".equals(language);
            }
            @Override
            public StyledLine tokenize(String line, int entryState, String language) {
                // Return a sentinel span so we can verify delegation happened.
                return new StyledLine(
                    java.util.Collections.singletonList(
                        new LineSpan(0, line.length(), TokenType.ANNOTATION)),
                    entryState, entryState);
            }
        };
        SyntaxHighlighter.setTextMateTokenizer(stub);
        SyntaxHighlighter.setTextMateEnabled(true);
        try {
            assertTrue(SyntaxHighlighter.isTextMateEnabled(),
                "toggle should report enabled");
            StyledLine line = hl.styleLine("public class Foo",
                LexState.NORMAL, "java");
            // All spans should be ANNOTATION (sentinel) — proving
            // delegation happened.
            assertTrue(
                line.spans.stream().allMatch(s -> s.type == TokenType.ANNOTATION),
                "delegation should have happened");
        } finally {
            SyntaxHighlighter.setTextMateTokenizer(null);
            SyntaxHighlighter.setTextMateEnabled(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // v2.46 — PARSER MAISON: tests for the new built-in language tokenizers.
    //
    // These tests cover the new language tokenizers added in v2.46 so the
    // parser maison is exercised when :tm4e is not on the classpath. Each
    // test asserts the bare minimum: the tokenizer recognizes at least one
    // KEYWORD / STRING / COMMENT / NUMBER for a sample line in that language.
    // ═══════════════════════════════════════════════════════════════════

    // ─── C ─────────────────────────────────────────────────────────────

    @Test
    void cKeywords_recognized() {
        StyledLine line = hl.styleLine("int main() {", LexState.NORMAL, "c");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "C keywords (int) should be KEYWORD");
    }

    @Test
    void cPreprocessorDirective_recognized() {
        StyledLine line = hl.styleLine("#include <stdio.h>", LexState.NORMAL, "c");
        // The default Java path treats '#' as a color literal start, but
        // "include" after '#' should still produce some span. We just check
        // that the tokenizer doesn't crash and produces something.
        assertTrue(!line.spans.isEmpty(),
            "C preprocessor line should produce spans");
    }

    // ─── C++ ───────────────────────────────────────────────────────────

    @Test
    void cppKeywords_recognized() {
        StyledLine line = hl.styleLine("namespace foo { class Bar {}; }",
            LexState.NORMAL, "cpp");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "C++ keywords (namespace, class) should be KEYWORD");
    }

    // ─── Go ────────────────────────────────────────────────────────────

    @Test
    void goKeywords_recognized() {
        StyledLine line = hl.styleLine("func main() {", LexState.NORMAL, "go");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Go keyword (func) should be KEYWORD");
    }

    // ─── Rust ──────────────────────────────────────────────────────────

    @Test
    void rustKeywords_recognized() {
        StyledLine line = hl.styleLine("fn main() {", LexState.NORMAL, "rust");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Rust keyword (fn) should be KEYWORD");
    }

    @Test
    void rustLetKeyword_recognized() {
        StyledLine line = hl.styleLine("let x = 42;", LexState.NORMAL, "rust");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Rust keyword (let) should be KEYWORD");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER),
            "Rust number literal should be NUMBER");
    }

    // ─── Ruby ──────────────────────────────────────────────────────────

    @Test
    void rubyKeywords_recognized() {
        StyledLine line = hl.styleLine("def hello", LexState.NORMAL, "ruby");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Ruby keyword (def) should be KEYWORD");
    }

    // ─── PHP ───────────────────────────────────────────────────────────

    @Test
    void phpKeywords_recognized() {
        StyledLine line = hl.styleLine("function hello() {", LexState.NORMAL, "php");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "PHP keyword (function) should be KEYWORD");
    }

    // ─── Swift ─────────────────────────────────────────────────────────

    @Test
    void swiftKeywords_recognized() {
        StyledLine line = hl.styleLine("func hello() {", LexState.NORMAL, "swift");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Swift keyword (func) should be KEYWORD");
    }

    @Test
    void swiftLetKeyword_recognized() {
        StyledLine line = hl.styleLine("let x = 42", LexState.NORMAL, "swift");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Swift keyword (let) should be KEYWORD");
    }

    // ─── Dart ─────────────────────────────────────────────────────────

    @Test
    void dartKeywords_recognized() {
        StyledLine line = hl.styleLine("void main() {", LexState.NORMAL, "dart");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Dart keyword (void) should be KEYWORD");
    }

    // ─── Groovy ────────────────────────────────────────────────────────

    @Test
    void groovyKeywords_recognized() {
        StyledLine line = hl.styleLine("def x = 42", LexState.NORMAL, "groovy");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Groovy keyword (def) should be KEYWORD");
    }

    // ─── CSS ───────────────────────────────────────────────────────────

    @Test
    void css_atRule_recognizedAsKeyword() {
        StyledLine line = hl.styleLine("@media screen {", LexState.NORMAL, "css");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "CSS @media should be KEYWORD");
    }

    @Test
    void css_property_recognizedAsProperty() {
        StyledLine line = hl.styleLine("color: red;", LexState.NORMAL, "css");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.PROPERTY),
            "CSS color should be PROPERTY");
    }

    @Test
    void css_hexColor_recognizedAsNumber() {
        StyledLine line = hl.styleLine("background: #ff0000;", LexState.NORMAL, "css");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER),
            "CSS hex color should be NUMBER");
    }

    @Test
    void css_numberWithUnit_recognizedAsNumber() {
        StyledLine line = hl.styleLine("margin: 12px;", LexState.NORMAL, "css");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER),
            "CSS 12px should be NUMBER");
    }

    @Test
    void css_blockComment_singleLine() {
        StyledLine line = hl.styleLine("/* comment */", LexState.NORMAL, "css");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT),
            "CSS block comment should be COMMENT");
    }

    @Test
    void css_blockComment_multiLine_start() {
        StyledLine line = hl.styleLine("/* start", LexState.NORMAL, "css");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
        assertEquals(LexState.CSS_COMMENT, line.exitState);
    }

    // ─── Shell / Bash ──────────────────────────────────────────────────

    @Test
    void shellComment_recognized() {
        StyledLine line = hl.styleLine("# this is a comment", LexState.NORMAL, "shell");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
    }

    @Test
    void shellVariable_recognized() {
        StyledLine line = hl.styleLine("echo $HOME", LexState.NORMAL, "shell");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.VARIABLE),
            "$HOME should be VARIABLE");
    }

    @Test
    void shellKeyword_if_recognized() {
        StyledLine line = hl.styleLine("if [ -z \"$x\" ]; then", LexState.NORMAL, "shell");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "'if' should be KEYWORD");
    }

    @Test
    void shellDoubleQuotedString_recognized() {
        StyledLine line = hl.styleLine("echo \"hello world\"", LexState.NORMAL, "shell");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    @Test
    void shellSingleQuotedString_recognized() {
        StyledLine line = hl.styleLine("echo 'literal $var'", LexState.NORMAL, "shell");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    // ─── YAML ─────────────────────────────────────────────────────────

    @Test
    void yamlKey_recognizedAsProperty() {
        StyledLine line = hl.styleLine("name: John", LexState.NORMAL, "yaml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.PROPERTY),
            "YAML key should be PROPERTY");
    }

    @Test
    void yamlComment_recognized() {
        StyledLine line = hl.styleLine("# a comment", LexState.NORMAL, "yaml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
    }

    @Test
    void yamlDocumentSeparator_recognized() {
        StyledLine line = hl.styleLine("---", LexState.NORMAL, "yaml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "YAML --- separator should be KEYWORD");
    }

    @Test
    void yamlBoolean_recognizedAsConstant() {
        StyledLine line = hl.styleLine("enabled: true", LexState.NORMAL, "yaml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.CONSTANT),
            "YAML true should be CONSTANT");
    }

    @Test
    void yamlNumber_recognized() {
        StyledLine line = hl.styleLine("port: 8080", LexState.NORMAL, "yaml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER),
            "YAML 8080 should be NUMBER");
    }

    // ─── SQL ──────────────────────────────────────────────────────────

    @Test
    void sqlKeywords_recognized() {
        StyledLine line = hl.styleLine("SELECT * FROM users",
            LexState.NORMAL, "sql");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "SQL SELECT/FROM should be KEYWORD");
    }

    @Test
    void sqlLineComment_recognized() {
        StyledLine line = hl.styleLine("-- this is a comment",
            LexState.NORMAL, "sql");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
    }

    @Test
    void sqlSingleQuotedString_recognized() {
        StyledLine line = hl.styleLine("WHERE name = 'Alice'",
            LexState.NORMAL, "sql");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    // ─── Properties ────────────────────────────────────────────────────

    @Test
    void propertiesComment_recognized() {
        StyledLine line = hl.styleLine("# a comment", LexState.NORMAL, "properties");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
    }

    @Test
    void propertiesKey_recognizedAsProperty() {
        StyledLine line = hl.styleLine("foo.bar=value", LexState.NORMAL, "properties");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.PROPERTY),
            "Properties key should be PROPERTY");
    }

    @Test
    void propertiesValue_recognizedAsString() {
        StyledLine line = hl.styleLine("key=value", LexState.NORMAL, "properties");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
            "Properties value should be STRING");
    }

    // ─── TOML ─────────────────────────────────────────────────────────

    @Test
    void tomlSection_recognizedAsType() {
        StyledLine line = hl.styleLine("[dependencies]", LexState.NORMAL, "toml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.TYPE),
            "TOML section should be TYPE");
    }

    @Test
    void tomlComment_recognized() {
        StyledLine line = hl.styleLine("# a comment", LexState.NORMAL, "toml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
    }

    @Test
    void tomlKey_recognizedAsProperty() {
        StyledLine line = hl.styleLine("version = \"1.0\"", LexState.NORMAL, "toml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.PROPERTY),
            "TOML key should be PROPERTY");
    }

    @Test
    void tomlBoolean_recognizedAsConstant() {
        StyledLine line = hl.styleLine("enabled = true", LexState.NORMAL, "toml");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.CONSTANT),
            "TOML true should be CONSTANT");
    }

    // ─── Smali ────────────────────────────────────────────────────────

    @Test
    void smaliDirective_recognizedAsKeyword() {
        StyledLine line = hl.styleLine(".class public LFoo;",
            LexState.NORMAL, "smali");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
            "Smali .class should be KEYWORD");
    }

    @Test
    void smaliComment_recognized() {
        StyledLine line = hl.styleLine("# a comment", LexState.NORMAL, "smali");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT));
    }

    @Test
    void smaliRegister_recognizedAsVariable() {
        StyledLine line = hl.styleLine("invoke-virtual {p0}, LFoo;.bar()V",
            LexState.NORMAL, "smali");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.VARIABLE),
            "Smali p0 should be VARIABLE");
    }

    @Test
    void smaliHexLiteral_recognizedAsNumber() {
        StyledLine line = hl.styleLine("const/16 v0, 0x1A",
            LexState.NORMAL, "smali");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.NUMBER),
            "Smali 0x1A should be NUMBER");
    }

    @Test
    void smaliTypeDescriptor_recognizedAsType() {
        StyledLine line = hl.styleLine(".class Ljava/lang/String;",
            LexState.NORMAL, "smali");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.TYPE),
            "Smali Ljava/lang/String; should be TYPE");
    }

    // ── v2.55 — JS/TS template literals ────────────────────────────

    @Test
    void jsTemplateLiteral_basicString() {
        // `Hello world` → 1 STRING span covering the whole literal.
        StyledLine line = hl.styleLine("`Hello world`", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
            "JS template literal should produce at least one STRING span");
    }

    @Test
    void jsTemplateLiteral_withInterpolation() {
        // `Hello ${name}` → STRING segment(s) + VARIABLE for ${name}.
        StyledLine line = hl.styleLine("`Hello ${name}`", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
            "JS template literal should produce a STRING segment for the literal text");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.VARIABLE),
            "JS template literal interpolation ${name} should produce a VARIABLE span");
    }

    @Test
    void jsTemplateLiteral_multipleInterpolations() {
        // `${a}${b}` → 2 distinct VARIABLE spans + 0 STRING (just backticks at edges).
        StyledLine line = hl.styleLine("`x${a}y${b}`", LexState.NORMAL, "typescript");
        long varCount = line.spans.stream().filter(s -> s.type == TokenType.VARIABLE).count();
        assertEquals(2, varCount, "Expected 2 VARIABLE spans for ${a} and ${b}");
    }

    @Test
    void jsTemplateLiteral_withEscape() {
        // `\n` inside template literal should be ESCAPE.
        StyledLine line = hl.styleLine("`line1\\nline2`", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.ESCAPE),
            "JS template literal escape \\n should produce an ESCAPE span");
    }

    @Test
    void jsTemplateLiteral_nestedBracesInInterpolation() {
        // `${ {a:1}.a }` — nested object literal inside interpolation.
        // The parser should find the matching } at the right place.
        StyledLine line = hl.styleLine("`${ {a:1}.a }`", LexState.NORMAL, "javascript");
        // At least one VARIABLE span should cover the whole interpolation.
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.VARIABLE),
            "JS template literal with nested braces should still produce a VARIABLE span");
    }

    @Test
    void jsTemplateLiteral_notTriggeredForJava() {
        // Backticks in Java should NOT be treated as template literals
        // (Java doesn't have them). They fall through to PUNCT.
        StyledLine line = hl.styleLine("int x = 0; // `not a template`",
            LexState.NORMAL, "java");
        // The comment should cover the backtick portion.
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT),
            "Java line with backtick in comment should be COMMENT, not STRING");
    }

    @Test
    void jsTemplateLiteral_unterminatedGoesToStringEof() {
        // `unterminated on one line → STRING until end of line.
        StyledLine line = hl.styleLine("`unterminated string", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
            "Unterminated JS template literal should produce a STRING span to EOF");
    }
}
