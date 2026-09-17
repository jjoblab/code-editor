package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.lang.provider.ReferencesProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Traduit la recherche de références du SPI éditeur en requête LSP
 * {@code textDocument/references} (sans la déclaration) et convertit chaque
 * {@code Location} en position éditeur — en lisant le contenu du fichier
 * déclarant pour les résultats multi-fichiers. Instancié par
 * {@link LspLanguage} quand le serveur annonce la capability
 * referencesProvider.
 */
class LspReferencesProvider implements ReferencesProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspReferencesProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<DefinitionLocation> references(CharSequence text, int offset) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        // Flush didChange en attente (cf. complete).
        editor.flushPendingChange();
        org.eclipse.lsp4j.ReferenceParams params = new org.eclipse.lsp4j.ReferenceParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(offset));
        // includeDeclaration=false — la déclaration du symbole elle-même
        // est le travail de getDefinitionProvider(). Le find-references ne
        // doit retourner QUE les usages.
        params.setContext(new org.eclipse.lsp4j.ReferenceContext(false));
        log("references: requesting at offset=" + offset);
        try {
            CompletableFuture<List<? extends org.eclipse.lsp4j.Location>> future =
                server.getTextDocumentService().references(params);
            // ★ 15 s : les références multi-fichiers balayent les racines
            // sources côté serveur (chacune résolue par ecj).
            List<? extends org.eclipse.lsp4j.Location> locations = future.get(15, TimeUnit.SECONDS);
            if (locations == null || locations.isEmpty()) {
                log("references: no results");
                return Collections.emptyList();
            }
            log("references: got " + locations.size() + " locations");
            List<DefinitionLocation> out = new ArrayList<>();
            for (org.eclipse.lsp4j.Location loc : locations) {
                String uri = loc.getUri();
                org.eclipse.lsp4j.Position p = loc.getRange().getStart();
                boolean current = editor.getFileUri() != null
                        && editor.getFileUri().equals(uri);
                int targetOffset;
                String label;
                if (current) {
                    // Fichier courant : conversion via l'éditeur.
                    targetOffset = editor.positionToOffset(p);
                    label = "L" + (p.getLine() + 1) + ":C" + (p.getCharacter() + 1);
                } else {
                    // Autre fichier : conversion sur SON contenu (le
                    // positionToOffset de l'éditeur courant serait faux)
                    // + libellé lisible « NomFichier.java:L12 ».
                    // Lecture compatible API 24 — Path.of +
                    // Files.readAllBytes (API 34+/26+) plantaient sur
                    // Android < 8. IoCompat retire lui-même file://.
                    String path = uri.startsWith("file://")
                            ? uri.substring("file://".length()) : uri;
                    String content = IoCompat.readUtf8(uri);
                    if (content == null) {
                        log("references: fichier illisible " + path);
                        targetOffset = 0;
                    } else {
                        targetOffset = LspRenameProvider.lspPositionToOffset(content, p);
                        if (targetOffset < 0) targetOffset = 0;
                    }
                    String fileName = path.substring(path.lastIndexOf('/') + 1);
                    label = fileName + ":L" + (p.getLine() + 1);
                }
                out.add(new DefinitionLocation(uri, targetOffset, label));
            }
            return out;
        } catch (TimeoutException e) {
            log("references: TIMEOUT (15s) — server too slow or hung");
            return Collections.emptyList();
        } catch (InterruptedException | ExecutionException e) {
            String msg = "references: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
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
        android.util.Log.i("LspRef", message);
        if (logSink != null) logSink.log(message);
    }
}
