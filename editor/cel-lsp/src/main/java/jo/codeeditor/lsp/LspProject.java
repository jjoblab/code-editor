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
 * L'espace de travail LSP. Gère les définitions de serveurs de langage,
 * les instances de serveurs en cours d'exécution
 * ({@link LanguageServerWrapper}) et les éditeurs par fichier
 * ({@link LspEditor}).
 *
 * <p>Utilisation :
 * <pre>{@code
 * LspProject project = new LspProject("/path/to/workspace");
 * project.addServerDefinition(myJdtlsDefinition);
 * LspEditor editor = project.createEditor("file:///path/to/Foo.java");
 * editor.setEditorView(editorView);
 * editor.connect();
 * }</pre>
 *
 * <p>Un projet peut avoir plusieurs définitions de serveurs (ex. Java +
 * Kotlin dans le même espace de travail). Chaque éditeur est routé vers le
 * serveur correspondant à l'extension de son fichier.
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
    /** Récepteur de journal optionnel pour que l'application de démonstration puisse afficher l'activité LSP. */
    private volatile LspLogSink logSink;

    /**
     * @param workspacePath le chemin absolu de la racine de l'espace de travail
     */
    public LspProject(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    /** Retourne le chemin de l'espace de travail. */
    public String getWorkspacePath() { return workspacePath; }

    /** Définit le récepteur de journal qui reçoit l'activité LSP à des fins de diagnostic. */
    public void setLogSink(LspLogSink sink) { this.logSink = sink; }

    /** Retourne le récepteur de journal, ou null. */
    public LspLogSink getLogSink() { return logSink; }

    /**
     * Enregistre une définition de serveur de langage. Le serveur démarre
     * paresseusement quand le premier éditeur de son extension se connecte.
     *
     * <p>Si la définition gère plusieurs extensions
     * (via {@link LanguageServerDefinition#getAdditionalExtensions()}),
     * elle est enregistrée sous TOUTES les extensions et l'instance unique
     * du serveur est partagée entre ces extensions (pas de duplication).</p>
     */
    public void addServerDefinition(LanguageServerDefinition def) {
        // Enregistre sous l'extension principale
        definitions.put(def.getExt(), def);
        // Enregistre aussi sous les extensions supplémentaires
        for (String ext : def.getAdditionalExtensions()) {
            if (ext != null && !ext.isEmpty() && !ext.equals(def.getExt())) {
                definitions.put(ext, def);
            }
        }
    }

    /**
     * Retourne la définition pour l'extension donnée, ou null.
     */
    public LanguageServerDefinition getDefinition(String ext) {
        return definitions.get(ext);
    }

    /**
     * Crée un nouveau {@link LspEditor} pour l'URI de fichier donné.
     * L'éditeur est routé vers le serveur correspondant à l'extension
     * du fichier.
     *
     * @param fileUri l'URI du fichier (ex. "file:///path/to/Foo.java")
     */
    public LspEditor createEditor(String fileUri) {
        LspEditor editor = new LspEditor(this, fileUri);
        editors.put(fileUri, editor);
        return editor;
    }

    /**
     * Retourne le {@link LanguageServerWrapper} pour l'extension donnée,
     * en démarrant le serveur si nécessaire.
     *
     * <p>Si la définition gère plusieurs extensions
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
        // synchronized — la séquence vérifier-puis-agir ci-dessous entrait
        // en concurrence quand deux éditeurs d'extensions différentes de la
        // MÊME définition se connectaient simultanément (ex. onglets .kt +
        // .kts ouverts ensemble) : les deux threads rataient le chemin
        // rapide, créaient chacun un wrapper, et les serveurs des perdants
        // fuyaient — des processus en exécution que rien n'arrêtait jamais.
        // Sérialiser la création garantit un seul serveur par définition.
        synchronized (wrapperCreationLock) {
        LanguageServerWrapper existing2 = wrappers.get(ext);
        if (existing2 != null) return existing2;
        LanguageServerDefinition def = definitions.get(ext);
        if (def == null) {
            throw new IllegalArgumentException("No server definition for extension: " + ext);
        }
        // Cherche un wrapper existant sous une autre
        // extension de la même def (ex. « kt » déjà connecté, on demande « kts »)
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

    /** Moniteur de la séquence vérifier-puis-agir de getWrapper. */
    private final Object wrapperCreationLock = new Object();

    /**
     * La source (attachée ou stub décompilé) d'un FQN binaire.
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

    /** Arrête tous les serveurs et libère les ressources. */
    public void shutdown() {
        for (LspEditor editor : editors.values()) {
            editor.disconnect();
        }
        editors.clear();
        // Déduplication — un wrapper enregistré sous plusieurs extensions
        // (.kt + .kts partagent un serveur) serait sinon arrêté une fois PAR
        // CLÉ. Le wrapper le tolère (contrôles de nullité) mais chaque appel
        // en double rejouerait la poignée de main d'arrêt bornée à 2 s.
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
