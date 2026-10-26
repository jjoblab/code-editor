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

    /**
     * Signale si la liste en cours de publication est TRONQUÉE par le
     * fournisseur (le backend a plafonné le nombre de candidats renvoyés
     * et pourrait en produire d'autres pour le même token). Un client qui
     * honore ce drapeau sait que son filtrage local travaille sur une
     * base partielle : un candidat écarté par le plafond ne peut jamais
     * réapparaître en filtrant davantage.
     *
     * <p>Sémantique : « faux » (ou absence d'appel) = liste réputée
     * complète, le client peut filtrer localement sans re-requête.
     * L'appel est idempotent ; à effectuer avant {@link #flush()} pour
     * que le drapeau voyage avec le lot.</p>
     *
     * <p>Implémentation par défaut : no-op — les fournisseurs existants
     * (qui ne l'appellent pas) publient des listes réputées complètes,
     * aucun comportement ne change pour eux.</p>
     *
     * @param incomplete vrai si la liste publiée est tronquée
     * @since 4.3.0
     */
    default void setIncomplete(boolean incomplete) {
    }
}
