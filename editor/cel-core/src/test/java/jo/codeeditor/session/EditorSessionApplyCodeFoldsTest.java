package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de {@link EditorSession#applyCodeFolds} : préservation de l'état
 * replié utilisateur, application UNIQUE des collapsedByDefault (imports),
 * reset par document.
 *
 * @author jo@Dev
 */
class EditorSessionApplyCodeFoldsTest {

    private static DiagnosticShift.FoldRegion region(int start, int end,
            String kind, boolean collapsed, boolean collapsedByDefault) {
        return new DiagnosticShift.FoldRegion(start, end, "…", kind,
                collapsed, collapsedByDefault);
    }

    @Test
    void collapsedByDefaultAppliedFirstTimeOnly() {
        EditorSession s = new EditorSession(EditorDocument.of("line0\nline1\nline2\n"));
        // Premier tir serveur : le groupe d'imports est collapsedByDefault.
        s.applyCodeFolds(List.of(region(0, 10, "imports", false, true)));
        assertTrue(s.getFoldRegions().get(0).collapsed,
                "imports repliés à la première application");

        // L'utilisateur DÉPLIE.
        s.toggleFoldAtLine(0);
        assertFalse(s.getFoldRegions().get(0).collapsed,
                "l'utilisateur a déplié le groupe d'imports");

        // Re-tir serveur (même région) : NE PAS re-replier.
        s.applyCodeFolds(List.of(region(0, 10, "imports", false, true)));
        assertFalse(s.getFoldRegions().get(0).collapsed,
                "un re-tir ne re-plie pas une région dépliée");
    }

    @Test
    void userCollapsedRegionStaysCollapsedAcrossRefresh() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc\nd\n"));
        s.applyCodeFolds(List.of(region(0, 2, "block", false, false)));
        assertFalse(s.getFoldRegions().get(0).collapsed);

        // L'utilisateur REPLIE le corps de méthode.
        s.toggleFoldAtLine(0);
        assertTrue(s.getFoldRegions().get(0).collapsed);

        // Le serveur re-tire après une édition : la région reste repliée.
        s.applyCodeFolds(List.of(region(0, 2, "block", false, false)));
        assertTrue(s.getFoldRegions().get(0).collapsed,
                "l'état replié utilisateur survit au refresh serveur");
    }

    @Test
    void newRegionFollowsFreshDefault() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\n"));
        s.applyCodeFolds(List.of(region(0, 1, "block", false, false)));
        // foldDefaultsApplied est true après le premier tir — une NOUVELLE
        // région collapsedByDefault (créée par une édition) ne se plie PAS
        // automatiquement.
        s.applyCodeFolds(List.of(
                region(0, 1, "block", false, false),
                region(2, 5, "imports", false, true)));
        assertFalse(s.getFoldRegions().get(1).collapsed,
                "nouvelle région par défaut NON replie après le premier tir");
    }

    @Test
    void setLanguageResetsDefaults() {
        EditorSession s = new EditorSession(EditorDocument.of("x\ny\n"));
        s.applyCodeFolds(List.of(region(0, 1, "imports", false, true)));
        assertTrue(s.getFoldRegions().get(0).collapsed);
        s.toggleFoldAtLine(0); // déplié
        // NOUVEAU document : setLanguage reset foldDefaultsApplied → les
        // imports se replient à nouveau à l'ouverture suivante.
        s.setLanguage("java");
        s.applyCodeFolds(List.of(region(0, 1, "imports", false, true)));
        assertTrue(s.getFoldRegions().get(0).collapsed,
                "après setLanguage, les défauts s'appliquent à nouveau");
    }

    @Test
    void collapsedDefaultFieldSurvivesLegacyConstructor() {
        // L'ancien constructeur 5-args positionne collapsedByDefault à false.
        DiagnosticShift.FoldRegion legacy =
                new DiagnosticShift.FoldRegion(0, 5, "…", "block", true);
        assertTrue(legacy.collapsed);
        assertFalse(legacy.collapsedByDefault);
    }
}
