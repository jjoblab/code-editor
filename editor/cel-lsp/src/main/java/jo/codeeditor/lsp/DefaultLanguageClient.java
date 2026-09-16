package jo.codeeditor.lsp;

import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link LanguageClient} implementation — receives notifications
 * and requests FROM the language server.
 *
 * <p>v3.5.0: proper {@code workspace/configuration} PULL handling. Many
 * servers (EmmyLua, gopls, rust-analyzer, …) pull their settings from the
 * client via {@code workspace/configuration} requests instead of — or in
 * addition to — receiving them via {@code workspace/didChangeConfiguration}
 * push. Previously this returned an empty list, which meant EmmyLua never
 * got its inspection settings and therefore never published diagnostics
 * (completion + signature help worked because they don't depend on
 * inspection config). Now the settings pushed via
 * {@link LspEditor#sendDidChangeConfiguration(Object)} are stored and
 * served back to the server on demand, with dot-path section navigation
 * (e.g. a request for section {@code "emmylua.inspections"} returns the
 * nested {@code inspections} object).
 *
 * @since v2.2.0
 */
public class DefaultLanguageClient implements LanguageClient {

    /** v3.3.2: Set by LspEditor so diagnostics reach the editor. */
    private volatile LspEditor lspEditor;

    /** v3.5.0: The settings object last pushed via didChangeConfiguration. */
    private volatile Object settings;

    public void setLspEditor(LspEditor editor) {
        this.lspEditor = editor;
    }

    /**
     * v3.5.0: Stores the settings object so {@link #configuration} can
     * serve it back to the server on {@code workspace/configuration} pull
     * requests. Called by {@link LspEditor#sendDidChangeConfiguration}.
     */
    public void setSettings(Object settings) {
        this.settings = settings;
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        // v3.5.0: log EVERY publishDiagnostics notification BEFORE the null
        // check, so we can see if the server is sending them even when
        // lspEditor isn't wired yet (race condition diagnosis).
        int count = diagnostics != null && diagnostics.getDiagnostics() != null
            ? diagnostics.getDiagnostics().size() : 0;
        String uri = diagnostics != null ? diagnostics.getUri() : "(null)";
        android.util.Log.i("LspClient", "publishDiagnostics RECEIVED: uri=" + uri + " count=" + count);
        if (lspEditor != null && lspEditor.getProject() != null && lspEditor.getProject().getLogSink() != null) {
            lspEditor.getProject().getLogSink().log(
                "publishDiagnostics RECEIVED: count=" + count + " uri=" + uri);
        }
        // v3.3.2 fix: actually forward diagnostics to the LspEditor.
        if (lspEditor != null) {
            lspEditor.publishDiagnostics(diagnostics);
        }
    }

    /**
     * v3.5.0: Properly handle the server's {@code workspace/configuration}
     * PULL request. For each requested {@link org.eclipse.lsp4j.ConfigurationItem}
     * we navigate the stored settings by the item's {@code section} (a
     * dot-separated path like {@code "emmylua.inspections.undeclaredVariable"})
     * and return the value at that path. If the section is null or empty,
     * the whole settings object is returned. If the path doesn't exist or
     * no settings were pushed, null is returned for that item (per the LSP
     * spec, the server must tolerate null entries).
     *
     * <p>This is what makes EmmyLua actually publish diagnostics: it pulls
     * {@code "emmylua.inspections.*"} settings, and without a real answer
     * here every inspection level defaults to "None" → zero diagnostics.
     *
     * <p>Same pattern as Sora Editor's DefaultLanguageClient, except Sora
     * returns the whole settings object for every section (we do proper
     * path navigation so a request for {@code "emmylua.inspections"}
     * returns just the inspections sub-object, not the whole tree).
     */
    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        List<Object> results = new ArrayList<>();
        if (configurationParams != null && configurationParams.getItems() != null) {
            for (org.eclipse.lsp4j.ConfigurationItem item : configurationParams.getItems()) {
                results.add(resolveSection(settings, item.getSection()));
            }
        }
        String logMsg = "server requested workspace/configuration: "
            + (configurationParams != null && configurationParams.getItems() != null
                ? configurationParams.getItems().size() + " item(s)" : "0 items")
            + " → answered " + results.size();
        android.util.Log.i("LspClient", logMsg);
        if (lspEditor != null && lspEditor.getProject() != null && lspEditor.getProject().getLogSink() != null) {
            lspEditor.getProject().getLogSink().log(logMsg);
        }
        return CompletableFuture.completedFuture(results);
    }

    /**
     * v3.5.0: Navigates a dot-separated {@code section} path inside the
     * {@code settings} object (a {@link com.google.gson.JsonElement} or a
     * POJO). Returns the value at the path, or null if the path doesn't
     * exist or settings is null. A null/empty section returns the whole
     * settings object.
     */
    private static Object resolveSection(Object settings, String section) {
        if (settings == null) return null;
        if (section == null || section.isEmpty()) return settings;
        // Only JsonObject supports dot-path navigation. If the server pushed
        // a non-JSON object (e.g. a Map), we return the whole thing — the
        // server will have to deal with it.
        if (settings instanceof com.google.gson.JsonElement) {
            com.google.gson.JsonElement el = (com.google.gson.JsonElement) settings;
            if (!el.isJsonObject()) return settings;
            com.google.gson.JsonObject obj = el.getAsJsonObject();
            // Split on "." — but be careful: some servers use nested paths.
            String[] parts = section.split("\\.");
            com.google.gson.JsonElement current = obj;
            for (String part : parts) {
                if (current == null || !current.isJsonObject()) return null;
                com.google.gson.JsonObject o = current.getAsJsonObject();
                if (!o.has(part)) return null;
                current = o.get(part);
            }
            return current;
        }
        // POJO / Map fallback — return the whole object.
        return settings;
    }

    /**
     * v3.3.9: Handle the server's workspace/workspaceFolders PULL request.
     * Returns the project's workspace path so the server can register
     * the workspace root.
     * <p>Same pattern as Sora Editor's DefaultLanguageClient.
     */
    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        if (lspEditor != null && lspEditor.getProject() != null) {
            String workspacePath = lspEditor.getProject().getWorkspacePath();
            WorkspaceFolder folder = new WorkspaceFolder();
            folder.setUri("file://" + workspacePath);
            folder.setName(new java.io.File(workspacePath).getName());
            android.util.Log.i("LspClient", "server requested workspaceFolders (returning " + folder.getUri() + ")");
            if (lspEditor.getProject().getLogSink() != null) {
                lspEditor.getProject().getLogSink().log("server requested workspaceFolders (returning " + folder.getUri() + ")");
            }
            return CompletableFuture.completedFuture(Collections.singletonList(folder));
        }
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        if (requestParams.getActions() != null && !requestParams.getActions().isEmpty()) {
            return CompletableFuture.completedFuture(requestParams.getActions().get(0));
        }
        return CompletableFuture.completedFuture(new MessageActionItem(""));
    }

    // v3.3.6: Log server messages so the user can see what the server is
    // saying — EmmyLua sends progress and error info via window/logMessage.
    @Override
    public void showMessage(MessageParams message) {
        String log = "server/showMessage: " + message.getType() + ": " + message.getMessage();
        android.util.Log.i("LspClient", log);
        if (lspEditor != null && lspEditor.getProject() != null && lspEditor.getProject().getLogSink() != null) {
            lspEditor.getProject().getLogSink().log(log);
        }
    }

    @Override
    public void logMessage(MessageParams message) {
        String log = "server/logMessage: " + message.getType() + ": " + message.getMessage();
        android.util.Log.i("LspClient", log);
        if (lspEditor != null && lspEditor.getProject() != null && lspEditor.getProject().getLogSink() != null) {
            lspEditor.getProject().getLogSink().log(log);
        }
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }
}
