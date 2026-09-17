package jo.codeeditor.cache;

import java.util.*;

/**
 * Cache par ligne de layout/rendu avec invalidation par révision.
 * Gère les inlays (texte fantôme) et les plages sémantiques.
 * Reprend le design du {@code LineRenderCache.kt} de CodeAssist.
 *
 * <p>Validation par triple tampon ({@code rev} + {@code inlayRev} +
 * {@code semRev}), payload {@code layout} opaque (typé par l'appelant —
 * typiquement un {@code StyledLine} ou un wrapper contenant les inlays/
 * plages sem filtrés par ligne + les maps de colonnes), tampon
 * {@code lastUsed} monotone pour le LRU, et plafond dur de 512 entrées
 * avec éviction LRU.
 *
 * <p>Tampons de révision par ligne : un compteur de révision global
 * invaliderait toutes les lignes en cache à chaque édition, ruinant le
 * cache. L'appelant n'incrémente le tampon que des lignes réellement
 * re-tokenisées, et le cache valide l'entrée par rapport au triple
 * (rev, inlayRev, semRev) avec lequel elle a été stockée.
 */
public class LineRenderCache {

    /** Plafond dur du nombre d'entrées en cache. L'éviction LRU s'applique au-delà. */
    public static final int MAX_ENTRIES = 512;

    /**
     * Un morceau de texte fantôme/inlay inséré à une colonne.
     */
    public static final class InlayPiece {
        public final int col;
        public final String text;

        public InlayPiece(int col, String text) {
            this.col = col;
            this.text = text;
        }

        @Override
        public String toString() {
            return "InlayPiece(col=" + col + ", text=\"" + text + "\")";
        }
    }

    /**
     * Une plage de coloration sémantique.
     */
    public static final class SemSpan {
        public final int start;
        public final int end;
        public final int color;

        public SemSpan(int start, int end, int color) {
            this.start = start;
            this.end = end;
            this.color = color;
        }

        @Override
        public String toString() {
            return "SemSpan([" + start + "," + end + ") color=" + Integer.toHexString(color) + ")";
        }
    }

    /**
     * Données de layout mises en cache pour une ligne.
     *
     * <p>{@code revision} est le tampon de révision texte de la ligne au
     * moment de la mise en cache. {@code inlayRev} / {@code semRev} sont
     * les tampons de révision globaux (hints d'inlay / jetons sémantiques)
     * au moment de la mise en cache. {@code layout} est un payload opaque
     * défini par l'appelant (ex. le layout filtré par ligne de la vue).
     * {@code lastUsed} est incrémenté à chaque hit de {@link #get} afin
     * que l'éviction LRU de {@link #put} puisse choisir l'entrée la moins
     * récemment utilisée.
     */
    public static final class LineCacheEntry {
        public final int line;
        public final int revision;
        public final int inlayRev;
        public final int semRev;
        public final List<InlayPiece> inlays;
        public final List<SemSpan> semSpans;
        /** Mappe colonne brute → colonne visuelle (inlays précédents inclus). */
        public final int[] rawToVisual;
        /** Mappe colonne visuelle → colonne brute. */
        public final int[] visualToRaw;
        /** Payload opaque typé par l'appelant (ex. un StyledLine ou un wrapper). */
        public Object layout;
        /** Tampon monotone incrémenté à chaque hit de cache, utilisé pour l'éviction LRU. */
        public long lastUsed;

        /**
         * Constructeur de compatibilité — validation par simple tampon
         * (révision texte seule), sans tampons inlay/sem ni payload layout.
         */
        public LineCacheEntry(int line, int revision, List<InlayPiece> inlays,
                              List<SemSpan> semSpans, int[] rawToVisual, int[] visualToRaw) {
            this(line, revision, 0, 0, inlays, semSpans, rawToVisual, visualToRaw, null);
        }

        /**
         * Constructeur complet — validation par triple tampon + payload layout.
         */
        public LineCacheEntry(int line, int revision, int inlayRev, int semRev,
                              List<InlayPiece> inlays, List<SemSpan> semSpans,
                              int[] rawToVisual, int[] visualToRaw, Object layout) {
            this.line = line;
            this.revision = revision;
            this.inlayRev = inlayRev;
            this.semRev = semRev;
            this.inlays = inlays != null ? Collections.unmodifiableList(inlays) : Collections.emptyList();
            this.semSpans = semSpans != null ? Collections.unmodifiableList(semSpans) : Collections.emptyList();
            this.rawToVisual = rawToVisual;
            this.visualToRaw = visualToRaw;
            this.layout = layout;
            this.lastUsed = 0;
        }
    }

