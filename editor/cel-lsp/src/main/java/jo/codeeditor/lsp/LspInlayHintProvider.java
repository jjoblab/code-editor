package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.InlayHint;
import jo.codeeditor.lang.provider.InlayHintProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Traduit la demande d'inlay hints du SPI éditeur en requête LSP
 * {@code textDocument/inlayHint} bornée aux lignes visibles, et convertit
 * libellés (avec padding), kind et position en hints éditeur. Instancié par
 * {@link LspLanguage} quand le serveur annonce la capability
 * inlayHintProvider.
 *
 * <p>I2 (lot 2, J3) : le tooltip (direct ou résolu) est porté par le hint
 * SPI ; la résolution {@code inlayHint/resolve} est PARESSEUSE — émise au
 * premier appui long via {@link #resolveHint}, JAMAIS au rendu, et
 * uniquement si le serveur annonce {@code resolveProvider}. Le cache des
 * hints serveur originaux (clé offset+texte) vit le temps du dernier lot
 * servi ; un hint périmé (édité depuis) est retourné inchangé.</p>
 *
 * <p>I3 (lot 2, J6) : les libellés multi-parts
 * ({@code InlayHint.labelParts}) sont CONSERVÉS — le hint SPI porte la
 * liste des parts (texte + tooltip propre), l'assemblage du texte restant
 * inchangé au rendu. Le padding LSP (espaces de respiration) est porté
 * par des parts de blanc SANS tooltip pour que la concaténation des
 * parts soit ÉGALE au texte tissé (l'invariant du hit-test I2/I3).</p>
 *
 * <p>I4 (lot 2, J6) : la {@code command} LSP du hint devient un
 * {@link Runnable} SPI exécuté au TAP simple sur la plage tissée
 * (résolu avant le placement du caret). Le Runnable déclenche
 * {@code workspace/executeCommand} de façon asynchrone — jamais
 * bloquant pour le thread UI.</p>
 */
class LspInlayHintProvider implements InlayHintProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    /** Hints serveur originaux du DERNIER lot servi (clé offset+texte du
     *  hint SPI) — la cible de {@code inlayHint/resolve} (le champ data
     *  du hint LSP ne traverse pas le SPI). */
    private final java.util.HashMap<String, org.eclipse.lsp4j.InlayHint> serverHints =
            new java.util.HashMap<>();

    LspInlayHintProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<InlayHint> inlayHints(CharSequence text, int startLine, int endLine) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        // Flush didChange en attente (cf. complete).
        // Les inlay hints sont déduits par inférence de type sur le
        // texte source — si le texte serveur est STALE (didChange
        // debouncé à 300 ms pas encore envoyé), les hints seront
        // calculés sur une version obsolète et les offsets retournés
        // ne correspondront pas aux positions du texte live.
        editor.flushPendingChange();
        org.eclipse.lsp4j.InlayHintParams params = new org.eclipse.lsp4j.InlayHintParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        // Convertit (startLine, endLine) en plage LSP. Colonne 0 au
        // début et Integer.MAX_VALUE à la fin pour couvrir toute la
        // plage de lignes.
        jo.codeeditor.document.EditorDocument doc = editor.getEditorView() != null
            ? editor.getEditorView().getSession().getDocument() : null;
        int safeStartLine = doc != null ? Math.max(0, Math.min(startLine, doc.lineCount() - 1)) : Math.max(0, startLine);
        int safeEndLine = doc != null ? Math.max(safeStartLine, Math.min(endLine, doc.lineCount() - 1)) : Math.max(safeStartLine, endLine);
        org.eclipse.lsp4j.Range range = new org.eclipse.lsp4j.Range(
            new org.eclipse.lsp4j.Position(safeStartLine, 0),
            new org.eclipse.lsp4j.Position(safeEndLine, Integer.MAX_VALUE));
        params.setRange(range);
        log("inlayHint: requesting lines " + safeStartLine + "-" + safeEndLine);
        try {
            CompletableFuture<List<org.eclipse.lsp4j.InlayHint>> future =
                server.getTextDocumentService().inlayHint(params);
            List<org.eclipse.lsp4j.InlayHint> hints =
                    LspRequestTimeouts.await(future, wrapper.requestTimeouts().inlayHintsMs());
            if (hints == null || hints.isEmpty()) {
                log("inlayHint: no results");
                return Collections.emptyList();
            }
            List<InlayHint> out = new ArrayList<>();
            serverHints.clear();
            for (org.eclipse.lsp4j.InlayHint h : hints) {
                int offset = editor.positionToOffset(h.getPosition());
                String label;
                List<InlayHint.Part> parts = null;
                org.eclipse.lsp4j.Command lspCommand = null;
                if (h.getLabel().isLeft()) {
                    label = h.getLabel().getLeft();
                } else if (h.getLabel().isRight()) {
                    // Assemble les parties du libellé (certains serveurs
                    // utilisent des parts).
                    // I3 (J6) : les parts sont CONSERVÉES (texte +
                    // tooltip propre) — l'assemblage reste le texte affiché.
                    StringBuilder sb = new StringBuilder();
                    List<InlayHint.Part> built = new ArrayList<>();
                    for (org.eclipse.lsp4j.InlayHintLabelPart part : h.getLabel().getRight()) {
                        String value = part.getValue() != null ? part.getValue() : "";
                        sb.append(value);
                        built.add(new InlayHint.Part(value,
                                tooltipText(part.getTooltip())));
                        // I4 : la command LSP vit sur la PART (spec 3.17 —
                        // InlayHintLabelPart.command) : la première
                        // rencontrée devient la command du hint SPI.
                        if (lspCommand == null && part.getCommand() != null) {
                            lspCommand = part.getCommand();
                        }
                    }
                    label = sb.toString();
                    if (!built.isEmpty()) parts = built;
                } else {
                    label = "";
                }
                String kind = "type";
                if (h.getKind() == org.eclipse.lsp4j.InlayHintKind.Parameter) {
                    kind = "parameter";
                }
                // Padding LSP honoré : « txt = (si paddingLeft " ") +
                // text + (si paddingRight " ") » — les espaces de padding
                // font partie du TEXTE FANTÔME tissé dans la ligne. Sans
                // cela, « name: » collerait à l'argument et le type de
                // chaînage au dernier caractère de l'appel.
                // I3 : avec des parts, le blanc de padding devient une
                // part SANS tooltip — l'invariant concat(parts) == text tient.
                if (Boolean.TRUE.equals(h.getPaddingLeft())) {
                    label = " " + label;
                    if (parts != null) parts.add(0, new InlayHint.Part(" ", null));
                }
                if (Boolean.TRUE.equals(h.getPaddingRight())) {
                    label = label + " ";
                    if (parts != null) parts.add(new InlayHint.Part(" ", null));
                }
                // I2 : le tooltip servi DIRECTEMENT par le serveur est
                // transporté (la résolution paresseuse ne concerne que
                // les serveurs à resolveProvider sans tooltip initial).
                String tooltip = tooltipText(h.getTooltip());
                // I4 (J6) : la command LSP (portée par une part) devient
                // un Runnable — exécuté au TAP sur la plage tissée. La
                // requête workspace/executeCommand part de façon
                // ASYNCHRONE : le Runnable tourne sur le thread UI et ne
                // bloque JAMAIS (retour immédiat, livraison journalisée).
                Runnable command = lspCommandRunnable(lspCommand);
                InlayHint converted = new InlayHint(offset, label, kind, tooltip,
                        parts, command);
                serverHints.put(key(converted), h);
                out.add(converted);
            }
            log("inlayHint: got " + out.size() + " hints");
            return out;
        } catch (TimeoutException e) {
            log("inlayHint: TIMEOUT (5s) — server too slow or hung");
            return Collections.emptyList();
        } catch (InterruptedException | ExecutionException e) {
            String msg = "inlayHint: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                msg += "\n  caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                cause = cause.getCause();
            }
            log(msg);
            return Collections.emptyList();
        }
    }

    /** I4 (J6) : command LSP {@code InlayHint.command} → Runnable
     *  exécuté au tap. Fire-and-forget asynchrone sur
     *  {@code workspace/executeCommand} — jamais bloquant pour l'UI. */
    private Runnable lspCommandRunnable(final org.eclipse.lsp4j.Command lspCommand) {
        if (lspCommand == null || lspCommand.getCommand() == null) return null;
        final String commandId = lspCommand.getCommand();
        final List<Object> args = lspCommand.getArguments();
        return () -> {
            LanguageServer server = wrapper.getServer();
            if (server == null) {
                log("inlayHint command: serveur parti — ignorée (" + commandId + ")");
                return;
            }
            try {
                org.eclipse.lsp4j.ExecuteCommandParams params =
                        new org.eclipse.lsp4j.ExecuteCommandParams();
                params.setCommand(commandId);
                params.setArguments(args);
                server.getWorkspaceService().executeCommand(params)
                        .whenComplete((r, e) -> {
                            if (e != null) {
                                log("inlayHint command ERROR " + commandId
                                        + ": " + e.getMessage());
                            } else {
                                log("inlayHint command OK: " + commandId);
                            }
                        });
            } catch (Exception e) {
                log("inlayHint command ERROR " + commandId + ": "
                        + e.getClass().getSimpleName());
            }
        };
    }

    @Override
    public InlayHint resolveHint(CharSequence text, InlayHint hint) {
        if (hint == null) return null;
        LanguageServer server = wrapper.getServer();
        if (server == null || !resolveSupported()) return hint;
        org.eclipse.lsp4j.InlayHint original = serverHints.get(key(hint));
        if (original == null) {
            // Hint périmé (le lot a été recalculé depuis) : inchangé,
            // AUCUNE requête n'est émise.
            log("inlayHint/resolve: hint inconnu du dernier lot — ignoré");
            return hint;
        }
        log("inlayHint/resolve: requesting tooltip for \"" + hint.text + "\"");
        try {
            org.eclipse.lsp4j.InlayHint resolved = LspRequestTimeouts.await(
                    server.getTextDocumentService().resolveInlayHint(original),
                    wrapper.requestTimeouts().inlayResolveMs());
            String tooltip = resolved != null ? tooltipText(resolved.getTooltip()) : null;
            if (tooltip == null || tooltip.isEmpty()) return hint;
            return new InlayHint(hint.offset, hint.text, hint.kind, tooltip);
        } catch (TimeoutException e) {
            log("inlayHint/resolve: TIMEOUT (3s) — hint inchangé");
            return hint;
        } catch (InterruptedException | ExecutionException e) {
            log("inlayHint/resolve: ERROR " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return hint;
        }
    }

    /** Vrai si le serveur annonce {@code inlayHint.resolveProvider}. */
    private boolean resolveSupported() {
        org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
        if (caps == null || caps.getInlayHintProvider() == null) return false;
        if (!caps.getInlayHintProvider().isRight()) return false;
        return Boolean.TRUE.equals(
                caps.getInlayHintProvider().getRight().getResolveProvider());
    }

    /** Clé de cache d'un hint SPI : offset + séparateur + texte (le hint
     *  passé à resolveHint est reconstruit par l'appelant — seule la
     *  VALEUR (offset, texte) identifie la cible). */
    private static String key(InlayHint hint) {
        return hint.offset + "\u0000" + hint.text;
    }

    /** Either&lt;String, MarkupContent&gt; → texte du tooltip (null si absent). */
    private static String tooltipText(
            org.eclipse.lsp4j.jsonrpc.messages.Either<String,
                    org.eclipse.lsp4j.MarkupContent> tooltip) {
        if (tooltip == null) return null;
        if (tooltip.isLeft()) return tooltip.getLeft();
        if (tooltip.isRight() && tooltip.getRight() != null) {
            return tooltip.getRight().getValue();
        }
        return null;
    }

    private void log(String message) {
        android.util.Log.i("LspInlay", message);
        if (logSink != null) logSink.log(message);
    }
}
