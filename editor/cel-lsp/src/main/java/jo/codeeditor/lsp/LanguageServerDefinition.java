package jo.codeeditor.lsp;

import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Abstract registration for a language server. The host creates one
 * {@code LanguageServerDefinition} per server type (jdtls for Java,
 * kotlin-language-server for Kotlin, etc.) and registers it with a
 * {@link LspProject}.
 *
 * <p>Subclasses must implement {@link #createConnectionProvider(String)} to
 * return the transport (subprocess, TCP socket, or LocalSocket). Everything
 * else (LSP4J launcher, initialize handshake, capability negotiation) is
 * handled by the framework.
 *
 * <p><b>v2.52 — Subset F multi-extension support :</b> par défaut, un
 * LanguageServerDefinition gère une seule extension (la valeur passée au
 * constructeur). Les sous-classes qui gèrent plusieurs extensions (ex. Kotlin
 * pour {@code .kt} ET {@code .kts}) peuvent redéfinir
 * {@link #getAdditionalExtensions()} pour retourner un set d'extensions
 * supplémentaires. Le {@link LspProject} enregistre alors la définition sous
 * TOUTES les extensions (principale + supplémentaires) et
 * <em>partage</em> l'instance unique du serveur entre ces extensions
 * (pas de duplication de server).</p>
 *
 * <p>Example for a remote jdtls:
 * <pre>{@code
 * LanguageServerDefinition jdtls = new LanguageServerDefinition("java", "jdtls") {
 *     @Override
 *     public StreamConnectionProvider createConnectionProvider(String workingDir) {
 *         return new SocketConnectionProvider("192.168.1.100", 5007);
 *     }
 *     @Override
 *     public ServerCapabilities expectedCapabilities() {
 *         ServerCapabilities caps = new ServerCapabilities();
 *         caps.setCompletionProvider(new org.eclipse.lsp4j.CompletionOptions(true, List.of(".", "@")));
 *         caps.setTextDocumentSync(org.eclipse.lsp4j.Either.forLeft(org.eclipse.lsp4j.TextDocumentSyncKind.Incremental));
 *         return caps;
 *     }
 * };
 * }</pre>
 *
 * @since v2.2.0
 */
public abstract class LanguageServerDefinition {

    private final String ext;
    private final String name;
    private Set<LspFeature> disabledFeatures = Collections.emptySet();

    /**
     * @param ext the file extension this server handles (e.g. "java", "kt")
     * @param name a display name (e.g. "jdtls", "kotlin-language-server")
     */
    protected LanguageServerDefinition(String ext, String name) {
        this.ext = ext;
        this.name = name;
    }

    /** Returns the file extension (without the dot). */
    public String getExt() { return ext; }

    /** Returns the display name. */
    public String getName() { return name; }

    /**
     * ★ v2.52 Subset F — Extensions supplémentaires que ce serveur gère
     * (en plus de {@link #getExt()}). Par défaut, retourne un set vide
     * (mono-extension). Les sous-classes peuvent override pour gérer
     * plusieurs extensions — ex. Kotlin gère {@code .kt} (principale) ET
     * {@code .kts} (supplémentaire).
     *
     * <p>Le {@link LspProject} enregistre la définition sous TOUTES les
     * extensions (principale + supplémentaires) via
     * {@link LspProject#addServerDefinition(LanguageServerDefinition)} et
     * <em>partage</em> l'instance unique du serveur entre ces extensions.</p>
     */
    public Set<String> getAdditionalExtensions() {
        return Collections.emptySet();
    }

    /**
     * ★ v2.52 Subset F — Toutes les extensions gérées par ce serveur
     * (principale + supplémentaires). Utilisé par {@link LspProject} pour
     * l'enregistrement multi-extension.
     */
    public final Set<String> getAllExtensions() {
        Set<String> all = new HashSet<>();
        all.add(ext);
        all.addAll(getAdditionalExtensions());
        return Collections.unmodifiableSet(all);
    }

    /**
     * Creates the connection provider for this server. Called when the
     * server is started. The working directory is the project root.
     */
    public abstract StreamConnectionProvider createConnectionProvider(String workingDir);

    /**
     * Returns the expected server capabilities, or {@code null} to discover
     * them from the initialize result. Override to pre-declare capabilities
     * for faster startup (avoids waiting for the server's response before
     * enabling features).
     */
    public ServerCapabilities expectedCapabilities() { return null; }

    /**
     * Returns the initialization options to send in the initialize request,
     * or {@code null} for none.
     */
    public Object getInitializationOptions() { return null; }

    /**
     * Returns the set of LSP features to disable (e.g. {@link LspFeature#INLAY_HINT}
     * if the host doesn't want inlay hints from this server).
     */
    public Set<LspFeature> getDisabledFeatures() { return disabledFeatures; }

    /** Sets the disabled features. */
    public void setDisabledFeatures(Set<LspFeature> features) {
        this.disabledFeatures = features != null ? features : Collections.emptySet();
    }

    /** Returns true if the server should be sent an exit notification on shutdown. */
    public boolean callExitForLanguageServer() { return true; }
}
