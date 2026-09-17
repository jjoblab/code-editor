package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.lang.provider.TypeDefinitionProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Traduit le go-to-type-definition du SPI éditeur en requête LSP
 * {@code textDocument/typeDefinition} et convertit les résultats en
 * positions éditeur. Instancié par {@link LspLanguage} quand le serveur
 * annonce la capability typeDefinitionProvider.
 */
class LspTypeDefinitionProvider implements TypeDefinitionProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspTypeDefinitionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<DefinitionLocation> typeDefinitions(CharSequence text, int offset) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        org.eclipse.lsp4j.TypeDefinitionParams params = new org.eclipse.lsp4j.TypeDefinitionParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(offset));
        log("typeDefinition: requesting at offset=" + offset);
        try {
            org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> defResult =
                server.getTextDocumentService().typeDefinition(params).get(3, TimeUnit.SECONDS);
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
                log("typeDefinition: no results");
                return Collections.emptyList();
            }
            log("typeDefinition: got " + locations.size() + " locations");
            List<DefinitionLocation> out = new ArrayList<>();
            for (org.eclipse.lsp4j.Location loc : locations) {
                int targetOffset = editor.positionToOffset(loc.getRange().getStart());
                out.add(new DefinitionLocation(loc.getUri(), targetOffset, loc.getUri()));
            }
            return out;
        } catch (Exception e) {
            log("typeDefinition: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspTypeDef", message);
        if (logSink != null) logSink.log(message);
    }
}