    private final Map<Integer, LineCacheEntry> cache = new LinkedHashMap<Integer, LineCacheEntry>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, LineCacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    /**
     * Tampons de révision par ligne stockés dans des TABLEAUX PRIMITIFS
     * PARALLÈLES plutôt que dans un {@code HashMap<Integer, Integer>}.
     *
     * <p>Une réindexation de map (réallocation complète du HashMap + boxing
     * d'{@code Integer} pour CHAQUE ligne) à chaque splice de lignes —
     * c.-à-d. à chaque appui sur Entrée — coûterait ~0,31 ms et 426 Ko
     * d'allocations par nouvelle ligne sur un fichier de 4 000 lignes
     * (1 GC toutes les 8 lignes), contre ~0,03 ms / 4,3 Ko avec deux
     * déplacements de région {@code System.arraycopy} comme ici.
     *
     * <p>Les tableaux denses indexés par ligne sont le bon compromis :
     * le numéro de ligne est le domaine d'index, la croissance est
     * géométrique, et 50k lignes d'{@code int[]} occupent ~400 Ko contre
     * plusieurs Mo d'entrées HashMap boxées.
     */
    private static final class LineOverlay {
        /** Sentinelle « aucune révision enregistrée pour cette ligne » —
         *  même sémantique que {@code HashMap#getOrDefault(line, -1)} pour
         *  les lignes apparues par splice sans jamais avoir été écrites. */
        private static final int ABSENT = Integer.MIN_VALUE;

        int[] values = new int[16];
        int length = 0;

        int get(int line, int notFound) {
            if (line < 0 || line >= length) return notFound;
            int v = values[line];
            return v == ABSENT ? notFound : v;
        }

        void put(int line, int value) {
            ensure(line + 1);
            if (line >= length) {
                java.util.Arrays.fill(values, length, line + 1, ABSENT);
                length = line + 1;
            }
            values[line] = value;
        }

        void splice(int fromLine, int delta) {
            if (delta == 0 || length == 0) return;
            if (fromLine >= length) return;
            if (delta > 0) {
                ensure(length + delta);
                System.arraycopy(values, fromLine, values, fromLine + delta, length - fromLine);
                java.util.Arrays.fill(values, fromLine, fromLine + delta, ABSENT);
                length += delta;
            } else {
                int remove = Math.min(-delta, length - fromLine);
                System.arraycopy(values, fromLine + remove, values, fromLine, length - fromLine - remove);
                length -= remove;
            }
        }

        void removeFrom(int fromLine) {
            if (fromLine < length) length = Math.max(0, fromLine);
        }

        void clear() {
            length = 0;
        }

        private void ensure(int cap) {
            if (cap <= values.length) return;
            int newCap = Math.max(cap, values.length * 2);
            values = java.util.Arrays.copyOf(values, newCap);
        }
    }

    /** Tampons de révision par ligne pour l'invalidation des inlays (stockés en tableaux primitifs). */
    private final LineOverlay inlayRevisions = new LineOverlay();
    /** Tampons de révision par ligne pour l'invalidation des plages sémantiques (stockés en tableaux primitifs). */
    private final LineOverlay semRevisions = new LineOverlay();
    /** Compteur monotone — chaque hit de {@link #get} l'incrémente et
     *  l'affecte au {@code lastUsed} de l'entrée. Utilisé par
     *  {@link #evictLru(int)} quand la {@link LinkedHashMap} n'a pas
     *  encore évincé l'entrée périmée. */
    private long clock = 0;

    /**
     * Récupère une entrée de ligne en cache, ou null si périmée/absente.
     * Validation par simple tampon (révision texte seule) — conservée
     * pour la compatibilité avec les appelants et tests existants.
     */
    public LineCacheEntry get(int line, int currentRevision) {
        LineCacheEntry entry = cache.get(line);
        if (entry != null && entry.revision == currentRevision) {
            entry.lastUsed = ++clock;
            return entry;
        }
        return null;
    }

    /**
     * Récupère une entrée de ligne en cache, ou null si périmée/absente.
     * Validation par triple tampon (texte + inlay + sem). À utiliser dans
     * le chemin de dessin : une mise à jour globale inlay/sem n'invalide
     * pas toutes les lignes en cache — seules les lignes dont la révision
     * inlay/sem a réellement changé produisent un miss.
     */
    public LineCacheEntry get(int line, int rev, int inlayRev, int semRev) {
        LineCacheEntry entry = cache.get(line);
        if (entry != null
                && entry.revision == rev
                && entry.inlayRev == inlayRev
                && entry.semRev == semRev) {
            entry.lastUsed = ++clock;
            return entry;
        }
        return null;
    }

    /**
     * Stocke une entrée de cache de ligne. Évict l'entrée la moins
     * récemment utilisée quand le cache dépasse {@link #MAX_ENTRIES}.
     */
    public void put(LineCacheEntry entry) {
        entry.lastUsed = ++clock;
        cache.put(entry.line, entry);
        // Éviction défensive — removeEldestEntry de la LinkedHashMap plafonne
        // déjà la taille, mais la sémantique LRU repose sur l'ordre d'accès,
        // rafraîchi uniquement par get/put. Si de nombreuses entrées sont
        // insérées sans être lues, la plus ancienne est évictée automatiquement.
        if (cache.size() > MAX_ENTRIES) {
            evictLru(MAX_ENTRIES);
        }
    }

