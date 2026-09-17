package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.SymbolProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.SymbolKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit la demande de symboles du document (fil d'Ariane, barre de
 * symboles) en requête LSP {@code textDocument/documentSymbol} et aplatit
 * l'arbre retourné en liste de positions éditeur. Instancié par
 * {@link LspLanguage} quand le serveur annonce la capability
 * documentSymbolProvider.
 */
class LspSymbolProvider implements SymbolProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspSymbolProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<jo.codeeditor.lang.model.Symbol> symbols(CharSequence text) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        DocumentSymbolParams params = new DocumentSymbolParams(editor.getTextDocumentIdentifier());
        log("documentSymbol: requesting");
        try {
            CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>>> future =
                server.getTextDocumentService().documentSymbol(params);
            List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> result =
                future.get(3, TimeUnit.SECONDS);
            if (result == null) {
                log("documentSymbol: null result");
                return Collections.emptyList();
            }
            log("documentSymbol: got " + result.size() + " symbols");
            List<jo.codeeditor.lang.model.Symbol> symbols = new ArrayList<>();
            for (org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol> e : result) {
                if (e.isRight()) {
                    DocumentSymbol ds = e.getRight();
                    int offset = editor.positionToOffset(ds.getRange().getStart());
                    symbols.add(new jo.codeeditor.lang.model.Symbol(
                        ds.getName(), offset, symbolKindToString(ds.getKind()), ""));
                }
            }
            return symbols;
        } catch (Exception e) {
            log("documentSymbol: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspSymbol", message);
        if (logSink != null) logSink.log(message);
    }

    private static String symbolKindToString(SymbolKind kind) {
        if (kind == null) return "field";
        switch (kind) {
            case Class: return "class";
            case Interface: return "interface";
            case Enum: return "enum";
            case Method: case Constructor: return "method";
            case Field: case Property: return "field";
            default: return "field";
        }
    }
}
