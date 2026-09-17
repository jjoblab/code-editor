package jo.codeeditor.lang;

import jo.codeeditor.lang.model.CodeAction;
import jo.codeeditor.lang.model.CodeBlock;
import jo.codeeditor.lang.model.CompletionItem;
import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.lang.model.Diagnostic;
import jo.codeeditor.lang.model.DocumentHighlight;
import jo.codeeditor.lang.model.HoverContent;
import jo.codeeditor.lang.model.Signature;
import jo.codeeditor.lang.model.SignatureHelp;
import jo.codeeditor.lang.model.Symbol;
import jo.codeeditor.lang.model.TextEdit;
import jo.codeeditor.lang.model.ViewZone;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des méthodes par défaut du SPI {@link Language}.
 *
 * <p>Vérifie qu'une implémentation minimale de {@link Language} (seules
 * les méthodes requises sont fournies) retourne {@code null} pour tous
 * les providers optionnels — le contrat sur lequel l'éditeur s'appuie.</p>
 */
class LanguageSPITest {

    @Test
    void minimalLanguage_returnsNullForAllOptionalProviders() {
        Language lang = new MinimalLanguage();
        assertNull(lang.getCompletionProvider());
        assertNull(lang.getHoverProvider());
        assertNull(lang.getSignatureHelpProvider());
        assertNull(lang.getDefinitionProvider());
        assertNull(lang.getReferencesProvider());
        assertNull(lang.getDiagnosticsProvider());
        assertNull(lang.getCodeActionsProvider());
        assertNull(lang.getDocumentHighlightProvider());
        assertNull(lang.getInlayHintProvider());
        assertNull(lang.getViewZoneProvider());
        assertNull(lang.getFormatter());
        assertNull(lang.getSymbolProvider());
        assertNull(lang.getRenameProvider());
        assertNull(lang.getTypeDefinitionProvider());
        assertNull(lang.getImplementationsProvider());
        assertNull(lang.getSuperDefinitionProvider());
    }

    @Test
    void referencesProvider_isNewSpiSlot_addedIn_v3_33_10() {
        // Le slot references doit exposer null par défaut : une
        // implémentation minimale ne le fournit pas.
        Language lang = new MinimalLanguage();
        assertNull(lang.getReferencesProvider());
    }

    @Test
    void navigationSpiSlots_typeDefinition_implementations_super_defaultNull() {
        // Le menu GO TO s'appuie sur trois slots SPI optionnels ; une
        // implémentation minimale doit les exposer à null (méthodes par
        // défaut rétro-compatibles).
        Language lang = new MinimalLanguage();
        assertNull(lang.getTypeDefinitionProvider(),
                "typeDefinition slot (v2.36)");
        assertNull(lang.getImplementationsProvider(),
                "implementations slot (v2.37)");
        assertNull(lang.getSuperDefinitionProvider(),
                "superDefinition slot (v2.37)");
    }

    @Test
    void definitionLocation_creation() {
        DefinitionLocation loc = new DefinitionLocation("file:///foo.java", 42, "foo");
        assertEquals("file:///foo.java", loc.path);
        assertEquals(42, loc.offset);
        assertEquals("foo", loc.displayName);
    }

    @Test
    void minimalLanguage_hasAnalyzerAndInterruptionLevel() {
        Language lang = new MinimalLanguage();
        assertNotNull(lang.getAnalyzer());
        assertEquals(Language.INTERRUPTION_LEVEL_STRONG, lang.getInterruptionLevel());
    }

    @Test
    void completionItem_convenienceConstructor() {
        CompletionItem item = new CompletionItem("foo", "foo");
        assertEquals("foo", item.label);
        assertEquals("foo", item.insertText);
        assertEquals("k", item.kind);
        assertEquals(100, item.sortPriority);
        assertFalse(item.isSnippet);
    }

    @Test
    void signatureHelp_getActiveSignature() {
        Signature sig1 = new Signature("foo()", "", Collections.emptyList(), 0);
        Signature sig2 = new Signature("bar()", "", Collections.emptyList(), 0);
        SignatureHelp help = new SignatureHelp(List.of(sig1, sig2), 1, 0);
        assertSame(sig2, help.getActiveSignature());
    }

    @Test
    void signatureHelp_emptyList_returnsNull() {
        SignatureHelp help = new SignatureHelp(Collections.emptyList(), 0, 0);
        assertNull(help.getActiveSignature());
    }

    @Test
    void hoverContent_isEmpty() {
        assertTrue(new HoverContent("", "", "").isEmpty());
        assertFalse(new HoverContent("foo", "", "").isEmpty());
    }

    @Test
    void codeAction_convenienceConstructor() {
        Runnable r = () -> {};
        CodeAction action = new CodeAction("Fix", r);
        assertEquals("Fix", action.title);
        assertEquals("quickfix", action.kind);
        assertFalse(action.isPreferred);
        assertSame(r, action.apply);
    }

    @Test
    void symbol_creation() {
        Symbol s = new Symbol("foo", 42, "method", "MyClass");
        assertEquals("foo", s.name);
        assertEquals(42, s.offset);
        assertEquals("method", s.kind);
        assertEquals("MyClass", s.container);
    }

    @Test
    void diagnostic_creation() {
        Diagnostic d = new Diagnostic(0, 10, 3, "error", "E001");
        assertEquals(0, d.start);
        assertEquals(10, d.end);
        assertEquals(3, d.severity);
        assertEquals("error", d.message);
        assertEquals("E001", d.code);
    }

    @Test
    void textEdit_creation() {
        TextEdit e = new TextEdit(5, 10, "hello");
        assertEquals(5, e.start);
        assertEquals(10, e.end);
        assertEquals("hello", e.newText);
    }

    @Test
    void codeBlock_defaults() {
        CodeBlock b = new CodeBlock(0, 100);
        assertEquals(0, b.start);
        assertEquals(100, b.end);
        assertEquals("…", b.placeholder);
        assertEquals("block", b.kind);
        assertFalse(b.collapsed);
    }

    @Test
    void viewZone_creation() {
        ViewZone z = new ViewZone(5, 30, null, 1);
        assertEquals(5, z.afterLine);
        assertEquals(30, z.heightPx);
        assertNull(z.content);
        assertEquals(1, z.id);
    }

    /** Implémentation minimale de Language qui ne fournit qu'un Analyzer. */
    private static class MinimalLanguage implements Language {
        @Override
        public Analyzer getAnalyzer() {
            return new NoopAnalyzer();
        }
        @Override
        public int getInterruptionLevel() {
            return INTERRUPTION_LEVEL_STRONG;
        }
        @Override
        public void destroy() {}
    }

    private static class NoopAnalyzer implements Analyzer {
        @Override
        public void setReceiver(StyleReceiver receiver) {}
        @Override
        public void onReplace(CharSequence text, int editStart, int editEnd, CharSequence inserted) {}
        @Override
        public void reset(CharSequence text) {}
        @Override
        public jo.codeeditor.highlight.StyledLine styledLine(int line) { return null; }
        @Override
        public List<CodeBlock> computeBlocks() { return Collections.emptyList(); }
        @Override
        public BracketMatch computeBracketMatch(int offset) { return null; }
        @Override
        public void destroy() {}
    }
}
