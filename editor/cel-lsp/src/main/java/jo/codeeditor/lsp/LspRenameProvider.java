package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.RenameProvider;
import jo.codeeditor.lang.model.RenameResult;
import org.eclipse.lsp4j.services.LanguageServer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit le renommage du SPI éditeur en requête LSP
 * {@code textDocument/rename} puis répartit le {@code WorkspaceEdit} : le
 * fichier courant est retourné comme {@link RenameResult} éditable, les
 * autres fichiers sont réécrits directement sur disque — CONFINÉS au
 * workspace, validés intégralement avant écriture (tout-ou-rien) et
 * réécrits atomiquement via {@link WorkspaceDiskEdits}. Instancié par
 * {@link LspLanguage} quand le serveur annonce la capability renameProvider.
 */
class LspRenameProvider implements RenameProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspRenameProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    /**
     * Pré-validation {@code textDocument/prepareRename} — seulement si le
     * serveur l'annonce (renameProvider avec prepareProvider) : retourne
     * le placeholder proposé (nom pré-rempli du popup), ou null quand le
     * symbole n'est pas renommable (le popup refuse proprement à
     * l'ouverture). Sans annonce : null SANS requête (l'éditeur garde son
     * heuristique locale).
     */
    @Override
    public String prepareRename(CharSequence text, int offset) {
        LanguageServer server = wrapper.getServer();
        if (server == null || !prepareSupported()) return null;
        editor.flushPendingChange();
        org.eclipse.lsp4j.PrepareRenameParams params = new org.eclipse.lsp4j.PrepareRenameParams(
                editor.getTextDocumentIdentifier(), editor.offsetToPosition(offset));
        log("prepareRename: requesting at offset=" + offset);
        try {
            org.eclipse.lsp4j.jsonrpc.messages.Either3<
                    org.eclipse.lsp4j.Range, org.eclipse.lsp4j.PrepareRenameResult,
                    org.eclipse.lsp4j.PrepareRenameDefaultBehavior> result =
                    LspRequestTimeouts.await(
                            server.getTextDocumentService().prepareRename(params),
                            wrapper.requestTimeouts().prepareRenameMs());
            if (result == null) {
                log("prepareRename: null — symbole non renommable");
                return null;
            }
            if (result.isSecond() && result.getSecond() != null) {
                String placeholder = result.getSecond().getPlaceholder();
                log("prepareRename: placeholder=" + placeholder);
                return placeholder;
            }
            if (result.isFirst() && result.getFirst() != null && text != null) {
                // Plage seule : le texte couvert par la plage sert de
                // placeholder — converti sur l'instantané texte.
                String src = text.toString();
                int start = LspTextUtils.positionToOffset(src, result.getFirst().getStart());
                int end = LspTextUtils.positionToOffset(src, result.getFirst().getEnd());
                if (start >= 0 && end > start && end <= src.length()) {
                    String placeholder = src.substring(start, end);
                    log("prepareRename: placeholder(plage)=" + placeholder);
                    return placeholder;
                }
            }
            return null;
        } catch (Exception e) {
            log("prepareRename: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /** True si le serveur annonce la pré-validation de renommage. */
    private boolean prepareSupported() {
        org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
        if (caps == null || caps.getRenameProvider() == null) return false;
        if (!caps.getRenameProvider().isRight()) return false;
        org.eclipse.lsp4j.RenameOptions options = caps.getRenameProvider().getRight();
        return options != null && Boolean.TRUE.equals(options.getPrepareProvider());
    }

    @Override
    public RenameResult rename(CharSequence text, int offset, String newName) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return null;
        // Flush didChange en attente (cf. complete). Un rename
        // sur un identifiant qui vient d'être tapé doit voir le texte à jour.
        editor.flushPendingChange();
        org.eclipse.lsp4j.RenameParams params = new org.eclipse.lsp4j.RenameParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(offset));
        params.setNewName(newName);
        log("rename: requesting at offset=" + offset + " → '" + newName + "'");
        try {
            CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> future =
                server.getTextDocumentService().rename(params);
            org.eclipse.lsp4j.WorkspaceEdit edit =
                        LspRequestTimeouts.await(future, wrapper.requestTimeouts().renameMs());
            if (edit == null
                    || (edit.getChanges() == null || edit.getChanges().isEmpty())
                    && (edit.getDocumentChanges() == null || edit.getDocumentChanges().isEmpty())) {
                log("rename: no edits");
                return null;
            }

            // Multi-fichiers : le WorkspaceEdit peut couvrir PLUSIEURS
            // fichiers (renommage projet-wide). Le fichier COURANT est
            // retourné comme RenameResult (éditeur, annulable) ; les AUTRES
            // fichiers sont réécrits sur disque par la voie commune —
            // confinement, validation complète, écriture atomique.
            String currentUri = editor.getFileUri();
            List<jo.codeeditor.lang.model.TextEdit> currentEdits = new ArrayList<>();
            org.eclipse.lsp4j.WorkspaceEdit diskOnly = new org.eclipse.lsp4j.WorkspaceEdit();
            collectForRename(edit, currentUri, currentEdits, diskOnly);

            WorkspaceDiskEdits.Result disk = applyDiskEdits(diskOnly);
            int otherEdits = disk.editsApplied;
            int otherFiles = disk.filesWritten;
            if (!disk.applied && !diskOnly.getChanges().isEmpty()) {
                // Le renommage disque a été REFUSÉ (hors workspace, plages
                // invalides…) : on ne touche PAS le fichier courant non
                // plus — cohérence tout-ou-rien du renommage.
                log("rename: REFUS des éditions disque — " + disk.rejections);
                return null;
            }
            log("rename: " + currentEdits.size() + " éditions (fichier courant)"
                    + (otherFiles > 0
                            ? " + " + otherEdits + " dans " + otherFiles + " autre(s) fichier(s) (disque)"
                            : ""));
            if (currentEdits.isEmpty() && otherFiles == 0) return null;
            return new RenameResult(currentEdits);
        } catch (Exception e) {
            log("rename: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Répartit le WorkspaceEdit de renommage : éditions du fichier
     * courant (modèle éditeur) vs fichiers tiers (édition disque).
     * Les ResourceOperation éventuelles sont laissées à
     * {@link WorkspaceDiskEdits} qui les refuse et le signale.
     */
    private void collectForRename(org.eclipse.lsp4j.WorkspaceEdit edit, String currentUri,
            List<jo.codeeditor.lang.model.TextEdit> currentEdits,
            org.eclipse.lsp4j.WorkspaceEdit diskOnly) {
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.TextDocumentEdit,
                org.eclipse.lsp4j.ResourceOperation>> docChanges = edit.getDocumentChanges();
        if (docChanges != null && !docChanges.isEmpty()) {
            for (org.eclipse.lsp4j.jsonrpc.messages.Either<
                    org.eclipse.lsp4j.TextDocumentEdit,
                    org.eclipse.lsp4j.ResourceOperation> either : docChanges) {
                if (either == null || !either.isLeft()) continue;
                org.eclipse.lsp4j.TextDocumentEdit tde = either.getLeft();
                if (tde == null || tde.getTextDocument() == null) continue;
                String uri = tde.getTextDocument().getUri();
                if (uri.equals(currentUri)) {
                    addCurrentEdits(tde.getEdits(), currentEdits);
                } else if (tde.getEdits() != null && !tde.getEdits().isEmpty()) {
                    diskOnly.getChanges().put(uri, new ArrayList<>(tde.getEdits()));
                }
            }
            return;
        }
        for (java.util.Map.Entry<String, List<org.eclipse.lsp4j.TextEdit>> entry
                : edit.getChanges().entrySet()) {
            String uri = entry.getKey();
            if (uri.equals(currentUri)) {
                addCurrentEdits(entry.getValue(), currentEdits);
            } else if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                diskOnly.getChanges().put(uri, new ArrayList<>(entry.getValue()));
            }
        }
    }

    private void addCurrentEdits(List<org.eclipse.lsp4j.TextEdit> edits,
            List<jo.codeeditor.lang.model.TextEdit> out) {
        if (edits == null) return;
        for (org.eclipse.lsp4j.TextEdit te : edits) {
            int start = editor.positionToOffset(te.getRange().getStart());
            int end = editor.positionToOffset(te.getRange().getEnd());
            out.add(new jo.codeeditor.lang.model.TextEdit(
                    start, end, te.getNewText()));
        }
    }

    /** Applique les éditions disque du renommage (voie commune confinée). */
    private WorkspaceDiskEdits.Result applyDiskEdits(
            org.eclipse.lsp4j.WorkspaceEdit diskOnly) {
        if (diskOnly.getChanges().isEmpty()
                && (diskOnly.getDocumentChanges() == null
                    || diskOnly.getDocumentChanges().isEmpty())) {
            return new WorkspaceDiskEdits.Result(true, java.util.Collections.emptyList(), 0, 0);
        }
        LspProject project = editor.getProject();
        String root = project != null ? project.getWorkspacePath() : null;
        if (root == null) {
            return new WorkspaceDiskEdits.Result(false,
                    java.util.Collections.singletonList("racine du workspace inconnue"), 0, 0);
        }
        return WorkspaceDiskEdits.applyAll(diskOnly, new File(root));
    }

    private void log(String message) {
        jo.codeeditor.util.EditorLog.info("LspRename", message);
        if (logSink != null) logSink.log(message);
    }
}
