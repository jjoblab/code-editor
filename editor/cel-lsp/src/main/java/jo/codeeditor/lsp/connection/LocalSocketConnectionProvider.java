package jo.codeeditor.lsp.connection;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link StreamConnectionProvider} qui se connecte à un serveur de langage
 * en processus via un {@link LocalSocket} Android (socket domaine Unix).
 * Utile quand le serveur est une implémentation LSP en Java pur exécutée
 * dans un {@code Service} Android.
 *
 * <p>Exemple :
 * <pre>{@code
 * new LocalSocketConnectionProvider("lua-lsp")
 * }</pre>
 */
public class LocalSocketConnectionProvider implements StreamConnectionProvider {

    private final String socketName;
    private LocalSocket socket;

    /**
     * @param socketName le nom du socket domaine Unix (dans l'espace de noms abstrait)
     */
    public LocalSocketConnectionProvider(String socketName) {
        this.socketName = socketName;
    }

    @Override
    public StreamPair start() {
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
            return new StreamPair(socket.getInputStream(), socket.getOutputStream());
        } catch (IOException e) {
            throw new RuntimeException("Failed to connect to LocalSocket: " + socketName, e);
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
    public LocalSocket getSocket() {
        return socket;
    }
}
