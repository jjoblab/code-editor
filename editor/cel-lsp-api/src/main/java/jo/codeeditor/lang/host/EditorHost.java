package jo.codeeditor.lang.host;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.Language;
import jo.codeeditor.session.EditorSession;

/**
 * Hôte d'édition vu par le client LSP — contrat d'inversion de dépendance
 * (plan 03 « Inverser la dépendance cel-lsp → cel-ui »).
 *
 * <p>Le client LSP ({@code cel-lsp}) ne connaît plus la vue Android : il
 * programme contre CE contrat. L'implémentation de référence vit côté
 * interface ({@code cel-ui} fournit un adaptateur mince autour de
 * {@code EditorView}) ; une application hôte peut apporter la sienne
 * (éditeur maison, tests JVM purs sans Robolectric).</p>
 *
 * <h2>Contrat</h2>
 * <ul>
 *   <li><b>Liaison</b> — {@link #session()} / {@link #document()}
 *   retournent {@code null} tant que l'hôte n'est pas lié à un document ;
 *       les appelants du client LSP testent la nullité avant usage (comme
 *       ils testaient la vue avant) ;</li>
 *   <li><b>Mutations</b> — {@link #setLanguage(Language)} et les
 *       notifications ({@link #notifyDiagnosticsChanged()},
 *       {@link #notifyTextChanged()}) sont attendues sur le thread UI ;
 *       les méthodes de session ({@code setDiagnostics},
 *       {@code applyCodeFolds}, {@code setSemanticTokens},
 *       {@code addOnTextEditListener}) restent accessibles via
 *       {@link #session()} ;</li>
 *   <li><b>Marshalling</b> — {@link #post(Runnable)} et
 *       {@link #postDelayed(Runnable, long)} suivent la sémantique
 *       {@code View.post}/{@code View.postDelayed} : posés sur le thread
 *       UI quand l'hôte est attaché, sinon mis en file jusqu'à
 *       l'attachement. Le client qui exige l'exécution immédiate sur un
 *       hôte détaché (publication de diagnostics, réponse serveur) teste
 *       d'abord {@link #isAttachedToWindow()} et exécute en ligne ;</li>
 *   <li><b>Nullité</b> — aucune méthode ne lève : un hôte non lié est un
 *       état NORMAL du cycle de vie (éditeur avant liaison, après
 *       détachement).</li>
 * </ul>
 *
 * <p><b>Politique de compatibilité</b> : cette interface n'expose QUE de
 * l'API publique ({@code EditorView.getSession()} est public, de même que
 * {@code setLanguage} et les notifications) — aucun scell
 * {@code @RestrictTo(LIBRARY_GROUP)} ne doit fuiter ici, l'adaptateur
 * seul y a accès.</p>
 */
public interface EditorHost {

    /**
     * La session d'édition liée — porte du document vivant, des
     * mutations ({@code setDiagnostics}, {@code applyCodeFolds},
     * {@code setSemanticTokens}) et de l'écoute des éditions.
     * {@code null} tant que l'hôte n'est pas lié.
     */
    EditorSession session();

    /**
     * Le document vivant de l'éditeur lié (raccourci de
     * {@code session().getDocument()}). {@code null} si non lié.
     */
    EditorDocument document();

    /**
     * Pose le SPI langage sur l'hôte (thread UI attendu — la pose
     * déclenche la détection lexicale et les resolvers).
     */
    void setLanguage(Language language);

    /**
     * Notification unifiée de changement de diagnostics : reconstruction
     * du tableau de sévérité par ligne (gouttière), notification de
     * l'hôte, invalidation comprise. À appeler après
     * {@code session().setDiagnostics(...)}.
     */
    void notifyDiagnosticsChanged();

    /**
     * Notification de changement de texte — annulation, caches et
     * repositionnement du caret, la même comptabilité que le chemin IME.
     * À appeler après une mutation programmatique du document.
     */
    void notifyTextChanged();

    /** Invalidation visuelle de l'hôte. */
    void invalidate();

    /** L'hôte est-il attaché à une fenêtre (décision de marshalling) ? */
    boolean isAttachedToWindow();

    /**
     * Poste une tâche : thread UI si attaché, file d'attente jusqu'à
     * l'attachement sinon (sémantique {@code View.post}).
     */
    void post(Runnable task);

    /**
     * Poste une tâche différée (throttle des tirs clients) : thread UI
     * si attaché, file d'attente jusqu'à l'attachement sinon
     * (sémantique {@code View.postDelayed}).
     */
    void postDelayed(Runnable task, long delayMillis);

    /**
     * L'appelant est-il déjà sur le thread UI de l'hôte ? Complète la
     * règle de marshalling des mutations (poser/retirer le langage) :
     * un hôte attaché dont l'appelant est déjà sur le thread UI peut
     * exécuter la mutation immédiatement — la poster ne ferait que la
     * retarder derrière les poses suivantes de l'application hôte
     * (changement d'onglet), qui seraient alors effacées par erreur.
     *
     * <p>Défaut prudent : « non » pour un hôte attaché (la mutation est
     * postée, comportement historique), « oui » pour un hôte détaché
     * (tout est déjà exécuté en ligne). L'adaptateur de référence
     * ({@code cel-ui}) retourne l'appartenance réelle au thread UI.</p>
     *
     * @since 3.74.0
     */
    default boolean isOnUiThread() {
        return !isAttachedToWindow();
    }
}
