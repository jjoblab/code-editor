package jo.codeeditor.util;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Point d'accès au {@link EditorLogger} installé — inert tant que l'hôte
 * n'en fournit pas.
 *
 * <p>Installation unique à l'initialisation de l'application hôte (ou
 * automatique par {@code EditorView} pour l'implémentation Android) ;
 * les modules de la bibliothèque journalisent via
 * {@code EditorLog.get().warn(...)}. L'état est un {@link AtomicReference}
 * : lisible depuis n'importe quel thread sans verrou.</p>
 */
public final class EditorLog {

    /** Implémentation inerte : ne fait strictement rien. */
    public static final EditorLogger NOOP = new EditorLogger() {
        @Override public void info(String tag, String message) { }
        @Override public void warn(String tag, String message) { }
        @Override public void warn(String tag, String message, Throwable cause) { }
        @Override public void error(String tag, String message, Throwable cause) { }
    };

    private static final AtomicReference<EditorLogger> LOGGER =
            new AtomicReference<>(NOOP);

    private EditorLog() {
        // Point d'accès statique uniquement.
    }

    /** Installe (ou remplace) le logger de l'hôte. */
    public static void install(EditorLogger logger) {
        LOGGER.set(logger != null ? logger : NOOP);
    }

    /** Le logger courant (jamais null — inert par défaut). */
    public static EditorLogger get() {
        return LOGGER.get();
    }

    /** Raccourcis usuels. */
    public static void warn(String tag, String message) {
        LOGGER.get().warn(tag, message);
    }

    public static void warn(String tag, String message, Throwable cause) {
        LOGGER.get().warn(tag, message, cause);
    }

    public static void error(String tag, String message, Throwable cause) {
        LOGGER.get().error(tag, message, cause);
    }

    public static void info(String tag, String message) {
        LOGGER.get().info(tag, message);
    }
}
