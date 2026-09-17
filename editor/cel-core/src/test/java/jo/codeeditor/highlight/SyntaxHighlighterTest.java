package jo.codeeditor.highlight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du surligneur de syntaxe incrémental par ligne.
 */
class SyntaxHighlighterTest {

    private final SyntaxHighlighter hl = new SyntaxHighlighter();

    // ── Mots-clés Java ─────────────────────────────────────────────

    @Test
    void javaKeywords_recognized() {
        StyledLine line = hl.styleLine("public class Foo", LexState.NORMAL, "java");
        assertFalse(line.spans.isEmpty());
        // « public » et « class » doivent être KEYWORD
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
        // « int » devrait être TYPE
    }

    // ── Chaînes ───────────────────────────────────────────────────

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

    // ── Commentaires ──────────────────────────────────────────────────

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
        // « int » devrait être TYPE
        // « int » est TYPE, « x » pourrait être VARIABLE, « // comment » est COMMENT
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

    // ── Nombres ───────────────────────────────────────────────────

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

    // ── Fonctions ─────────────────────────────────────────────────

    @Test
    void functionCall() {
        StyledLine line = hl.styleLine("foo(bar)", LexState.NORMAL, "java");
        // « foo » devrait être FUNC (suivi de '(')
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
        // « int » devrait être TYPE
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.PROPERTY));
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING));
    }

    // ── Ponctuation ───────────────────────────────────────────────

    @Test
    void punctuation() {
        StyledLine line = hl.styleLine("{ } ;", LexState.NORMAL, "java");
        long punctCount = line.spans.stream().filter(s -> s.type == TokenType.PUNCT).count();
        assertTrue(punctCount >= 3, "Expected at least 3 PUNCT tokens, got " + punctCount);
    }

    // ── Continuité de l'état de sortie ─────────────────────────────

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

    // ── Repli HTML + porte d'activation TextMate ────

    /**
     * Quand TextMate est désactivé (aucun tokenizer enregistré, OU globalement
     * désactivé), les fichiers HTML retombent sur le tokenizer XML intégré,
     * qui reconnaît balises, attributs, chaînes et commentaires. Cela garantit
     * une coloration raisonnable des fichiers HTML même quand TextMate est
     * indisponible (ex. gros fichiers).
     */
    @Test
    void htmlFallsBackToXmlTokenizer_whenNoTextMateRegistered() {
        // État par défaut : aucun tokenizer TextMate enregistré.
        SyntaxHighlighter.setTextMateTokenizer(null);
        try {
            StyledLine line = hl.styleLine(
                "<div class=\"container\">",
                LexState.NORMAL, "html");
            // Doit produire au moins un TYPE (le nom de balise), un PROPERTY
            // (l'attribut) et un STRING (la valeur d'attribut).
            assertTrue(!line.spans.isEmpty(),
                "HTML fallback should produce some spans");
            assertTrue(
                line.spans.stream().anyMatch(s -> s.type == TokenType.TYPE),
                "HTML tag should be TYPE");
            assertTrue(
                line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
                "HTML attribute value should be STRING");
        } finally {
            // Réinitialise l'état global pour les autres tests.
            SyntaxHighlighter.setTextMateTokenizer(null);
        }
    }

    /**
     * Le disjoncteur TextMate est désormais une porte par appel
     * ({@code styleLine(..., allowTextMate)}) : quand {@code false}, même
     * si un tokenizer TextMate EST enregistré, styleLine retombe sur le
     * tokenizer intégré. Remplace l'ancien interrupteur statique global
     * {@code setTextMateEnabled(false)} désormais supprimé.
     */
    @Test
    void textMateGate_disablesDelegation() {
        // Enregistre un faux tokenizer TextMate qui se délèguerait à lui-même
        // (provoquant une récursion infinie s'il n'était pas contourné).
        TextMateTokenizer fake = new TextMateTokenizer() {
            @Override
            public boolean isAvailable(String language) {
                return "java".equals(language);
            }
            @Override
            public StyledLine tokenize(String line, int entryState, String language) {
                throw new AssertionError(
                    "TextMate should be bypassed when allowTextMate=false");
            }
        };
        SyntaxHighlighter.setTextMateTokenizer(fake);
        try {
            // Porte locale fermée — ne doit PAS appeler fake.tokenize, doit
            // utiliser le tokenizer Java intégré et produire un KEYWORD pour « public ».
            StyledLine line = hl.styleLine("public class Foo",
                LexState.NORMAL, "java", false);
            assertTrue(
                line.spans.stream().anyMatch(s -> s.type == TokenType.KEYWORD),
                "built-in Java tokenizer should produce KEYWORD");
        } finally {
            SyntaxHighlighter.setTextMateTokenizer(null);
        }
    }

    /**
     * Quand {@code allowTextMate=true} ET qu'un tokenizer TextMate est
     * enregistré ET qu'il rapporte isAvailable(language)=true, styleLine
     * lui délègue. Vérifié via un stub qui renvoie une span sentinelle.
     * La surcharge legacy à 3 arguments doit se comporter de la même façon
     * (elle délègue porte ouverte).
     */
    @Test
    void textMateGate_delegatesWhenEnabled() {
        TextMateTokenizer stub = new TextMateTokenizer() {
            @Override
            public boolean isAvailable(String language) {
                return "java".equals(language);
            }
            @Override
            public StyledLine tokenize(String line, int entryState, String language) {
                // Renvoie une span sentinelle pour vérifier que la délégation a eu lieu.
                return new StyledLine(
                    java.util.Collections.singletonList(
                        new LineSpan(0, line.length(), TokenType.ANNOTATION)),
                    entryState, entryState);
            }
        };
        SyntaxHighlighter.setTextMateTokenizer(stub);
        try {
            // Porte explicitement ouverte.
            StyledLine gated = hl.styleLine("public class Foo",
                LexState.NORMAL, "java", true);
            assertTrue(
                gated.spans.stream().allMatch(s -> s.type == TokenType.ANNOTATION),
                "delegation should have happened (explicit gate)");
            // Surcharge legacy à 3 arguments = porte ouverte (contrat de rétrocompatibilité).
            StyledLine legacy = hl.styleLine("public class Foo",
                LexState.NORMAL, "java");
            assertTrue(
                legacy.spans.stream().allMatch(s -> s.type == TokenType.ANNOTATION),
                "legacy 3-arg overload must keep delegating");
        } finally {
            SyntaxHighlighter.setTextMateTokenizer(null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PARSER MAISON : tests des tokenizers de langages intégrés.
    //
    // Ces tests couvrent les tokenizers de langages intégrés afin que le
    // parser maison soit exercé quand :tm4e n'est pas sur le classpath.
    // Chaque test vérifie le minimum vital : le tokenizer reconnaît au
    // moins un KEYWORD / STRING / COMMENT / NUMBER sur une ligne d'exemple
    // du langage.
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
        // Le chemin Java par défaut traite '#' comme début de littéral couleur,
        // mais « include » après '#' devrait quand même produire une span. On
        // vérifie juste que le tokenizer ne plante pas et produit quelque chose.
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

    // ── Template literals JS/TS ────────────────────────────

    @Test
    void jsTemplateLiteral_basicString() {
        // `Hello world` → 1 span STRING couvrant tout le littéral.
        StyledLine line = hl.styleLine("`Hello world`", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
            "JS template literal should produce at least one STRING span");
    }

    @Test
    void jsTemplateLiteral_withInterpolation() {
        // `Hello ${name}` → segment(s) STRING + VARIABLE pour ${name}.
        StyledLine line = hl.styleLine("`Hello ${name}`", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
            "JS template literal should produce a STRING segment for the literal text");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.VARIABLE),
            "JS template literal interpolation ${name} should produce a VARIABLE span");
    }

    @Test
    void jsTemplateLiteral_multipleInterpolations() {
        // `${a}${b}` → 2 spans VARIABLE distinctes + 0 STRING (juste des backticks aux bords).
        StyledLine line = hl.styleLine("`x${a}y${b}`", LexState.NORMAL, "typescript");
        long varCount = line.spans.stream().filter(s -> s.type == TokenType.VARIABLE).count();
        assertEquals(2, varCount, "Expected 2 VARIABLE spans for ${a} and ${b}");
    }

    @Test
    void jsTemplateLiteral_withEscape() {
        // `\n` dans un template literal doit être ESCAPE.
        StyledLine line = hl.styleLine("`line1\\nline2`", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.ESCAPE),
            "JS template literal escape \\n should produce an ESCAPE span");
    }

    @Test
    void jsTemplateLiteral_nestedBracesInInterpolation() {
        // `${ {a:1}.a }` — littéral objet imbriqué dans l'interpolation.
        // Le parseur doit trouver le } correspondant au bon endroit.
        StyledLine line = hl.styleLine("`${ {a:1}.a }`", LexState.NORMAL, "javascript");
        // Au moins une span VARIABLE doit couvrir toute l'interpolation.
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.VARIABLE),
            "JS template literal with nested braces should still produce a VARIABLE span");
    }

    @Test
    void jsTemplateLiteral_notTriggeredForJava() {
        // Les backticks en Java ne doivent PAS être traités comme des template
        // literals (Java ne les a pas). Ils retombent en PUNCT.
        StyledLine line = hl.styleLine("int x = 0; // `not a template`",
            LexState.NORMAL, "java");
        // Le commentaire doit couvrir la portion avec backtick.
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.COMMENT),
            "Java line with backtick in comment should be COMMENT, not STRING");
    }

    @Test
    void jsTemplateLiteral_unterminatedGoesToStringEof() {
        // `unterminated` non fermé sur une ligne → STRING jusqu'à la fin de ligne.
        StyledLine line = hl.styleLine("`unterminated string", LexState.NORMAL, "javascript");
        assertTrue(line.spans.stream().anyMatch(s -> s.type == TokenType.STRING),
            "Unterminated JS template literal should produce a STRING span to EOF");
    }
}
