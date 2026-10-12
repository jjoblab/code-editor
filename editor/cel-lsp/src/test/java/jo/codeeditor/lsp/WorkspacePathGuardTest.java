package jo.codeeditor.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Confinement des écritures disque issues du serveur LSP :
 * {@link WorkspacePathGuard} refuse tout chemin qui sort de la racine du
 * workspace — {@code ..}, chemin absolu externe ou lien symbolique
 * pointant au-dehors. Un serveur défaillant ou compromis ne doit jamais
 * pouvoir faire réécrire un fichier hors du projet.
 */
class WorkspacePathGuardTest {

    @TempDir
    File ws;

    @TempDir
    File hors;

    @Test
    void accepteFichierDansLeWorkspace() throws IOException {
        File f = WorkspacePathGuard.resolveInsideWorkspace(
                "file://" + ws.getAbsolutePath() + "/Main.java", ws);
        assertEquals(new File(ws, "Main.java").getCanonicalPath(), f.getCanonicalPath());
    }

    @Test
    void accepteSousDossierProfond() throws IOException {
        File d = new File(ws, "src/main/java");
        assertTrue(d.mkdirs());
        File f = WorkspacePathGuard.resolveInsideWorkspace(
                "file://" + ws.getAbsolutePath() + "/src/main/java/A.java", ws);
        assertEquals(new File(d, "A.java").getCanonicalPath(), f.getCanonicalPath());
    }

    @Test
    void refuseDotDotSortant() {
        assertThrows(WorkspacePathGuard.PathOutsideWorkspaceException.class, () ->
                WorkspacePathGuard.resolveInsideWorkspace(
                        "file://" + ws.getAbsolutePath() + "/../escape.txt", ws));
    }

    @Test
    void refuseCheminAbsoluExterne() {
        assertThrows(WorkspacePathGuard.PathOutsideWorkspaceException.class, () ->
                WorkspacePathGuard.resolveInsideWorkspace(
                        "file:///etc/passwd", ws));
    }

    @Test
    void refusePrefixeDeNomEtNonDeDossier() {
        // « /ws-malin/x » ne doit PAS passer pour « dans /ws » : la véri-
        // fication se fait sur la frontière de dossier, pas par préfixe de
        // chaîne.
        File voisin = new File(ws.getParentFile(), ws.getName() + "-malin");
        String uri = "file://" + voisin.getAbsolutePath() + "/x.txt";
        assertThrows(WorkspacePathGuard.PathOutsideWorkspaceException.class, () ->
                WorkspacePathGuard.resolveInsideWorkspace(uri, ws));
    }

    @Test
    void refuseLienSymboliqueSortant() throws IOException {
        Path cible = hors.toPath().resolve("secret.txt");
        Files.write(cible, "données".getBytes("UTF-8"));
        Path lien = ws.toPath().resolve("lien");
        Files.createSymbolicLink(lien, cible);
        assertThrows(WorkspacePathGuard.PathOutsideWorkspaceException.class, () ->
                WorkspacePathGuard.resolveInsideWorkspace(
                        "file://" + ws.getAbsolutePath() + "/lien", ws));
    }

    @Test
    void refuseUriMalFormee() {
        assertThrows(WorkspacePathGuard.PathOutsideWorkspaceException.class, () ->
                WorkspacePathGuard.resolveInsideWorkspace("http://x/y", ws));
        assertThrows(WorkspacePathGuard.PathOutsideWorkspaceException.class, () ->
                WorkspacePathGuard.resolveInsideWorkspace(null, ws));
    }
}
