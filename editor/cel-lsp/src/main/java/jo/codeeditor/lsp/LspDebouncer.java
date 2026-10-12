package jo.codeeditor.lsp;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Débounce LSP sur un exécuteur planifié mono-thread <b>daemon</b>.
 *
 * <p>Remplace le « un {@code new Thread} par frappe » de
 * {@code LspDocumentSynchronizer} : la reprogrammation passe par
 * {@code ScheduledFuture.cancel(false)} — <b>jamais</b> d'interruption
 * d'une tâche déjà démarrée (l'ancien {@code interrupt()} pouvait tomber
 * pendant l'envoi {@code didChange}) — et la tâche relit l'état à
 * l'exécution plutôt qu'à la programmation (la valeur la plus récente
 * gagne toujours).</p>
 *
 * <p>L'arrêt est explicite ({@link #shutdown()}) : après fermeture, plus
 * aucun thread « lsp-* » n'est vivant.</p>
 */
final class LspDebouncer {

    private final ScheduledExecutorService scheduler;
    private final long delayMs;
    private ScheduledFuture<?> pending;

    /**
     * @param name    préfixe du nom du thread (« lsp-&lt;name&gt;-N »)
     * @param delayMs fenêtre de débounce (ms)
     */
    LspDebouncer(String name, long delayMs) {
        this.delayMs = delayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lsp-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Programme (ou REprogramme) la tâche : la précédente programmation
     * non démarrée est annulée ; la tâche s'exécutera {@code delayMs}
     * après CE dernier appel, à condition qu'elle n'ait pas été
     * annulée/supplantée d'ici là.
     */
    synchronized void debounce(Runnable task) {
        if (pending != null) {
            pending.cancel(false); // jamais interrupt() : un envoi en
                                   // cours doit finir proprement
        }
        pending = scheduler.schedule(() -> {
            synchronized (LspDebouncer.this) {
                pending = null;
            }
            task.run();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Annule la programmation en cours (la tâche non démarrée ne tirera
     * jamais). Une tâche déjà démarrée termine naturellement.
     */
    synchronized void cancel() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    /**
     * Ferme le débounceur : arrêt du thread daemon, attente bornée des
     * tâches déjà démarrées. Idempotent.
     */
    void shutdown() {
        cancel();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
