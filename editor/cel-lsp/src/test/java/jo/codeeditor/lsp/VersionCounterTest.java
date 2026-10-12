package jo.codeeditor.lsp;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compteur de version de document LSP ({@link VersionCounter}) : la
 * spécification LSP exige un numéro de version STRICTEMENT CROISSANT par
 * didChange. L'ancien champ {@code int documentVersion} était lu,
 * incrémenté et journalisé depuis plusieurs threads (debounce, flush sur
 * l'exécuteur de complétion, didOpen) sans synchronisation : deux envois
 * concurrents pouvaient porter LE MÊME numéro — certains serveurs
 * ignorent alors la mise à jour.
 *
 * <p>Ce test reproduit la course : N threads incrémentent en parallèle
 * et chaque incrément doit obtenir une valeur unique, l'ensemble des
 * valeurs formant exactement 1..N.</p>
 */
class VersionCounterTest {

    @Test
    void incrementsConcurrents_uniquesEtStrictementCroissants() throws Exception {
        final int threads = 8;
        final int parThread = 250;
        final VersionCounter counter = new VersionCounter();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final Set<Integer> vus = java.util.Collections.synchronizedSet(new HashSet<Integer>());
        final AtomicInteger erreurs = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    int precedent = 0;
                    for (int i = 0; i < parThread; i++) {
                        int v = counter.next();
                        if (!vus.add(v)) erreurs.incrementAndGet(); // doublon
                        if (v <= precedent) erreurs.incrementAndGet(); // non croissant localement
                        precedent = v;
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }, "version-racer");
            th.start();
        }
        start.countDown();
        done.await();
        assertEquals(0, erreurs.get(), "doublons ou inversions de version");
        assertEquals(threads * parThread, vus.size(), "chaque incrément doit être unique");
        assertEquals(threads * parThread, counter.get());
    }

    @Test
    void demarrageAZeroEtMonotonie() {
        VersionCounter c = new VersionCounter();
        assertEquals(0, c.get());
        assertEquals(1, c.next());
        assertEquals(1, c.get());
        assertEquals(2, c.next());
        assertEquals(3, c.next());
    }
}
