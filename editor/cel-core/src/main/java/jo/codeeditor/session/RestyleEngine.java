package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.SyntaxHighlighter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Moteur de tokenization d'une session : détient les lignes stylées, les
 * tampons de révision par ligne et le listener de décalage de lignes, et
 * orchestre le restyle — épissure incrémentale à l'édition, restyle
 * complet synchrone et restyle complet asynchrone (exécuteur mono-thread,
 * cancellation, détection de résultats périmés, re-soumission).
 * <p>
 * Existe pour extraire de {@link EditorSession} cette responsabilité
 * concurrente sans en changer le comportement ; la session délègue.
 */
final class RestyleEngine {

    private final EditorSession session;
    private final SyntaxHighlighter highlighter;

    /**
     * Volatile pour que le remplacement par le thread de restyle en arrière-plan
     * ({@code styledLines = newStyled}) soit visible du thread UI sans
     * synchronisation. La liste elle-même est remplacée (jamais mutée) à
     * chaque restyle : un lecteur itérant une ancienne référence voit donc
     * toujours un instantané cohérent.
     */
    private volatile List<StyledLine> styledLines;

    // ── Tampons de révision par ligne pour le cache de rendu ──
    // lineTextRevisions[i] n'est incrémenté QUE quand la ligne i est
    // re-tokenisée (dans splice ou restyleAll). Le LineRenderCache de
    // la vue utilise ces tampons par ligne pour qu'une édition unique
    // n'invalide pas chaque ligne en cache — seulement les lignes dont le
    // texte a réellement changé.
    private int[] lineTextRevisions = new int[0];
    private int lineTextRevCounter = 1;

    // Listener déclenché après splice pour que le cache de rendu de la
    // vue puisse décaler ses clés (shiftKeys) vers le nouveau découpage en
    // lignes.
    private EditorSession.OnLinesShiftedListener linesShiftListener;

    RestyleEngine(EditorSession session) {
        this.session = session;
        this.highlighter = new SyntaxHighlighter();
        this.styledLines = new ArrayList<>();
    }

    /** La liste de lignes stylées courante (remplacée à chaque restyle). */
    List<StyledLine> styledLines() { return styledLines; }

    /**
     * Retourne le tampon de révision de texte de la ligne donnée. Le cache de
     * rendu de la vue s'en sert pour éviter de re-tokeniser les lignes
     * inchangées depuis la dernière frame.
     */
    int lineTextRevision(int line) {
        if (line < 0 || line >= lineTextRevisions.length) return 0;
        return lineTextRevisions[line];
    }

    void setLinesShiftListener(EditorSession.OnLinesShiftedListener listener) {
        this.linesShiftListener = listener;
    }

    // ── Épissure incrémentale (chemin d'édition) ──────────────

