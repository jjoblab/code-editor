package jo.codeeditor.demo.lsp;

import jo.codeeditor.lsp.LanguageServerDefinition;
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

/**
 * Enregistrement du mini serveur Java de la démo auprès du
 * {@code LspProject} — l'unique point de branchement entre la démo et
 * le module {@code :cel-lsp}.
 *
 * <p>Le transport est <b>in-process</b> :
 * {@link InProcessStreamConnectionProvider} instancie le
 * {@link MiniJavaLanguageServer} et relie ses streams JSON-RPC au cadre
 * LSP de la bibliothèque. Toute la plomberie — poignée de main
 * {@code initialize}, négociation de capacités, debounce
 * {@code didChange}, publication des diagnostics vers l'éditeur — vit
 * dans {@code :cel-lsp} : cette classe ne fournit QUE la fabrique de
 * transport, exactement comme le ferait une intégration réelle
 * (sous-processus, socket TCP, LocalSocket).</p>
 */
public class MiniJavaServerDefinition extends LanguageServerDefinition {

    /** Le mini serveur Java de la démo — extension {@code java}. */
    public MiniJavaServerDefinition() {
        super("java", "mini-java (démo)");
    }

    @Override
    public StreamConnectionProvider createConnectionProvider(String workingDir) {
        // Fabrique paresseuse : le serveur n'est instancié que si le
        // wrapper démarre réellement (premier fichier Java connecté).
        return new InProcessStreamConnectionProvider(MiniJavaLanguageServer::new);
    }
}
