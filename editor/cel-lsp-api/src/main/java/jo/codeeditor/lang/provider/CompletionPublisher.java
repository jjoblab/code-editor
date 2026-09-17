package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.CompletionItem;

import java.util.List;

/**
 * Publisher de complétion thread-safe. Le {@link CompletionProvider} appelle
 * {@link #addItem} depuis son thread de travail ; l'implémentation de
 * l'éditeur regroupe les items par lots et les pousse sur le thread UI.
 */
public interface CompletionPublisher {

    /**
     * Ajoute un item de complétion. Thread-safe.
     */
    void addItem(CompletionItem item);

    /**
     * Ajoute plusieurs items d'un coup. Thread-safe.
     */
    void addItems(List<CompletionItem> items);

    /**
     * Pousse les items en attente vers l'UI. Appelée par le provider quand
     * il a terminé d'ajouter des items. Thread-safe.
     */
    void flush();

    /**
     * Annule la requête de complétion courante. Thread-safe.
     */
    void cancel();

    /**
     * Retourne vrai si la complétion a été annulée.
     */
    boolean isCancelled();
}
