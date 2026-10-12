package jo.codeeditor.view;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Voies d'exécution en arrière-plan de la bibliothèque (Lot 7).
 *
 * <p>Deux voies nommées, isolées l'une de l'autre :</p>
 * <ul>
 *   <li><b>features</b> — requêtes interactives non bloquantes pour la
 *       vue : aide de signature, documentation rapide, actions de code,
 *       menu de navigation, renommage, symboles, occurrences du symbole
 *       sous le curseur, indications en ligne, définitions, formatage ;</li>
 *   <li><b>complétion</b> — la mécanique requête/réponse de complétion,
 *       priorité de thread légèrement supérieure (interactive).</li>
 * </ul>
 *
 * <p><b>Capacité : 2 threads par voie.</b> L'ancienne voie mono-thread
 * partagée par tout le processus avait deux défauts : un fournisseur
 * lent d'un éditeur bloquait <i>tous</i> les éditeurs du processus
 * (ordonnancement global), et l'exécuteur était exposé dans l'API
 * publique ({@code ExecutorService} que l'hôte ne contrôle pas). Les
 * voies sont désormais privées, à capacité bornée, en threads daemon
 * nommés ({@code cel-features-*}, {@code cel-completion-*}) : un
 * fournisseur bloqué n'occupe qu'un thread, la voie conserve un thread
 * libre pour les autres éditeurs.</p>
 *
 * <p><b>Cycle de vie :</b> threads daemon, durée de vie = processus.
 * Rien à fermer par l'hôte ; aucune API d'arrêt n'est exposée. Les tâches
 * soumises doivent être courtes ou annulables par elles-mêmes (garde de
 * génération), jamais interrompues de l'extérieur.</p>
 *
 * <p><b>Migration (rupture signalée) :</b> l'ancien champ public
 * {@code EditorPopupManager.FEATURE_EXECUTOR} est retiré — un hôte qui
 * l'utilisait doit soumettre à son propre exécuteur, ou utiliser
 * {@link #submitFeature(Runnable)}/{@link #submitCompletion(Runnable)}.</p>
 */
public final class EditorTaskLanes {

    private static final ExecutorService FEATURES = newLane("cel-features", 0);
    private static final ExecutorService COMPLETION = newLane("cel-completion", 1);

    private EditorTaskLanes() {
        // Point d'accès statique uniquement.
    }

    /** Fabrique une voie bornée à 2 threads daemon nommés. */
    private static ExecutorService newLane(String prefix, int priorityBoost) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + index.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY + priorityBoost);
                return t;
            }
        };
        return Executors.newFixedThreadPool(2, factory);
    }

    /**
     * Soumet une tâche sur la voie « features » (requêtes interactives
     * de la vue). Ne lève jamais d'exception de rejet : la file est non
     * bornée, l'exécution est garantie (éventuellement différée).
     *
     * @param task tâche à exécuter ; doit être courte ou munie d'une
     *             garde de génération pour devenir obsolète d'elle-même
     */
    public static void submitFeature(Runnable task) {
        if (task != null) FEATURES.execute(task);
    }

    /**
     * Soumet une tâche sur la voie « complétion » (priorité interactive,
     * isolée des autres requêtes pour ne jamais être retardée par elles).
     *
     * @param task tâche à exécuter ; doit être munie d'une garde de
     *             génération (une complétion périmée se rend obsolète
     *             d'elle-même à la livraison)
     */
    public static void submitCompletion(Runnable task) {
        if (task != null) COMPLETION.execute(task);
    }
}
