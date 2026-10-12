package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.Formatter;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit le formatage de document du SPI éditeur en requête LSP
 * {@code textDocument/formatting} puis applique les éditions retournées au
 * texte courant, en ordre d'offset décroissant. Instancié par
 * {@link LspLanguage} quand le serveur annonce la capability
 * documentFormattingProvider.
 */
class LspFormatter implements Formatter {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspFormatter(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public CharSequence format(CharSequence text, int startOffset, int endOffset) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return text;
        // Flush didChange en attente (cf. complete / inlayHints).
        // Le formatter LSP calcule des éditions sur le texte serveur — si
        // le texte est STALE (didChange debouncé à 300 ms pas encore envoyé),
        // les éditions seront calculées sur une version obsolète et
        // appliqueront des offsets hors-sync.
        editor.flushPendingChange();
        try {
            org.eclipse.lsp4j.DocumentFormattingParams params =
                new org.eclipse.lsp4j.DocumentFormattingParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            CompletableFuture<List<org.eclipse.lsp4j.TextEdit>> future =
                (CompletableFuture<List<org.eclipse.lsp4j.TextEdit>>) (CompletableFuture<?>)
                server.getTextDocumentService().formatting(params);
            List<org.eclipse.lsp4j.TextEdit> edits = future.get(5, TimeUnit.SECONDS);
            if (edits == null || edits.isEmpty()) return text;
            // Applique les éditions en ordre inverse (offset décroissant).
            StringBuilder sb = new StringBuilder(text);
            edits.sort((a, b) -> Integer.compare(
                editor.positionToOffset(b.getRange().getStart()),
                editor.positionToOffset(a.getRange().getStart())));
            for (org.eclipse.lsp4j.TextEdit te : edits) {
                int s = editor.positionToOffset(te.getRange().getStart());
                int e = editor.positionToOffset(te.getRange().getEnd());
                sb.replace(s, e, te.getNewText());
            }
            return sb.toString();
        } catch (Exception e) {
            return text;
        }
    }
}
