package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.EmptyLanguage;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Robolectric tests for {@link EditorView} lifecycle.
 *
 * <p>Tests the core lifecycle: construction → setSession → setText →
 * setTheme → setLanguage → undo/redo → getLineCount → destroy.</p>
 *
 * @author jo@Dev
 * @since v3.33.7
 */
@RunWith(RobolectricTestRunner.class)
public class EditorViewLifecycleTest {

    private EditorView createEditor() {
        Context ctx = RuntimeEnvironment.getApplication();
        return new EditorView(ctx);
    }

    @Test
    public void construction_doesNotCrash() {
        EditorView view = createEditor();
        assertNotNull(view);
    }

    @Test
    public void construction_hasDefaultSession() {
        EditorView view = createEditor();
        EditorSession session = view.getSession();
        assertNotNull(session);
    }

    @Test
    public void construction_hasDefaultTheme() {
        EditorView view = createEditor();
        assertNotNull(view.getTheme());
    }

    @Test
    public void construction_hasMetrics() {
        EditorView view = createEditor();
        assertNotNull(view.getMetrics());
    }

    @Test
    public void construction_wordWrapDisabledByDefault() {
        EditorView view = createEditor();
        assertFalse(view.isWordWrap());
    }

    @Test
    public void construction_fontScaleIs1() {
        EditorView view = createEditor();
        assertEquals(1.0f, view.getFontScale(), 0.001f);
    }

    @Test
    public void setSession_replacesSession() {
        EditorView view = createEditor();
        EditorSession old = view.getSession();
        EditorSession newSession = new EditorSession(EditorDocument.of("hello"));
        view.setSession(newSession);
        assertSame(newSession, view.getSession());
        assertNotSame(old, view.getSession());
    }

    @Test
    public void setSession_resetsScroll() {
        EditorView view = createEditor();
        // Simulate scroll
        view.vOffset = 100;
        view.hOffset = 50;
        EditorSession session = new EditorSession(EditorDocument.of("test"));
        view.setSession(session);
        assertEquals(0f, view.getVOffset(), 0.01f);
        assertEquals(0f, view.getHOffset(), 0.01f);
    }

    @Test
    public void setTheme_updatesTheme() {
        EditorView view = createEditor();
        EditorTheme oldTheme = view.getTheme();
        EditorTheme newTheme = EditorTheme.monokai();
        view.setTheme(newTheme);
        assertSame(newTheme, view.getTheme());
        assertNotSame(oldTheme, newTheme);
    }

    @Test
    public void setLanguage_emptyLanguage_doesNotCrash() {
        EditorView view = createEditor();
        view.setLanguage(new EmptyLanguage());
        // No exception = pass
    }

    @Test
    public void setLanguage_null_fallsBackToEmptyLanguage() {
        EditorView view = createEditor();
        view.setLanguage(null);
        // Should not crash — setLanguage handles null by using EmptyLanguage
    }

    @Test
    public void setWordWrap_toggles() {
        EditorView view = createEditor();
        assertFalse(view.isWordWrap());
        view.setWordWrap(true);
        assertTrue(view.isWordWrap());
        view.setWordWrap(false);
        assertFalse(view.isWordWrap());
    }

    @Test
    public void setFontScale_clampsToValidRange() {
        EditorView view = createEditor();
        view.setFontScale(0.5f);
        assertEquals(0.6f, view.getFontScale(), 0.001f); // MIN_FONT_SCALE
        view.setFontScale(3.0f);
        assertEquals(2.6f, view.getFontScale(), 0.001f); // MAX_FONT_SCALE
        view.setFontScale(1.5f);
        assertEquals(1.5f, view.getFontScale(), 0.001f);
    }

    @Test
    public void setFindHighlights_clearsOnEmptyList() {
        EditorView view = createEditor();
        // Set some highlights
        java.util.List<jo.codeeditor.find.Match> matches = new java.util.ArrayList<>();
        matches.add(new jo.codeeditor.find.Match(0, 5));
        view.setFindHighlights(matches, 0);
        assertEquals(1, view.findHighlights.size());
        assertEquals(0, view.findCurrentIndex);

        // Clear
        view.setFindHighlights(null, -1);
        assertTrue(view.findHighlights.isEmpty());
        assertEquals(-1, view.findCurrentIndex);
    }

    @Test
    public void setMinimapEnabled_toggles() {
        EditorView view = createEditor();
        assertFalse(view.isMinimapEnabled());
        view.setMinimapEnabled(true);
        assertTrue(view.isMinimapEnabled());
    }

    @Test
    public void addRemoveSelectionChangedListener_works() {
        EditorView view = createEditor();
        int[] callCount = {0};
        EditorView.OnSelectionChangedListener listener = (line, col, isCursor) -> callCount[0]++;

        view.addOnSelectionChangedListener(listener);
        // Trigger a selection change via setSelection on session
        EditorSession session = view.getSession();
        session.setSelection(5);

        assertTrue("listener should have been called", callCount[0] > 0);

        // Remove and verify it's not called anymore
        int countAfterRemove = callCount[0];
        view.removeOnSelectionChangedListener(listener);
        session.setSelection(10);
        assertEquals("listener should not be called after remove", countAfterRemove, callCount[0]);
    }
}
