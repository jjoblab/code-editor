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
 * Aides de lecture/écriture de fichiers compatibles API 24.
 *
 * <p>{@code java.nio.file.Files.readAllBytes}, {@code File.toPath} (API 26+)
 * et {@code java.nio.file.Path.of} (API 34+) sont indisponibles alors que le
 * {@code minSdk} de la bibliothèque est 24 : sur Android 7.x (API 24/25),
 * chaque point d'appel levait {@code NoSuchMethodError} à l'exécution —
 * les fonctionnalités LSP de renommage et d'application d'éditions sur
 * disque plantaient au lieu de se dégrader proprement.
 *
 * <p>Cette aide réalise le même travail uniquement avec des flux
 * {@code java.io}, présents à tous les niveaux d'API. Le comportement
 * correspond à {@code Files.readAllBytes} / {@code Files.write} pour les
 * cas utiles ici (fichiers texte UTF-8, écrasement complet de fichier).
 */
final class IoCompat {

    private static final int BUFFER_SIZE = 8192;

    private IoCompat() {
        // Classe utilitaire.
    }

    /**
     * Lit tout le fichier en texte UTF-8. Équivalent à
     * {@code new String(Files.readAllBytes(file.toPath()), UTF_8)} mais
     * compatible API 24+.
     *
     * @return le contenu du fichier, ou {@code null} si la lecture échoue
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
     * Lit tout le fichier au chemin donné (URI ou chemin simple) en texte
     * UTF-8. Retire un éventuel schéma {@code file://} en tête, comme
     * l'attendent les points d'appel.
     *
     * @return le contenu du fichier, ou {@code null} si illisible
     */
    static String readUtf8(String uriOrPath) {
        if (uriOrPath == null || uriOrPath.isEmpty()) return null;
        String path = uriOrPath.startsWith("file://")
                ? uriOrPath.substring("file://".length())
                : uriOrPath;
        return readUtf8(new File(path));
    }

    /**
     * Écrase le fichier avec le texte UTF-8 donné. Équivalent à
     * {@code Files.write(file.toPath(), text.getBytes(UTF_8))} mais
     * compatible API 24+.
     *
     * @return true en cas de succès
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
