package jo.codeeditor.demo.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mini serveur de langage Java de la démo — <b>diagnostics et complétion
 * à résolution différée</b>.
 *
 * <p>C'est la vitrine du module {@code :cel-lsp} : un VRAI serveur LSP
 * (protocole JSON-RPC via LSP4J, poignée de main {@code initialize},
 * notifications {@code didOpen}/{@code didChange}/{@code didClose},
 * publication {@code textDocument/publishDiagnostics}), implémenté en
 * ~250 lignes. Il tourne <b>in-process</b> derrière un
 * {@code InProcessStreamConnectionProvider} : le trafic traverse le
 * cadre LSP de la bibliothèque exactement comme avec un serveur externe
 * (sous-processus, socket), seul le transport change.</p>
 *
 * <p><b>Capacités annoncées :</b> {@code textDocumentSync = Full} — le
 * client renvoie le texte complet à chaque changement, le plus simple
 * et le plus robuste pour un mini serveur (le synchroniseur de
 * {@code cel-lsp} gère nativement ce mode) — depuis la 4.7.0
 * {@code completionProvider} avec {@code resolveProvider = true} :
 * la liste initiale est SERVIE SANS documentation pour les méthodes
 * d'ajout ({@code add}, {@code addAll}) — la documentation ne vient
 * qu'au {@code completionItem/resolve}, le comportement standard des
 * serveurs réels qui économisent la bande passante de la liste. Le
 * panneau de documentation du popup se remplit donc APRÈS un séjour
 * de la sélection sur le candidat (dwell 200 ms) : c'est le banc
 * observable de la résolution différée, journal à l'appui (les lignes
 * {@code completion:} et {@code completion/resolve:} du panneau
 * Journal).</p>
 *
 * <p><b>4.11.0 — aide de signature réelle :</b>
 * {@code signatureHelpProvider} (déclencheurs {@code (} et {@code ,})
 * servi par {@link MiniJavaSignatures} — les VRAIES signatures des
 * méthodes appelées : surcharges d'impression pour
 * {@code System.out.println(…)}, maths pour {@code Math.max(…)},
 * chaînes/bâtisseur/collections pour les variables typées du buffer,
 * et les méthodes DÉCLARÉES dans le fichier (javadoc {@code @param}
 * comprise) pour les appels nus ou qualifiés. Le paramètre actif est
 * calculé à la position du caret — le popup éditorial suit la frappe
 * virgule par virgule.</p>
 *
 * <p><b>Connexion du client :</b> la méthode publique
 * {@link #connect(LanguageClient)} est invoquée par réflexion par le
 * pont in-process — elle DOIT rester publique sur une classe publique
 * (voir {@code InProcessStreamConnectionProvider.start}).</p>
 *
 * <p>Pur JVM (aucune dépendance Android) : testable directement en JUnit
 * avec un {@link LanguageClient} d'enregistrement (voir
 * {@code MiniJavaLanguageServerTest}).</p>
 */
public class MiniJavaLanguageServer implements LanguageServer {

    /** Source annoncée dans les diagnostics LSP. */
    static final String SOURCE = "mini-java";

    /** Le client LSP reçu du pont in-process (canal de publication). */
    private volatile LanguageClient client;

    /** Documents ouverts — URI → dernier texte connu (sync Full). */
    private final Map<String, String> openDocuments = new ConcurrentHashMap<>();

    /** Client reçu du pont — appelé par réflexion, doit rester public. */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        // Sync FULL : le client renvoie le texte complet à chaque
        // didChange — l'analyse repart toujours de la source exacte.
        caps.setTextDocumentSync(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
                TextDocumentSyncKind.Full));
        // 4.7.0 — complétion à résolution différée : la liste est servie
        // SANS documentation pour add/addAll (elle ne vient qu'au
        // completionItem/resolve, data à l'appui), DIRECTE pour les
        // autres — le banc observable du dwell du popup.
        CompletionOptions completion = new CompletionOptions();
        completion.setResolveProvider(true);
        caps.setCompletionProvider(completion);
        // 4.11.0 — l'aide de signature RÉELLE : les surcharges de la
        // méthode appelée (bibliothèque embarquée + méthodes déclarées
        // dans le fichier), déclenchée par la frappe de '(' et ','.
        caps.setSignatureHelpProvider(new SignatureHelpOptions(
                java.util.Arrays.asList("(", ",")));
        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // Rien à nettoyer : le pont ferme les streams et coupe les
        // threads (stop() du provider in-process).
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                openDocuments.put(uri, params.getTextDocument().getText());
                analyzeAndPublish(uri);
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                // Sync Full : le premier changement porte le texte
                // complet (les suivants n'existent pas dans ce mode).
                if (!params.getContentChanges().isEmpty()) {
                    openDocuments.put(uri,
                            params.getContentChanges().get(0).getText());
                    analyzeAndPublish(uri);
                }
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                openDocuments.remove(uri);
                // Convention LSP : à la fermeture, le serveur vide les
                // diagnostics du document.
                publish(uri, new ArrayList<>());
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                // Rien : l'analyse est déjà relancée à chaque didChange.
            }

            // ── Complétion (4.7.0) ────────────────────────────────

            @Override
            public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
                    CompletionParams position) {
                // Le filtre par préfixe vit côté CLIENT (matcher
                // unifié) : le serveur sert sa base fixe, comme un
                // serveur réel qui répond à la position sans
                // re-filtrer ce que le client re-filtrera.
                List<CompletionItem> items = new ArrayList<>();
                items.add(méthode("toString",
                        "La représentation textuelle de l'élément", null));
                items.add(méthode("size",
                        "Le nombre d'éléments de la liste", null));
                // Les deux candidats PARESSEUX : aucune documentation
                // dans la liste — le champ data fait l'aller-retour du
                // resolve (la clé du banc).
                items.add(méthode("add", null, "add"));
                items.add(méthode("addAll", null, "addAll"));
                return CompletableFuture.completedFuture(
                        Either.forRight(new CompletionList(false, items)));
            }

            // ── Aide de signature (4.11.0) ──────────────────────

            @Override
            public CompletableFuture<SignatureHelp> signatureHelp(
                    SignatureHelpParams params) {
                String uri = params.getTextDocument().getUri();
                String text = openDocuments.get(uri);
                // Le document inconnu n'a pas de call-site : réponse
                // vide (le client garde son repli local).
                if (text == null) {
                    return CompletableFuture.completedFuture(new SignatureHelp());
                }
                int caret = positionVersOffset(text, params.getPosition());
                MiniJavaSignatures.Result r = MiniJavaSignatures.resolve(text, caret);
                SignatureHelp out = new SignatureHelp();
                if (r == null) {
                    return CompletableFuture.completedFuture(out);
                }
                List<SignatureInformation> signatures = new ArrayList<>();
                for (MiniJavaSignatures.Entry e : r.signatures) {
                    SignatureInformation info = new SignatureInformation();
                    info.setLabel(e.label);
                    if (!e.documentation.isEmpty()) {
                        info.setDocumentation(
                                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
                                        e.documentation));
                    }
                    List<org.eclipse.lsp4j.ParameterInformation> pis =
                            new ArrayList<>();
                    for (int i = 0; i < e.params.size(); i++) {
                        org.eclipse.lsp4j.ParameterInformation pi =
                                new org.eclipse.lsp4j.ParameterInformation();
                        pi.setLabel(org.eclipse.lsp4j.jsonrpc.messages.Either
                                .forLeft(e.params.get(i)));
                        if (i < e.paramDocs.size() && !e.paramDocs.get(i).isEmpty()) {
                            pi.setDocumentation(
                                    org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
                                            e.paramDocs.get(i)));
                        }
                        pis.add(pi);
                    }
                    info.setParameters(pis);
                    // Le paramètre actif par signature (LSP 3.16) — le
                    // popup éditorial surligne le paramètre en cours.
                    info.setActiveParameter(r.activeParameter);
                    signatures.add(info);
                }
                out.setSignatures(signatures);
                out.setActiveSignature(0);
                out.setActiveParameter(r.activeParameter);
                return CompletableFuture.completedFuture(out);
            }

            @Override
            public CompletableFuture<CompletionItem> resolveCompletionItem(
                    CompletionItem item) {
                // La documentation ne vit QUE ici — un serveur réel
                // l'économise dans la liste pour épargner la bande
                // passante.
                String label = item.getLabel();
                String doc;
                if ("add".equals(label)) {
                    doc = "Ajoute un élément à la fin de la liste. La capacité "
                            + "interne est doublée si nécessaire. "
                            + "@param x l'élément à ajouter "
                            + "@return vrai si la liste a changé";
                } else if ("addAll".equals(label)) {
                    doc = "Ajoute tous les éléments de la collection donnée à "
                            + "la fin de la liste, dans l'ordre de son itérateur. "
                            + "@param c la collection à ajouter "
                            + "@return vrai si la liste a changé";
                } else {
                    doc = "Aucune documentation supplémentaire pour " + label;
                }
                CompletionItem resolved = new CompletionItem();
                resolved.setLabel(label);
                // Le champ data fait l'aller-retour TEL QUEL.
                resolved.setData(item.getData());
                resolved.setDocumentation(doc);
                return CompletableFuture.completedFuture(resolved);
            }
        };
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(
                    org.eclipse.lsp4j.DidChangeConfigurationParams params) {
                // Rien : le mini serveur n'a aucun réglage client.
            }

            @Override
            public void didChangeWatchedFiles(
                    org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
                // Rien : le mini serveur ne regarde pas l'espace de
                // travail hors documents ouverts.
            }
            // Les autres notifications (didChangeConfiguration,
            // executeCommand…) héritent des implémentations par défaut
            // de LSP4J — ce serveur n'annonce aucune capacité d'espace
            // de travail.
        };
    }

    /**
     * Analyse le document ouvert et pousse les diagnostics au client.
     *
     * <p>Exécuté sur un thread du pont in-process (jamais le thread UI) :
     * la publication voyage par JSON-RPC et remonte au
     * {@code LspDiagnosticsManager} qui, lui, marshalle vers le thread
     * UI de l'hôte.</p>
     */
    private void analyzeAndPublish(String uri) {
        String text = openDocuments.get(uri);
        if (text == null) return;
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (MiniJavaParser.Issue issue : MiniJavaParser.parse(text)) {
            Diagnostic diag = new Diagnostic();
            diag.setRange(new Range(
                    new Position(issue.line, issue.character),
                    new Position(issue.endLine, issue.endCharacter)));
            diag.setSeverity(DiagnosticSeverity.Error);
            diag.setSource(SOURCE);
            diag.setCode(issue.code);
            diag.setMessage(issue.message);
            diagnostics.add(diag);
        }
        publish(uri, diagnostics);
    }

    private void publish(String uri, List<Diagnostic> diagnostics) {
        LanguageClient c = client;
        if (c != null) {
            c.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
    }

    /** Un candidat de complétion : méthode, documentation directe ou
     *  paresseuse (data à l'aller-retour du resolve). */
    private static CompletionItem méthode(String label, String documentation,
                                           Object data) {
        CompletionItem item = new CompletionItem();
        item.setLabel(label);
        item.setKind(CompletionItemKind.Method);
        item.setDetail("java.util.List");
        if (documentation != null) {
            item.setDocumentation(documentation);
        }
        if (data != null) {
            item.setData(data);
        }
        return item;
    }

    /**
     * Convertit une position LSP (ligne, caractère — unités UTF-16,
     * comme les chaînes Java) en offset du texte — la réciproque
     * exacte de l'offsetToPosition du client.
     */
    static int positionVersOffset(String text, Position position) {
        int ligne = Math.max(0, position.getLine());
        int car = Math.max(0, position.getCharacter());
        int offset = 0;
        int lignesVues = 0;
        while (lignesVues < ligne && offset < text.length()) {
            char c = text.charAt(offset);
            // \r\n compte pour un seul séparateur.
            if (c == '\r') {
                if (offset + 1 < text.length() && text.charAt(offset + 1) == '\n') {
                    offset++;
                }
                lignesVues++;
            } else if (c == '\n') {
                lignesVues++;
            }
            offset++;
        }
        // Dernier segment : jusqu'au caractère demandé ou à la fin de
        // ligne, auquel cas la position désigne la fin de la ligne.
        int fin = offset;
        while (fin < text.length() && text.charAt(fin) != '\n'
                && text.charAt(fin) != '\r') {
            fin++;
        }
        return Math.min(offset + car, fin);
    }
}
