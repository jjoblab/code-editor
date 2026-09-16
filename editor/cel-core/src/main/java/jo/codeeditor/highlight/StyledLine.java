package jo.codeeditor.highlight;

import java.util.Collections;
import java.util.List;

/**
 * A tokenized line with syntax highlighting spans and the exit state
 * for cross-line parsing (e.g., multi-line comments).
 
 *
 * @since v1.0.0
*/
public final class StyledLine {
    public final List<LineSpan> spans;
    public final int entryState;
    public final int exitState;

    public StyledLine(List<LineSpan> spans, int entryState, int exitState) {
        this.spans = Collections.unmodifiableList(spans);
        this.entryState = entryState;
        this.exitState = exitState;
    }

    @Override
    public String toString() {
        return "StyledLine(spans=" + spans.size() + ", state " + entryState + "→" + exitState + ")";
    }
}
