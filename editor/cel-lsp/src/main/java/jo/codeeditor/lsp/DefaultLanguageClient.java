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
 * Implémentation {@link LanguageClient} par défaut — reçoit les
 * notifications et requêtes ENVOYÉES PAR le serveur de langage.
 *
 * <p>Gestion PULL de {@code workspace/configuration} : de nombreux serveurs
 * (EmmyLua, gopls, rust-analyzer, …) tirent leurs réglages du client via des
 * requêtes {@code workspace/configuration} au lieu de — ou en plus de — les
 * recevoir par push {@code workspace/didChangeConfiguration}. Sans réponse
 * réelle, EmmyLua n'obtient jamais ses réglages d'inspection et ne publie
 * donc aucun diagnostic (la complétion et l'aide à la signature fonctionnent
 * car elles ne dépendent pas de la configuration d'inspection). Les réglages
 * poussés via {@link LspEditor#sendDidChangeConfiguration(Object)} sont donc
 * stockés et resservis au serveur à la demande, avec navigation par chemin
 * pointé (ex. une requête pour la section {@code "emmylua.inspections"}
 * retourne l'objet {@code inspections} imbriqué).
 */
public class DefaultLanguageClient implements LanguageClient {

    /** Positionné par LspEditor pour que les diagnostics atteignent l'éditeur. */
    private volatile LspEditor lspEditor;

    /** L'objet de réglages dernièrement poussé via didChangeConfiguration. */
    private volatile Object settings;

    public void setLspEditor(LspEditor editor) {
        this.lspEditor = editor;
    }

    /**
     * Stocke l'objet de réglages afin que {@link #configuration} puisse le
     * resservir au serveur sur les requêtes PULL
     * {@code workspace/configuration}. Appelée par
     * {@link LspEditor#sendDidChangeConfiguration}.
     */
    public void setSettings(Object settings) {
        this.settings = settings;
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        // Journalise CHAQUE notification publishDiagnostics AVANT le contrôle
        // de nullité, afin de voir si le serveur les envoie même quand
        // lspEditor n'est pas encore câblé (diagnostic de condition de course).
        int count = diagnostics != null && diagnostics.getDiagnostics() != null
            ? diagnostics.getDiagnostics().size() : 0;
        String uri = diagnostics != null ? diagnostics.getUri() : "(null)";
        android.util.Log.i("LspClient", "publishDiagnostics RECEIVED: uri=" + uri + " count=" + count);
        if (lspEditor != null && lspEditor.getProject() != null && lspEditor.getProject().getLogSink() != null) {
            lspEditor.getProject().getLogSink().log(
                "publishDiagnostics RECEIVED: count=" + count + " uri=" + uri);
        }
        // Transmet réellement les diagnostics au LspEditor.
        if (lspEditor != null) {
            lspEditor.publishDiagnostics(diagnostics);
        }
    }

    /**
     * Traite la requête PULL {@code workspace/configuration} du serveur.
     * Pour chaque {@link org.eclipse.lsp4j.ConfigurationItem} demandé,
     * navigue dans les réglages stockés selon son {@code section} (chemin
     * séparé par points, ex.
     * {@code "emmylua.inspections.undeclaredVariable"}) et retourne la
     * valeur à ce chemin. Section null ou vide : l'objet de réglages entier
     * est retourné. Chemin inexistant ou aucun réglage poussé : null est
     * retourné pour cet item (selon la spécification LSP, le serveur doit
     * tolérer les entrées null).
     *
     * <p>C'est ce qui permet à EmmyLua de publier des diagnostics : il tire
     * les réglages {@code "emmylua.inspections.*"} et, sans réponse réelle
     * ici, chaque niveau d'inspection vaut « None » par défaut → zéro
     * diagnostic.
     *
     * <p>La navigation de chemin est réelle : une requête pour
     * {@code "emmylua.inspections"} retourne uniquement le sous-objet
     * inspections, pas l'arbre complet des réglages.
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
     * Navigue un chemin {@code section} séparé par points dans l'objet
     * {@code settings} (un {@link com.google.gson.JsonElement} ou un POJO).
     * Retourne la valeur au chemin, ou null si le chemin n'existe pas ou si
     * settings est null. Une section null/vide retourne l'objet de réglages
     * entier.
     */
    private static Object resolveSection(Object settings, String section) {
        if (settings == null) return null;
        if (section == null || section.isEmpty()) return settings;
        // Seul JsonObject permet la navigation par chemin pointé. Si l'objet
        // poussé n'est pas JSON (ex. une Map), l'objet entier est retourné —
        // le serveur devra s'en accommoder.
        if (settings instanceof com.google.gson.JsonElement) {
            com.google.gson.JsonElement el = (com.google.gson.JsonElement) settings;
            if (!el.isJsonObject()) return settings;
            com.google.gson.JsonObject obj = el.getAsJsonObject();
            // Découpe sur « . » — attention : certains serveurs utilisent des
            // chemins imbriqués.
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
        // Repli POJO / Map — retourner l'objet entier.
        return settings;
    }

    /**
     * Traite la requête PULL workspace/workspaceFolders du serveur.
     * Retourne le chemin de l'espace de travail du projet pour que le
     * serveur puisse enregistrer la racine de l'espace de travail.
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

    // Journalise les messages du serveur pour que l'utilisateur voie ce
    // qu'il dit — EmmyLua envoie ses informations de progression et d'erreur
    // via window/logMessage.
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
