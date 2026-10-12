package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.lang.provider.DefinitionProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Traduit le go-to-definition du SPI éditeur en requête LSP
 * {@code textDocument/definition} et convertit les {@code Location} ou
 * {@code LocationLink} retournés en positions éditeur. Instancié par
 * {@link LspLanguage} quand le serveur annonce la capability
 * definitionProvider.
 */
class LspDefinitionProvider implements DefinitionProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspDefinitionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<DefinitionLocation> definitions(CharSequence text, int offset) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        // Flush didChange en attente (cf. complete).
        editor.flushPendingChange();
        org.eclipse.lsp4j.DefinitionParams params = new org.eclipse.lsp4j.DefinitionParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(offset));
        log("definition: requesting at offset=" + offset);
        try {
            org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> defResult =
                server.getTextDocumentService().definition(params).get(3, TimeUnit.SECONDS);
            List<org.eclipse.lsp4j.Location> locations;
            if (defResult == null) {
                locations = Collections.emptyList();
            } else if (defResult.isLeft()) {
                locations = new ArrayList<>(defResult.getLeft());
            } else {
                locations = new ArrayList<>();
                for (org.eclipse.lsp4j.LocationLink link : defResult.getRight()) {
                    org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
                    loc.setUri(link.getTargetUri());
                    loc.setRange(link.getTargetSelectionRange());
                    locations.add(loc);
                }
            }
            if (locations == null || locations.isEmpty()) {
                log("definition: no results");
                return Collections.emptyList();
            }
            log("definition: got " + locations.size() + " locations");
            List<DefinitionLocation> out = new ArrayList<>();
            for (org.eclipse.lsp4j.Location loc : locations) {
                int targetOffset = editor.positionToOffset(loc.getRange().getStart());
                out.add(new DefinitionLocation(loc.getUri(), targetOffset, loc.getUri()));
            }
            return out;
        } catch (Exception e) {
            log("definition: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspDef", message);
        if (logSink != null) logSink.log(message);
    }
}
