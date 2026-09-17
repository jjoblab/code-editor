package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

/**
 * Préfetch du viewport au repos : réchauffe le cache de rendu
 * ({@code LineRenderCache}) jusqu'à un viewport au-dessus et un
 * en-dessous de la plage visible, pour qu'atterrir sur du texte froid
 * après un fling / goto / saut de recherche ne paie pas le coût de
 * layout par ligne dans la frame de dessin.
 *
 * <p>Extrait d'EditorView pour que la vue reste l'orchestrateur : la
 * tâche s'arme à chaque scroll ({@code schedule()}) et s'annule au
 * détachement ({@code cancel()}) ; elle lit l'état de scroll et les
 * caches de lignes via les champs package-privés de la vue.</p>
 */
final class EditorViewportPrefetcher {

    private final EditorView view;

    /** Période de calme avant de préfetcher les lignes autour du viewport. */
    private static final int PREFETCH_IDLE_MS = 150;
    /** Préfetch par morceaux de ce nombre de lignes, avec pause entre les
     *  morceaux comme point d'annulation (la tâche revérifie l'offset de
     *  scroll à chaque morceau). */
    private static final int PREFETCH_CHUNK = 8;
    /** Pause entre morceaux (ms) — garde le thread principal réactif. */
    private static final int PREFETCH_CHUNK_PAUSE_MS = 4;

    private Runnable viewportPrefetchTask;

    EditorViewportPrefetcher(EditorView view) {
        this.view = view;
    }

    /**
     * (Ré-)arme le préfetch au repos. Bon marché quand appelé répétitivement
     * pendant un fling — un removeCallbacks + un postDelayed.
     */
    void schedule() {
        if (view.session == null || view.getWidth() == 0 || view.getHeight() == 0) return;
        if (viewportPrefetchTask == null) {
            viewportPrefetchTask = () -> run();
        }
        if (view.getHandler() != null) {
            view.getHandler().removeCallbacks(viewportPrefetchTask);
            view.getHandler().postDelayed(viewportPrefetchTask, PREFETCH_IDLE_MS);
        }
    }

    /** Retire la tâche de préfetch en attente (détachement de la vue). */
    void cancel() {
        if (view.getHandler() != null && viewportPrefetchTask != null) {
            view.getHandler().removeCallbacks(viewportPrefetchTask);
        }
    }

    /**
     * Réchauffe le cache de rendu jusqu'à un viewport au-dessus et un
     * en-dessous de la plage visible, pour qu'atterrir sur du texte froid
     * après un fling / goto / saut de recherche ne paie pas le coût de
     * layout par ligne dans la frame de dessin (~3.6 ms + 1.2 Mo dans une
     * seule frame en arrivant sur du texte non façonné — le layout est
     * ~96 % du coût d'une ligne entrant dans le viewport).
     *
     * <p>Ordre : LE BAS D'ABORD, en alternant dessous/dessus — les
     * utilisateurs lisent et scrollent vers le bas, et un préfetch
     * interrompu laisse les deux bords à moitié chauds au lieu d'un bord
     * froid. Les lignes cachées par des plis repliés sont ignorées. La
     * tâche s'arrête dès que le viewport a bougé (le prochain scroll la
     * ré-arme).
     */
    private void run() {
        EditorSession s = view.session;
        if (s == null || view.metrics == null || view.getWidth() == 0) return;
        EditorDocument doc = s.getDocument();
        int lineCount = doc.lineCount();
        if (lineCount == 0) return;
        float lineHeight = view.metrics.getLineHeight();
        if (lineHeight <= 0) return;
        int first = Math.max(0, (int) (view.vOffset / lineHeight) - 1);
        int last = Math.min(lineCount - 1,
                (int) ((view.vOffset + view.getHeight()) / lineHeight) + 1);
        int span = Math.max(1, last - first);
        // Périmètre : un viewport en dessous + un au-dessus (jeu de travail
        // ≈ 3 viewports, ne peut jamais évicter les entrées à l'écran de la
        // LRU à 512 entrées).
        int belowStart = last + 1;
        int belowEnd = Math.min(lineCount - 1, last + span);
        int aboveStart = Math.max(0, first - span);
        int aboveEnd = first - 1;

        // Alterne dessous/dessus par morceaux de PREFETCH_CHUNK, le bas
        // d'abord.
        int bi = belowStart, ai = aboveEnd; // ai descend depuis aboveEnd
        boolean moreBelow = bi <= belowEnd;
        boolean moreAbove = ai >= aboveStart;
        while (moreBelow || moreAbove) {
            // Point d'annulation : viewport bougé → stop (ré-armé par le
            // scroll lui-même). Lire vOffset ici est sûr — même thread.
            int nowFirst = Math.max(0, (int) (view.vOffset / lineHeight) - 1);
            if (Math.abs(nowFirst - first) > span / 2) return;

            if (moreBelow) {
                for (int n = 0; n < PREFETCH_CHUNK && bi <= belowEnd; n++, bi++) {
                    prefetchLine(s, doc, bi);
                }
                moreBelow = bi <= belowEnd;
            }
            if (moreAbove) {
                for (int n = 0; n < PREFETCH_CHUNK && ai >= aboveStart; n++, ai--) {
                    prefetchLine(s, doc, ai);
                }
                moreAbove = ai >= aboveStart;
            }
            if ((moreBelow || moreAbove)
                    && view.getHandler() != null) {
                // Pause entre morceaux — poste une continuation APRÈS les
                // messages input/draw en attente, gardant l'UI réactive. La
                // continuation relance simplement tout le calcul depuis le
                // viewport COURANT : les lignes déjà préfetchées sont des
                // hits de cache (une recherche dans la map), donc le restart
                // converge au lieu de refaire le travail, et un scroll
                // pendant la pause re-cible naturellement le préfetch.
                view.getHandler().postDelayed(this::run,
                        PREFETCH_CHUNK_PAUSE_MS);
                return;
            }
        }
    }

    /** Réchauffe le cache pour une ligne si pas déjà en cache et non pliée. */
    private void prefetchLine(EditorSession s, EditorDocument doc, int line) {
        try {
            if (view.isLineFoldedCached(line)) return; // cachée par un pli replié
            String text = doc.lineText(line);
            view.layoutForLine(line, text);       // remplit le cache au miss
        } catch (Exception ignored) {
            // Offset périmé entre le calcul de plage et la récupération —
            // on ignore, le chemin de dessin recalcule de façon autoritaire.
        }
    }
}
