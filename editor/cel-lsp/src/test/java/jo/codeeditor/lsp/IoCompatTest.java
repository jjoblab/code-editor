package jo.codeeditor.lsp;

import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * v3.34.0 regression tests — API-24-safe file IO.
 *
 * <p>The previous implementation used {@code java.nio.file.Files.readAllBytes},
 * {@code File.toPath} (API 26+) and {@code Path.of} (API 34+) with
 * {@code minSdk 24}: every LSP rename / apply-edits-to-disk call crashed
 * with {@code NoSuchMethodError} on Android 7.x. {@link IoCompat} replaces
 * them with plain {@code java.io} streams; these tests pin the observable
 * behavior (round-trip, file:// stripping, null on unreadable, parent-dir
 * creation, overwrite semantics).</p>
 *
 * <p>JUnit 4 (Robolectric module convention) with the default mockable
 * android.jar ({@code isReturnDefaultValues = true}) — {@code android.util.Log}
 * calls in IoCompat's failure paths return 0 and never throw.</p>
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
        // A file:// URI resolves to the same file.
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
        // ~1 MB — exercises the buffered loop across many 8 KB chunks.
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
