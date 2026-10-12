package jo.codeeditor.view;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Voies d'exécution {@link EditorTaskLanes} (Lot 7) : capacité bornée à
 * 2 threads par voie, isolation de la voie complétion, threads daemon
 * nommés.
 *
 * <p>L'ancienne voie « features » mono-thread partagée par tout le
 * processus laissait un fournisseur lent d'un éditeur bloquer TOUS les
 * éditeurs. La voie à 2 threads garde toujours un thread libre pour les
 * autres tâches ; la complétion vit sur sa propre voie et ne peut jamais
 * attendre derrière la voie features.</p>
 */
public class EditorTaskLanesTest {

    /** Tâche qui bloque jusqu'à l'ouverture du loquet. */
    private static Runnable bloque(CountDownLatch loquet) {
        return () -> {
            try {
                loquet.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test(timeout = 10_000)
    public void fournisseurBloque_neRetardePasUneAutreTache() throws Exception {
        CountDownLatch blocage = new CountDownLatch(1);
        CountDownLatch fait = new CountDownLatch(1);
        // Un fournisseur défaillant/lent occupe UN thread de la voie…
        EditorTaskLanes.submitFeature(bloque(blocage));
        // …la tâche suivante doit passer sur le second thread.
        EditorTaskLanes.submitFeature(fait::countDown);
        try {
            assertTrue("la voie à 2 threads doit exécuter la seconde tâche "
                    + "malgré le blocage de la première",
                    fait.await(2, TimeUnit.SECONDS));
        } finally {
            blocage.countDown();
        }
    }

    @Test(timeout = 10_000)
    public void voieCompletionIsoleeDeLaVoieFeatures() throws Exception {
        // Occupe TOUTE la voie features (2 threads bloqués).
        CountDownLatch b1 = new CountDownLatch(1);
        CountDownLatch b2 = new CountDownLatch(1);
        CountDownLatch fait = new CountDownLatch(1);
        EditorTaskLanes.submitFeature(bloque(b1));
        EditorTaskLanes.submitFeature(bloque(b2));
        try {
            EditorTaskLanes.submitCompletion(fait::countDown);
            assertTrue("la complétion ne doit jamais attendre derrière "
                    + "la voie features (voie dédiée)",
                    fait.await(2, TimeUnit.SECONDS));
        } finally {
            b1.countDown();
            b2.countDown();
        }
    }

    @Test(timeout = 10_000)
    public void threadsDaemonEtNommes() throws Exception {
        CountDownLatch features = new CountDownLatch(1);
        CountDownLatch completion = new CountDownLatch(1);
        EditorTaskLanes.submitFeature(features::countDown);
        EditorTaskLanes.submitCompletion(completion::countDown);
        assertTrue(features.await(2, TimeUnit.SECONDS));
        assertTrue(completion.await(2, TimeUnit.SECONDS));

        Thread[] threads = new Thread[Thread.activeCount() + 16];
        int n = Thread.enumerate(threads);
        boolean voieFeatures = false, voieCompletion = false;
        for (int i = 0; i < n; i++) {
            if (!threads[i].isAlive()) continue;
            String name = threads[i].getName();
            if (name.startsWith("cel-features-")) {
                voieFeatures = true;
                assertTrue("les threads de voie doivent être daemon",
                        threads[i].isDaemon());
            }
            if (name.startsWith("cel-completion-")) {
                voieCompletion = true;
                assertTrue("les threads de voie doivent être daemon",
                        threads[i].isDaemon());
            }
        }
        assertTrue("un thread cel-features-* doit exister", voieFeatures);
        assertTrue("un thread cel-completion-* doit exister", voieCompletion);
    }
}
