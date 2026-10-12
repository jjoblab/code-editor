package jo.codeeditor.lang.provider;


import java.util.List;

/**
 * Fournit les items de complétion automatique.
 *
 * <p>Contrat : l'éditeur appelle {@link #complete} sur un thread de travail
 * dédié, après un court debounce (caractère déclencheur tapé, raccourci de
 * complétion ou nouveau token). L'appel peut bloquer le temps d'une requête
 * au moteur de langage ; la livraison est « latest wins » — si le caret a
 * quitté le token demandé à l'arrivée de la réponse, les items sont
 * ignorés.</p>
 *
 * <p>L'implémentation publie ses items via le {@link CompletionPublisher}
 * fourni, qui est thread-safe : appeler {@code publisher.addItem(...)} pour
 * chaque item, puis {@code publisher.flush()} en fin de requête. Les
 * exceptions levées sont interceptées par l'éditeur.</p>
 */
public interface CompletionProvider {

    /**
     * Demande les items de complétion pour la position du caret.
     *
     * @param text      le texte complet du document (instantané)
     * @param caret     l'offset du caret (indice de caractère, 0-based)
     * @param publisher le publisher thread-safe — appeler
     *                  {@code publisher.addItem(...)} pour chaque item, puis
     *                  {@code publisher.flush()} quand c'est terminé
     */
    void complete(CharSequence text, int caret, CompletionPublisher publisher);

    /**
     * Variante de {@link #complete} informant du caractère déclencheur.
     *
     * <p>Le modèle « Android Studio » distingue la complétion auto-déclenchée
     * par la frappe d'un caractère déclencheur (ex. {@code "."} → membres
     * d'une instance) de l'invocation explicite (raccourci, menu). Le
     * protocole LSP transporte cette distinction dans le
     * {@code CompletionContext} ({@code triggerKind} =
     * {@code TriggerCharacter} + caractère, sinon {@code Invoked}) : certains
     * serveurs s'en servent pour affiner radicalement les propositions
     * (membres vs tout l'univers lexical).</p>
     *
     * <p>Implémentation par défaut : l'information est ignorée (délègue à
     * {@link #complete}) — les fournisseurs existants ne changent pas.</p>
     *
     * @param text             le texte complet du document (instantané)
     * @param caret            l'offset du caret (indice de caractère, 0-based)
     * @param triggerCharacter le caractère (premier caractère de la chaîne)
     *                         qui a auto-déclenché la complétion, ou
     *                         {@code null} pour une invocation explicite
     * @param publisher        le publisher thread-safe
     * @since 3.51.0
     */
    default void complete(CharSequence text, int caret, String triggerCharacter,
                          CompletionPublisher publisher) {
        complete(text, caret, publisher);
    }

    /**
     * Retourne les caractères qui déclenchent automatiquement la complétion
     * (ex. {@code "."}, {@code "@"}, {@code "#"}). L'éditeur déclenche la
     * complétion quand l'utilisateur tape l'un d'eux. Appelée sur le thread
     * UI, potentiellement à chaque frappe : doit être immédiate. Retourner
     * une liste vide si l'auto-déclenchement est désactivé.
     */
    List<String> getTriggerCharacters();
}
