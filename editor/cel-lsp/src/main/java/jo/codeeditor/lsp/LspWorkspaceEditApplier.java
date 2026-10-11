package jo.codeeditor.lsp;

import jo.codeeditor.view.EditorView;

import java.util.List;

/**
 * Application des éditions LSP renvoyées par le serveur (WorkspaceEdit des
 * actions de code, additionalTextEdits des complétions) : éditions du
 * fichier courant appliquées à la session de l'éditeur, éditions des
 * fichiers tiers réécrites sur disque en arrière-plan. Extrait de
 * {@link LspEditor} pour isoler cette responsabilité du reste du pont
 * éditeur↔serveur.
 */
class LspWorkspaceEditApplier {

    private final LspEditor editor;

    LspWorkspaceEditApplier(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * Applique un {@link org.eclipse.lsp4j.WorkspaceEdit} LSP à l'éditeur
     * lié. Appelée quand l'utilisateur accepte une action de code. Parcourt
     * les changements de l'édition (par URI) et les applique à la session
     * de l'éditeur — pour l'URI du fichier courant, applique les TextEdits ;
     * pour les autres URI, réécrit les éditions sur disque en arrière-plan.
     *
     * <p>Doit être appelée sur le thread UI (elle mute la session).</p>
     */
    void applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit) {
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) {
            android.util.Log.w("LspEditor", "applyWorkspaceEdit: no editor bound");
            return;
        }
        if (edit == null) return;
        // Préfère documentChanges (LSP 3.16+) qui portent un
        // OptionalVersionedTextDocumentIdentifier ; repli sur changes
        // (LSP 3.13) indexé par URI.
        //
        // Les fichiers TIERS ne sont pas ignorés — leurs
        // éditions sont réécrites SUR DISQUE (même sémantique que le
        // renommage multi-fichiers : offsets triés décroissants), en
        // tâche de fond pour éviter tout IO sur le thread UI.
        java.util.List<org.eclipse.lsp4j.jsonrpc.messages.Either<
            org.eclipse.lsp4j.TextDocumentEdit,
            org.eclipse.lsp4j.ResourceOperation>> docChanges = edit.getDocumentChanges();
        if (docChanges != null && !docChanges.isEmpty()) {
            for (org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.TextDocumentEdit,
                org.eclipse.lsp4j.ResourceOperation> either : docChanges) {
                if (either == null || !either.isLeft()) continue;
                org.eclipse.lsp4j.TextDocumentEdit tde = either.getLeft();
                if (tde == null) continue;
                String uri = tde.getTextDocument().getUri();
                if (!editor.getFileUri().equals(uri)) {
                    applyOtherFileEditsAsync(uri, tde.getEdits());
                    continue;
                }
                applyTextEdits(tde.getEdits());
            }
            return;
        }
        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> changes = edit.getChanges();
        if (changes == null) return;
        for (java.util.Map.Entry<String, java.util.List<org.eclipse.lsp4j.TextEdit>> entry
                : changes.entrySet()) {
            if (!editor.getFileUri().equals(entry.getKey())) {
                applyOtherFileEditsAsync(entry.getKey(), entry.getValue());
            } else {
                applyTextEdits(entry.getValue());
            }
        }
    }

    /**
     * Applique les éditions d'un fichier NON ouvert en
     * réécrivant le fichier sur disque (thread de fond). Journalisation au
     * mieux ; un échec n'affecte pas les autres fichiers du WorkspaceEdit.
     */
    private void applyOtherFileEditsAsync(String uri,
            java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        if (uri == null || edits == null || edits.isEmpty()) return;
        final String fUri = uri;
        final java.util.List<org.eclipse.lsp4j.TextEdit> fEdits =
                new java.util.ArrayList<>(edits);
        DISK_EDITS_EXECUTOR.execute(() -> {
            int applied = applyEditsToDiskFile(fUri, fEdits);
            android.util.Log.i("LspEditor",
                "applyWorkspaceEdit: " + applied + "/" + fEdits.size()
                    + " éditions appliquées sur disque pour " + fUri);
        });
    }

    /** Exécuteur dédié aux réécritures disque des fichiers tiers (daemon). */
    private static final java.util.concurrent.ExecutorService DISK_EDITS_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cel-lsp-disk-edits");
                t.setDaemon(true);
                return t;
            });

    /**
     * Applique des éditions LSP à un fichier NON ouvert : lecture disque,
     * application en ordre décroissant d'offset, réécriture. Retourne le
     * nombre d'éditions appliquées.
     */
    private static int applyEditsToDiskFile(String uri,
            java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        if (uri == null || edits == null || edits.isEmpty()) return 0;
        String path = uri.startsWith("file://") ? uri.substring("file://".length()) : uri;
        java.io.File file = new java.io.File(path);
        if (!file.isFile()) return 0;
        try {
            // Lecture compatible API 24 — Files.readAllBytes (API 26+)
            // plantait sur Android 7.x.
            String content = IoCompat.readUtf8(file);
            if (content == null) return 0;
            List<int[]> offsets = new java.util.ArrayList<>(edits.size());
            List<String> texts = new java.util.ArrayList<>(edits.size());
            for (org.eclipse.lsp4j.TextEdit te : edits) {
                int start = positionToOffsetFor(content, te.getRange().getStart());
                int end = positionToOffsetFor(content, te.getRange().getEnd());
                if (start < 0 || end < start || end > content.length()) return 0;
                offsets.add(new int[]{start, end});
                texts.add(te.getNewText() == null ? "" : te.getNewText());
            }
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
            android.util.Log.w("LspEditor", "applyEditsToDiskFile failed: " + e.getMessage());
            return 0;
        }
    }

    /** Position LSP → offset sur un texte donné (fichier tiers). */
    private static int positionToOffsetFor(String text, org.eclipse.lsp4j.Position pos) {
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

    /**
     * Applique une liste de TextEdits LSP à la session de l'éditeur. Les
     * trie par offset de début décroissant pour que les éditions
     * antérieures ne décalent pas les offsets ultérieurs.
     */
    private void applyTextEdits(java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) return;
        // Trie par offset de début décroissant pour appliquer chaque édition
        // sans invalider les offsets suivants.
        java.util.List<org.eclipse.lsp4j.TextEdit> sorted = new java.util.ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(
            editor.positionToOffset(b.getRange().getStart()),
            editor.positionToOffset(a.getRange().getStart())));
        jo.codeeditor.session.EditorSession session = editorView.getSession();
        jo.codeeditor.document.EditorDocument doc = session.getDocument();
        StringBuilder sb = new StringBuilder(session.getText());
        for (org.eclipse.lsp4j.TextEdit te : sorted) {
            int start = editor.positionToOffset(te.getRange().getStart());
            int end = editor.positionToOffset(te.getRange().getEnd());
            if (start < 0 || end > sb.length() || start > end) continue;
            sb.replace(start, end, te.getNewText() != null ? te.getNewText() : "");
        }
        String newText = sb.toString();
        if (!newText.equals(session.getText().toString())) {
            session.replaceRange(0, doc.length(), newText);
            editorView.notifyTextChanged();
            android.util.Log.i("LspEditor", "applyWorkspaceEdit: applied " + sorted.size() + " edits");
        }
    }

    /**
     * Applique des éditions LSP ADDITIONNELLES (ex:
     * {@code additionalTextEdits} d'un candidat de complétion — insertion
     * auto-import) sur la session courante.
     *
     * <p>Contrairement à {@link LspEditor#applyWorkspaceEdit} ce chemin accepte les
     * éditions quel que soit leur URI : elles proviennent du document
     * courant (le serveur calcule les positions pour la version qui a servi
     * la complétion). L'insertion de l'identifiant à l'acceptation ne change
     * pas le nombre de lignes, donc les positions d'import (toujours placé
     * AVANT le curseur) restent valides.</p>
     *
     * <p>Public : appelé par le Runnable post-accept transporté sur
     * {@code CompletionSession.Item.attachment}.</p>
     */
    void applyAdditionalEdits(java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        if (edits == null || edits.isEmpty()) return;
        applyTextEdits(edits);
    }
}
