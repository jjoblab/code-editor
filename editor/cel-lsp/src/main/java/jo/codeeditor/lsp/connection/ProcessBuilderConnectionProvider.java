package jo.codeeditor.lsp.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link StreamConnectionProvider} qui lance un serveur de langage en
 * sous-processus via {@link ProcessBuilder}.
 *
 * <p>La plupart des usages consistent à lancer {@code jdt-language-server}
 * ou {@code kotlin-language-server} en sous-processus ; ce fournisseur est
 * donc fourni intégré.
 *
 * <p>Exemple :
 * <pre>{@code
 * new ProcessBuilderConnectionProvider(
 *     "/path/to/jdtls",
 *     List.of("-data", workspacePath),
 *     workspacePath)
 * }</pre>
 */
public class ProcessBuilderConnectionProvider implements StreamConnectionProvider {

    private final String command;
    private final List<String> args;
    private final String workingDir;
    private Process process;

    /**
     * @param command   le chemin de l'exécutable du serveur
     * @param args      les arguments de ligne de commande (peut être vide)
     * @param workingDir le répertoire de travail du sous-processus (peut être null)
     */
    public ProcessBuilderConnectionProvider(String command, List<String> args, String workingDir) {
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.workingDir = workingDir;
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
            return new StreamPair(process.getInputStream(), process.getOutputStream());
        } catch (IOException e) {
            throw new RuntimeException("Failed to start language server: " + command, e);
        }
    }

    @Override
    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    /** Retourne le processus sous-jacent, ou null s'il n'a pas été lancé. */
    public Process getProcess() {
        return process;
    }
}
