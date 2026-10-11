package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.DocumentHighlight;
import jo.codeeditor.lang.provider.DocumentHighlightProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit le surlignage des occurrences du symbole au caret en requête LSP
 * {@code textDocument/documentHighlight} et convertit les plages retournées
 * en offsets éditeur. Instancié par {@link LspLanguage} quand le serveur
 * annonce la capability documentHighlightProvider.
 */
class LspDocumentHighlightProvider implements DocumentHighlightProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspDocumentHighlightProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<DocumentHighlight> highlights(CharSequence text, int offset) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        org.eclipse.lsp4j.DocumentHighlightParams params = new org.eclipse.lsp4j.DocumentHighlightParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(offset));
        log("documentHighlight: requesting at offset=" + offset);
        try {
            CompletableFuture<List<org.eclipse.lsp4j.DocumentHighlight>> future =
                (CompletableFuture<List<org.eclipse.lsp4j.DocumentHighlight>>) (CompletableFuture<?>)
                server.getTextDocumentService().documentHighlight(params);
            List<org.eclipse.lsp4j.DocumentHighlight> result = future.get(10, TimeUnit.SECONDS);
            if (result == null || result.isEmpty()) {
                log("documentHighlight: no results");
                return Collections.emptyList();
            }
            log("documentHighlight: got " + result.size() + " occurrences");
            List<DocumentHighlight> out = new ArrayList<>();
            for (org.eclipse.lsp4j.DocumentHighlight dh : result) {
                int start = editor.positionToOffset(dh.getRange().getStart());
                int end = editor.positionToOffset(dh.getRange().getEnd());
                String kind = dh.getKind() != null ? dh.getKind().name().toLowerCase() : "text";
                out.add(new DocumentHighlight(start, end, kind));
            }
            return out;
        } catch (Exception e) {
            log("documentHighlight: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspDocHighlight", message);
        if (logSink != null) logSink.log(message);
    }
}
