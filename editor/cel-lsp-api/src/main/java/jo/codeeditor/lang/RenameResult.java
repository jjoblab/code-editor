package jo.codeeditor.lang;

import java.util.Collections;
import java.util.List;

/**
 * The result of a rename operation. Returned by {@link RenameProvider}.
 *
 * @since v2.0.0
 */
public final class RenameResult {

    /** The text edits to apply (sorted by offset, descending so they don't shift). */
    public final List<TextEdit> edits;

    public RenameResult(List<TextEdit> edits) {
        this.edits = edits != null ? Collections.unmodifiableList(edits) : Collections.emptyList();
    }
}
