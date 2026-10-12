package jo.codeeditor.lsp;

import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Tests de régression — IO fichiers compatible API 24.
 *
 * <p>Les API {@code java.nio.file.Files.readAllBytes}, {@code File.toPath}
 * (API 26+) et {@code Path.of} (API 34+) sont inutilisables avec
 * {@code minSdk 24} : chaque appel LSP de renommage / d'application
 * d'éditions sur disque plantait avec {@code NoSuchMethodError} sur
 * Android 7.x. {@link IoCompat} les remplace par des flux {@code java.io}
 * simples ; ces tests épinglent le comportement observable (aller-retour,
 * retrait de file://, null si illisible, création des répertoires parents,
 * sémantique d'écrasement).</p>
 *
 * <p>JUnit 4 (convention du module Robolectric) avec le android.jar
 * simulable par défaut ({@code isReturnDefaultValues = true}) — les appels
 * à {@code android.util.Log} dans les chemins d'échec d'IoCompat
 * retournent 0 et ne lèvent jamais.</p>
 */
public class IoCompatTest {

    @org.junit.Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void roundTrip_utf8Content() throws IOException {
        File f = tmp.newFile("a.txt");
        String content = "public class A { /* éàü — 中文 🎉 */ }";
        assertTrue(IoCompat.writeUtf8(f, content));
        assertEquals(content, IoCompat.readUtf8(f));
    }

    @Test
    public void readUtf8_stripsFileScheme() throws IOException {
        File f = tmp.newFile("b.txt");
        assertTrue(IoCompat.writeUtf8(f, "hello"));
        // Une URI file:// désigne le même fichier.
        assertEquals("hello", IoCompat.readUtf8("file://" + f.getAbsolutePath()));
        assertEquals("hello", IoCompat.readUtf8(f.getAbsolutePath()));
    }

    @Test
    public void readUtf8_missingFile_returnsNull() {
        assertNull(IoCompat.readUtf8(new File(tmp.getRoot(), "does-not-exist.txt")));
        assertNull(IoCompat.readUtf8((File) null));
        assertNull(IoCompat.readUtf8((String) null));
        assertNull(IoCompat.readUtf8(""));
    }

    @Test
    public void readUtf8_directory_returnsNull() {
        assertNull("a directory is not a readable text file", IoCompat.readUtf8(tmp.getRoot()));
    }

    @Test
    public void writeUtf8_createsParentDirectories() {
        File f = new File(tmp.getRoot(), "deep/nested/dir/c.txt");
        assertTrue(IoCompat.writeUtf8(f, "nested"));
        assertEquals("nested", IoCompat.readUtf8(f));
    }

    @Test
    public void writeUtf8_overwritesExistingContent() throws IOException {
        File f = tmp.newFile("d.txt");
        assertTrue(IoCompat.writeUtf8(f, "first version"));
        assertTrue(IoCompat.writeUtf8(f, "second"));
        assertEquals("second", IoCompat.readUtf8(f));
    }

    @Test
    public void writeUtf8_nullArguments_returnFalse() {
        assertFalse(IoCompat.writeUtf8(null, "x"));
        assertFalse(IoCompat.writeUtf8(new File("x"), null));
    }

    @Test
    public void largeRoundTrip_isByteExact() throws IOException {
        // ~1 Mo — exerce la boucle bufferisée sur de nombreux blocs de 8 Ko.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            sb.append("ligne numéro ").append(i).append(" — content non-ASCII àü\n");
        }
        File f = tmp.newFile("big.txt");
        assertTrue(IoCompat.writeUtf8(f, sb.toString()));
        String back = IoCompat.readUtf8(f);
        assertEquals(sb.length(), back.length());
        assertEquals(sb.toString(), back);
    }
}
