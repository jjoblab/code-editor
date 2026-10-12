package jo.codeeditor.lsp.connection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link StreamConnectionProvider} qui lance un serveur de langage en
 * sous-processus via {@link ProcessBuilder}.
 *
 * <p>La plupart des usages consistent à lancer un exécutable de serveur
 * de langage en sous-processus ; ce fournisseur est donc fourni intégré.</p>
 *
 * <p><b>Robustesse du cycle de vie</b> (ce fournisseur traite le serveur
 * comme un processus tiers potentiellement défectueux) :</p>
 * <ul>
 *   <li><b>stderr drainé</b> — une pompe daemon lit le flux d'erreur en
 *       continu vers {@link StderrListener} (journal borné par défaut) :
 *       un serveur bavard remplirait sinon le tampon du pipe (~64 Kio) et
 *       se bloquerait définitivement sur {@code write()} ;</li>
 *   <li><b>arrêt échelonné</b> — {@link #stop()} envoie
 *       {@code destroy()}, attend la fin du processus (5 s au plus), puis
 *       force l'arrêt : {@code stop()} ne rend la main qu'une fois le
 *       processus mort et la pompe interrompue ;</li>
 *   <li><b>échec typé</b> — le démarrage impossible lève
 *       {@link LspStartException}.</li>
 * </ul>
 *
 * <p>Exemple :
 * <pre>{@code
 * new ProcessBuilderConnectionProvider(
 *     "/path/to/language-server",
 *     List.of("-data", workspacePath),
 *     workspacePath)
 * }</pre></p>
 */
public class ProcessBuilderConnectionProvider implements StreamConnectionProvider {

    /** Attente maximale de la fin du processus après destroy() (ms). */
    private static final long STOP_TIMEOUT_MS = 5000;

    private final String command;
    private final List<String> args;
    private final String workingDir;
    private Process process;
    private Thread stderrPump;

    /**
     * Récepteur (borné par l'implémentation) des lignes écrites par le
     * serveur sur stderr. Le récepteur par défaut garde une trace
     * circulaire limitée ; injecter un récepteur pour router vers le
     * journal de l'application.
     */
    public interface StderrListener {
        void onStderrLine(String line);
    }

    /** Récepteur par défaut : trace circulaire bornée (64 lignes). */
    private static final class BoundedStderrTail implements StderrListener {
        private final ArrayRing lignes = new ArrayRing(64);

        @Override
        public void onStderrLine(String line) {
            lignes.add(line);
        }

        synchronized String dump() {
            return String.join("\n", lignes.snapshot());
        }
    }

    /** Anneau mémoire minimal (taille fixe, écrase les plus anciennes). */
    private static final class ArrayRing {
        private final String[] items;
        private int head = 0;
        private int size = 0;

        ArrayRing(int capacity) {
            items = new String[capacity];
        }

        synchronized void add(String s) {
            items[head] = s;
            head = (head + 1) % items.length;
            size = Math.min(size + 1, items.length);
        }

        synchronized List<String> snapshot() {
            List<String> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                out.add(items[(head + items.length - size + i) % items.length]);
            }
            return out;
        }
    }

    private final StderrListener stderrListener;

    /**
     * @param command   le chemin de l'exécutable du serveur
     * @param args      les arguments de ligne de commande (peut être vide)
     * @param workingDir le répertoire de travail du sous-processus (peut être null)
     */
    public ProcessBuilderConnectionProvider(String command, List<String> args, String workingDir) {
        this(command, args, workingDir, null);
    }

    /**
     * Variante avec récepteur stderr (journalisation bornée de l'appelant).
     */
    public ProcessBuilderConnectionProvider(String command, List<String> args,
            String workingDir, StderrListener stderrListener) {
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.workingDir = workingDir;
        this.stderrListener = stderrListener != null
                ? stderrListener : new BoundedStderrTail();
    }

    @Override
    public StreamPair start() {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workingDir != null) {
            pb.directory(new java.io.File(workingDir));
        }
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new LspStartException(
                    "échec de démarrage du serveur de langage : " + command, e);
        }
        startStderrPump(process);
        return new StreamPair(process.getInputStream(), process.getOutputStream());
    }

    /**
     * Pompe stderr : thread daemon qui vide le flux d'erreur ligne par
     * ligne vers le récepteur. Sans elle, un serveur écrivant plus que la
     * capacité du pipe se bloquerait (symptôme : « le serveur ne répond
     * plus »).
     */
    private void startStderrPump(final Process proc) {
        final StderrListener listener = stderrListener;
        stderrPump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(),
                            Charset.forName("UTF-8")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try {
                        listener.onStderrLine(line);
                    } catch (RuntimeException ignored) {
                        // Le récepteur ne doit jamais casser la pompe.
                    }
                }
            } catch (IOException ignored) {
                // Flux fermé à l'arrêt du processus : sortie normale.
            }
        }, "cel-lsp-stderr-pump");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    @Override
    public void stop() {
        if (process != null) {
            Process proc = process;
            proc.destroy();
            // Attente bornée de la fin du processus — destroy() est une
            // demande, pas une garantie (les SIGTERM ignorés existent).
            // waitFor(timeout) et destroyForcibly() exigent l'API 26 :
            // branche protégée, repli par sondage exitValue() en 24/25.
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    if (!proc.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly();
                        proc.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                waitForBorneApi24(proc);
            }
            process = null;
        }
        if (stderrPump != null) {
            stderrPump.interrupt();
            stderrPump = null;
        }
    }

    /**
     * Attente bornée compatible API 24/25 : {@code waitFor(timeout)} et
     * {@code isAlive()} n'existent pas — on sonde {@code exitValue()}
     * (lève {@code IllegalThreadStateException} tant que le processus
     * vit) puis on redemande l'arrêt à l'expiration.
     */
    private static void waitForBorneApi24(Process proc) {
        long fin = System.currentTimeMillis() + STOP_TIMEOUT_MS;
        while (System.currentTimeMillis() < fin) {
            try {
                proc.exitValue();
                return; // terminé
            } catch (IllegalThreadStateException encoreVivant) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        // Expiration : deuxième demande d'arrêt, puis dernier sondage.
        proc.destroy();
        fin = System.currentTimeMillis() + STOP_TIMEOUT_MS;
        while (System.currentTimeMillis() < fin) {
            try {
                proc.exitValue();
                return;
            } catch (IllegalThreadStateException encoreVivant) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Retourne le processus sous-jacent, ou null s'il n'a pas été lancé. */
    public Process getProcess() {
        return process;
    }

    /**
     * Trace stderr accumulée par le récepteur par défaut (bornée),
     * pour diagnostic — vide si un récepteur personnalisé est injecté.
     */
    public String stderrTail() {
        return stderrListener instanceof BoundedStderrTail
                ? ((BoundedStderrTail) stderrListener).dump() : "";
    }
}
