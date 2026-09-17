package jo.codeeditor.highlight;

import java.util.Collections;
import java.util.List;

/**
 * Ligne tokénisée avec ses portions de coloration syntaxique et l'état
 * de sortie pour l'analyse inter-lignes (ex. commentaires multi-lignes).
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
