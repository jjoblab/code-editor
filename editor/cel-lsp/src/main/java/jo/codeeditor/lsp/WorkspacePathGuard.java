package jo.codeeditor.lsp;

import java.io.File;
import java.io.IOException;

/**
 * Confinement des écritures disque pilotées par le serveur de langage :
 * toute URI reçue d'un {@code WorkspaceEdit} (ou d'un renommage
 * multi-fichiers) doit désigner un chemin STRICTEMENT INTÉRIEUR à la
 * racine du workspace. Un serveur défaillant ou compromis ne doit jamais
 * pouvoir faire réécrire un fichier hors du projet.
 *
 * <p>La vérification est <b>canonique</b> : {@link File#getCanonicalFile()}
 * résout {@code ..} ET les liens symboliques — un symlink du workspace
 * pointant au-dehors est résolu vers sa cible réelle et donc refusé.
 * La frontière est celle des <b>dossiers</b> : {@code /ws-malin/x} ne
 * passe pas pour « dans /ws ».</p>
 */
final class WorkspacePathGuard {

    /** Chemin refusé : hors workspace, URI mal formée ou lien sortant. */
    static final class PathOutsideWorkspaceException extends IOException {
        PathOutsideWorkspaceException(String message) {
            super(message);
        }
    }

    private WorkspacePathGuard() {
        // Classe utilitaire.
    }

    /**
     * Résout l'URI (ou le chemin nu) en fichier canonique et vérifie
     * qu'il vit dans le workspace.
     *
     * @param uri URI LSP ({@code file:///…} encodée) ou chemin nu
     * @param workspaceRoot racine du workspace (dossier existant)
     * @return le fichier canonique, INTÉRIEUR au workspace
     * @throws PathOutsideWorkspaceException si le chemin sort du
     *         workspace, si l'URI est mal formée ou non lisible
     */
    static File resolveInsideWorkspace(String uri, File workspaceRoot)
            throws PathOutsideWorkspaceException {
        File f = UriUtils.toFile(uri);
        if (f == null) {
            throw new PathOutsideWorkspaceException("URI illisible ou non file : " + uri);
        }
        try {
            File root = workspaceRoot.getCanonicalFile();
            File target = f.getCanonicalFile();
            String rootPath = root.getPath();
            String targetPath = target.getPath();
            // Frontière de dossier : le préfixe doit être « racine + séparateur ».
            boolean inside = targetPath.startsWith(rootPath + File.separatorChar);
            if (!inside) {
                throw new PathOutsideWorkspaceException(
                        "chemin hors du workspace : " + targetPath
                                + " (racine : " + rootPath + ")");
            }
            return target;
        } catch (IOException e) {
            // getCanonicalFile a échoué (fichier fantôme, permissions) :
            // par prudence, refus plutôt qu'écriture à l'aveugle.
            throw new PathOutsideWorkspaceException(
                    "chemin non résolvable : " + uri + " (" + e.getMessage() + ")");
        }
    }
}
