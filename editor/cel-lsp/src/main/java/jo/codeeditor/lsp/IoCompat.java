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
     * Lit tout le fichier au chemin donné (URI encodée ou chemin simple)
     * en texte UTF-8, via {@link UriUtils#toFile} — décodage %XX inclus.
     *
     * @return le contenu du fichier, ou {@code null} si illisible
     */
    static String readUtf8(String uriOrPath) {
        if (uriOrPath == null || uriOrPath.isEmpty()) return null;
        File file = UriUtils.toFile(uriOrPath);
        return file != null ? readUtf8(file) : null;
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

    /**
     * Hook appelé après l'écriture complète du fichier temporaire, juste
     * avant le renommage final — point d'injection des tests de panne.
     */
    interface AtomicWriteListener {
        void onTempWritten(File tempFile) throws IOException;
    }

    /**
     * Écriture ATOMIQUE du texte UTF-8 dans le fichier : le contenu part
     * dans un fichier temporaire DU MÊME DOSSIER (même système de
     * fichiers, renommage atomique), est intégralement écrit, puis
     * {@code FileDescriptor.sync()}-édé AVANT le renommage final.
     *
     * <p>Garanties :</p>
     * <ul>
     *   <li>une panne au milieu de l'écriture ne JAMAIS corrompre le
     *       fichier d'origine (contrairement à
     *       {@link #writeUtf8(File, String)} qui tronque d'abord) ;</li>
     *   <li>le fichier temporaire est supprimé en cas d'échec ;</li>
     *   <li>les permissions du fichier d'origine (lecture seule, etc.)
     *       sont conservées ;</li>
     *   <li>compatible API 24 — {@code Files.move(ATOMIC_MOVE)} exige
     *       l'API 26 et n'apporte rien de plus que {@code File.renameTo}
     *       sur un même dossier.</li>
     * </ul>
     *
     * @param listener hook de test (null en production)
     * @return true si le renommage final a eu lieu
     */
    static boolean writeUtf8Atomically(File file, String text, AtomicWriteListener listener) {
        if (file == null || text == null) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            android.util.Log.w("IoCompat", "writeUtf8Atomically: cannot create parent dir for " + file);
            return false;
        }
        boolean originalLectureSeule = file.exists() && !file.canWrite();
        long originalLastModified = file.exists() ? file.lastModified() : Long.MIN_VALUE;
        File tmp = new File(parent, "." + file.getName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            byte[] bytes = text.getBytes(Charset.forName("UTF-8"));
            try (FileOutputStream fos = new FileOutputStream(tmp, false);
                 OutputStream out = new BufferedOutputStream(fos, BUFFER_SIZE)) {
                out.write(bytes);
                out.flush();
                // Persiste sur le disque AVANT le renommage : en cas de
                // coupure de courant, le contenu renommé est complet.
                fos.getFD().sync();
            }
            if (listener != null) listener.onTempWritten(tmp);
            if (originalLectureSeule) tmp.setReadOnly();
            if (!tmp.renameTo(file)) {
                android.util.Log.w("IoCompat", "writeUtf8Atomically: rename failed for " + file);
                return false;
            }
            if (originalLastModified != Long.MIN_VALUE
                    && originalLastModified > 0) {
                // Best effort — l'horodatage n'est pas une garantie.
                file.setLastModified(originalLastModified);
            }
            return true;
        } catch (IOException e) {
            android.util.Log.w("IoCompat", "writeUtf8Atomically failed for " + file + ": " + e.getMessage());
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }
}
