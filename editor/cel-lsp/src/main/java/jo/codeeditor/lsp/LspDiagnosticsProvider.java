package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.DiagnosticsProvider;
import jo.codeeditor.lang.model.Diagnostic;
import java.util.List;

/**
 * Expose au SPI éditeur les diagnostics POUSSÉS par le serveur via
 * {@code textDocument/publishDiagnostics} : relit le cache maintenu par
 * {@link LspEditor} au lieu d'interroger le serveur. Instancié par
 * {@link LspLanguage} sans condition de capability.
 */
class LspDiagnosticsProvider implements DiagnosticsProvider {
    private final LspEditor editor;


    LspDiagnosticsProvider(LspEditor editor) {
        this.editor = editor;
    }

    @Override
    public List<Diagnostic> computeDiagnostics(CharSequence text) {
        // Les diagnostics sont poussés par le serveur via
        // publishDiagnostics, pas tirés. Retourne la liste en cache de
        // l'éditeur.
        return editor.getCachedDiagnostics();
    }
}
