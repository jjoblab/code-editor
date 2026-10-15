package jo.codeeditor.lsp.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * {@link StreamConnectionProvider} qui se connecte à un serveur de langage
 * distant via TCP. Utile quand le serveur s'exécute sur une machine de
 * développement ou une instance cloud.
 *
 * <p><b>Transport en clair</b> : le TCP seul ne chiffre rien — réserver
 * cette connexion à la boucle locale ou à un réseau de confiance ; sur
 * un réseau ouvert, le trafic LSP (contenu des documents inclus) est
 * lisible en transit.</p>
 *
 * <p>Exemple :
 * <pre>{@code
 * new SocketConnectionProvider("192.168.1.100", 5007)
 * }</pre>
 */
public class SocketConnectionProvider implements StreamConnectionProvider {

    private final String host;
    private final int port;
    private Socket socket;

    /**
     * @param host le nom d'hôte ou l'adresse IP du serveur
     * @param port le port du serveur
     */
    public SocketConnectionProvider(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public StreamPair start() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10_000); // délai d'attente de 10 s
            return new StreamPair(socket.getInputStream(), socket.getOutputStream());
        } catch (IOException e) {
            // D-06 : exception typée (symétrique du ProcessBuilder),
            // message français, cause conservée — l'ancienne
            // RuntimeException générique à message anglais ne se
            // distinguait pas d'un bug interne du client.
            throw new LspStartException(
                    "Impossible de se connecter au serveur de langage "
                            + host + ":" + port, e);
        }
    }

    @Override
    public void stop() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Fermeture best-effort : le socket peut déjà être mort ;
                // il est de toute façon détaché et remplacé par null.
            }
            socket = null;
        }
    }

    /** Retourne le socket sous-jacent, ou null si non connecté. */
    public Socket getSocket() {
        return socket;
    }
}
