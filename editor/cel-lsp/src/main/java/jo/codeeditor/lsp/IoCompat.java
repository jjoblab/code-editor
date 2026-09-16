package jo.codeeditor.lsp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * API-24-safe file read/write helpers.
 *
 * <p>v3.34.0 fix (lint NewApi ×12): the previous implementation used
 * {@code java.nio.file.Files.readAllBytes}, {@code File.toPath} (API 26+)
 * and {@code java.nio.file.Path.of} (API 34+) while the library's
 * {@code minSdk} is 24. On Android 7.x (API 24/25) every call site threw
 * {@code NoSuchMethodError} at runtime — the LSP rename / apply-edits-to-disk
 * features crashed instead of degrading gracefully.
 *
 * <p>This helper performs the same work with {@code java.io} streams only,
 * which exist on every API level. Behavior matches
 * {@code Files.readAllBytes} / {@code Files.write} for the cases we care
 * about (UTF-8 text files, whole-file overwrite).
 *
 * @since v3.34.0
 */
final class IoCompat {

    private static final int BUFFER_SIZE = 8192;

    private IoCompat() {
        // Utility class.
    }

    /**
     * Reads the whole file as UTF-8 text. Equivalent to
     * {@code new String(Files.readAllBytes(file.toPath()), UTF_8)} but
     * compatible with API 24+.
     *
     * @return the file content, or {@code null} if the file cannot be read
     */
    static String readUtf8(File file) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(file.length(), 1 << 20));
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = bis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (IOException e) {
            android.util.Log.w("IoCompat", "readUtf8 failed for " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads the whole file at the given (URI or plain) path as UTF-8 text.
     * Strips a leading {@code file://} scheme if present, matching the
     * previous inline logic at the call sites.
     *
     * @return the file content, or {@code null} if unreadable
     */
    static String readUtf8(String uriOrPath) {
        if (uriOrPath == null || uriOrPath.isEmpty()) return null;
        String path = uriOrPath.startsWith("file://")
                ? uriOrPath.substring("file://".length())
                : uriOrPath;
        return readUtf8(new File(path));
    }

    /**
     * Overwrites the file with the given UTF-8 text. Equivalent to
     * {@code Files.write(file.toPath(), text.getBytes(UTF_8))} but
     * compatible with API 24+.
     *
     * @return true on success
     */
    static boolean writeUtf8(File file, String text) {
        if (file == null || text == null) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            android.util.Log.w("IoCompat", "writeUtf8: cannot create parent dir for " + file);
            return false;
        }
        try (FileOutputStream fos = new FileOutputStream(file, false);
             OutputStream out = new BufferedOutputStream(fos, BUFFER_SIZE)) {
            out.write(text.getBytes(Charset.forName("UTF-8")));
            out.flush();
            return true;
        } catch (IOException e) {
            android.util.Log.w("IoCompat", "writeUtf8 failed for " + file + ": " + e.getMessage());
            return false;
        }
    }
}