    /** Épisse les styles de lignes pour une édition (chemin incrémental de replaceRange). */
    void splice(int firstLine, int removedLines, String insertion) {
        int newLineCount = 1;
        for (int i = 0; i < insertion.length(); i++) {
            if (insertion.charAt(i) == '\n') newLineCount++;
        }
        // Porte TextMate calculée depuis la taille de CE document (et non un
        // drapeau statique global partagé entre toutes les sessions).
        boolean allowTextMate = session.doc.lineCount() <= EditorSession.TEXTMATE_LINE_LIMIT;
        for (int i = 0; i < removedLines && firstLine < styledLines.size(); i++) {
            styledLines.remove(firstLine);
        }
        // Le tableau de tampons de révision par ligne est reconstruit plus
        // bas, en parallèle de la liste styledLines mise à jour — les tampons
        // des lignes inchangées sont préservés, ceux des lignes
        // re-tokenisées sont incrémentés.
        int entryState = (firstLine > 0 && firstLine - 1 < styledLines.size())
            ? styledLines.get(firstLine - 1).exitState
            : LexState.NORMAL;
        for (int i = 0; i < newLineCount; i++) {
            int lineNum = firstLine + i;
            String lineText = session.doc.lineText(lineNum);
            StyledLine styled = highlighter.styleLine(lineText, entryState, session.language, allowTextMate);
            styledLines.add(lineNum, styled);
            entryState = styled.exitState;
        }
        int nextLine = firstLine + newLineCount;
        while (nextLine < styledLines.size()) {
            int prevState = styledLines.get(nextLine - 1).exitState;
            StyledLine current = styledLines.get(nextLine);
            if (current.entryState == prevState) break;
            String lineText = session.doc.lineText(nextLine);
            StyledLine restyled = highlighter.styleLine(lineText, prevState, session.language, allowTextMate);
            styledLines.set(nextLine, restyled);
            nextLine++;
        }

        // Reconstruire le tableau de tampons de révision par ligne en
        // parallèle de styledLines, en incrémentant le tampon pour chaque
        // ligne dont l'instance de StyledLine est nouvellement créée ou
        // re-tokenisée dans cette épissure. Les lignes dont l'instance de
        // StyledLine est inchangée gardent leur ancien tampon — le cache de
        // rendu de la vue reste valide pour elles.
        int totalLines = styledLines.size();
        int[] newRevs = new int[totalLines];
        // Préserver les tampons des lignes AVANT firstLine (inchangées).
        for (int i = 0; i < firstLine && i < newRevs.length; i++) {
            newRevs[i] = (i < lineTextRevisions.length) ? lineTextRevisions[i] : 0;
        }
        // Incrémenter les tampons des lignes fraîchement tokenisées [firstLine, firstLine + newLineCount).
        for (int i = firstLine; i < firstLine + newLineCount && i < newRevs.length; i++) {
            newRevs[i] = ++lineTextRevCounter;
        }
        // Pour les lignes APRÈS la région épissée : si leur instance de
        // StyledLine a changé (re-tokenisée par la cascade d'états d'entrée),
        // incrémenter le tampon. Sinon reporter l'ancien tampon.
        for (int i = firstLine + newLineCount; i < newRevs.length; i++) {
            int oldIdx = i - newLineCount + removedLines;
            if (oldIdx >= 0 && oldIdx < lineTextRevisions.length
                && i < styledLines.size() && oldIdx + firstLine < styledLines.size()) {
                // La cascade ci-dessus a pu remplacer le StyledLine de cette
                // ligne. C'est difficilement détectable ici — incrémenter
                // conservatoirement jusqu'à nextLine (la fin de cascade).
                // Au-delà de nextLine, l'instance est la même qu'avant
                // l'épissure — reporter le tampon.
                if (i < nextLine) {
                    newRevs[i] = ++lineTextRevCounter;
                } else {
                    newRevs[i] = lineTextRevisions[oldIdx];
                }
            } else {
                newRevs[i] = ++lineTextRevCounter;
            }
        }
        lineTextRevisions = newRevs;

        // Notifier le cache de rendu de la vue pour qu'il puisse décaler
        // (shiftKeys) ses entrées vers le nouveau découpage en lignes.
        // delta = newLineCount - removedLines.
        if (linesShiftListener != null) {
            int delta = newLineCount - removedLines;
            if (delta != 0) {
                linesShiftListener.onLinesShifted(firstLine, delta);
            }
        }
    }

    // ── Restyle complet synchrone ─────────────────────────────

    /** Re-tokenise TOUTES les lignes du document courant (bloquant). */
    void restyleAll() {
        styledLines.clear();
        // Porte TextMate par appel (et non drapeau statique global).
        boolean allowTextMate = session.doc.lineCount() <= EditorSession.TEXTMATE_LINE_LIMIT;
        int state = LexState.NORMAL;
        for (int i = 0; i < session.doc.lineCount(); i++) {
            String lineText = session.doc.lineText(i);
            StyledLine styled = highlighter.styleLine(lineText, state, session.language, allowTextMate);
            styledLines.add(styled);
            state = styled.exitState;
        }
        // Chaque ligne est re-tokenisée — incrémenter chaque tampon par ligne
        // pour que le cache de rendu de la vue soit en cache-miss sur chaque
        // ligne et la reconstruise.
        lineTextRevisions = new int[session.doc.lineCount()];
        for (int i = 0; i < lineTextRevisions.length; i++) {
            lineTextRevisions[i] = ++lineTextRevCounter;
        }
        // Dire au cache de la vue de tout jeter — le découpage en lignes a
        // pu changer (p. ex. l'undo d'une insertion de saut de ligne supprime
        // une ligne) et les clés ne s'alignent plus.
        if (linesShiftListener != null) {
            linesShiftListener.onLinesReset();
        }
    }

    // ── Restyle asynchrone (tokenization hors thread UI) ──────────

