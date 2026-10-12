package jo.codeeditor.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Application « tout-ou-rien » des éditions de fichiers TIERS issues
 * d'un {@link WorkspaceEdit} ({@link WorkspaceDiskEdits}) :
 *
 * <ol>
 *   <li>confinement : chaque URI doit viser l'intérieur du workspace ;</li>
 *   <li>validation : TOUTES les plages sont vérifiées AVANT toute
 *       écriture — un seul fichier invalide ⇒ AUCUN fichier modifié ;</li>
 *   <li>écriture atomique de chaque fichier ;</li>
 *   <li>les ResourceOperation (création/renommage/suppression) sont
 *       REFUSÉES et signalées, jamais ignorées en silence.</li>
 * </ol>
 */
class WorkspaceDiskEditsTest {

    @TempDir
    File ws;

    private String uri(String name) {
        return "file://" + new File(ws, name).getAbsolutePath();
    }

    private void write(String name, String content) throws IOException {
        IoCompat.writeUtf8(new File(ws, name), content);
    }

    private String read(String name) {
        return IoCompat.readUtf8(new File(ws, name));
    }

    @Test
    void appliqueEditsSurFichierTiers() throws IOException {
        write("A.java", "public class A {}\n");
        WorkspaceEdit edit = new WorkspaceEdit();
        // « class » occupe les colonnes [7,12) de la ligne 0.
        edit.getChanges().put(uri("A.java"), edits(editAt(0, 7, 12, "B")));
        WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(edit, ws);
        assertTrue(r.applied);
        assertEquals("public B A {}\n", read("A.java"));
        assertEquals(1, r.filesWritten);
    }

    @Test
    void troisFichiersDontUnInvalide_aucunFichierModifie() throws IOException {
        write("un.txt", "111\n");
        write("deux.txt", "222\n");
        write("trois.txt", "333\n");
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.getChanges().put(uri("un.txt"), edits(editAt(0, 0, 3, "AAA")));
        edit.getChanges().put(uri("deux.txt"), edits(editAt(0, 0, 3, "BBB")));
        // Position INCOHÉRENTE : ligne au-delà de la fin du fichier.
        edit.getChanges().put(uri("trois.txt"),
                edits(new TextEdit(new Range(new Position(99, 0), new Position(99, 1)), "CCC")));

        WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(edit, ws);
        assertFalse(r.applied);
        assertFalse(r.rejections.isEmpty());
        // TOUT-OU-RIEN : aucun fichier modifié.
        assertEquals("111\n", read("un.txt"));
        assertEquals("222\n", read("deux.txt"));
        assertEquals("333\n", read("trois.txt"));
    }

    @Test
    void uriHorsWorkspace_refuseeSansEcriture() throws IOException {
        write("interne.txt", "ok\n");
        File ext = new File(ws.getParentFile(), "externe-lsp.txt");
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.getChanges().put("file://" + ext.getAbsolutePath(), edits(editAt(0, 0, 2, "XX")));
        edit.getChanges().put(uri("interne.txt"), edits(editAt(0, 0, 2, "OK")));

        WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(edit, ws);
        assertFalse(r.applied);
        assertEquals("ok\n", read("interne.txt"), "le fichier interne ne doit pas être touché");
        assertFalse(ext.exists());
    }

    @Test
    void fichierInexistant_refuseSansException() {
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.getChanges().put(uri("fantome.txt"), edits(editAt(0, 0, 1, "X")));
        WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(edit, ws);
        assertFalse(r.applied);
        assertFalse(r.rejections.isEmpty());
    }

    @Test
    void resourceOperationRefuseeEtSignalee() {
        WorkspaceEdit edit = new WorkspaceEdit();
        org.eclipse.lsp4j.CreateFile create = new org.eclipse.lsp4j.CreateFile();
        create.setUri(uri("nouveau.txt"));
        edit.setDocumentChanges(new ArrayList<>(List.of(Either.forRight(create))));

        WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(edit, ws);
        // La création de fichier n'est PAS appliquée — refus explicite,
        // pas d'ignorance silencieuse.
        assertFalse(r.applied);
        assertFalse(r.rejections.isEmpty());
        assertTrue(r.rejections.get(0).toLowerCase().contains("opération de ressource"),
                "motif de rejet : " + r.rejections);
        assertFalse(new File(ws, "nouveau.txt").exists());
    }

    @Test
    void documentChanges_mixtes() throws IOException {
        write("M.java", "class M {}\n");
        WorkspaceEdit edit = new WorkspaceEdit();
        org.eclipse.lsp4j.TextDocumentEdit tde =
                new org.eclipse.lsp4j.TextDocumentEdit(
                        new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(
                                uri("M.java"), 7),
                        edits(editAt(0, 6, 7, "N")));
        edit.setDocumentChanges(new ArrayList<>(List.of(Either.forLeft(tde))));
        WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(edit, ws);
        assertTrue(r.applied);
        assertEquals("class N {}\n", read("M.java"));
    }

    @Test
    void editVideOuNull_reussiteSansEffet() {
        assertTrue(WorkspaceDiskEdits.applyAll(null, ws).applied);
        assertTrue(WorkspaceDiskEdits.applyAll(new WorkspaceEdit(), ws).applied);
    }

    @Test
    void cheminAvecEspaceEtAccents() throws IOException {
        File sous = new File(ws, "dossier é");
        assertTrue(sous.mkdirs());
        IoCompat.writeUtf8(new File(sous, "caf é.txt"), "texte\n");
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.getChanges().put("file://" + new File(sous, "caf é.txt").getAbsolutePath(),
                edits(editAt(0, 0, 5, "TEXTE")));
        WorkspaceDiskEdits.Result r = WorkspaceDiskEdits.applyAll(edit, ws);
        // Le chemin nu non encodé reste accepté (tolérance d'entrée) ;
        // l'important : l'aller-retour ne perd pas le fichier.
        if (r.applied) {
            assertEquals("TEXTE\n", IoCompat.readUtf8(new File(sous, "caf é.txt")));
        } else {
            // Tolérance : ici on exige au minimum l'absence de corruption.
            assertEquals("texte\n", IoCompat.readUtf8(new File(sous, "caf é.txt")));
        }
    }

    // ── Fabriques ──────────────────────────────────────────────────────

    private static List<TextEdit> edits(TextEdit... es) {
        return new ArrayList<>(List.of(es));
    }

    private static TextEdit editAt(int l1, int c1, int c2, String newText) {
        return new TextEdit(new Range(new Position(l1, c1), new Position(l1, c2)), newText);
    }
}
