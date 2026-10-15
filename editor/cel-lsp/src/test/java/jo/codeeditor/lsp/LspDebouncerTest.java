package jo.codeeditor.lsp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Débounce LSP sur exécuteur unique ({@link LspDebouncer}) :
 *
 * <p>L'ancien code créait un {@code new Thread} PAR FRAPPE (plus une
 * interruption qui pouvait tomber pendant l'envoi), copiait le texte
 * complet sur le thread appelant à chaque frappe, et programmatit le
 * didSave sur un thread NON daemon sans référence conservée. Sous 10
 * frappes/s : création/destruction de threads continue, et des threads
 * non daemon retenaient l'arrêt du processus.</p>
 *
 * <p>Le débounce utilise un {@code ScheduledExecutorService}
 * mono-thread daemon : reprogrammation par {@code cancel(false)} (jamais
 * d'interruption d'un envoi en cours), la tâche relit l'état à
 * l'exécution, et l'arrêt est explicite.</p>
 */
class LspDebouncerTest {

    @Test
    void milleFrappesRapides_auPlusUneExecutionParFenetre() throws Exception {
        LspDebouncer d = new LspDebouncer("test", 60);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch fait = new CountDownLatch(1);
        for (int i = 0; i < 1000; i++) {
            d.debounce(() -> {
                executions.incrementAndGet();
                fait.countDown();
            });
        }
        // La fenêtre s'étire à chaque frappe : UNE exécution au total
        // après le calme.
        assertTrue(fait.await(3, TimeUnit.SECONDS), "la tâche doit finir par tirer");
        Thread.sleep(150); // marge pour d'éventuels doublons interdits
        assertEquals(1, executions.get(),
                "1000 frappes rapprochées doivent produire exactement 1 exécution");
        d.shutdown();
    }

    @Test
    void deuxSalves_deuxExecutionsDansLordre() throws Exception {
        LspDebouncer d = new LspDebouncer("test", 40);
        List<String> ordre = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch deux = new CountDownLatch(2);
        d.debounce(() -> { ordre.add("première"); deux.countDown(); });
        // A-06 : attend (borné) que la première ait tiré avant de relancer.
        jo.codeeditor.AttenteBornee.jusquA(2000, () -> !ordre.isEmpty());
        d.debounce(() -> { ordre.add("deuxième"); deux.countDown(); });
        assertTrue(deux.await(3, TimeUnit.SECONDS));
        assertEquals(java.util.Arrays.asList("première", "deuxième"), ordre,
                "l'ordre des exécutions doit suivre l'ordre des salves");
        d.shutdown();
    }

    @Test
    void annulationAvantFenetre_aucuneExecution() throws Exception {
        LspDebouncer d = new LspDebouncer("test", 150);
        AtomicInteger executions = new AtomicInteger();
        d.debounce(executions::incrementAndGet);
        d.cancel();
        Thread.sleep(300);
        assertEquals(0, executions.get(), "une tâche annulée ne doit jamais tirer");
        d.shutdown();
    }

    @Test
    void apresShutdown_aucunThreadDuDebouncerVivant() throws Exception {
        LspDebouncer d = new LspDebouncer("fuite", 20);
        d.debounce(() -> { });
        d.shutdown();
        // A-06 : attente bornée de l'extinction du thread du débounceur.
        boolean éteint = jo.codeeditor.AttenteBornee.jusquA(3000, 50L, () -> {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().contains("lsp-fuite")) {
                    return false;
                }
            }
            return true;
        });
        assertTrue(éteint, "le thread du débounceur doit s'arrêter avec shutdown()");
    }

    @Test
    void threadDaemon() throws Exception {
        LspDebouncer d = new LspDebouncer("daemon-check", 10);
        try {
            java.util.concurrent.atomic.AtomicBoolean daemon =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            CountDownLatch fait = new CountDownLatch(1);
            d.debounce(() -> {
                daemon.set(Thread.currentThread().isDaemon());
                fait.countDown();
            });
            assertTrue(fait.await(2, TimeUnit.SECONDS));
            assertTrue(daemon.get(), "la tâche doit tourner sur un thread daemon");
        } finally {
            d.shutdown();
        }
    }
}
