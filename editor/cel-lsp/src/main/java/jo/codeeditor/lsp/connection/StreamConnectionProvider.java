package jo.codeeditor.lsp.connection;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Fournit les flux d'entrée/sortie reliant le client LSP à un serveur de
 * langage. Les implémentations choisissent le transport :
 * <ul>
 *   <li>{@link ProcessBuilderConnectionProvider} — lance un sous-processus</li>
 *   <li>{@link SocketConnectionProvider} — se connecte à un socket TCP (serveur distant)</li>
 *   <li>{@link LocalSocketConnectionProvider} — se connecte à un socket domaine Unix Android (serveur en processus)</li>
 * </ul>
 *
 * <p>Couche indépendante du processus : le module LSP ne connaît que des
 * flux, jamais la manière dont ils sont créés. Contrairement à Sora Editor,
 * un {@link ProcessBuilderConnectionProvider} est fourni intégré.
 */
public interface StreamConnectionProvider {

    /**
     * Démarre la connexion et retourne les flux d'entrée et de sortie.
     * Appelée une seule fois sur un thread de travail avant la poignée de
     * main LSP.
     *
     * @return une {@link StreamPair} contenant les deux flux
     */
    StreamPair start();

    /**
     * Ferme la connexion. Appelée à l'arrêt du serveur de langage.
     */
    void stop();

    /**
     * Paire de flux connectée au serveur de langage.
     */
    final class StreamPair {
        public final InputStream input;
        public final OutputStream output;

        public StreamPair(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }
    }
}