    /**
     * Exécuteur mono-thread pour le travail de restyle en arrière-plan.
     *
     * <p>Mono-thread pour que :
     * <ul>
     *   <li>les tâches de restyle soient sérialisées — pas de tokenization
     *       concurrente sur la même session (ce serait une race sur
     *       {@code stateMaps} dans {@link SyntaxHighlighter} /
     *       {@code TextMateTokenizerImpl}) ;</li>
     *   <li>l'annulation d'un restyle en attente (via {@code Future.cancel(true)})
     *       interrompt le thread worker — celui-ci vérifie
     *       {@code Thread.interrupted()} dans sa boucle et s'interrompt tôt.</li>
     * </ul>
     *
     * <p>Démon pour ne pas bloquer l'arrêt de la JVM. Priorité sous NORM
     * pour que le thread UI gagne les conflits CPU.
     */
    private final ExecutorService restyleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EditorSession-restyle");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    /**
     * Restyle asynchrone en attente, le cas échéant. Sert à annuler un
     * restyle en vol quand un nouveau est demandé (p. ex. l'utilisateur
     * ouvre un autre fichier avant la fin du premier).
     *
     * <p>Atomique pour que le cancel-and-replace soit sans race entre le
     * thread UI (soumission du nouveau travail) et le thread worker
     * (nettoyage du champ à la complétion).
     */
    private final AtomicReference<Future<?>> pendingRestyle = new AtomicReference<>();

    /**
     * Jeton monotone pour détecter les résultats asynchrones périmés.
     *
     * <p>Chaque appel à {@link #restyleAsync()} incrémente ce compteur
     * et capture la nouvelle valeur. Le thread worker capture la valeur au
     * départ ; avant de permuter les résultats, il vérifie que sa valeur
     * capturée égale toujours le champ courant. Sinon, un restyle plus
     * récent a été émis — jeter le résultat.
     *
     * <p>Pas besoin d'atome ici — le champ n'est muté que sur le thread UI
     * (appelant de {@code setLanguage} / etc. côté session) et lu sur le
     * thread worker. Volatile suffit pour la visibilité.
     */
    private volatile long restyleGeneration = 0;

