package jo.codeeditor.fold;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FoldModelTest {

    @Test
    void build_noRegions() {
        var model = FoldModel.build(10, List.of(), lineTexts(10));
        assertEquals(10, model.totalDocLines());
    }

    @Test
    void build_singleFold() {
        var regions = List.of(
            new FoldRegion(0, 9, "{...}", "block", true)
        );
        var texts = List.of("if (true) {", "    a", "    b", "    c", "}");
        var model = FoldModel.build(5, regions, texts);
        assertNotNull(model);
    }

    @Test
    void build_expandedRegion() {
        var regions = List.of(
            new FoldRegion(0, 20, "...", "block", false)
        );
        var model = FoldModel.build(10, regions, lineTexts(10));
        assertEquals(10, model.totalDocLines());
    }

    @Test
    void isHidden_outOfRange() {
        var model = FoldModel.build(5, List.of(), lineTexts(5));
        assertFalse(model.isHidden(-1));
        assertFalse(model.isHidden(10));
    }

    @Test
    void compositeText_noFold() {
        var model = FoldModel.build(3, List.of(), List.of("line1", "line2", "line3"));
        var composite = model.compositeText(0, List.of("line1", "line2", "line3"));
        assertEquals("line1", composite);
    }

    @Test
    void visualLineCount_noFolds() {
        var model = FoldModel.build(5, List.of(), lineTexts(5));
        assertEquals(5, model.visualLineCount());
    }

    @Test
    void docLineForVisual_noFolds() {
        var model = FoldModel.build(5, List.of(), lineTexts(5));
        for (int i = 0; i < 5; i++) {
            assertEquals(i, model.docLineForVisual(i));
        }
    }

    @Test
    void visualForDocLine_noFolds() {
        var model = FoldModel.build(5, List.of(), lineTexts(5));
        for (int i = 0; i < 5; i++) {
            assertEquals(i, model.visualForDocLine(i));
        }
    }

    @Test
    void foldStartingAt_noFold() {
        var model = FoldModel.build(5, List.of(), lineTexts(5));
        assertNull(model.foldStartingAt(0));
    }

    private List<String> lineTexts(int count) {
        var texts = new ArrayList<String>();
        for (int i = 0; i < count; i++) texts.add("line" + i);
        return texts;
    }
}
