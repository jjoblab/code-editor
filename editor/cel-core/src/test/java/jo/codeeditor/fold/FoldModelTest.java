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
    void compositeText_suffixStartsAtFoldEnd_notAtLineStart() {
        // Pli [6, 21) : commence dans la ligne 0 (col 6, après « alpha »),
        // se termine dans la ligne 1 (col 8, sur « delta »).
        // Lignes : "alpha bravo," (0-11), "charlie delta," (13-26), "echo" (28-31).
        var regions = List.of(new FoldRegion(6, 21, "…", "block", true));
        var texts = List.of("alpha bravo,", "charlie delta,", "echo");
        var model = FoldModel.build(3, regions, texts);

        var composite = model.compositeText(0, texts);
        // Suffixe = dernière ligne à partir de la colonne de FIN du pli
        // (« delta, ») — historiquement la formule recalcule une colonne
        // toujours 0 et affichait « charlie delta, » EN ENTIER.
        assertEquals("alpha …delta,", composite);
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
