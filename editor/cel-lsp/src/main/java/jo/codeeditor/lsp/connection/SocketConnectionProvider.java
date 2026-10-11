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
            throw new RuntimeException("Failed to connect to LSP server at " + host + ":" + port, e);
        }
    }

    @Override
    public void stop() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
            socket = null;
        }
    }

    /** Retourne le socket sous-jacent, ou null si non connecté. */
    public Socket getSocket() {
        return socket;
    }
}
