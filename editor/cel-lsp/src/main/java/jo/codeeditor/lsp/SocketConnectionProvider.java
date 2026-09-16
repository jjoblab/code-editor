package jo.codeeditor.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * {@link StreamConnectionProvider} that connects to a remote language server
 * via TCP. Useful when the server runs on a dev machine or cloud instance.
 *
 * <p>Example:
 * <pre>{@code
 * new SocketConnectionProvider("192.168.1.100", 5007)
 * }</pre>
 *
 * @since v2.2.0
 */
public class SocketConnectionProvider implements StreamConnectionProvider {

    private final String host;
    private final int port;
    private Socket socket;

    /**
     * @param host the server hostname or IP
     * @param port the server port
     */
    public SocketConnectionProvider(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public StreamPair start() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10_000); // 10s timeout
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

    /** Returns the underlying socket, or null if not connected. */
    public Socket getSocket() {
        return socket;
    }
}
