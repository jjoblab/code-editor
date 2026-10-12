package jo.codeeditor.doc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests JVM pur pour les parseurs statiques de {@link QuickDoc}.
 *
 * <p>Le popup lui-même est un artefact de la couche View (dessiné sur
 * Canvas), mais l'analyse KDoc/Javadoc sous-jacente est du Java pur et
 * pleinement testable ici.
 */
class QuickDocTest {

    @Test
    void parseQuickDoc_emptyInput() {
        var content = QuickDoc.parseQuickDoc("", "java");
        assertTrue(content.isEmpty());
    }

    @Test
    void parseQuickDoc_nullInput() {
        var content = QuickDoc.parseQuickDoc(null, "java");
        assertTrue(content.isEmpty());
    }

    @Test
    void parseQuickDoc_simpleDescription() {
        String doc = "/**\n * This is a simple description.\n */";
        var content = QuickDoc.parseQuickDoc(doc, "java");
        assertFalse(content.isEmpty());
        assertTrue(content.description.contains("This is a simple description"));
        assertEquals(0, content.sections.size());
    }

    @Test
    void parseQuickDoc_withParamTag() {
        String doc = "/**\n * Computes a sum.\n *\n * @param a first number\n * @param b second number\n */";
        var content = QuickDoc.parseQuickDoc(doc, "java");
        assertTrue(content.description.contains("Computes a sum"));
        assertFalse(content.sections.isEmpty());
        boolean hasParam = false;
        for (QuickDoc.DocSection s : content.sections) {
            if (s.title.contains("param")) hasParam = true;
        }
        assertTrue(hasParam);
    }

    @Test
    void parseQuickDoc_withMultipleTags() {
        String doc = "/**\n * Does something.\n *\n * @param x the x\n * @return the result\n * @throws Exception on error\n */";
        var content = QuickDoc.parseQuickDoc(doc, "java");
        assertFalse(content.sections.isEmpty());
        assertTrue(content.sections.size() >= 3);
    }

    @Test
    void stripDocMarkers_removesJavaDocMarkers() {
        String doc = "/**\n * Hello\n * World\n */";
        String stripped = QuickDoc.stripDocMarkers(doc);
        assertFalse(stripped.contains("/**"));
        assertFalse(stripped.contains("*/"));
        assertTrue(stripped.contains("Hello"));
        assertTrue(stripped.contains("World"));
    }

    @Test
    void stripDocMarkers_handlesKDoc() {
        String doc = "/**\n * Hello\n */";
        String stripped = QuickDoc.stripDocMarkers(doc);
        assertTrue(stripped.contains("Hello"));
        assertFalse(stripped.contains("/**"));
    }

    @Test
    void inlineMarkup_processesCodeTags() {
        String text = "Use {@code foo} for the value.";
        String result = QuickDoc.inlineMarkup(text);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void inlineMarkup_processesLinkTags() {
        String text = "See {@link Foo} for details.";
        String result = QuickDoc.inlineMarkup(text);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void parseTags_extractsParams() {
        String tagBlock = "@param a first\n@param b second\n@return the sum";
        List<QuickDoc.DocSection> sections = QuickDoc.parseTags(tagBlock);
        assertNotNull(sections);
        assertFalse(sections.isEmpty());
    }

    @Test
    void parseTags_emptyBlock() {
        List<QuickDoc.DocSection> sections = QuickDoc.parseTags("");
        assertNotNull(sections);
        assertTrue(sections.isEmpty());
    }

    @Test
    void parseTags_nullBlock() {
        List<QuickDoc.DocSection> sections = QuickDoc.parseTags(null);
        assertNotNull(sections);
        assertTrue(sections.isEmpty());
    }

    @Test
    void quickDocContent_isEmpty_trueForEmpty() {
        assertTrue(new QuickDoc.QuickDocContent("").isEmpty());
        assertTrue(new QuickDoc.QuickDocContent("", null).isEmpty());
    }

    @Test
    void quickDocContent_isEmpty_falseForNonEmpty() {
        assertFalse(new QuickDoc.QuickDocContent("hello").isEmpty());
    }

    // ── ★ Fences de code markdown → en-tête signature ───────

    @Test
    void parseQuickDoc_fenceExtractedToSignature_descriptionStripped() {
        // Le serveur hover LSP envoie typiquement un fence java contenant
        // la signature exacte, suivi de la javadoc en plain text.
        String doc = "/**\n * ```java\n * public void run(int n)\n * ```\n * Runs the loop n times.\n * @param n iteration count\n */";
        var content = QuickDoc.parseQuickDoc(doc, "java");
        // La signature est extraite du fence (les ``` sont retirés).
        assertTrue(content.signature.contains("public void run(int n)"));
        assertFalse(content.signature.contains("```"));
        // La description ne contient PLUS le fence (retiré AVANT le split).
        assertFalse(content.description.contains("```"));
        assertTrue(content.description.contains("Runs the loop"));
        // @param reste une section (l'annotation dans la signature n'a pas
        // créé de section parasite — le retrait préventif la corrige).
        boolean hasParam = false;
        for (QuickDoc.DocSection s : content.sections) {
            if (s.title.contains("param")) hasParam = true;
        }
        assertTrue(hasParam);
    }

    @Test
    void parseQuickDoc_annotationInsideFence_notParsedAsTag() {
        // Une signature contenant @Override NE doit PAS créer une section
        // @Override parasite — le fence est retiré avant findFirstTag.
        String doc = "/**\n * ```java\n * @Override\n * public String toString()\n * ```\n * Returns a string representation.\n */";
        var content = QuickDoc.parseQuickDoc(doc, "java");
        assertTrue(content.signature.contains("@Override"));
        assertTrue(content.signature.contains("toString"));
        // Aucune section @Override (le fence est hors du tag block).
        assertTrue(content.sections.isEmpty(),
                "sections=" + content.sections);
        assertTrue(content.description.contains("string representation"));
    }

    @Test
    void quickDocContent_isEmpty_falseForSignatureOnly() {
        // Une signature seule (fence sans description) n'est PAS vide.
        var content = new QuickDoc.QuickDocContent(
                "public void run()", "", java.util.Collections.emptyList());
        assertFalse(content.isEmpty());
    }
}
