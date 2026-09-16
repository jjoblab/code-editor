package jo.codeeditor.lsp;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Provides the input/output streams that connect the LSP client to a
 * language server. Implementations choose the transport:
 * <ul>
 *   <li>{@link ProcessBuilderConnectionProvider} — launches a subprocess</li>
 *   <li>{@link SocketConnectionProvider} — connects to a TCP socket (remote server)</li>
 *   <li>{@link LocalSocketConnectionProvider} — connects to an Android Unix-domain socket (in-process server)</li>
 * </ul>
 *
 * <p>This is the process-agnostic layer Sora Editor uses — the LSP module
 * only knows about streams, not how they're created. Unlike Sora, we ship
 * a built-in {@link ProcessBuilderConnectionProvider} (Sora issue #710).
 *
 * @since v2.2.0
 */
public interface StreamConnectionProvider {

    /**
     * Starts the connection and returns the input + output streams.
     * Called once on a worker thread before the LSP handshake.
     *
     * @return a {@link StreamPair} holding the two streams
     */
    StreamPair start();

    /**
     * Closes the connection. Called when the language server is shut down.
     */
    void stop();

    /**
     * A pair of streams connecting to the language server.
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
