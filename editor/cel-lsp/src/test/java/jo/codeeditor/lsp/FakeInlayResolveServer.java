package jo.codeeditor.lsp;

import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.InlayHintRegistrationOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Faux serveur LSP des tests d'inlay hints — top-level PUBLIC : le pont
 * in-process découvre {@link #connect(LanguageClient)} par réflexion
 * (une classe package-privée chargée par le classloader de test provoquerait
 * un IllegalAccessException silencieux).
 *
 * <p>Annonce {@code inlayHintProvider} (avec ou sans
 * {@code resolveProvider} selon {@link #announceResolve}) ; sert deux
 * hints — l'un avec tooltip DIRECT, l'autre résolu par
 * {@code inlayHint/resolve} — et COMPTE les requêtes reçues pour les
 * assertions du contrat I2 : la résolution est paresseuse (premier appui
 * long), jamais au rendu, et silencieuse sans annonce.</p>
 */
public class FakeInlayResolveServer implements LanguageServer {

    /** Annonce resolveProvider à l'initialisation (négociée une fois). */
    public volatile boolean announceResolve;
    /** I3 (J6) : hint #2 servi avec un libellé multi-parts (tooltips par part). */
    public volatile boolean multiPartLabels;
    /** Tooltip servi par inlayHint/resolve (null = refus du serveur). */
    public volatile String resolveTooltip = "java.lang.String — le type de la variable";
    /** Compteur de requêtes inlayHint reçues. */
    public volatile int inlayHintRequests;
    /** Compteur de requêtes inlayHint/resolve reçues. */
    public volatile int resolveRequests;

    /** Client reçu du pont (invocation réflexe de connect). */
    public volatile LanguageClient client;

    /** Reçu du pont in-process — doit rester public sur une classe publique. */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        InlayHintRegistrationOptions options = new InlayHintRegistrationOptions();
        options.setResolveProvider(announceResolve);
        caps.setInlayHintProvider(options);
        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
            }

            @Override
            public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
            }

            @Override
            public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {
            }

            @Override
            public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
            }

            @Override
            public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
                inlayHintRequests++;
                // Hint #1 : tooltip DIRECT (transporté tel quel, sans
                // résolution). Hint #2 : sans tooltip — cible de resolve.
                InlayHint direct = new InlayHint();
                direct.setPosition(new org.eclipse.lsp4j.Position(0, 0));
                direct.setLabel(": String");
                direct.setKind(org.eclipse.lsp4j.InlayHintKind.Type);
                direct.setTooltip("Le type inféré de la variable");
                InlayHint lazy = new InlayHint();
                lazy.setPosition(new org.eclipse.lsp4j.Position(0, 4));
                lazy.setKind(org.eclipse.lsp4j.InlayHintKind.Parameter);
                if (multiPartLabels) {
                    // I3 : libellé structuré en parts, chacune avec SON
                    // tooltip, padding left+right demandé (les blancs de
                    // padding doivent devenir des parts SANS tooltip).
                    org.eclipse.lsp4j.InlayHintLabelPart p1 =
                            new org.eclipse.lsp4j.InlayHintLabelPart();
                    p1.setValue("name");
                    p1.setTooltip("Le nom du paramètre");
                    org.eclipse.lsp4j.InlayHintLabelPart p2 =
                            new org.eclipse.lsp4j.InlayHintLabelPart();
                    p2.setValue(": str");
                    p2.setTooltip("Le type inféré du paramètre");
                    lazy.setLabel(java.util.Arrays.asList(p1, p2));
                    lazy.setPaddingLeft(true);
                    lazy.setPaddingRight(true);
                } else {
                    lazy.setLabel("name: ");
                }
                return CompletableFuture.completedFuture(List.of(direct, lazy));
            }

            @Override
            public CompletableFuture<InlayHint> resolveInlayHint(InlayHint unresolved) {
                resolveRequests++;
                InlayHint resolved = new InlayHint();
                resolved.setPosition(unresolved.getPosition());
                resolved.setLabel(labelOf(unresolved));
                resolved.setKind(unresolved.getKind());
                resolved.setTooltip(resolveTooltip);
                return CompletableFuture.completedFuture(resolved);
            }
        };
    }

    private static String labelOf(InlayHint hint) {
        return hint.getLabel() != null && hint.getLabel().isLeft()
                ? hint.getLabel().getLeft() : "";
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(
                    org.eclipse.lsp4j.DidChangeConfigurationParams params) {
            }

            @Override
            public void didChangeWatchedFiles(
                    org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
            }
        };
    }
}
