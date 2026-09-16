package jo.codeeditor.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link StreamConnectionProvider} that launches a language server as a
 * subprocess via {@link ProcessBuilder}.
 *
 * <p>This is the provider Sora Editor is missing (issue #710) — most users
 * want to launch {@code jdt-language-server} or {@code kotlin-language-server}
 * as a subprocess. We ship it built-in.
 *
 * <p>Example:
 * <pre>{@code
 * new ProcessBuilderConnectionProvider(
 *     "/path/to/jdtls",
 *     List.of("-data", workspacePath),
 *     workspacePath)
 * }</pre>
 *
 * @since v2.2.0
 */
public class ProcessBuilderConnectionProvider implements StreamConnectionProvider {

    private final String command;
    private final List<String> args;
    private final String workingDir;
    private Process process;

    /**
     * @param command   the server executable path
     * @param args      command-line arguments (may be empty)
     * @param workingDir the working directory for the subprocess (may be null)
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

    /** Returns the underlying process, or null if not started. */
    public Process getProcess() {
        return process;
    }
}
