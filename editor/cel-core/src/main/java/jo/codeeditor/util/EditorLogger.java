package jo.codeeditor.util;

/**
 * Journal de la bibliothèque — injecté par l'hôte, silencieux par défaut.
 *
 * <p>La bibliothèque ne doit NI écrire sur {@code System.err}, NI dépendre
 * d'Android ({@code android.util.Log}) hors des modules de vue : les
 * événements passent par cette interface. L'implémentation inert par
 * défaut ne fait rien (tests et usage embarqué sans journal) ;
 * {@code cel-ui} fournit l'implémentation {@code android.util.Log} et
 * l'installe automatiquement à l'initialisation de {@code EditorView}.</p>
 *
 * <p>Implémentations attendues thread-safe : les appels arrivent des
 * threads de rendu, de débounce et d'exécution LSP.</p>
 */
public interface EditorLogger {

    /** Événement informatif (cycle de vie, compteurs de debug). */
    void info(String tag, String message);

    /** Anomalie récupérable (provider tiers défaillant, repli dégradé). */
    void warn(String tag, String message);

    /** Anomalie récupérable avec sa cause (trace conservée). */
    void warn(String tag, String message, Throwable cause);

    /** Échec d'une opération (envoi LSP, écriture disque…). */
    void error(String tag, String message, Throwable cause);
}
