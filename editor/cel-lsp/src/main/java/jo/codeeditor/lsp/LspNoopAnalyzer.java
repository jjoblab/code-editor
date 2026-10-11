package jo.codeeditor.lsp;

import jo.codeeditor.lang.Analyzer;
import java.util.Collections;
import java.util.List;

/** Analyseur no-op — l'EditorView utilise son propre tokéniseur lexical. */
class LspNoopAnalyzer implements Analyzer {
    @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver receiver) {}
    @Override public void onReplace(CharSequence text, int s, int e, CharSequence i) {}
    @Override public void reset(CharSequence text) {}
    @Override public jo.codeeditor.highlight.StyledLine styledLine(int line) { return null; }
    @Override public List<jo.codeeditor.lang.model.CodeBlock> computeBlocks() { return Collections.emptyList(); }
    @Override public jo.codeeditor.lang.BracketMatch computeBracketMatch(int offset) { return null; }
    @Override public void destroy() {}
}