    /**
     * Version asynchrone de {@link #restyleAll()}. Lance le travail de
     * tokenization sur un thread en arrière-plan ; le thread UI peut
     * rendre la main immédiatement à l'utilisateur (l'ouverture de fichier
     * reste rapide même pour un HTML de 5 000 lignes avec TextMate activé).
     *
     * <p>Comportement :
     * <ol>
     *   <li>Annule tout restyle asynchrone précédemment en attente.</li>
     *   <li>Snapshote le texte du doc + le nombre de lignes + le langage à
     *       l'appel (EditorDocument est immuable, donc
     *       {@link EditorDocument#getText()} est une référence String
     *       stable).</li>
     *   <li>Ne vide PAS {@link #styledLines} : les anciens spans restent
     *       affichés pendant l'intervalle asynchrone (voir plus bas).</li>
     *   <li>Soumet une tâche à {@link #restyleExecutor} qui :
     *       <ul>
     *         <li>construit une {@code List<StyledLine>} fraîche depuis le
     *             snapshot ;</li>
     *         <li>revérifie le snapshot contre le doc courant — si le doc a
     *             changé (replaceRange est survenu), jette le résultat ;</li>
     *         <li>permute {@link #styledLines} atomiquement vers la nouvelle
     *             liste (affectation de champ volatile) ;</li>
     *         <li>réinitialise {@link #lineTextRevisions} ;</li>
     *         <li>appelle {@link EditorSession.OnLinesShiftedListener#onLinesReset()} — le
     *             listener est censé poster l'appel sur le thread UI si
     *             besoin (comme le schéma {@code notifyDiagnosticsChanged}
     *             d'EditorView).</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p><b>Pourquoi pas d'{@code invalidate()} ici ?</b> EditorSession est
     * pur-Java et ne touche pas la View. Le listener de la vue
     * (EditorView.cacheShiftListener) est responsable de vider le cache de
     * rendu ET d'appeler {@code invalidate()} sur la vue.
     */
    void restyleAsync() {
        // Après dispose(), l'exécuteur est arrêté — soumettre lèverait
        // RejectedExecutionException. Retomber sur le chemin synchrone :
        // correct, simplement bloquant. Les éditions fonctionnent toujours.
        if (disposed) {
            restyleAll();
            return;
        }
        // Synchronisé — restyleAsync peut aussi être appelé depuis le
        // thread WORKER (re-planification sur doc changé dans
        // doAsyncRestyle). Sans moniteur, un setLanguage() concurrent du
        // thread UI pourrait courir avec la comptabilité ci-dessous (en
        // particulier le ++restyleGeneration non atomique et la
        // reconstruction du tableau lineTextRevisions) et produire deux
        // tâches de même génération passant toutes deux le contrôle de
        // fraîcheur. Sérialiser le chemin de soumission rend la génération
        // strictement monotone pour tous les appelants. (Moniteur = la
        // session, comme historiquement.)
        synchronized (session) {
        // Snapshot de l'état du doc.
        final String textSnapshot = session.doc.getText();
        final int lineCountSnapshot = session.doc.lineCount();
        final String langSnapshot = session.language;
        final long myGeneration = ++restyleGeneration;

        // Annuler tout restyle en attente.
        Future<?> prev = pendingRestyle.getAndSet(null);
        if (prev != null) {
            prev.cancel(/*mayInterrupt=*/true);
        }

        // NE PAS vider styledLines ici. Garder les anciens tokens
        // (périmés) visibles pendant l'intervalle asynchrone. Raisons :
        // - Le chemin d'édition incrémental (replaceRange → splice)
        //   opère sur la liste styledLines vivante. Si on la vidait ici,
        //   une édition pendant l'intervalle asynchrone laisserait une
        //   liste partielle, et le rejet de résultat périmé du worker
        //   nous laisserait dans un état incohérent (styledLines.size() !=
        //   doc.lineCount()).
        // - Avec l'approche conserver-ancien, l'utilisateur voit des
        //   couleurs brièvement fausses (p. ex. couleurs Java sur un
        //   fichier HTML pendant ~200 ms) jusqu'à ce que l'async termine
        //   et que les nouveaux tokens se permutent atomiquement.
        // - Le renderer de la vue gère élégamment styledLines.size() <
        //   doc.lineCount() (il retombe sur le texte brut), donc pas de
        //   crash si le nouveau doc a plus de lignes que l'ancien
        //   styledLines.
        //
        // On RÉINITIALISE bien lineTextRevisions pour forcer un cache-miss
        // du cache de rendu sur chaque ligne, afin que la vue reconstruise
        // ses rendus de lignes en cache depuis le nouveau styledLines
        // (post-permutation).
        lineTextRevisions = new int[Math.max(lineTextRevisions.length, lineCountSnapshot)];
        for (int i = 0; i < lineTextRevisions.length; i++) {
            lineTextRevisions[i] = ++lineTextRevCounter;
        }

        // Capturer pour le contrôle de fraîcheur du worker.
        final EditorDocument docAtStart = session.doc;

        Future<?> task = restyleExecutor.submit(() -> {
            try {
                doAsyncRestyle(textSnapshot, lineCountSnapshot, langSnapshot,
                    myGeneration, docAtStart);
            } catch (Throwable t) {
                // Défensif : logger et abandonner. La session reste
                // fonctionnelle avec les anciens styledLines — l'utilisateur
                // voit juste des couleurs périmées jusqu'à ce qu'une prochaine
                // édition déclenche un restyle (synchrone).
                System.err.println("EditorSession async restyle failed: " + t);
            }
        });
        pendingRestyle.set(task);
        } // synchronized(session)
    }

