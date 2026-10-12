package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.SignatureHelp;
import jo.codeeditor.lang.provider.SignatureHelpProvider;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit l'aide à la signature du SPI éditeur en requête LSP
 * {@code textDocument/signatureHelp} et convertit signatures, paramètres et
 * documentation pour le popup éditeur. Instancié par {@link LspLanguage}
 * quand le serveur annonce la capability signatureHelpProvider.
 */
class LspSignatureHelpProvider implements SignatureHelpProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspSignatureHelpProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public SignatureHelp signatureHelp(CharSequence text, int caret) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return null;
        // Flush didChange en attente (cf. complete). La
        // signature-help déclenchée par frappe/live doit voir le texte
        // serveur à jour, sinon le call-site analysé peut être décalé.
        editor.flushPendingChange();
        SignatureHelpParams params = new SignatureHelpParams(
            editor.getTextDocumentIdentifier(), editor.offsetToPosition(caret));
        log("signatureHelp: requesting at caret=" + caret);
        try {
            CompletableFuture<org.eclipse.lsp4j.SignatureHelp> future =
                server.getTextDocumentService().signatureHelp(params);
            org.eclipse.lsp4j.SignatureHelp help = future.get(10, TimeUnit.SECONDS);
            if (help == null || help.getSignatures().isEmpty()) {
                log("signatureHelp: no signatures returned");
                return null;
            }
            log("signatureHelp: got " + help.getSignatures().size() + " signatures");
            List<jo.codeeditor.lang.model.Signature> sigs = new ArrayList<>();
            for (SignatureInformation info : help.getSignatures()) {
                List<jo.codeeditor.lang.model.Parameter> params2 = new ArrayList<>();
                if (info.getParameters() != null) {
                    info.getParameters().forEach(p -> {
                        String label = p.getLabel().getLeft() != null ? p.getLabel().getLeft() : "";
                        params2.add(new jo.codeeditor.lang.model.Parameter(label, ""));
                    });
                }
                // La documentation arrive en String (Left) OU en
                // MarkupContent (Right) selon le serveur — les deux côtés
                // sont lus (le serveur :lspjava envoie du plain text).
                String doc = "";
                if (info.getDocumentation() != null) {
                    if (info.getDocumentation().getRight() != null) {
                        doc = info.getDocumentation().getRight().getValue();
                    } else if (info.getDocumentation().getLeft() != null) {
                        doc = info.getDocumentation().getLeft();
                    }
                }
                sigs.add(new jo.codeeditor.lang.model.Signature(info.getLabel(), doc, params2,
                    help.getActiveParameter() != null ? help.getActiveParameter() : 0));
            }
            return new SignatureHelp(sigs,
                help.getActiveSignature() != null ? help.getActiveSignature() : 0,
                help.getActiveParameter() != null ? help.getActiveParameter() : 0);
        } catch (Exception e) {
            log("signatureHelp: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> getTriggerCharacters() {
        if (wrapper.getCapabilities() != null
            && wrapper.getCapabilities().getSignatureHelpProvider() != null
            && wrapper.getCapabilities().getSignatureHelpProvider().getTriggerCharacters() != null) {
            return wrapper.getCapabilities().getSignatureHelpProvider().getTriggerCharacters();
        }
        return Collections.singletonList("(");
    }

    private void log(String message) {
        android.util.Log.i("LspSig", message);
        if (logSink != null) logSink.log(message);
    }
}
