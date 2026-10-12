package jo.codeeditor.lsp;

import jo.codeeditor.view.EditorView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application des éditions LSP renvoyées par le serveur (WorkspaceEdit des
 * actions de code, additionalTextEdits des complétions) : éditions du
 * fichier courant appliquées à la session de l'éditeur, éditions des
 * fichiers tiers réécrites sur disque en arrière-plan — CONFINÉES au
 * workspace, validées intégralement AVANT toute écriture (tout-ou-rien)
 * et réécrites de façon atomique. Extrait de {@link LspEditor} pour
 * isoler cette responsabilité du reste du pont éditeur↔serveur.
 */
class LspWorkspaceEditApplier {

    private final LspEditor editor;

    LspWorkspaceEditApplier(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * Applique un {@link org.eclipse.lsp4j.WorkspaceEdit} LSP à l'éditeur
     * lié. Appelée quand l'utilisateur accepte une action de code.
     *
     * <p>Sémantique <b>tout-ou-rien</b> : les éditions du fichier courant
     * et celles des fichiers tiers sont VALIDÉES d'abord (confinement au
     * workspace, plages cohérentes) ; si l'une d'elles est invalide, rien
     * n'est appliqué et le rejet est journalisé. Les ResourceOperation
     * (création/renommage/suppression) ne sont pas prises en charge :
     * elles sont refusées et signalées, jamais ignorées en silence.</p>
     *
     * <p>Doit être appelée sur le thread UI (elle mute la session).</p>
     *
     * @return le compte rendu structuré (appliqué / motifs de rejet)
     */
    WorkspaceDiskEdits.Result applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit) {
        return applyWorkspaceEdit(edit, null);
    }

    /**
     * @param versionAtRequest version du document capturée au moment où
     *        la requête à l'origine de cet edit est partie (null = pas
     *        de contrôle). Si le document a changé depuis, les positions
     *        du serveur sont périmées : l'édition est REFUSÉE (voir
     *        {@link LspTextUtils#isStaleEdit}) au lieu de corrompre le
     *        texte.
     */
    WorkspaceDiskEdits.Result applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit,
            Integer versionAtRequest) {
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) {
            android.util.Log.w("LspEditor", "applyWorkspaceEdit: no editor bound");
            return new WorkspaceDiskEdits.Result(false,
                    java.util.Collections.singletonList("aucun éditeur lié"), 0, 0);
        }
        if (edit == null) return new WorkspaceDiskEdits.Result(true, java.util.Collections.emptyList(), 0, 0);
        String currentUri = editor.getFileUri();
        File workspaceRoot = workspaceRoot();

        // ── Phase 0 : fraîcheur du document. Version portée par le
        // WorkspaceEdit (LSP 3.16 documentChanges) OU version capturée à
        // la requête — tout écart avec la version synchronisée courante
        // ⇒ refus net (l'utilisateur est informé par le journal).
        Integer versionPortee = versionDuDocumentCourant(edit, currentUri);
        int versionCourante = editor.getDocumentVersion();
        if (LspTextUtils.isStaleEdit(versionPortee, versionCourante)
                || LspTextUtils.isStaleEdit(versionAtRequest, versionCourante)) {
            String motif = "éditions périmées (document modifié depuis la requête : version "
                    + versionCourante + " ≠ " + (versionPortee != null ? versionPortee : versionAtRequest) + ")";
            android.util.Log.w("LspEditor", "applyWorkspaceEdit REFUSÉ : " + motif);
            return new WorkspaceDiskEdits.Result(false,
                    java.util.Collections.singletonList(motif), 0, 0);
        }

        // ── Phase 1 : sépare éditions du fichier courant / fichiers
        // tiers, en signalant les ResourceOperation refusées.
        List<org.eclipse.lsp4j.TextEdit> currentEdits = new ArrayList<>();
        org.eclipse.lsp4j.WorkspaceEdit diskOnly = new org.eclipse.lsp4j.WorkspaceEdit();
        List<String> rejections = new ArrayList<>();
        collect(edit, currentUri, currentEdits, diskOnly, rejections);
        if (!rejections.isEmpty()) {
            // Une opération refusée = le WorkspaceEdit n'est pas appliqué
            // en entier : rien ne bouge, l'utilisateur est informé.
            android.util.Log.w("LspEditor", "WorkspaceEdit refusé : " + rejections);
            return new WorkspaceDiskEdits.Result(false, rejections, 0, 0);
        }

        // ── Phase 2 : applique les éditions du fichier courant (thread
        // UI) — validation complète + groupe d'annulation unique via
        // SessionEditGroup — AVANT toute écriture disque (même politique
        // tout-ou-rien que les fichiers tiers).
        if (!applySessionEdits(currentEdits)) {
            rejections.add("éditions invalides sur le fichier courant");
            android.util.Log.w("LspEditor", "WorkspaceEdit refusé : " + rejections);
            return new WorkspaceDiskEdits.Result(false, rejections, 0, 0);
        }

        // ── Phase 3 : disque en arrière-plan (confinement + écriture
        // atomique) — la session est déjà appliquée.
        int diskCount = countDiskEdits(diskOnly);
        if (diskCount > 0) {
            applyDiskEditsAsync(diskOnly, workspaceRoot);
            // Le détail du résultat disque arrive de façon asynchrone.
            return new WorkspaceDiskEdits.Result(true,
                    java.util.Collections.emptyList(), 0, diskCount);
        }
        return new WorkspaceDiskEdits.Result(true, java.util.Collections.emptyList(), 0, 0);
    }

    /** Racine du workspace du projet lié à l'éditeur. */
    private File workspaceRoot() {
        LspProject project = editor.getProject();
        String path = project != null ? project.getWorkspacePath() : null;
        return path != null ? new File(path) : null;
    }

    /**
     * Parcourt le WorkspaceEdit (documentChanges LSP 3.16+, repli
     * changes LSP 3.13) : éditions du fichier courant vs fichiers tiers
     * ; les ResourceOperation sont refusées explicitement.
     */
    private void collect(org.eclipse.lsp4j.WorkspaceEdit edit, String currentUri,
            List<org.eclipse.lsp4j.TextEdit> currentEdits,
            org.eclipse.lsp4j.WorkspaceEdit diskOnly, List<String> rejections) {
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.TextDocumentEdit,
                org.eclipse.lsp4j.ResourceOperation>> docChanges = edit.getDocumentChanges();
        if (docChanges != null && !docChanges.isEmpty()) {
            for (org.eclipse.lsp4j.jsonrpc.messages.Either<
                    org.eclipse.lsp4j.TextDocumentEdit,
                    org.eclipse.lsp4j.ResourceOperation> either : docChanges) {
                if (either == null) continue;
                if (either.isRight()) {
                    rejections.add("opération de ressource non prise en charge : "
                            + describeResourceOperation(either.getRight()));
                    continue;
                }
                org.eclipse.lsp4j.TextDocumentEdit tde = either.getLeft();
                if (tde == null || tde.getTextDocument() == null) continue;
                if (currentUri.equals(tde.getTextDocument().getUri())) {
                    addAllSafe(currentEdits, tde.getEdits());
                } else {
                    diskOnly.getChanges().put(tde.getTextDocument().getUri(),
                            copyOf(tde.getEdits()));
                }
            }
            return;
        }
        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> changes =
                edit.getChanges();
        if (changes == null) return;
        for (java.util.Map.Entry<String, java.util.List<org.eclipse.lsp4j.TextEdit>> entry
                : changes.entrySet()) {
            if (currentUri.equals(entry.getKey())) {
                addAllSafe(currentEdits, entry.getValue());
            } else {
                diskOnly.getChanges().put(entry.getKey(), copyOf(entry.getValue()));
            }
        }
    }

    /**
     * Résout les TextEdits LSP en offsets session, les valide puis les
     * applique via {@link SessionEditGroup} : éditions INDIVIDUELLES
     * (pipeline commun — plis/annotations décalés, caret et sélection
     * préservés) regroupées en UNE SEULE étape d'annulation.
     *
     * @return true si appliqué (ou rien à faire) ; false si invalide
     */
    private boolean applySessionEdits(List<org.eclipse.lsp4j.TextEdit> edits) {
        if (edits == null || edits.isEmpty()) return true;
        EditorView editorView = editor.getEditorView();
        jo.codeeditor.session.EditorSession session = editorView.getSession();
        String text = session.getText().toString();
        List<SessionEditGroup.Edit> resolues = new ArrayList<>(edits.size());
        for (org.eclipse.lsp4j.TextEdit te : edits) {
            if (te == null || te.getRange() == null) return false;
            int start = editor.positionToOffset(te.getRange().getStart());
            int end = editor.positionToOffset(te.getRange().getEnd());
            if (start < 0 || end < start || end > text.length()) return false;
            resolues.add(new SessionEditGroup.Edit(start, end, te.getNewText()));
        }
        if (!SessionEditGroup.apply(session, resolues)) return false;
        editorView.notifyTextChanged();
        android.util.Log.i("LspEditor",
                "applyWorkspaceEdit: applied " + edits.size() + " edits (un groupe d'annulation)");
        return true;
    }

    /**
     * Version du document courant portée par le WorkspaceEdit
     * (documentChanges → TextDocumentEdit.textDocument.version),
     * ou null si absente (LSP 3.13).
     */
    private static Integer versionDuDocumentCourant(
            org.eclipse.lsp4j.WorkspaceEdit edit, String currentUri) {
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.TextDocumentEdit,
                org.eclipse.lsp4j.ResourceOperation>> docChanges = edit.getDocumentChanges();
        if (docChanges == null) return null;
        for (org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.TextDocumentEdit,
                org.eclipse.lsp4j.ResourceOperation> either : docChanges) {
            if (either == null || !either.isLeft()) continue;
            org.eclipse.lsp4j.TextDocumentEdit tde = either.getLeft();
            if (tde == null || tde.getTextDocument() == null) continue;
            if (currentUri.equals(tde.getTextDocument().getUri())) {
                return tde.getTextDocument().getVersion();
            }
        }
        return null;
    }

    /**
     * Applique les éditions de fichiers tiers sur le disque, en
     * arrière-plan : confinement au workspace + validation complète +
     * écriture atomique (voir {@link WorkspaceDiskEdits}).
     */
    private void applyDiskEditsAsync(org.eclipse.lsp4j.WorkspaceEdit diskOnly,
            File workspaceRoot) {
        if (workspaceRoot == null) {
            android.util.Log.w("LspEditor",
                    "WorkspaceEdit disque ignoré : racine du workspace inconnue");
            return;
        }
        final org.eclipse.lsp4j.WorkspaceEdit fEdit = diskOnly;
        final File fRoot = workspaceRoot;
        DISK_EDITS_EXECUTOR.execute(() -> {
            WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(fEdit, fRoot);
            if (!r.applied) {
                android.util.Log.w("LspEditor",
                        "WorkspaceEdit disque REFUSÉ : " + r.rejections);
            } else if (r.filesWritten > 0) {
                android.util.Log.i("LspEditor",
                        "WorkspaceEdit disque : " + r.filesWritten + " fichier(s), "
                                + r.editsApplied + " édition(s)");
            }
        });
    }

    private static int countDiskEdits(org.eclipse.lsp4j.WorkspaceEdit edit) {
        int n = 0;
        for (java.util.List<org.eclipse.lsp4j.TextEdit> l : edit.getChanges().values()) {
            n += l != null ? l.size() : 0;
        }
        return n;
    }

    private static void addAllSafe(List<org.eclipse.lsp4j.TextEdit> dst,
            List<org.eclipse.lsp4j.TextEdit> src) {
        if (src != null) dst.addAll(src);
    }

    private static List<org.eclipse.lsp4j.TextEdit> copyOf(List<org.eclipse.lsp4j.TextEdit> src) {
        return src == null ? new ArrayList<>() : new ArrayList<>(src);
    }

    private static String describeResourceOperation(org.eclipse.lsp4j.ResourceOperation op) {
        if (op == null) return "(null)";
        if (op instanceof org.eclipse.lsp4j.CreateFile) return "création";
        if (op instanceof org.eclipse.lsp4j.RenameFile) return "renommage";
        if (op instanceof org.eclipse.lsp4j.DeleteFile) return "suppression";
        return op.getClass().getSimpleName();
    }

    /** Exécuteur dédié aux réécritures disque des fichiers tiers (daemon). */
    private static final ExecutorService DISK_EDITS_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cel-lsp-disk-edits");
                t.setDaemon(true);
                return t;
            });

    /**
     * Applique des éditions LSP ADDITIONNELLES (ex:
     * {@code additionalTextEdits} d'un candidat de complétion — insertion
     * auto-import) sur la session courante.
     *
     * <p>Contrairement à {@link #applyWorkspaceEdit} ce chemin accepte les
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
        applyAdditionalEdits(edits, null);
    }

    void applyAdditionalEdits(java.util.List<org.eclipse.lsp4j.TextEdit> edits,
            Integer versionAtRequest) {
        if (edits == null || edits.isEmpty()) return;
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) return;
        // Contrôle de fraîcheur : l'utilisateur peut avoir tapé entre la
        // réponse de complétion et l'acceptation du candidat.
        if (versionAtRequest != null
                && LspTextUtils.isStaleEdit(versionAtRequest, editor.getDocumentVersion())) {
            android.util.Log.w("LspEditor",
                    "additionalTextEdits refusés (document modifié depuis la complétion)");
            return;
        }
        if (!applySessionEdits(new ArrayList<>(edits))) {
            // additionalTextEdits invalides : refus journalisé —
            // l'insertion de l'identifiant reste correcte.
            android.util.Log.w("LspEditor",
                    "additionalTextEdits refusés (plages invalides)");
        }
    }
}
