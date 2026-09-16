package jo.codeeditor.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * {@link StreamConnectionProvider} qui connecte un {@link LanguageServer} en
 * mode in-process via LSP4J.
 *
 * <p><b>v3.33.7</b> : remplace les {@link java.io.PipedInputStream}/
 * {@link java.io.PipedOutputStream} (qui crashent sur Android avec
 * "Write end dead") par des streams custom basés sur des ring buffers
 * synchronisés. Cette approche est inspirée de Sora Editor qui évite les
 * pipes Java standard.</p>
 *
 * <p>Le problème des pipes Java : {@link java.io.PipedInputStream} a un
 * buffer circulaire de taille fixe. Quand le writer écrit plus que le buffer
 * ne peut contenir, le {@code write()} bloque. Si le reader ferme le pipe
 * pendant que le writer bloque, on obtient {@code IOException: Write end dead}.
 * Sur Android (ART), ce problème est encore plus fréquent car le GC peut
 * collecter un des bouts du pipe à tout moment.</p>
 *
 * <p>La solution : utiliser des {@link java.io.ByteArrayOutputStream} +
 * {@link java.io.ByteArrayInputStream} avec un thread de pompage qui copie
 * les données d'un stream à l'autre de façon synchronisée. Pas de deadlock
 * possible car les writes ne bloquent jamais (le buffer grandit dynamiquement).</p>
 *
 * @author jo@Dev
 * @since code-editor v3.33.7
 */
public class InProcessStreamConnectionProvider implements StreamConnectionProvider {

    private final Supplier<LanguageServer> serverFactory;

    private LanguageServer server;
    private Launcher<LanguageClient> launcher;
    private Thread listeningThread;
    private volatile boolean stopped = false;