    /**
     * Implémentation côté worker du restyle asynchrone.
     *
     * <p>Construit la nouvelle {@code List<StyledLine>} depuis le snapshot,
     * vérifie la fraîcheur, puis permute atomiquement le champ et notifie
     * le listener.
     */
    private void doAsyncRestyle(String textSnapshot, int lineCountSnapshot,
            String langSnapshot, long myGeneration, EditorDocument docAtStart) {
        // Construire le nouveau styledLines depuis le snapshot du texte.
        // Optimisation : éviter doc.lineText(i) (O(N) par appel →
        // O(N²) au total) en découpant nous-mêmes le texte du snapshot.
        List<StyledLine> newStyled = new ArrayList<>(lineCountSnapshot);
        int state = LexState.NORMAL;
        // Porte TextMate calculée depuis la taille du SNAPSHOT — décidée
        // par passe asynchrone, insensible aux interférences entre sessions.
        boolean allowTextMate = lineCountSnapshot <= EditorSession.TEXTMATE_LINE_LIMIT;
        int start = 0;
        for (int i = 0; i < lineCountSnapshot; i++) {
            // Vérifier l'interruption (annulation).
            if (Thread.interrupted()) {
                // Un restyle plus récent a été émis — abandonner silencieusement.
                return;
            }
            int end = textSnapshot.indexOf('\n', start);
            String lineText = (end < 0)
                ? textSnapshot.substring(start)
                : textSnapshot.substring(start, end);
            StyledLine styled = highlighter.styleLine(lineText, state, langSnapshot, allowTextMate);
            newStyled.add(styled);
            state = styled.exitState;
            if (end < 0) break;
            start = end + 1;
        }

        // Contrôle de fraîcheur : si un restyle plus récent a été émis, abandonner.
        if (restyleGeneration != myGeneration) {
            return;
        }
        // Contrôle doc changé : si la référence du doc a été remplacée
        // (replaceRange / undo / redo pendant le travail asynchrone), le
        // snapshot est périmé — ne PAS jeter silencieusement. Re-soumettre
        // un restyle pour le doc ACTUEL : restyleAsync snapshote un état
        // frais, donc la passe suivante récupère le texte post-édition.
        // Sinon, une édition tombant dans l'intervalle asynchrone laisserait
        // le document avec une coloration à langages MÉLANGÉS de façon
        // PERMANENTE : splice n'aurait re-tokenisé que les lignes
        // éditées avec le NOUVEAU langage tandis que toutes les autres
        // garderaient les tokens de l'ANCIEN (p. ex. basculer un fichier de
        // 5000 lignes de Java vers XML, taper un caractère en ~200 ms :
        // cette ligne se rendrait en XML, les 4999 autres resteraient
        // colorées Java jusqu'au prochain setLanguage/undo).
        if (session.doc != docAtStart) {
            restyleAsync();
            return;
        }

        // Permutation atomique de styledLines. Écriture volatile — visible
        // du thread UI à la prochaine lecture de getStyledLines().
        styledLines = newStyled;

        // Réinitialiser lineTextRevisions pour forcer le cache de rendu à
        // être en cache-miss sur chaque ligne. (Même logique que le chemin
        // synchrone.)
        int[] newRevs = new int[newStyled.size()];
        for (int i = 0; i < newRevs.length; i++) {
            newRevs[i] = ++lineTextRevCounter;
        }
        lineTextRevisions = newRevs;

        // Notifier le listener. Le listener attendu est le
        // cacheShiftListener d'EditorView, qui poste le travail sur le
        // thread UI (comme le schéma notifyDiagnosticsChanged).
        if (linesShiftListener != null) {
            linesShiftListener.onLinesReset();
        }

        // Note : on ne nettoie pas pendingRestyle ici — le Future est
        // déjà complété quand on atteint ce point (on s'exécute dedans).
        // isAsyncRestylePending() retourne faux pour les futures complétés,
        // donc les appelants voient le bon état. Le prochain
        // restyleAsync() nous annulera via pendingRestyle.getAndSet(null)
        // — un no-op sur un Future complété.
    }

    /**
     * Attend (bloquant) la complétion de tout restyle asynchrone en attente.
     *
     * <p>Pour les tests : garantit que les assertions sur styledLines voient
     * l'état post-restyle, pas l'intervalle en vol.
     *
     * @throws InterruptedException si le thread appelant est interrompu
     */
    void awaitPendingRestyle() throws InterruptedException {
        Future<?> task = pendingRestyle.get();
        if (task != null) {
            try {
                task.get();
            } catch (java.util.concurrent.ExecutionException e) {
                // Restyle échoué — logger et continuer. styledLines peut être vide.
                System.err.println("EditorSession awaitPendingRestyle: " + e);
            }
        }
    }

    /**
     * Indique si un restyle asynchrone est actuellement en vol.
     * Pour les tests + le diagnostic.
     */
    boolean isAsyncRestylePending() {
        Future<?> task = pendingRestyle.get();
        return task != null && !task.isDone();
    }

    // ── Cycle de vie ─────────────────────────────────────────

    /** Positionné par dispose() ; rend les demandes de restyle ultérieures synchrones. */
    private volatile boolean disposed = false;

    /**
     * Annule le restyle asynchrone en attente et arrête l'exécuteur ;
     * positionne le drapeau qui fait retomber les demandes de restyle
     * ultérieures sur le chemin synchrone.
     */
    void dispose() {
        Future<?> prev = pendingRestyle.getAndSet(null);
        if (prev != null) {
            prev.cancel(true);
        }
        restyleExecutor.shutdownNow();
        disposed = true;
    }

    /** Indique si {@link #dispose()} a été appelé. */
    boolean isDisposed() {
        return disposed;
    }
}
