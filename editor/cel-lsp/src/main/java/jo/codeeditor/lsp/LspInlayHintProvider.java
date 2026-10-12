package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.InlayHint;
import jo.codeeditor.lang.provider.InlayHintProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Traduit la demande d'inlay hints du SPI éditeur en requête LSP
 * {@code textDocument/inlayHint} bornée aux lignes visibles, et convertit
 * libellés (avec padding), kind et position en hints éditeur. Instancié par
 * {@link LspLanguage} quand le serveur annonce la capability
 * inlayHintProvider.
 */
class LspInlayHintProvider implements InlayHintProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspInlayHintProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<InlayHint> inlayHints(CharSequence text, int startLine, int endLine) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        // Flush didChange en attente (cf. complete).
        // Les inlay hints sont déduits par inférence de type sur le
        // texte source — si le texte serveur est STALE (didChange
        // debouncé à 300 ms pas encore envoyé), les hints seront
        // calculés sur une version obsolète et les offsets retournés
        // ne correspondront pas aux positions du texte live.
        editor.flushPendingChange();
        org.eclipse.lsp4j.InlayHintParams params = new org.eclipse.lsp4j.InlayHintParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        // Convertit (startLine, endLine) en plage LSP. Colonne 0 au
        // début et Integer.MAX_VALUE à la fin pour couvrir toute la
        // plage de lignes.
        jo.codeeditor.document.EditorDocument doc = editor.getEditorView() != null
            ? editor.getEditorView().getSession().getDocument() : null;
        int safeStartLine = doc != null ? Math.max(0, Math.min(startLine, doc.lineCount() - 1)) : Math.max(0, startLine);
        int safeEndLine = doc != null ? Math.max(safeStartLine, Math.min(endLine, doc.lineCount() - 1)) : Math.max(safeStartLine, endLine);
        org.eclipse.lsp4j.Range range = new org.eclipse.lsp4j.Range(
            new org.eclipse.lsp4j.Position(safeStartLine, 0),
            new org.eclipse.lsp4j.Position(safeEndLine, Integer.MAX_VALUE));
        params.setRange(range);
        log("inlayHint: requesting lines " + safeStartLine + "-" + safeEndLine);
        try {
            CompletableFuture<List<org.eclipse.lsp4j.InlayHint>> future =
                server.getTextDocumentService().inlayHint(params);
            List<org.eclipse.lsp4j.InlayHint> hints = future.get(5, TimeUnit.SECONDS);
            if (hints == null || hints.isEmpty()) {
                log("inlayHint: no results");
                return Collections.emptyList();
            }
            List<InlayHint> out = new ArrayList<>();
            for (org.eclipse.lsp4j.InlayHint h : hints) {
                int offset = editor.positionToOffset(h.getPosition());
                String label;
                if (h.getLabel().isLeft()) {
                    label = h.getLabel().getLeft();
                } else if (h.getLabel().isRight()) {
                    // Assemble les parties du libellé (certains serveurs
                    // utilisent des parts).
                    StringBuilder sb = new StringBuilder();
                    for (org.eclipse.lsp4j.InlayHintLabelPart part : h.getLabel().getRight()) {
                        sb.append(part.getValue());
                    }
                    label = sb.toString();
                } else {
                    label = "";
                }
                String kind = "type";
                if (h.getKind() == org.eclipse.lsp4j.InlayHintKind.Parameter) {
                    kind = "parameter";
                }
                // Padding LSP honoré : « txt = (si paddingLeft " ") +
                // text + (si paddingRight " ") » — les espaces de padding
                // font partie du TEXTE FANTÔME tissé dans la ligne. Sans
                // cela, « name: » collerait à l'argument et le type de
                // chaînage au dernier caractère de l'appel.
                if (Boolean.TRUE.equals(h.getPaddingLeft())) {
                    label = " " + label;
                }
                if (Boolean.TRUE.equals(h.getPaddingRight())) {
                    label = label + " ";
                }
                out.add(new InlayHint(offset, label, kind));
            }
            log("inlayHint: got " + out.size() + " hints");
            return out;
        } catch (TimeoutException e) {
            log("inlayHint: TIMEOUT (5s) — server too slow or hung");
            return Collections.emptyList();
        } catch (InterruptedException | ExecutionException e) {
            String msg = "inlayHint: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                msg += "\n  caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                cause = cause.getCause();
            }
            log(msg);
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspInlay", message);
        if (logSink != null) logSink.log(message);
    }
}
