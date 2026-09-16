package jo.codeeditor.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The LSP workspace. Manages language server definitions, running server
 * instances ({@link LanguageServerWrapper}), and per-file editors
 * ({@link LspEditor}).
 *
 * <p>Usage:
 * <pre>{@code
 * LspProject project = new LspProject("/path/to/workspace");
 * project.addServerDefinition(myJdtlsDefinition);
 * LspEditor editor = project.createEditor("file:///path/to/Foo.java");
 * editor.setEditorView(editorView);
 * editor.connect();
 * }</pre>
 *
 * <p>One project can have multiple server definitions (e.g. Java + Kotlin
 * in the same workspace). Each editor is routed to the server matching its
 * file extension.
 *
 * @since v2.2.0
 */
public class LspProject {

    private final String workspacePath;
    private final Map<String, LanguageServerDefinition> definitions = new HashMap<>();
    private final Map<String, LanguageServerWrapper> wrappers = new ConcurrentHashMap<>();
    private final Map<String, LspEditor> editors = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-project");
        t.setDaemon(true);
        return t;
    });
    /** v3.3.3: Optional log sink so the demo app can show LSP activity. */
    private volatile LspLogSink logSink;

    /**
     * @param workspacePath the absolute path to the workspace root
     */
    public LspProject(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    /** Returns the workspace path. */
    public String getWorkspacePath() { return workspacePath; }

    /** v3.3.3: Sets the log sink that receives LSP activity for diagnostics. */
    public void setLogSink(LspLogSink sink) { this.logSink = sink; }

    /** v3.3.3: Returns the log sink, or null. */
    public LspLogSink getLogSink() { return logSink; }

    /**
     * Registers a language server definition. The server is started lazily
     * when the first editor for its extension connects.
     *
     * <p>★ v2.52 Subset F — Si la définition gère plusieurs extensions
     * (via {@link LanguageServerDefinition#getAdditionalExtensions()}),
     * elle est enregistrée sous TOUTES les extensions et l'instance unique
     * du serveur est partagée entre ces extensions (pas de duplication).</p>
     */
    public void addServerDefinition(LanguageServerDefinition def) {
        // Enregistre sous l'extension principale
        definitions.put(def.getExt(), def);
        // ★ v2.52 Subset F — enregistre aussi sous les extensions supplémentaires
        for (String ext : def.getAdditionalExtensions()) {
            if (ext != null && !ext.isEmpty() && !ext.equals(def.getExt())) {
                definitions.put(ext, def);
            }
        }
    }

    /**
     * Returns the definition for the given extension, or null.
     */
    public LanguageServerDefinition getDefinition(String ext) {
        return definitions.get(ext);
    }

    /**
     * Creates a new {@link LspEditor} for the given file URI. The editor
     * is routed to the server matching the file's extension.
     *
     * @param fileUri the file URI (e.g. "file:///path/to/Foo.java")
     */
    public LspEditor createEditor(String fileUri) {
        LspEditor editor = new LspEditor(this, fileUri);
        editors.put(fileUri, editor);
        return editor;
    }

    /**
     * Returns the {@link LanguageServerWrapper} for the given extension,
     * starting the server if necessary.
     *
     * <p>★ v2.52 Subset F — Si la définition gère plusieurs extensions
     * (ex. {@code .kt} + {@code .kts}), on partage le MÊME wrapper pour
     * toutes les extensions de cette def — pas de duplication de server.
     * On cherche d'abord le wrapper sous l'ext passé ; si absent, on cherche
     * sous les autres extensions de la def ; si toujours absent, on crée
     * un nouveau wrapper et on l'enregistre sous TOUTES les extensions de la
     * def.</p>
     */
    LanguageServerWrapper getWrapper(String ext) {
        // Fast-path : wrapper déjà connu pour cette extension
        LanguageServerWrapper existing = wrappers.get(ext);
        if (existing != null) return existing;
        // v3.34.0: synchronized — the check-then-act sequence below raced
        // when two editors for different extensions of the SAME definition
        // connected concurrently (e.g. .kt + .kts tabs opening together):
        // both threads missed the fast path, both created a wrapper, and
        // the losers' servers leaked — running processes nothing ever
        // shut down. Serializing creation keeps one server per definition.
        synchronized (wrapperCreationLock) {
        LanguageServerWrapper existing2 = wrappers.get(ext);
        if (existing2 != null) return existing2;
        LanguageServerDefinition def = definitions.get(ext);
        if (def == null) {
            throw new IllegalArgumentException("No server definition for extension: " + ext);
        }
        // ★ v2.52 Subset F — Cherche un wrapper existant sous une autre
        // extension de la même def (ex. "kt" déjà connecté, on demande "kts")
        for (String otherExt : def.getAllExtensions()) {
            LanguageServerWrapper shared = wrappers.get(otherExt);
            if (shared != null) {
                // Enregistre le wrapper partagé sous cette ext aussi
                wrappers.put(ext, shared);
                return shared;
            }
        }
        // Crée un nouveau wrapper
        LanguageServerWrapper newWrapper = new LanguageServerWrapper(def, workspacePath, executor);
        // Enregistre sous TOUTES les extensions de la def (partage)
        wrappers.put(ext, newWrapper);
        for (String otherExt : def.getAllExtensions()) {
            if (!otherExt.equals(ext)) {
                wrappers.putIfAbsent(otherExt, newWrapper);
            }
        }
        return newWrapper;
        }
    }

    /** v3.34.0: monitor for the getWrapper check-then-act sequence. */
    private final Object wrapperCreationLock = new Object();

    /**
     * ★ v2.40 — La source (attachée ou stub décompilé) d'un FQN binaire.
     *
     * <p>Façade publique pour la méthode LSP PERSONNALISÉE
     * {@code textDocument/decompiledSource} exposée par le serveur Java
     * ({@code :lspjava}). Câble de bout en bout la fonctionnalité « GO TO
     * Definition sur cible binaire » : le serveur renvoie une
     * {@code Location} à URI {@code jdt://decompiled/<fqcn>.java} depuis
     * {@code definition}/{@code typeDefinition} quand la cible est un type
     * binaire (JDK, JAR du classpath) ; l'app host appelle alors cette
     * façade pour récupérer le texte source à afficher dans le buffer
     * read-only.</p>
     *
     * <p>Le serveur est en mode IN-PROCESS ({@code InProcessStreamConnectionProvider}
     * + {@code CodeIdeLanguageServer} proxy) — l'appel RPC est local mais
     * garde la séparation de couches : l'app ne connaît que les méthodes
     * LSP, pas le moteur JDT sous-jacent.</p>
     *
     * @param ext  l'extension du serveur cible (typiquement {@code "java"})
     * @param fqcn le fully-qualified name (ex. {@code java.util.HashMap})
     * @return la source (attachée ou stub), ou {@code null} si le serveur
     *         n'est pas démarré, le FQCN est introuvable, ou l'appel timeout
     * @since v2.40
     */
    @java.lang.SuppressWarnings("unused")
    public String decompiledSource(String ext, String fqcn) {
        LanguageServerWrapper w = wrappers.get(ext);
        if (w == null || !w.isInitialized()) return null;
        CodeIdeLanguageServer ext2 = w.getExtendedServer();
        if (ext2 == null) return null;
        try {
            CodeIdeLanguageServer.DecompiledSourceParams params =
                    new CodeIdeLanguageServer.DecompiledSourceParams(fqcn);
            CodeIdeLanguageServer.DecompiledSourceResult r =
                    ext2.decompiledSource(params).get(5, java.util.concurrent.TimeUnit.SECONDS);
            return r == null ? null : r.source;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Shuts down all servers and releases resources. */
    public void shutdown() {
        for (LspEditor editor : editors.values()) {
            editor.disconnect();
        }
        editors.clear();
        // v3.34.0: dedupe — a wrapper registered under several extensions
        // (v2.52 Subset F: .kt + .kts share one server) was previously shut
        // down once PER KEY. The wrapper tolerated it (null checks) but each
        // duplicate call re-ran the 2-second-bounded shutdown handshake.
        java.util.Set<LanguageServerWrapper> seen = new java.util.HashSet<>();
        for (LanguageServerWrapper wrapper : wrappers.values()) {
            if (seen.add(wrapper)) {
                wrapper.shutdown();
            }
        }
        wrappers.clear();
        executor.shutdown();
    }
}
