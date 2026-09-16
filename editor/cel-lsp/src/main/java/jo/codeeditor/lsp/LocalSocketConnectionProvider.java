package jo.codeeditor.lsp;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link StreamConnectionProvider} that connects to an in-process language
 * server via an Android {@link LocalSocket} (Unix-domain socket). Useful
 * when the server is a pure-Java LSP impl running in an Android {@code Service}
 * (like Sora Editor's Lua demo).
 *
 * <p>Example:
 * <pre>{@code
 * new LocalSocketConnectionProvider("lua-lsp")
 * }</pre>
 *
 * @since v2.2.0
 */
public class LocalSocketConnectionProvider implements StreamConnectionProvider {

    private final String socketName;
    private LocalSocket socket;

    /**
     * @param socketName the Unix-domain socket name (in the abstract namespace)
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

    /** Returns the underlying socket, or null if not connected. */
    public LocalSocket getSocket() {
        return socket;
    }
}
