package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.lang.provider.ImplementationsProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code textDocument/implementation} : les héritiers DIRECTS du
 * type en contexte. MULTI-FICHIERS : chaque Location vit dans le fichier
 * DECLARANT — la conversion Position→offset se fait sur le CONTENU de ce
 * fichier (pattern du provider references), jamais sur le document
 * courant. Libellé picker : « NomFichier.java ».
 */
class LspImplementationsProvider implements ImplementationsProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspImplementationsProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<DefinitionLocation> implementations(CharSequence text, int offset) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        org.eclipse.lsp4j.ImplementationParams params = new org.eclipse.lsp4j.ImplementationParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(offset));
        log("implementation: requesting at offset=" + offset);
        try {
            org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                server.getTextDocumentService().implementation(params).get(8, TimeUnit.SECONDS);
            List<org.eclipse.lsp4j.Location> locations;
            if (result == null) {
                locations = Collections.emptyList();
            } else if (result.isLeft()) {
                locations = new ArrayList<>(result.getLeft());
            } else {
                locations = new ArrayList<>();
                for (org.eclipse.lsp4j.LocationLink link : result.getRight()) {
                    org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
                    loc.setUri(link.getTargetUri());
                    loc.setRange(link.getTargetSelectionRange());
                    locations.add(loc);
                }
            }
            if (locations == null || locations.isEmpty()) {
                log("implementation: no results");
                return Collections.emptyList();
            }
            log("implementation: got " + locations.size() + " locations");
            List<DefinitionLocation> out = new ArrayList<>();
            for (org.eclipse.lsp4j.Location loc : locations) {
                String uri = loc.getUri();
                org.eclipse.lsp4j.Position p = loc.getRange().getStart();
                boolean current = editor.getFileUri() != null
                        && editor.getFileUri().equals(uri);
                int targetOffset;
                String label;
                if (current) {
                    targetOffset = editor.positionToOffset(p);
                } else {
                    // Conversion sur le CONTENU du fichier cible (le
                    // positionToOffset de l'éditeur courant serait faux).
                    // Lecture compatible API 24 — Path.of +
                    // Files.readAllBytes (API 34+/26+) plantaient sur
                    // Android < 8. IoCompat retire lui-même le schéma
                    // file://.
                    String content = IoCompat.readUtf8(uri);
                    targetOffset = content != null
                            ? LspRenameProvider.lspPositionToOffset(content, p) : 0;
                    if (targetOffset < 0) targetOffset = 0;
                }
                // Libellé : le nom COURT du fichier (une classe publique
                // Java vit dans le fichier de son nom — « ImplA.java »).
                String path = uri.startsWith("file://")
                        ? uri.substring("file://".length()) : uri;
                int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                label = slash >= 0 ? path.substring(slash + 1) : path;
                out.add(new DefinitionLocation(uri, targetOffset, label));
            }
            return out;
        } catch (Exception e) {
            log("implementation: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspImpl", message);
        if (logSink != null) logSink.log(message);
    }
}
