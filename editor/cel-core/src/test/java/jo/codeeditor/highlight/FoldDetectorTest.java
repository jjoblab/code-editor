package jo.codeeditor.highlight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de {@link FoldDetector}.
 */
class FoldDetectorTest {

    @Test
    void detectBraceFolds_javaClass() {
        String text = "public class Foo {\n" +
            "    public void method() {\n" +
            "        System.out.println(\"hello\");\n" +
            "    }\n" +
            "}\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "java");
        // Doit détecter le bloc de la classe et celui de la méthode.
        assertTrue(folds.size() >= 2, "expected >= 2 folds, got " + folds.size());
    }

    @Test
    void detectBraceFolds_skipsBracesInStrings() {
        String text = "String s = \"{ not a block }\";\n" +
            "public class Foo {\n" +
            "    int x = 1;\n" +
            "    int y = 2;\n" +
            "}\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "java");
        // Doit détecter uniquement le bloc de la classe, pas les accolades de la chaîne.
        assertEquals(1, folds.size(), "expected 1 fold, got " + folds.size());
    }

    @Test
    void detectBraceFolds_skipsBracesInComments() {
        String text = "// { not a block }\n" +
            "public class Foo {\n" +
            "    int x;\n" +
            "    int y;\n" +
            "}\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "java");
        assertEquals(1, folds.size(), "expected 1 fold, got " + folds.size());
    }

    @Test
    void detectBraceFolds_noFoldForSingleLineBlock() {
        String text = "public class Foo { int x = 1; }\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "java");
        assertTrue(folds.isEmpty(), "single-line block should not fold");
    }

    @Test
    void detectLuaFolds_functionEnd() {
        String text = "function foo()\n" +
            "    local x = 1\n" +
            "    local y = 2\n" +
            "    return x + y\n" +
            "end\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "lua");
        assertEquals(1, folds.size(), "expected 1 fold for function...end");
        assertFalse(folds.get(0).collapsed, "fold should start uncollapsed");
    }

    @Test
    void detectLuaFolds_nestedBlocks() {
        String text = "function foo()\n" +
            "    if true then\n" +
            "        print(\"yes\")\n" +
            "        print(\"still yes\")\n" +
            "    end\n" +
            "end\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "lua");
        assertTrue(folds.size() >= 2, "expected >= 2 folds (function + if), got " + folds.size());
    }

    @Test
    void detectLuaFolds_skipsComments() {
        String text = "-- function fake()\n" +
            "-- end\n" +
            "function real()\n" +
            "    print(\"hi\")\n" +
            "    print(\"there\")\n" +
            "end\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "lua");
        assertEquals(1, folds.size(), "should only detect real function, not comment");
    }

    @Test
    void detectPythonFolds_defClass() {
        String text = "class Foo:\n" +
            "    def bar(self):\n" +
            "        x = 1\n" +
            "        y = 2\n" +
            "        return x + y\n" +
            "\n" +
            "    def baz(self):\n" +
            "        return 42\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "python");
        assertTrue(folds.size() >= 2, "expected >= 2 folds (class + method), got " + folds.size());
    }

    @Test
    void detectPythonFolds_ifElif() {
        String text = "if True:\n" +
            "    x = 1\n" +
            "    y = 2\n" +
            "elif False:\n" +
            "    z = 3\n" +
            "else:\n" +
            "    w = 4\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "python");
        assertTrue(folds.size() >= 2, "expected >= 2 folds (if + elif), got " + folds.size());
    }

    @Test
    void detectXmlFolds_elementBlock() {
        String text = "<LinearLayout>\n" +
            "    <TextView\n" +
            "        android:text=\"hello\"\n" +
            "        android:layout_width=\"wrap\" />\n" +
            "    <Button\n" +
            "        android:text=\"click\" />\n" +
            "</LinearLayout>\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "xml");
        assertEquals(1, folds.size(), "expected 1 fold for LinearLayout");
        assertTrue(folds.get(0).placeholder.contains("LinearLayout"));
    }

    @Test
    void detectXmlFolds_skipsSelfClosingTags() {
        String text = "<TextView android:text=\"hi\" />\n" +
            "<TextView android:text=\"bye\" />\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "xml");
        assertTrue(folds.isEmpty(), "self-closing tags should not fold");
    }

    @Test
    void detectMarkdownFolds_headings() {
        String text = "# Title\n" +
            "Some content here\n" +
            "More content\n" +
            "## Section 1\n" +
            "Section 1 content\n" +
            "More section 1\n" +
            "## Section 2\n" +
            "Section 2 content\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "markdown");
        assertTrue(folds.size() >= 1, "expected >= 1 fold for headings");
    }

    @Test
    void detect_nullLanguage_usesBraceFolds() {
        String text = "class Foo {\n" +
            "    int x;\n" +
            "    int y;\n" +
            "}\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, null);
        assertEquals(1, folds.size(), "null language should default to brace detection");
    }

    @Test
    void detect_emptyText_returnsEmptyList() {
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect("", "java");
        assertTrue(folds.isEmpty());
    }

    @Test
    void detect_allFoldsStartUncollapsed() {
        String text = "class Foo {\n" +
            "    int x;\n" +
            "    int y;\n" +
            "}\n";
        List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds =
            FoldDetector.detect(text, "java");
        for (jo.codeeditor.shift.DiagnosticShift.FoldRegion r : folds) {
            assertFalse(r.collapsed, "fold should start uncollapsed");
        }
    }
}
