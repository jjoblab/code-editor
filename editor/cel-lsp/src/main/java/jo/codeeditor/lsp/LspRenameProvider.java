package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.RenameProvider;
import jo.codeeditor.lang.model.RenameResult;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit le renommage du SPI éditeur en requête LSP
 * {@code textDocument/rename} puis répartit le {@code WorkspaceEdit} : le
 * fichier courant est retourné comme {@link RenameResult} éditable, les
 * autres fichiers sont réécrits directement sur disque. Instancié par
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
            org.eclipse.lsp4j.WorkspaceEdit edit = future.get(10, TimeUnit.SECONDS);
            if (edit == null || edit.getChanges() == null || edit.getChanges().isEmpty()) {
                log("rename: no edits");
                return null;
            }

            // Multi-fichiers : le WorkspaceEdit peut couvrir PLUSIEURS
            // fichiers (renommage ecj projet-wide). Le fichier COURANT est
            // retourné comme RenameResult (éditeur, annulable) ; les AUTRES
            // fichiers sont réécrits sur disque — éditions triées en offset
            // DÉCROISSANT pour rester valides pendant l'application.
            String currentUri = editor.getFileUri();
            List<jo.codeeditor.lang.model.TextEdit> currentEdits = new ArrayList<>();
            int otherFiles = 0;
            int otherEdits = 0;
            for (java.util.Map.Entry<String, List<org.eclipse.lsp4j.TextEdit>> entry
                    : edit.getChanges().entrySet()) {
                String uri = entry.getKey();
                if (uri.equals(currentUri)) {
                    for (org.eclipse.lsp4j.TextEdit te : entry.getValue()) {
                        int start = editor.positionToOffset(te.getRange().getStart());
                        int end = editor.positionToOffset(te.getRange().getEnd());
                        currentEdits.add(new jo.codeeditor.lang.model.TextEdit(
                                start, end, te.getNewText()));
                    }
                } else {
                    int applied = applyEditsToDiskFile(uri, entry.getValue());
                    if (applied > 0) {
                        otherFiles++;
                        otherEdits += applied;
                    }
                }
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
     * Applique des éditions LSP à un fichier NON ouvert : lecture disque,
     * application en ordre décroissant d'offset, réécriture. Retourne le
     * nombre d'éditions appliquées (0 = fichier introuvable/illisible).
     */
    private static int applyEditsToDiskFile(String uri,
            List<org.eclipse.lsp4j.TextEdit> edits) {
        if (uri == null || edits == null || edits.isEmpty()) return 0;
        String path = uri.startsWith("file://")
                ? uri.substring("file://".length()) : uri;
        java.io.File file = new java.io.File(path);
        if (!file.isFile()) {
            android.util.Log.w("LspRename", "applyEditsToDiskFile: introuvable " + path);
            return 0;
        }
        try {
            // Lecture compatible API 24 — Files.readAllBytes exige
            // l'API 26+.
            String content = IoCompat.readUtf8(file);
            if (content == null) return 0;
            // Position LSP (ligne, colonne) → offset, sur CE contenu.
            List<int[]> offsets = new ArrayList<>(edits.size());
            List<String> texts = new ArrayList<>(edits.size());
            for (org.eclipse.lsp4j.TextEdit te : edits) {
                int start = lspPositionToOffset(content, te.getRange().getStart());
                int end = lspPositionToOffset(content, te.getRange().getEnd());
                if (start < 0 || end < start || end > content.length()) {
                    android.util.Log.w("LspRename",
                            "applyEditsToDiskFile: plage invalide dans " + path);
                    return 0; // édition incohérente → ABANDONNE le fichier entier
                }
                offsets.add(new int[]{start, end});
                texts.add(te.getNewText() == null ? "" : te.getNewText());
            }
            // Ordre décroissant de début.
            Integer[] order = new Integer[offsets.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) ->
                    Integer.compare(offsets.get(b)[0], offsets.get(a)[0]));
            String result = content;
            for (int idx : order) {
                int[] r = offsets.get(idx);
                result = result.substring(0, r[0]) + texts.get(idx)
                        + result.substring(r[1]);
            }
            // Écriture compatible API 24 — Files.write exige l'API 26+.
            if (!IoCompat.writeUtf8(file, result)) return 0;
            return edits.size();
        } catch (Exception e) {
            android.util.Log.w("LspRename",
                    "applyEditsToDiskFile: échec " + path + " — " + e);
            return 0;
        }
    }

    /** Convertit une Position LSP (ligne, colonne) en offset du texte. */
    static int lspPositionToOffset(String text,
            org.eclipse.lsp4j.Position pos) {
        if (pos == null) return -1;
        int line = Math.max(0, pos.getLine());
        int lineStart = 0;
        for (int i = 0; i < line && lineStart < text.length(); i++) {
            int nl = text.indexOf('\n', lineStart);
            if (nl < 0) return -1;
            lineStart = nl + 1;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        int offset = lineStart + Math.max(0, pos.getCharacter());
        return Math.min(offset, lineEnd);
    }

    private void log(String message) {
        android.util.Log.i("LspRename", message);
        if (logSink != null) logSink.log(message);
    }
}
