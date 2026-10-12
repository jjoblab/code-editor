package jo.codeeditor.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Écriture disque ATOMIQUE ({@code IoCompat.writeUtf8Atomically}) :
 * le nouveau contenu part dans un fichier temporaire DU MÊME DOSSIER,
 * est intégralement écrit puis {@code fsync}-édi avant le renommage
 * final. Un crash (ou un flux qui échoue) au milieu de l'écriture ne
 * JAMAIS corrompre le fichier d'origine : l'ancien code tronquait
 * d'abord (FileOutputStream(file, false)) et laissait un fichier vide
 * ou partiel en cas d'échec.
 */
class AtomicFileWriterTest {

    @TempDir
    File tmp;

    /** Hook injectant un échec après l'écriture complète du temporaire. */
    private static final class CrashApresTemporaire implements IoCompat.AtomicWriteListener {
        boolean appele;
        @Override
        public void onTempWritten(File tempFile) throws IOException {
            appele = true;
            throw new IOException("crash simulé après écriture du temporaire");
        }
    }

    @Test
    void succes_remplaceContenuEtNettoieTemporaire() throws IOException {
        File f = new File(tmp, "a.txt");
        assertTrue(IoCompat.writeUtf8(f, "ancien"));
        assertTrue(IoCompat.writeUtf8Atomically(f, "nouveau contenu é", null));
        assertEquals("nouveau contenu é", IoCompat.readUtf8(f));
        // Aucun temporaire orphelin.
        for (String n : tmp.list()) assertFalse(n.contains(".tmp"), "temporaire restant : " + n);
    }

    @Test
    void succes_creeFichierInexistant() throws IOException {
        File f = new File(tmp, "nouveau.txt");
        assertTrue(IoCompat.writeUtf8Atomically(f, "créé", null));
        assertEquals("créé", IoCompat.readUtf8(f));
    }

    @Test
    void crashEnCoursDEcriture_originalIntact() throws IOException {
        File f = new File(tmp, "precieux.txt");
        String original = "DONNÉES ORIGINALES — ne pas perdre";
        assertTrue(IoCompat.writeUtf8(f, original));

        CrashApresTemporaire crash = new CrashApresTemporaire();
        assertFalse(IoCompat.writeUtf8Atomically(f, "contenu partiel", crash));
        assertTrue(crash.appele, "le hook doit être appelé");

        // Le fichier d'origine est INTACT : la panne n'a touché que le
        // temporaire, jamais renommé.
        assertEquals(original, IoCompat.readUtf8(f));
        for (String n : tmp.list()) {
            assertFalse(n.contains(".tmp"), "temporaire restant : " + n);
        }
    }

    @Test
    void crashAvantEcriture_originalIntact() {
        File f = new File(tmp, "b.txt");
        assertTrue(IoCompat.writeUtf8(f, "stable"));
        // Dossier parent non inscriptible : impossible de créer le
        // temporaire → échec propre, original intact.
        File ro = new File(tmp, "ro");
        assertTrue(ro.mkdir());
        assertTrue(ro.setWritable(false));
        try {
            File cible = new File(ro, "c.txt");
            assertFalse(IoCompat.writeUtf8Atomically(cible, "x", null));
            assertFalse(cible.exists());
        } finally {
            ro.setWritable(true); // nettoyage
        }
    }

    @Test
    void permissionsConservees() throws IOException {
        File f = new File(tmp, "perm.txt");
        assertTrue(IoCompat.writeUtf8(f, "a"));
        boolean lisible = f.canRead();
        boolean inscriptible = f.canWrite();
        assertTrue(f.setReadOnly());
        try {
            // Le renommage remplace l'inode : les permissions du fichier
            // d'origine doivent être réappliquées après écriture.
            assertTrue(IoCompat.writeUtf8Atomically(f, "b", null));
            assertEquals("b", IoCompat.readUtf8(f));
            assertFalse(f.canWrite(), "read-only doit être conservé");
        } finally {
            f.setWritable(inscriptible || true);
            if (!lisible) f.setReadable(false);
        }
    }
}
