package jo.codeeditor.session;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.highlight.TokenType;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liste de lignes stylées PROGRESSIVE (plan 11 §2.2) — le régime des
 * documents de plus de {@link EditorSession#TEXTMATE_EAGER_LIMIT}
 * lignes.
 *
 * <p>Modèle en couches, à la manière du lexer redémarrable de l'IDE de
 * référence : <b>les états sont calculés en arrière-plan, les spans à
 * la demande des lignes visibles, la mémoire est bornée quelle que
 * soit la taille du document.</b></p>
 *
 * <ul>
 *   <li><b>Deux pistes d'états</b> ({@code intEntry} pour le
 *       tokéniseur intégré, {@code tmEntry} pour la voie grammaires) —
 *       les espaces d'identifiants des deux tokéniseurs ne se
 *       mélangent JAMAIS (correctif P1-a : une ligne monstre qui
 *       bascule sur l'intégré ne corrompt plus la pile grammaires).
 *       Une piste est un tableau {@code int[]} d'états D'ENTRÉE par
 *       ligne, valide sur {@code [0, watermark)} ; le « veston »
 *       ({@code tail}) porte la sortie de la dernière ligne chaînée
 *       (l'entrée de sortie de document est l'entrée de la ligne
 *       suivante).</li>
 *   <li><b>Passe d'arrière-plan</b> : le worker de {@link RestyleEngine}
 *       appelle {@link #fillNextChunk()} — piste intégrée d'abord
 *       (~1,5 µs/ligne), puis piste grammaires (~200 µs/ligne : la
 *       coloration riche d'un document géant s'installe
 *       progressivement, l'intégré assure l'affichage immédiat). La
 *       tokénisation d'un morceau s'exécute HORS verrou ; la
 *       publication se fait sous verrou avec re-validation (une
 *       épissure concurrente invalide le morceau, jeté).</li>
 *   <li><b>Demande</b> ({@link #get(int)}) : cache LRU de spans
 *       ({@value #SPAN_CACHE_MAX} lignes) ; à défaut, UNE ligne est
 *       tokénisée : voie grammaires si son entrée est connue et la
 *       ligne raisonnable (budget INTERACTIF — frozen frame bornée),
 *       sinon voie intégrée (avec rattrapage borné de la piste),
 *       sinon ligne PLAIN provisoire.</li>
 *   <li><b>Épissure</b> ({@link #splice}) : re-chaînage synchronisé des
 *       lignes éditées, cascade de stabilisation BORNÉE, troncature des
 *       marques d'eau au-delà — le worker reprend la suite.</li>
 * </ul>
 */
final class ProgressiveStyledLines extends AbstractList<StyledLine> {

    // ── Constantes de calibrage (plan 11, D4) ──────────────────────

    /** Lignes de spans gardées en cache (LRU) — borne la mémoire. */
    static final int SPAN_CACHE_MAX = 4096;

    /** Lignes de piste avancées par morceau worker. */
    private static final int CHUNK = 256;

    /**
     * Rattrapage intégré SYNCHRONE maximal d'un {@link #get} : la
     * piste intégrée avance jusqu'à la ligne demandée si l'écart est
     * raisonnable (~1,5 µs/ligne → 2 048 lignes ≈ 3 ms).
     */
    private static final int SYNC_ADVANCE_MAX = 2048;

    /**
     * Cascade de stabilisation maximale au fil UI après épissure
     * (piste intégrée) — au-delà, la marque d'eau est tronquée et le
     * worker reprend.
     */
    private static final int CASCADE_MAX = 512;

    /**
     * Lignes insérées re-chaînées SYNCHRONEMENT sur la piste grammaires
     * à l'épissure (le reste part au worker) — la cascade grammaires,
     * plus coûteuse, partage cette borne.
     */
    private static final int TM_SYNC_MAX = 64;

    /** Initialisation synchrone piste intégrée (ouverture/reset). */
    private static final int INIT_INT_LINES = 2048;

    /** Initialisation synchrone piste grammaires (ouverture/reset). */
    private static final int INIT_TM_LINES = 128;

    /** Tiers d'une entrée de cache de spans. */
    private static final int TIER_PLAIN = 0;
    private static final int TIER_INT = 1;
    private static final int TIER_TM = 2;

    private static final int UNKNOWN = Integer.MIN_VALUE;

    // ── État ───────────────────────────────────────────────────────

    private final RestyleEngine host;
    private final SyntaxHighlighter highlighter;

    /** Moniteur de tout l'état mutable ci-dessous. */
    private final Object lock = new Object();

    /**
     * État d'entrée par ligne, tokéniseur INTÉGRÉ — valide sur
     * [0, intValid). Tableau à CAPACITÉ (l'épissure décale la queue
     * SUR PLACE : allouer trois tableaux de la taille du document à
     * chaque frasse noierait le GC — mesuré 5,7 Mo/frappe à 480k
     * lignes, la cause des pires frames 20-30 ms).
     */
    private int[] intEntry = new int[0];
    private int intValid = 0;
    /** Sortie de la ligne {@code intValid - 1} (veston de la chaîne intégrée). */
    private int intTail = 0;

    /** État d'entrée par ligne, voie GRAMMAIRES — valide sur [0, tmValid), capacité idem. */
    private int[] tmEntry = new int[0];
    private int tmValid = 0;
    /** Sortie de la ligne {@code tmValid - 1} (veston de la chaîne grammaires). */
    private int tmTail = 0;

    /** Nombre de lignes couvertes par les tableaux (cohérent avec les pistes). */
    private int lineCount = 0;

    /** Cache LRU de spans : index de ligne → entrée (tier + styled). */
    private final LinkedHashMap<Integer, Cached> spans =
            new LinkedHashMap<>(SPAN_CACHE_MAX * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Cached> eldest) {
                    return size() > SPAN_CACHE_MAX;
                }
            };

    /** Hint viewport (volatils, écrits par le fil UI, lus par le worker). */
    private volatile int hintFirst = -1;
    private volatile int hintLast = -1;

    /**
     * Jeton de changement structurel (épissure/reset) : le worker
     * capture sa valeur avant un morceau et re-valide avant de
     * publier — un changement concurrent jette le morceau.
     */
    private volatile long changeStamp = 0;

    private static final class Cached {
        final StyledLine styled;
        final int tier;
        Cached(StyledLine styled, int tier) {
            this.styled = styled;
            this.tier = tier;
        }
    }

    ProgressiveStyledLines(RestyleEngine host) {
        this.host = host;
        this.highlighter = host.highlighterForProgressive();
    }

    // ── AbstractList ───────────────────────────────────────────────

    @Override
    public int size() {
        return host.progressiveLineCount();
    }

    /**
     * La ligne stylée — calculée À LA DEMANDE (chemin interactif du
     * plan 11) : cache, voie grammaires (budget interactif), voie
     * intégrée (rattrapage borné), sinon PLAIN provisoire.
     */
    @Override
    public StyledLine get(int index) {
        int n = host.progressiveLineCount();
        if (index < 0 || index >= n) {
            throw new IndexOutOfBoundsException(
                    "ligne " + index + " hors [0, " + n + ")");
        }
        String line = host.progressiveLineText(index);
        // 1) Lecture d'état sous verrou — le calcul se fait hors verrou.
        int entryInt;
        int entryTm;
        synchronized (lock) {
            Cached c = spans.get(index);
            if (c != null && c.tier != TIER_PLAIN) return c.styled;
            // Voie grammaires : entrée connue ET ligne raisonnable —
            // une ligne monstre ne part JAMAIS sur la voie grammaires
            // (garde du plan 10), et son entrée intégrée est utilisée
            // à la place (pistes séparées, P1-a).
            entryTm = index < tmValid
                    && line.length() <= SyntaxHighlighter.TEXTMATE_CHAR_LIMIT
                    ? tmEntry[index] : UNKNOWN;
            if (entryTm == UNKNOWN) {
                if (index < intValid) {
                    entryInt = intEntry[index];
                } else {
                    advanceIntegratedLocked(index, SYNC_ADVANCE_MAX);
                    entryInt = index < intValid ? intEntry[index] : UNKNOWN;
                }
            } else {
                entryInt = UNKNOWN;
            }
        }
        String lang = host.progressiveLanguage();
        // 2) Calcul hors verrou : UNE ligne, jamais plus.
        if (entryTm != UNKNOWN) {
            StyledLine styled = highlighter.styleLine(line, entryTm, lang,
                    true, SyntaxHighlighter.INTERACTIVE_TM_TIMEOUT_NS);
            if (store(index, styled, TIER_TM, entryTm)) return styled;
            // État périmé pendant le calcul : retomber sur l'intégré.
        }
        if (entryInt != UNKNOWN) {
            StyledLine styled = highlighter.styleLine(line, entryInt, lang, false);
            if (store(index, styled, TIER_INT, entryInt)) return styled;
        }
        StyledLine plain = new StyledLine(List.of(new LineSpan(0,
                line.length(), TokenType.PLAIN)), 0, 0);
        store(index, plain, TIER_PLAIN, UNKNOWN);
        return plain;
    }

    /** Stocke sous verrou avec re-validation : vrai si conservé. */
    private boolean store(int index, StyledLine styled, int tier, int entryUsed) {
        synchronized (lock) {
            if (tier == TIER_TM && (index >= tmValid || tmEntry[index] != entryUsed)) {
                return false; // périmé : jeté, l'appelant retombe en dessous
            }
            if (tier == TIER_INT && (index >= intValid || intEntry[index] != entryUsed)) {
                return false;
            }
            spans.put(index, new Cached(styled, tier));
            return true;
        }
    }

    // ── Initialisation / remise à zéro (fil UI, moniteur session) ──

    /**
     * Réinitialise les deux pistes pour le document courant et chaîne
     * synchroniquement le premier segment (intégré
     * {@value #INIT_INT_LINES} lignes, grammaires
     * {@value #INIT_TM_LINES}) — l'ouverture d'un document géant reste
     * bornée à quelques ms (contre 619 ms mesurés pour la passe
     * complète intégrée à 480k lignes, plan 11 §1).
     */
    void resetForCurrentDoc() {
        synchronized (lock) {
            this.lineCount = host.progressiveLineCount();
            intEntry = new int[lineCount];
            tmEntry = new int[lineCount];
            intValid = 0;
            tmValid = 0;
            intTail = 0;
            tmTail = 0;
            spans.clear();
            advanceIntegratedLocked(INIT_INT_LINES - 1, INIT_INT_LINES);
            advanceTmLocked(INIT_TM_LINES - 1, INIT_TM_LINES);
            changeStamp++;
        }
    }

    // ── Passe d'arrière-plan (worker RestyleEngine) ────────────────

    /**
     * Avance d'UN morceau ({@value #CHUNK} lignes) : piste intégrée
     * d'abord, puis piste grammaires. La tokénisation du morceau se
     * fait HORS verrou (un morceau grammaires dure des dizaines de ms
     * — le fil UI ne doit jamais attendre) ; la publication sous
     * verrou re-valide le jeton de changement. Retourne
     * {@code true} tant qu'il reste du travail.
     */
    boolean fillNextChunk() {
        long stamp;
        boolean tmPass;
        int from;
        int tailIn;
        synchronized (lock) {
            if (intValid < lineCount) {
                tmPass = false;
                from = intValid;
                tailIn = intTail;
            } else if (tmValid < lineCount) {
                tmPass = true;
                from = tmValid;
                tailIn = tmTail;
            } else {
                return false;
            }
            stamp = changeStamp;
        }
        // Calcul hors verrou : au plus CHUNK lignes de texte.
        int max = Math.min(CHUNK, lineCount - from);
        int[] entries = new int[max];
        int tail = tailIn;
        String lang = host.progressiveLanguage();
        boolean allow = host.progressiveAllowTextMate();
        for (int i = 0; i < max; i++) {
            entries[i] = tail;
            String line = host.progressiveLineText(from + i);
            if (!tmPass) {
                tail = highlighter.styleLine(line, tail, lang, false).exitState;
            } else if (allow && line.length() <= SyntaxHighlighter.TEXTMATE_CHAR_LIMIT) {
                tail = highlighter.styleLine(line, tail, lang, true).exitState;
            } else {
                // Ligne monstre : la pile grammaires est FIGÉE pour
                // cette ligne (P1-a) — pas de tokénisation grammaires.
            }
        }
        int to;
        boolean intCompleted = false;
        boolean tmCompleted = false;
        synchronized (lock) {
            if (changeStamp != stamp) {
                return true; // épissure/reset concurrent : jeter, la tâche relancée reprend
            }
            if (!tmPass) {
                if (from != intValid) return true; // déjà avancé ailleurs : jeter
                System.arraycopy(entries, 0, intEntry, from, max);
                intTail = tail;
                intValid = from + max;
                to = intValid;
                intCompleted = intValid >= lineCount;
            } else {
                if (from != tmValid) return true;
                System.arraycopy(entries, 0, tmEntry, from, max);
                tmTail = tail;
                tmValid = from + max;
                to = tmValid;
                tmCompleted = tmValid >= lineCount;
            }
            purgeUpgradedLocked(from, to, tmPass ? TIER_TM : TIER_INT);
        }
        int hf = hintFirst;
        int hl = hintLast;
        boolean crossedHint = hf >= 0 && from <= hl && to > hf;
        if (crossedHint || intCompleted || tmCompleted) {
            host.progressiveUpgraded(from, to);
        }
        return true;
    }

    /** Marque le hint de viewport (fil UI, léger). */
    void hintViewport(int first, int last) {
        if (first < 0) first = 0;
        hintFirst = first;
        hintLast = Math.max(last, first);
    }

    /** Bornes de validité courantes (diagnostic/tests). */
    int integratedValid() {
        synchronized (lock) {
            return intValid;
        }
    }

    int textMateValid() {
        synchronized (lock) {
            return tmValid;
        }
    }

    /** Taille du cache de spans (diagnostic/tests). */
    int spanCacheSize() {
        synchronized (lock) {
            return spans.size();
        }
    }

    // ── Avance synchronisée des pistes (sous lock) ────────────────

    /**
     * Avance la piste INTÉGRÉE jusqu'à couvrir la ligne cible
     * (exclue), dans la limite de {@code max} lignes. Coût ~1,5
     * µs/ligne — appelé depuis le fil UI (init, rattrapage borné).
     */
    private void advanceIntegratedLocked(int targetLine, int max) {
        String lang = host.progressiveLanguage();
        int advanced = 0;
        while (intValid <= targetLine && intValid < lineCount && advanced < max) {
            String line = host.progressiveLineText(intValid);
            int exit = highlighter.styleLine(line, intTail, lang, false).exitState;
            int next = intValid + 1;
            if (next < lineCount) intEntry[next] = exit;
            intTail = exit;
            intValid = next;
            advanced++;
        }
    }

    /**
     * Avance la piste GRAMMAIRES jusqu'à couvrir la ligne cible
     * (exclue), dans la limite de {@code max} lignes — budget plein
     * (passe d'arrière-plan). Sous verrou : réservé aux petits
     * segments (init, épissure bornée).
     */
    private void advanceTmLocked(int targetLine, int max) {
        String lang = host.progressiveLanguage();
        boolean allow = host.progressiveAllowTextMate();
        int advanced = 0;
        while (tmValid <= targetLine && tmValid < lineCount && advanced < max) {
            String line = host.progressiveLineText(tmValid);
            int exit = tmTail;
            if (allow && line.length() <= SyntaxHighlighter.TEXTMATE_CHAR_LIMIT) {
                exit = highlighter.styleLine(line, tmTail, lang, true).exitState;
            }
            // Ligne monstre : pile figée (P1-a) — exit = entrée.
            int next = tmValid + 1;
            if (next < lineCount) tmEntry[next] = exit;
            tmTail = exit;
            tmValid = next;
            advanced++;
        }
    }

    /** Purge les entrées de cache dont la couche vient d'être dépassée. */
    private void purgeUpgradedLocked(int from, int to, int nowTier) {
        List<Integer> toRemove = null;
        for (Map.Entry<Integer, Cached> e : spans.entrySet()) {
            int i = e.getKey();
            if (i >= from && i < to) {
                if ((nowTier == TIER_INT && e.getValue().tier == TIER_PLAIN)
                        || (nowTier == TIER_TM && e.getValue().tier == TIER_INT)) {
                    (toRemove == null ? toRemove = new ArrayList<>() : toRemove).add(i);
                }
            }
        }
        if (toRemove != null) {
            for (Integer i : toRemove) {
                spans.remove(i);
                host.progressiveBumpRevision(i);
            }
        }
    }

    // ── Épissure (fil UI, moniteur session) ────────────────────────

    /**
     * Épisse une édition : décale les pistes, re-chaîne les lignes
     * insérées (bornes synchrones), cascade de stabilisation bornée,
     * tronque les marques d'eau au-delà — le worker reprend la suite.
     *
     * <p>Retourne la fin EXCLUSIVE de la zone purgée du cache de spans
     * (zone où les états d'au moins une piste ont pu changer : lignes
     * insérées + cascade jusqu'à re-stabilisation, ou tout l'aval si
     * la cascade n'a pas convergé dans sa borne) — l'hôte y incrément
     * les tampons de révision par ligne.</p>
     *
     * @param firstLine     première ligne remplacée
     * @param removedLines  nombre de lignes remplacées
     * @param insertedLines nombre de lignes insérées
     */
    int splice(int firstLine, int removedLines, int insertedLines) {
        synchronized (lock) {
            int oldN = lineCount;
            int newN = host.progressiveLineCount();
            int delta = insertedLines - removedLines;

            // Capacité : ne réallouer qu'en croissance ; la queue est
            // décalée SUR PLACE (zéro allocation par frappe en régime
            // établi — la capacité survit aux éditions).
            intEntry = ensureCapacity(intEntry, newN);
            tmEntry = ensureCapacity(tmEntry, newN);

            // Décaler la queue des deux pistes sur le nouveau découpage
            // AVANT le re-chaînage (le re-chaînage lit les positions
            // décalées comme « anciennes » entrées).
            shiftTail(intEntry, oldN, firstLine, removedLines, insertedLines);
            shiftTail(tmEntry, oldN, firstLine, removedLines, insertedLines);

            // Entrée de la première ligne éditée = sortie de la ligne
            // précédente (inchangée) = ancienne entrée de firstLine.
            int baseInt = firstLine <= 0 ? 0
                    : firstLine < intValid ? intEntry[firstLine] : intTail;
            int baseTm = firstLine <= 0 ? 0
                    : firstLine < tmValid ? tmEntry[firstLine] : tmTail;

            // result[0] = veston ; result[1] = première ligne
            // re-stabilisée (-1 : cascade non convergente / tronquée).
            int[] intResult = new int[2];
            int newIntValid = spliceTrack(intEntry, intValid,
                    baseInt, firstLine, removedLines, insertedLines, newN,
                    false, SYNC_ADVANCE_MAX, CASCADE_MAX, intResult);
            intValid = newIntValid;
            intTail = intResult[0];

            int[] tmResult = new int[2];
            int newTmValid = spliceTrack(tmEntry, tmValid,
                    baseTm, firstLine, removedLines, insertedLines, newN,
                    true, TM_SYNC_MAX, TM_SYNC_MAX, tmResult);
            tmValid = newTmValid;
            tmTail = tmResult[0];

            lineCount = newN;

            // Zone où les états d'au moins une piste ont pu changer :
            // lignes insérées + jusqu'à re-stabilisation de chaque
            // piste (toute l'aval si une cascade n'a pas convergé).
            int endInt = intResult[1] >= 0 ? intResult[1] : newN;
            int endTm = tmResult[1] >= 0 ? tmResult[1] : newN;
            int purgeEnd = Math.max(endInt, endTm);

            shiftSpanCacheLocked(firstLine, removedLines, insertedLines, delta, newN);
            purgeSpanRangeLocked(firstLine, purgeEnd);
            changeStamp++;
            return purgeEnd;
        }
    }

    /** Purge les entrées de cache d'une zone d'instabilité. */
    private void purgeSpanRangeLocked(int from, int toExclusive) {
        if (from >= toExclusive) return;
        Iterator<Map.Entry<Integer, Cached>> it = spans.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Cached> e = it.next();
            int key = e.getKey();
            if (key >= from && key < toExclusive) {
                it.remove();
            }
        }
    }

    /**
     * Re-chaîne une piste après épissure (SUR PLACE : les entrées de
     * la queue sont déjà décalées) : lignes insérées (borne
     * {@code syncMax}) puis cascade de stabilisation (borne
     * {@code cascadeMax}). Retourne la nouvelle marque d'eau ;
     * {@code result[0]} reçoit le veston, {@code result[1]} la
     * première ligne re-stabilisée (-1 : non convergente / tronquée).
     */
    private int spliceTrack(int[] entries, int oldValid, int baseEntry,
            int firstLine, int removedLines, int insertedLines, int newN,
            boolean tmTrack, int syncMax, int cascadeMax, int[] result) {
        String lang = host.progressiveLanguage();
        boolean allow = host.progressiveAllowTextMate();
        int entry = baseEntry;
        int wm = Math.min(oldValid, firstLine);
        int i = firstLine;
        int budget = Math.min(insertedLines, syncMax);
        for (; i < firstLine + budget && i < newN; i++) {
            entries[i] = entry;
            String line = host.progressiveLineText(i);
            entry = trackExit(line, entry, lang, tmTrack, allow);
            wm = i + 1;
        }
        if (i < firstLine + insertedLines) {
            // Insertion massive : le reste part au worker. Le veston
            // porte l'entrée de la première ligne non chaînée ; tout
            // l'aval est réputé changée.
            if (wm < newN) entries[wm] = entry;
            result[0] = entry;
            result[1] = -1;
            return wm;
        }
        // Cascade sur les lignes survivantes : la queue décalée est
        // DÉJÀ en place — l'ancienne entrée de la ligne k se lit à la
        // position k elle-même (décalage préalable), comparable si
        // l'ancien indice k - delta était sous la marque d'eau.
        int delta = insertedLines - removedLines;
        int k = firstLine + insertedLines;
        int cascaded = 0;
        while (k < newN && cascaded < cascadeMax) {
            int oldIdx = k - delta;
            int oldEntry = oldIdx >= 0 && oldIdx < oldValid ? entries[k] : UNKNOWN;
            if (oldEntry != UNKNOWN && oldEntry == entry) {
                break; // stabilisé : le suffixe garde ses anciens états
            }
            entries[k] = entry;
            String line = host.progressiveLineText(k);
            entry = trackExit(line, entry, lang, tmTrack, allow);
            k++;
            cascaded++;
        }
        if (k >= newN) {
            result[0] = entry;
            result[1] = -1; // chaîné jusqu'au bout : zone = tout l'aval
            return newN;
        }
        if (cascaded >= cascadeMax) {
            // non stabilisé dans la borne : tronquer, worker ensuite
            if (k < newN) entries[k] = entry;
            result[0] = entry;
            result[1] = -1;
            return k;
        }
        // Stabilisé : le suffixe décalé est déjà en place — la marque
        // d'eau couvre l'ancienne zone valide décalée.
        int newWm = Math.max(k, Math.min(newN, oldValid + delta));
        result[0] = newWm > 0 && newWm < newN ? entries[newWm] : entry;
        result[1] = k; // première ligne inchangée
        return newWm;
    }

    /**
     * Croît un tableau à capacité (1,5×) — jamais de rétrécissement :
     * les épissures suivantes décalent la queue sur place, sans
     * allocation par frappe.
     */
    private static int[] ensureCapacity(int[] a, int newN) {
        if (a.length >= newN) return a;
        int grown = Math.max(newN, a.length + (a.length >> 1));
        return java.util.Arrays.copyOf(a, grown);
    }

    /**
     * Décale la queue [firstLine+removedLines, oldN) vers
     * [firstLine+insertedLines, …) DANS LE MÊME tableau (System.arraycopy
     * gère le chevauchement ; la capacité est garantie par
     * {@link #ensureCapacity}).
     */
    private static void shiftTail(int[] a, int oldN, int firstLine,
            int removedLines, int insertedLines) {
        int srcFrom = Math.min(firstLine + removedLines, oldN);
        int len = oldN - srcFrom;
        if (len > 0 && insertedLines != removedLines) {
            System.arraycopy(a, srcFrom, a, firstLine + insertedLines, len);
        }
    }

    /** Sortie d'une ligne pour une piste donnée (épissure). */
    private int trackExit(String line, int entry, String lang,
            boolean tmTrack, boolean allowTextMate) {
        if (tmTrack) {
            if (allowTextMate && line.length() <= SyntaxHighlighter.TEXTMATE_CHAR_LIMIT) {
                return highlighter.styleLine(line, entry, lang, true).exitState;
            }
            return entry; // pile grammaires figée (P1-a)
        }
        return highlighter.styleLine(line, entry, lang, false).exitState;
    }

    /**
     * Décale le cache de spans sur le nouveau découpage en lignes :
     * les entrées avant l'épissure sont conservées, celles de l'aval
     * décalées de {@code delta}, celles de la zone remplacée
     * abandonnées (la purge d'instabilité les couvre de toute façon).
     */
    private void shiftSpanCacheLocked(int firstLine, int removedLines,
            int insertedLines, int delta, int newN) {
        if (delta == 0 && removedLines == 0) {
            return; // remplacement à nombre constant : rien à décaler
        }
        LinkedHashMap<Integer, Cached> shifted = new LinkedHashMap<>(
                SPAN_CACHE_MAX * 2, 0.75f, true);
        for (Map.Entry<Integer, Cached> e : spans.entrySet()) {
            int key = e.getKey();
            if (key < firstLine) {
                shifted.put(key, e.getValue());
            } else if (key >= firstLine + removedLines) {
                int nk = key + delta;
                if (nk >= 0 && nk < newN) shifted.put(nk, e.getValue());
            }
        }
        spans.clear();
        spans.putAll(shifted);
    }
}
