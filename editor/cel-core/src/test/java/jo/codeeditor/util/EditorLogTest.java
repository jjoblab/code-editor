package jo.codeeditor.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Journal de la bibliothèque ({@link EditorLog}/{@link EditorLogger}) :
 * inerte par défaut (aucune écriture, aucune exception), installable,
 * thread-safe, et les raccourcis délèguent bien à l'implémentation.
 */
class EditorLogTest {

    /** Implémentation témoin qui enregistre tout. */
    private static final class Recording implements EditorLogger {
        final List<String> events = new ArrayList<>();

        @Override public void info(String tag, String message) {
            events.add("info:" + tag + ":" + message);
        }
        @Override public void warn(String tag, String message) {
            events.add("warn:" + tag + ":" + message);
        }
        @Override public void warn(String tag, String message, Throwable cause) {
            events.add("warn:" + tag + ":" + message + ":" + cause);
        }
        @Override public void error(String tag, String message, Throwable cause) {
            events.add("error:" + tag + ":" + message + ":" + cause);
        }
    }

    @AfterEach
    void reinitialise() {
        EditorLog.install(null);
    }

    @Test
    void inerteParDefaut_aucuneException() {
        // Aucune implémentation installée : les appels doivent être des
        // no-ops silencieux (modules purs en usage embarqué).
        assertDoesNotThrow(() -> {
            EditorLog.get().info("t", "m");
            EditorLog.get().warn("t", "m");
            EditorLog.get().warn("t", "m", new RuntimeException());
            EditorLog.get().error("t", "m", new RuntimeException());
            EditorLog.info("t", "m");
            EditorLog.warn("t", "m");
            EditorLog.warn("t", "m", new RuntimeException());
            EditorLog.error("t", "m", new RuntimeException());
        });
        assertSame(EditorLog.NOOP, EditorLog.get());
    }

    @Test
    void installationEtRaccourcis() {
        Recording rec = new Recording();
        EditorLog.install(rec);
        assertSame(rec, EditorLog.get());
        EditorLog.info("Tag1", "message info");
        EditorLog.warn("Tag2", "message warn");
        EditorLog.warn("Tag3", "warn cause", new IllegalStateException("x"));
        EditorLog.error("Tag4", "error", new RuntimeException("y"));
        assertEquals(4, rec.events.size());
        assertTrue(rec.events.get(0).startsWith("info:Tag1:message info"));
        assertTrue(rec.events.get(1).startsWith("warn:Tag2:message warn"));
        assertTrue(rec.events.get(2).startsWith("warn:Tag3:warn cause:java.lang.IllegalStateException"));
        assertTrue(rec.events.get(3).startsWith("error:Tag4:error:java.lang.RuntimeException"));
    }

    @Test
    void installationNull_retourneInerte() {
        EditorLog.install(new Recording());
        EditorLog.install(null);
        assertSame(EditorLog.NOOP, EditorLog.get());
    }

    @Test
    void appelantsConcurrents_sansAnomalie() throws Exception {
        EditorLog.install(new Recording());
        Runnable batterie = () -> {
            for (int i = 0; i < 1000; i++) {
                EditorLog.warn("conc", "m" + i);
            }
        };
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(batterie, "log-racer-" + i);
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        // Aucune exception : le contrat est la sûreté d'exécution
        // concurrente des appels.
    }
}
