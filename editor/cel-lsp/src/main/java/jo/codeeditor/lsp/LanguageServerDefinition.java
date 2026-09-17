package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Enregistrement abstrait d'un serveur de langage. L'hôte crée un
 * {@code LanguageServerDefinition} par type de serveur (jdtls pour Java,
 * kotlin-language-server pour Kotlin, etc.) et l'enregistre auprès d'un
 * {@link LspProject}.
 *
 * <p>Les sous-classes doivent implémenter
 * {@link #createConnectionProvider(String)} pour retourner le transport
 * (sous-processus, socket TCP ou LocalSocket). Tout le reste (launcher
 * LSP4J, poignée de main initialize, négociation de capacités) est pris
 * en charge par le cadre.
 *
 * <p><b>Support multi-extension :</b> par défaut, un
 * LanguageServerDefinition gère une seule extension (la valeur passée au
 * constructeur). Les sous-classes qui gèrent plusieurs extensions (ex. Kotlin
 * pour {@code .kt} ET {@code .kts}) peuvent redéfinir
 * {@link #getAdditionalExtensions()} pour retourner un set d'extensions
 * supplémentaires. Le {@link LspProject} enregistre alors la définition sous
 * TOUTES les extensions (principale + supplémentaires) et
 * <em>partage</em> l'instance unique du serveur entre ces extensions
 * (pas de duplication de server).</p>
 *
 * <p>Exemple avec un jdtls distant :
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
 */
public abstract class LanguageServerDefinition {

    private final String ext;
    private final String name;
    private Set<LspFeature> disabledFeatures = Collections.emptySet();

    /**
     * @param ext l'extension de fichier gérée par ce serveur (ex. "java", "kt")
     * @param name un nom d'affichage (ex. "jdtls", "kotlin-language-server")
     */
    protected LanguageServerDefinition(String ext, String name) {
        this.ext = ext;
        this.name = name;
    }

    /** Retourne l'extension de fichier (sans le point). */
    public String getExt() { return ext; }

    /** Retourne le nom d'affichage. */
    public String getName() { return name; }

    /**
     * Extensions supplémentaires que ce serveur gère (en plus de
     * {@link #getExt()}). Par défaut, retourne un set vide (mono-extension).
     * Les sous-classes peuvent redéfinir cette méthode pour gérer plusieurs
     * extensions — ex. Kotlin gère {@code .kt} (principale) ET {@code .kts}
     * (supplémentaire).
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
     * Toutes les extensions gérées par ce serveur (principale +
     * supplémentaires). Utilisé par {@link LspProject} pour l'enregistrement
     * multi-extension.
     */
    public final Set<String> getAllExtensions() {
        Set<String> all = new HashSet<>();
        all.add(ext);
        all.addAll(getAdditionalExtensions());
        return Collections.unmodifiableSet(all);
    }

    /**
     * Crée le fournisseur de connexion pour ce serveur. Appelée au démarrage
     * du serveur. Le répertoire de travail est la racine du projet.
     */
    public abstract StreamConnectionProvider createConnectionProvider(String workingDir);

    /**
     * Retourne les capacités serveur attendues, ou {@code null} pour les
     * découvrir depuis le résultat d'initialize. Redéfinir pour pré-déclarer
     * des capacités et accélérer le démarrage (évite d'attendre la réponse
     * du serveur avant d'activer les fonctionnalités).
     */
    public ServerCapabilities expectedCapabilities() { return null; }

    /**
     * Retourne les options d'initialisation à envoyer dans la requête
     * initialize, ou {@code null} pour aucune.
     */
    public Object getInitializationOptions() { return null; }

    /**
     * Retourne l'ensemble des fonctionnalités LSP à désactiver (ex.
     * {@link LspFeature#INLAY_HINT} si l'hôte ne veut pas d'inlay hints de
     * ce serveur).
     */
    public Set<LspFeature> getDisabledFeatures() { return disabledFeatures; }

    /** Définit les fonctionnalités désactivées. */
    public void setDisabledFeatures(Set<LspFeature> features) {
        this.disabledFeatures = features != null ? features : Collections.emptySet();
    }

    /** Retourne true si une notification exit doit être envoyée au serveur à l'arrêt. */
    public boolean callExitForLanguageServer() { return true; }
}
