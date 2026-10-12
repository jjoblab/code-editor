package jo.codeeditor.lsp;

/**
 * Récepteur des journaux d'activité LSP. L'application de démonstration
 * l'implémente pour exposer les événements requête/réponse/expiration/
 * erreur LSP dans son panneau de journal, afin que l'utilisateur voie
 * précisément ce qui se passe quand une connexion serveur semble « bloquée »
 * ou que des fonctionnalités ne se déclenchent pas.
 */
public interface LspLogSink {
    /**
     * Journalise un message unique. Les implémentations doivent être
     * thread-safe — les rappels LSP arrivent sur le thread exécuteur LSP4J,
     * pas sur le thread UI.
     */
    void log(String message);
}