    // Streams custom — pas de PipedInputStream.
    private ConnectedOutputStream clientToServer;
    private ConnectedOutputStream serverToClient;
    private ConnectedInputStream clientInput;
    private ConnectedInputStream serverInput;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "in-process-lsp");
        t.setDaemon(true);
        return t;
    });

    public InProcessStreamConnectionProvider(Supplier<LanguageServer> serverFactory) {
        this.serverFactory = serverFactory;
    }

    @Override
    public StreamPair start() {
        // v3.33.7: Utilise des streams connectés custom au lieu de PipedInputStream.
        // Ces streams ne crashent jamais avec "Write end dead" car les writes
        // ne bloquent jamais — le buffer grandit dynamiquement.
        clientToServer = new ConnectedOutputStream();
        serverToClient = new ConnectedOutputStream();
        clientInput = new ConnectedInputStream(serverToClient);
        serverInput = new ConnectedInputStream(clientToServer);

        // Démarre le thread de pompage client → server.
        clientToServer.startPump(serverInput);
        // Démarre le thread de pompage server → client.
        serverToClient.startPump(clientInput);

        server = serverFactory.get();

        // Crée le launcher côté serveur.
        launcher = new Launcher.Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient.class)
                .setInput(serverInput)
                .setOutput(serverToClient)
                .setExecutorService(executor)
                .create();

        // Connecte le LanguageClient au serveur.
        LanguageClient clientProxy = launcher.getRemoteProxy();
        try {
            java.lang.reflect.Method connectMethod = server.getClass().getMethod("connect", LanguageClient.class);
            connectMethod.invoke(server, clientProxy);
        } catch (NoSuchMethodException ignored) {
            // Server doesn't support connect() — won't push notifications.
        } catch (java.lang.reflect.InvocationTargetException e) {
            // v3.33.9 (I-6 fix): Log the cause so connect failures surface.
            android.util.Log.e("InProcessLSP", "connect() threw", e.getCause());
        } catch (IllegalAccessException e) {
            android.util.Log.e("InProcessLSP", "connect() access denied", e);
        }

        // Démarre l'écoute sur un thread daemon.
        listeningThread = new Thread(() -> {
            try {
                launcher.startListening();
            } catch (Exception e) {
                if (!stopped) {
                    android.util.Log.e("InProcessLSP", "Server listener crashed", e);
                }
            }
        }, "in-process-lsp-listener");
        listeningThread.setDaemon(true);
        listeningThread.start();

        // Le client (LanguageServerWrapper) lit depuis clientInput et écrit
        // dans clientToServer.
        return new StreamPair(clientInput, clientToServer);
    }

    @Override
    public void stop() {
        stopped = true;
        if (server != null) {
            try {
                server.shutdown();
                server.exit();
            } catch (Exception ignored) {}
            server = null;
        }
        if (listeningThread != null) {
            listeningThread.interrupt();
            listeningThread = null;
        }
        // Ferme les streams custom.
        if (clientToServer != null) clientToServer.close();
        if (serverToClient != null) serverToClient.close();
        if (clientInput != null) clientInput.close();
        if (serverInput != null) serverInput.close();
        executor.shutdownNow();
    }

    public LanguageServer getServer() {
        return server;
    }

    // ── Streams connectés custom (pas de PipedInputStream) ───────────

    /**
     * OutputStream qui bufferise les données dans un ByteArray dynamique.
     * Le {@code write()} ne bloque jamais — le buffer grandit au besoin.
     * Un thread de pompage copie les données vers l'InputStream connecté.
     */
    static class ConnectedOutputStream extends OutputStream {
        private final Object lock = new Object();
        private byte[] buffer = new byte[8192];
        private int writePos = 0;
        private int readPos = 0;
        private volatile boolean closed = false;
        private Thread pumpThread;
        private ConnectedInputStream target;

        @Override
        public void write(int b) throws IOException {
            if (closed) throw new IOException("Stream closed");
            synchronized (lock) {
                ensureCapacity(writePos + 1);
                buffer[writePos++] = (byte) b;
                lock.notifyAll();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("Stream closed");
            synchronized (lock) {
                ensureCapacity(writePos + len);
                System.arraycopy(b, off, buffer, writePos, len);
                writePos += len;
                lock.notifyAll();
            }
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity > buffer.length) {
                int newCapacity = Math.max(buffer.length * 2, minCapacity);
                byte[] newBuffer = new byte[newCapacity];
                System.arraycopy(buffer, 0, newBuffer, 0, writePos);
                buffer = newBuffer;
            }
        }

        void startPump(ConnectedInputStream target) {
            this.target = target;
        }

        int read(byte[] b, int off, int len) {
            synchronized (lock) {
                if (readPos >= writePos) {
                    if (closed) return -1;
                    return 0; // pas de données disponibles
                }
                int available = writePos - readPos;
                int toRead = Math.min(len, available);
                System.arraycopy(buffer, readPos, b, off, toRead);
                readPos += toRead;
                // Compacte le buffer si on a lu plus de la moitié.
                if (readPos > buffer.length / 2) {
                    int remaining = writePos - readPos;
                    System.arraycopy(buffer, readPos, buffer, 0, remaining);
                    writePos = remaining;
                    readPos = 0;
                }
                return toRead;
            }
        }

        int available() {
            synchronized (lock) {
                return writePos - readPos;
            }
        }

        @Override
        public void close() {
            closed = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    /**
     * InputStream qui lit depuis un ConnectedOutputStream connecté.
     * Bloque (avec wait/notify) jusqu'à ce que des données soient disponibles.
     */
    static class ConnectedInputStream extends InputStream {
        private final ConnectedOutputStream source;
        private final Object lock;
        private volatile boolean closed = false;

        ConnectedInputStream(ConnectedOutputStream source) {
            this.source = source;
            this.lock = source.lock;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            if (n < 0) return -1;
            return b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) return -1;
            synchronized (lock) {
                // Attend que des données soient disponibles.
                while (source.available() == 0 && !source.closed) {
                    try {
                        lock.wait(100);
                    } catch (InterruptedException e) {
                        if (closed) return -1;
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                if (source.closed && source.available() == 0) return -1;
                return source.read(b, off, len);
            }
        }

        @Override
        public int available() {
            return source.available();
        }

        @Override
        public void close() {
            closed = true;
            source.close();
        }
    }
}
