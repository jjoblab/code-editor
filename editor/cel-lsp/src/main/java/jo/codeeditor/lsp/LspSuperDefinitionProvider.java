package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.lang.provider.SuperDefinitionProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code textDocument/superDefinition} (méthode LSP
 * PERSONNALISÉE, cf. NavTextDocumentService côté :lspjava) : les cibles
 * GO TO « Super ». Appel TYPÉ via le proxy étendu
 * ({@link CodeIdeLanguageServer#superDefinition}) — la réponse est
 * désérialisée par LSP4J en {@code SuperNavTarget} (la méthode fait
 * partie de la carte de désérialisation du launcher, sans quoi le result
 * serait silencieusement jeté). Un serveur tiers qui ne connaît pas la
 * méthode répond « method not found » → vide (l'option n'apparaît pas).
 * La réponse transporte l'OFFSET EXACT du nom dans le fichier déclarant +
 * le libellé picker (« Simple  ·  pkg » / « name  ·  Super »).
 */
class LspSuperDefinitionProvider implements SuperDefinitionProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspSuperDefinitionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<DefinitionLocation> superTargets(CharSequence text, int offset) {
        CodeIdeLanguageServer ext = wrapper.getExtendedServer();
        if (ext == null) {
            return Collections.emptyList();
        }
        org.eclipse.lsp4j.TextDocumentPositionParams params =
                new org.eclipse.lsp4j.TextDocumentPositionParams(
                        editor.getTextDocumentIdentifier(), editor.offsetToPosition(offset));
        log("superDefinition: requesting at offset=" + offset);
        try {
            List<CodeIdeLanguageServer.SuperNavTarget> hits = ext
                    .superDefinition(params)
                    .get(8, TimeUnit.SECONDS);
            if (hits == null || hits.isEmpty()) {
                log("superDefinition: no results");
                return Collections.emptyList();
            }
            log("superDefinition: got " + hits.size() + " targets");
            List<DefinitionLocation> out = new ArrayList<>();
            for (CodeIdeLanguageServer.SuperNavTarget hit : hits) {
                if (hit == null || hit.path == null) continue;
                // Le serveur envoie un chemin ABSOLU ; l'éditeur attend
                // une URI (ou un chemin nu — openFileAt accepte les deux).
                String uri = hit.path.startsWith("file://")
                        ? hit.path : "file://" + hit.path;
                String label = hit.label != null && !hit.label.isEmpty()
                        ? hit.label : uri;
                out.add(new DefinitionLocation(uri, hit.start, label));
            }
            return out;
        } catch (Exception e) {
            log("superDefinition: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspSuper", message);
        if (logSink != null) logSink.log(message);
    }
}
