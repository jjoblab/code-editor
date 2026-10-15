package jo.codeeditor.lsp;

import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Application des éditions de fichiers TIERS d'un {@link WorkspaceEdit}
 * sur le disque, avec confinement au workspace et sémantique
 * <b>tout-ou-rien</b>.
 *
 * <p>Les données d'un serveur de langage sont traitées comme NON
 * FIABLES : chaque URI est confinée à la racine du workspace
 * ({@link WorkspacePathGuard}), toutes les plages sont validées AVANT
 * toute écriture, puis chaque fichier est réécrit de façon ATOMIQUE
 * ({@code IoCompat.writeUtf8Atomically}). Un seul fichier invalide
 * ⇒ AUCUN fichier modifié.</p>
 *
 * <p>Les {@code ResourceOperation} (création/renommage/suppression de
 * fichiers) ne sont pas implémentées : elles sont REFUSÉES et signalées
 * dans le résultat — jamais ignorées en silence.</p>
 */
final class WorkspaceDiskEdits {

    /** Compte rendu structuré d'une application d'éditions disque. */
    static final class Result {
        /** true ssi tout ce qui devait être écrit l'a été. */
        final boolean applied;
        /** Motifs de rejet (un par fichier/opération refusée), journalisables. */
        final List<String> rejections;
        /** Nombre de fichiers réellement réécrits. */
        final int filesWritten;
        /** Nombre d'éditions appliquées au total. */
        final int editsApplied;

        Result(boolean applied, List<String> rejections, int filesWritten, int editsApplied) {
            this.applied = applied;
            this.rejections = rejections;
            this.filesWritten = filesWritten;
            this.editsApplied = editsApplied;
        }
    }

    private WorkspaceDiskEdits() {
        // Classe utilitaire.
    }

    /**
     * Applique TOUTES les éditions de fichiers tiers du WorkspaceEdit,
     * tout-ou-rien : phase de validation complète (confinement + lecture
     * + plages), puis phase d'écriture atomique.
     *
     * @param edit le WorkspaceEdit reçu du serveur (null/VIDE = succès
     *             sans effet)
     * @param workspaceRoot racine du workspace (répertoire du projet)
     * @return le compte rendu structuré
     */
    static Result applyAll(WorkspaceEdit edit, File workspaceRoot) {
        List<String> rejections = new ArrayList<>();
        if (edit == null) return new Result(true, rejections, 0, 0);

        // ── Collecte des éditions disque (URI → éditions), en refusant
        // explicitement les ResourceOperation.
        Map<File, List<TextEdit>> diskEdits = new TreeMap<>();
        int totalEdits = collectDiskEdits(edit, workspaceRoot, diskEdits, rejections);
        if (!rejections.isEmpty()) {
            // Tout-ou-rien STRICT : une URI hors workspace ou une
            // opération de ressource refusée ⇒ AUCUN fichier écrit.
            jo.codeeditor.util.EditorLog.warn("WorkspaceDiskEdits",
                    "WorkspaceEdit refusé (confinement) : " + rejections);
            return new Result(false, rejections, 0, 0);
        }
        if (diskEdits.isEmpty()) {
            return new Result(true, rejections, 0, 0);
        }

        // ── Phase 1 : validation complète — existence, lisibilité, plages
        // cohérentes. AUCUNE écriture dans cette phase.
        Map<File, String> nouveauxContenus = new TreeMap<>();
        for (Map.Entry<File, List<TextEdit>> e : diskEdits.entrySet()) {
            File file = e.getKey();
            if (!file.isFile()) {
                rejections.add("fichier introuvable dans le workspace : " + file);
                continue;
            }
            String contenu = IoCompat.readUtf8(file);
            if (contenu == null) {
                rejections.add("fichier illisible : " + file);
                continue;
            }
            String nouveau = LspTextUtils.applyEdits(contenu, e.getValue());
            if (nouveau == null) {
                rejections.add("éditions invalides (plage ou chevauchement) : " + file);
                continue;
            }
            nouveauxContenus.put(file, nouveau);
        }
        if (nouveauxContenus.size() != diskEdits.size()) {
            // Tout-ou-rien : une validation en échec → RIEN n'est écrit.
            jo.codeeditor.util.EditorLog.warn("WorkspaceDiskEdits",
                    "WorkspaceEdit refusé (tout-ou-rien) : " + rejections);
            return new Result(false, rejections, 0, 0);
        }

        // ── Phase 2 : écriture atomique de chaque fichier validé.
        int ecrits = 0;
        for (Map.Entry<File, String> e : nouveauxContenus.entrySet()) {
            if (IoCompat.writeUtf8Atomically(e.getKey(), e.getValue(), null)) {
                ecrits++;
            } else {
                rejections.add("écriture échouée : " + e.getKey());
            }
        }
        boolean ok = ecrits == nouveauxContenus.size();
        return new Result(ok, rejections, ecrits, ok ? totalEdits : 0);
    }

