package jo.codeeditor.lsp;

import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Miroir CLIENT des extensions LSP de CodeIDE : la méthode
 * personnalisée {@code textDocument/superDefinition} (GO TO Super — aucune
 * méthode LSP standard n'existe).
 *
 * <h2>Pourquoi une interface cliente est INDISPENSABLE</h2>
 *
 * <p>LSP4J ne désérialise le {@code result} d'une réponse QUE si la méthode
 * figure dans la carte du client ({@code Launcher.Builder.getSupportedMethods}
 * — construite depuis les interfaces REMOTES du launcher). Sans elle,
 * {@code MessageTypeAdapter.parseResult} reçoit un type {@code null} et
 * {@code fromJson(element, null)} rend… {@code null} : la réponse est
 * silencieusement PERDUE (vérifié par reproducteur autonome + wire trace —
 * le serveur envoie bien le JSON, le client le jette).</p>
 *
 * <h2>⚠ Le piège du delegate covariant côté client</h2>
 *
 * <p>La méthode est d'abord exposée par le serveur (:lspjava) via le pattern
 * {@code NavLanguageServer.getTextDocumentService()} COVARIANT annoté
 * {@code @JsonDelegate} — pattern nécessaire côté serveur (service LOCAL)
 * pour que la carte de désérialisation des PARAMÈTRES connaisse la méthode.
 * Mais le MÊME pattern côté client est INTERDIT :
 * {@code LanguageServer.getTextDocumentService()} est DÉJÀ annoté
 * {@code @JsonDelegate} dans lsp4j — le redéclarer covariant crée DEUX
 * segments delegate homonymes dans le scan d'{@code EndpointProxy} et le
 * launcher lève {@code IllegalStateException: Duplicate RPC method} pour
 * TOUS les serveurs (régression de toute l'intégration LSP, verrouillée par
 * {@code SuperDefinitionRoundTripTest}).</p>
 *
 * <p>La solution : déclarer la requête AU NIVEAU SUPÉRIEUR de l'interface —
 * une méthode RPC directe du proxy principal, sans toucher au delegate.
 * Le wire est identique ({@code "method": "textDocument/superDefinition"}),
 * le dispatch serveur est inchangé, et la carte client connaît la méthode ET
 * son type de retour (validé de bout en bout par reproducteur autonome :
 * initialize + appel typé + désérialisation en {@link SuperNavTarget}).</p>
 *
 * <p>Les serveurs tiers (jdtls, kotlin-language-server, EmmyLua…) passent
 * par la MÊME interface : les méthodes standard sont inchangées
 * (héritage), et un appel {@code superDefinition} à un serveur qui ne la
 * connaît pas échoue proprement (« method not found » → le provider rend
 * vide, l'option Super n'apparaît pas).</p>
 *
 * @author jo@Dev
 */
public interface CodeIdeLanguageServer extends LanguageServer {

    /**
     * Les cibles GO TO « Super » au caret : pour un membre outrepassant,
     * le même-nommé dans chaque supertype ; sinon les supertypes DIRECTS
     * du type en contexte.
     *
     * <p>Méthode LSP PERSONNALISÉE {@code textDocument/superDefinition}
     * (cf. {@code jo.lspjava.lsp.NavTextDocumentService} côté serveur) —
     * même signature JSON, résultat désérialisé en {@link SuperNavTarget}.</p>
     *
     * @param params le document + la position
     * @return les cibles ({@code SuperNavTarget}) — vide si rien
     */
    @JsonRequest("textDocument/superDefinition")
    CompletableFuture<List<SuperNavTarget>> superDefinition(
            TextDocumentPositionParams params);

    /**
     * La source (attachée ou stub décompilé) d'un FQN binaire
     * (classpath, JDK). Câble de bout en bout la fonctionnalité
     * « GO TO Definition sur cible binaire » : le serveur
     * ({@code :lspjava}) renvoie une {@code Location} à URI synthétique
     * {@code jdt://decompiled/<fqcn>.java} depuis
     * {@code definition}/{@code typeDefinition} quand la cible est binaire ;
     * le client appelle alors cette méthode pour récupérer le texte source
     * à afficher dans le buffer read-only.
     *
     * <p>Méthode LSP PERSONNALISÉE
     * {@code textDocument/decompiledSource} (cf.
     * {@code jo.lspjava.lsp.NavTextDocumentService} côté serveur) —
     * même signature JSON, résultat désérialisé en
     * {@link DecompiledSourceResult}.</p>
     *
     * <p><strong>Architecture :</strong> méthode AU NIVEAU SUPÉRIEUR de
     * l'interface (pas dans {@code getTextDocumentService()}) — même
     * précaution que pour {@link #superDefinition} : le delegate covariant
     * est interdit côté client (Duplicate RPC method, cf. javadoc de cette
     * classe).</p>
     *
     * @param params le FQN cible
     * @return la source (attachée ou stub), ou {@code null} si indisponible
     */
    @JsonRequest("textDocument/decompiledSource")
    CompletableFuture<DecompiledSourceResult> decompiledSource(
            DecompiledSourceParams params);

    /**
     * Miroir client du DTO serveur
     * {@code jo.lspjava.api.DecompiledSourceParams} (champs publics —
     * désérialisation Gson un à un).
     */
    final class DecompiledSourceParams {
        /** Le fully-qualified name (ex. {@code java.util.HashMap}). */
        public String fqcn;

        public DecompiledSourceParams() {
            // Gson.
        }

        public DecompiledSourceParams(String fqcn) {
            this.fqcn = fqcn;
        }
    }

    /**
     * Miroir client du DTO serveur
     * {@code jo.lspjava.api.DecompiledSourceResult} (champs publics —
     * désérialisation Gson un à un).
     */
    final class DecompiledSourceResult {
        /** La source (attachée ou stub), ou {@code null} si indisponible. */
        public String source;

        public DecompiledSourceResult() {
            // Gson.
        }

        public DecompiledSourceResult(String source) {
            this.source = source;
        }
    }

    /**
     * Miroir client du DTO serveur {@code jo.lspjava.api.NavigationTarget}
     * (champs publics — désérialisation Gson un à un).
     */
    final class SuperNavTarget {
        /** Chemin absolu du fichier déclarant (sans préfixe file://). */
        public String path;
        /** Offset du NOM de la déclaration dans ce fichier. */
        public int start;
        /** Fin (exclusive) du nom. */
        public int end;
        /** Libellé picker (« Simple  ·  pkg » / « name  ·  Super »). */
        public String label;
    }
}
