package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.HoverContent;
import jo.codeeditor.lang.provider.HoverProvider;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit le survol du SPI éditeur en requête LSP {@code textDocument/hover}
 * et convertit la réponse du serveur (markup ou liste structurée) en
 * {@link HoverContent} markdown. Instancié par {@link LspLanguage} quand le
 * serveur annonce la capability hoverProvider.
 */
class LspHoverProvider implements HoverProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspHoverProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public HoverContent hover(CharSequence text, int offset) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return null;
        // Flush didChange en attente (cf. complete). Le hover
        // déclenché par frappe/live doit voir le texte serveur à jour.
        editor.flushPendingChange();
        org.eclipse.lsp4j.HoverParams params = new org.eclipse.lsp4j.HoverParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(offset));
        log("hover: requesting at offset=" + offset);
        try {
            CompletableFuture<Hover> future = server.getTextDocumentService().hover(params);
            // 2 s — un tooltip de survol ne doit JAMAIS bloquer 10 s.
            Hover hover = future.get(2, TimeUnit.SECONDS);
            if (hover == null) {
                log("hover: server returned null");
                return null;
            }
            String markdown = "";
            if (hover.getContents().isRight()) {
                MarkupContent mc = hover.getContents().getRight();
                markdown = mc.getValue();
            } else if (hover.getContents().isLeft()) {
                markdown = hover.getContents().getLeft().stream()
                    .map(e -> e.isLeft() ? e.getLeft() : e.getRight().getValue())
                    .reduce("", (a, b) -> a + "\n" + b);
            }
            log("hover: got " + markdown.length() + " chars");
            return new HoverContent("", "", markdown);
        } catch (Exception e) {
            log("hover: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void log(String message) {
        android.util.Log.i("LspHover", message);
        if (logSink != null) logSink.log(message);
    }
}
