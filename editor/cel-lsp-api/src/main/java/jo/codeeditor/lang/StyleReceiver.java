package jo.codeeditor.lang;

/**
 * Reçoit les mises à jour de style poussées par un {@link Analyzer}.
 * L'analyseur appelle {@link #onStylesUpdated} depuis un thread de travail ;
 * l'implémentation de l'éditeur poste la mise à jour sur le thread UI et
 * invalide les lignes affectées.
 */
public interface StyleReceiver {

    /**
     * Appelée par l'analyseur lorsqu'une plage de lignes a été
     * re-tokenisée.
     *
     * @param startLine la première ligne mise à jour (0-based, incluse)
     * @param endLine   la dernière ligne mise à jour (0-based, incluse)
     */
    void onStylesUpdated(int startLine, int endLine);

    /**
     * Appelée par l'analyseur lorsque les blocs de code (régions de
     * folding) ont été recalculés.
     */
    void onBlocksUpdated();
}