    /**
     * Évict manuellement les entrées les plus anciennes jusqu'à ce que le
     * cache tienne dans {@code cap}. Public pour permettre à l'hôte de
     * forcer une réduction sous pression mémoire.
     */
    public void evictLru(int cap) {
        if (cache.size() <= cap) return;
        // Construit une liste de (line, lastUsed) et trie par lastUsed croissant.
        List<long[]> stamps = new ArrayList<>(cache.size());
        for (Map.Entry<Integer, LineCacheEntry> e : cache.entrySet()) {
            stamps.add(new long[]{e.getKey(), e.getValue().lastUsed});
        }
        stamps.sort((a, b) -> Long.compare(a[1], b[1]));
        int toRemove = cache.size() - cap;
        for (int i = 0; i < toRemove && i < stamps.size(); i++) {
            cache.remove((int) stamps.get(i)[0]);
        }
    }

    /**
     * Invalide toutes les entrées en cache à partir de la ligne donnée.
     */
    public void invalidateFrom(int line) {
        cache.entrySet().removeIf(e -> e.getKey() >= line);
        inlayRevisions.removeFrom(line);
        semRevisions.removeFrom(line);
    }

    /**
     * Décale les clés du cache après un splice de lignes (insertion/suppression).
     * Les deux overlays de révision sont splice-és en place via
     * System.arraycopy plutôt que reconstruits ; le cache de layout borné
     * à 512 entrées doit encore être re-clavé (une map ne peut pas se
     * splicer), mais dans un remplacement pré-dimensionné.
     */
    public void shiftKeys(int fromLine, int delta) {
        if (delta == 0) return;
        Map<Integer, LineCacheEntry> newCache = new LinkedHashMap<>(Math.max(64, cache.size() * 2), 0.75f, true);
        for (var entry : cache.entrySet()) {
            int key = entry.getKey();
            if (key < fromLine) {
                newCache.put(key, entry.getValue());
            } else {
                int newKey = key + delta;
                if (newKey >= 0) {
                    newCache.put(newKey, entry.getValue());
                }
            }
        }
        cache.clear();
        cache.putAll(newCache);

        // Décale les overlays de révision — O(région splice-ée), zéro boxing.
        inlayRevisions.splice(fromLine, delta);
        semRevisions.splice(fromLine, delta);
    }

    /**
     * Construit les maps de colonnes brutes→visuelles et visuelles→brutes
     * à partir des inlays.
     *
     * @param lineLength la longueur brute de la ligne
     * @param inlays     inlays triés par col
     * @return [rawToVisual, visualToRaw]
     */
    public static int[][] buildColumnMaps(int lineLength, List<InlayPiece> inlays) {
        // rawToVisual[rawCol] = visualCol
        int[] rawToVisual = new int[lineLength + 1];
        int[] visualToRaw = new int[lineLength + 1 + totalInlayLength(inlays)];

        int rawCol = 0;
        int visCol = 0;
        int inlayIdx = 0;

        while (rawCol <= lineLength) {
            rawToVisual[rawCol] = visCol;
            visualToRaw[visCol] = rawCol;

            // Insère les inlays éventuels à cette colonne brute
            while (inlayIdx < inlays.size() && inlays.get(inlayIdx).col == rawCol) {
                for (int j = 0; j < inlays.get(inlayIdx).text.length(); j++) {
                    visCol++;
                    if (visCol < visualToRaw.length) {
                        visualToRaw[visCol] = rawCol; // remappe vers la colonne brute
                    }
                }
                inlayIdx++;
            }

            if (rawCol < lineLength) {
                rawCol++;
                visCol++;
            } else {
                break;
            }
        }

        return new int[][]{rawToVisual, visualToRaw};
    }

    private static int totalInlayLength(List<InlayPiece> inlays) {
        int total = 0;
        if (inlays == null) return total;
        for (InlayPiece p : inlays) total += p.text.length();
        return total;
    }

    /**
     * Met à jour la révision des inlays d'une ligne.
     */
    public void setInlayRevision(int line, int revision) {
        inlayRevisions.put(line, revision);
    }

    /**
     * Renvoie la révision des inlays d'une ligne.
     */
    public int getInlayRevision(int line) {
        return inlayRevisions.get(line, -1);
    }

    /**
     * Met à jour la révision des plages sémantiques d'une ligne.
     */
    public void setSemRevision(int line, int revision) {
        semRevisions.put(line, revision);
    }

    /**
     * Renvoie la révision des plages sémantiques d'une ligne.
     */
    public int getSemRevision(int line) {
        return semRevisions.get(line, -1);
    }

    /**
     * Vide tout le cache.
     */
    public void clear() {
        cache.clear();
        inlayRevisions.clear();
        semRevisions.clear();
    }

    /**
     * Renvoie le nombre d'entrées en cache.
     */
    public int size() {
        return cache.size();
    }
}
