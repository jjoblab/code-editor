package jo.codeeditor.lsp;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compteur de version de document LSP, thread-safe.
 *
 * <p>La spécification LSP (textDocument/didChange) exige un numéro de
 * version STRICTEMENT CROISSANT. Les envois proviennent de plusieurs
 * threads (debounce didChange, {@code flushPendingChange} sur
 * l'exécuteur de complétion, didOpen) : un simple {@code int} incrémenté
 * sans synchronisation peut produire des numéros dupliqués ou non
 * monotones — certains serveurs ignorent alors les mises à jour.</p>
 */
final class VersionCounter {

    private final AtomicInteger value = new AtomicInteger(0);

    /** Version courante (dernière attribuée). */
    int get() {
        return value.get();
    }

    /**
     * Attribue la prochaine version — strictement croissante, jamais
     * dupliquée, quel que soit le thread appelant.
     */
    int next() {
        return value.incrementAndGet();
    }
}
