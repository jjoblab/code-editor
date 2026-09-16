package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides view zones — inline UI gaps inserted between lines. Used for
 * inline refactors, inline type hints, and "lightbulb" quick-fixes that
 * need more than a popup.
 *
 * <p>This is a feature Sora Editor is missing (issue #787) — we add it
 * from day one so it doesn't need to be retrofitted later.
 *
 * @since v2.0.0
 */
public interface ViewZoneProvider {

    /**
     * Returns the view zones for the given line range.
     *
     * @param text       the full document text
     * @param startLine  the first line (0-based, inclusive)
     * @param endLine    the last line (0-based, inclusive)
     * @return the list of view zones (may be empty)
     */
    List<ViewZone> viewZones(CharSequence text, int startLine, int endLine);
}
