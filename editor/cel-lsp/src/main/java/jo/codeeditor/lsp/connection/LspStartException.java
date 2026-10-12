package jo.codeeditor.lsp.connection;

/**
 * Échec de démarrage d'un serveur de langage — exception typée (l'ancien
 * code levait une {@link RuntimeException} générique).
 */
public class LspStartException extends RuntimeException {

    public LspStartException(String message, Throwable cause) {
        super(message, cause);
    }

    public LspStartException(String message) {
        super(message);
    }
}