    /**
     * Extrait du WorkspaceEdit les éditions par URI, confine chaque URI
     * au workspace et signale les ResourceOperation refusées.
     *
     * <p>Préfère {@code documentChanges} (LSP 3.16+) ; repli sur
     * {@code changes} (LSP 3.13). L'appelant filtre l'URI du fichier
     * courant AVANT d'invoquer cette classe.</p>
     *
     * @param diskEdits receptacle : fichier canonique → éditions
     * @return le nombre total d'éditions collectées
     */
    private static int collectDiskEdits(WorkspaceEdit edit, File workspaceRoot,
            Map<File, List<TextEdit>> diskEdits, List<String> rejections) {
        int total = 0;
        List<Either<TextDocumentEdit, org.eclipse.lsp4j.ResourceOperation>> docChanges =
                edit.getDocumentChanges();
        if (docChanges != null && !docChanges.isEmpty()) {
            for (Either<TextDocumentEdit, org.eclipse.lsp4j.ResourceOperation> either : docChanges) {
                if (either == null) continue;
                if (either.isRight()) {
                    // Refus explicite — jamais d'ignorance silencieuse.
                    rejections.add("opération de ressource non prise en charge : "
                            + describe(either.getRight()));
                    continue;
                }
                TextDocumentEdit tde = either.getLeft();
                if (tde == null || tde.getTextDocument() == null) continue;
                if (addDiskEdits(tde.getTextDocument().getUri(), tde.getEdits(),
                        workspaceRoot, diskEdits, rejections)) {
                    total += tde.getEdits() != null ? tde.getEdits().size() : 0;
                }
            }
            return total;
        }
        Map<String, List<TextEdit>> changes = edit.getChanges();
        if (changes != null) {
            for (Map.Entry<String, List<TextEdit>> e : changes.entrySet()) {
                if (addDiskEdits(e.getKey(), e.getValue(), workspaceRoot, diskEdits, rejections)) {
                    total += e.getValue() != null ? e.getValue().size() : 0;
                }
            }
        }
        return total;
    }

    /** Confinement + accumulation des éditions d'une URI. */
    private static boolean addDiskEdits(String uri, List<TextEdit> edits, File workspaceRoot,
            Map<File, List<TextEdit>> diskEdits, List<String> rejections) {
        if (uri == null || edits == null || edits.isEmpty()) return false;
        File file;
        try {
            file = WorkspacePathGuard.resolveInsideWorkspace(uri, workspaceRoot);
        } catch (WorkspacePathGuard.PathOutsideWorkspaceException e) {
            rejections.add(e.getMessage());
            return false;
        }
        List<TextEdit> existantes = diskEdits.get(file);
        if (existantes == null) {
            diskEdits.put(file, new ArrayList<>(edits));
        } else {
            existantes.addAll(edits); // même fichier référencé deux fois
        }
        return true;
    }

    private static String describe(org.eclipse.lsp4j.ResourceOperation op) {
        if (op == null) return "(null)";
        if (op instanceof org.eclipse.lsp4j.CreateFile) return "création";
        if (op instanceof org.eclipse.lsp4j.RenameFile) return "renommage";
        if (op instanceof org.eclipse.lsp4j.DeleteFile) return "suppression";
        return op.getClass().getSimpleName();
    }
}
