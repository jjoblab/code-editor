package jo.codeeditor.completion;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Historique d'acceptation des candidats de complétion — le moteur de
 * l'apprentissage du classement local.
 *
 * <p>Chaque acceptation du popup est enregistrée par couple
 * (langage, identifiant de tête) : le nombre d'acceptations et la
 * fraîcheur de la dernière. Au prochain classement, un candidat déjà
 * accepté remonte <b>à palier et groupe égaux</b> — l'apprentissage ne
 * franchit JAMAIS la barrière d'un palier ni d'un groupe (un candidat
 * déjà accepté mais apparié plus lâchement reste derrière un candidat
 * plus précis jamais accepté), et à l'intérieur d'un couple (palier,
 * groupe) l'ordre du serveur reste la référence pour les candidats
 * hors historique.</p>
 *
 * <h2>Score</h2>
 *
 * <p>{@link #boost(String, String)} encode un score décroissant de
 * force : nombre d'acceptations d'abord (fréquence), fraîcheur de la
 * dernière acceptation ensuite (à fréquence égale, le plus récemment
 * accepté passe devant). Le score d'un candidat inconnu vaut 0 —
 * neutre pour un tri stable.</p>
 *
 * <h2>Persistance (4.6.0)</h2>
 *
 * <p>{@link #snapshot()} photographie l'état appris sous une forme
 * immuable que l'hôte sérialise comme il l'entend (JSON, DataStore,
 * fichier) ; {@link #restore(Snapshot)} le remplace intégralement au
 * démarrage suivant. Le format est versionné
 * ({@link Snapshot#FORMAT_VERSION}) : un snapshot d'une version
 * inconnue est refusé silencieusement, jamais interprété à l'aveugle.
 * La fraîcheur du snapshot rebasse le compteur interne : une
 * acceptation TOUTE récente ne peut jamais paraître plus vieille
 * qu'une entrée restaurée. La bascule {@link #setEnabled(boolean)}
 * n'interdit ni la photographie ni la restauration — elle ne coupe que
 * la collecte et le boost.</p>
 *
 * <h2>Garde-fous</h2>
 *
 * <ul>
 *   <li>capacité bornée par langage ({@value #PER_LANGUAGE_CAPACITY}
 *       identifiants ; au-delà, l'entrée au score le plus faible est
 *       évincée) — la mémoire ne croît pas avec un document touffu ;</li>
 *   <li>bascule globale {@link #setEnabled(boolean)} : la collecte et
 *       le boost s'éteignent ensemble, sans perte de l'historique
 *       déjà collecté ;</li>
 *   <li>purge totale {@link #clear()} ou par langage
 *       {@link #clear(String)} — branchée sur le réglage utilisateur
 *       de l'hôte ;</li>
 *   <li>les langages sont des compartiments étanches : accepter
 *       {@code Text} en Java ne remonte pas {@code Text} en XML.</li>
 * </ul>
 *
 * <p>Classe thread-safe (méthodes synchronisées : le popup enregistre
 * et consulte sur le fil UI, la purge peut venir d'un réglage hôte).
 * Aucune dépendance Android — testable unitairement.</p>
 *
 * @since 4.5.0
 */
public final class CompletionAcceptanceHistory {

    /** Capacité maximale d'identifiants par langage (éviction du plus faible). */
    public static final int PER_LANGUAGE_CAPACITY = 200;

    /**
     * Photographie immuable de l'historique — l'unité de persistance
     * entre sessions de l'hôte.
     *
     * <p>La structure de données est volontairement plate et sans
     * type privé : {@code {langage → {identifiant → [fréquence,
     * séquence]}}} se sérialise en JSON tel quel et se relit par le
     * constructeur public — l'hôte choisit son stockage, la bibliothèque
     * n'impose ni format ni dépendance. La séquence est le compteur
     * de fraîcheur interne au moment de la photographie : opaque pour
     * l'hôte (sa signification n'est valable qu'au sein d'une même
     * instance), elle doit simplement faire l'aller-retour intacte.</p>
     *
     * <p>Immuabilité défensive : le constructeur copie les données
     * reçues et {@link #entries()} rend une copie — muter l'une ou
     * l'autre ne corrompt ni la source ni les restores futurs.</p>
     *
     * @since 4.6.0
     */
    public static final class Snapshot {

        /** Version courante du format (les snapshots plus récents sont refusés au restore). */
        public static final int FORMAT_VERSION = 1;

        /** Version des données portées (défensive : un snapshot lu garde SA version). */
        final int version;

        /** Copie profonde — jamais null, jamais exposée directement. */
        final Map<String, Map<String, long[]>> byLanguage;

        /**
         * Photographie depuis des données sérialisées par l'hôte
         * (relecture JSON, DataStore…) — {@code entries} suit le
         * format de {@link #entries()}, la version est celle qui avait
         * été sauvegardée (typiquement {@link #FORMAT_VERSION}).
         *
         * @param entries {langage → {identifiant → [fréquence, séquence]}}
         * @param version la version du format au moment de la sauvegarde
         */
        public Snapshot(Map<String, Map<String, long[]>> entries, int version) {
            Map<String, Map<String, long[]>> copy = new LinkedHashMap<>();
            if (entries != null) {
                for (Map.Entry<String, Map<String, long[]>> e : entries.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    Map<String, long[]> compartment = new LinkedHashMap<>();
                    for (Map.Entry<String, long[]> id : e.getValue().entrySet()) {
                        if (id.getKey() == null || id.getValue() == null) continue;
                        compartment.put(id.getKey(), id.getValue().clone());
                    }
                    copy.put(e.getKey(), compartment);
                }
            }
            this.byLanguage = copy;
            this.version = version;
        }

        Snapshot(Map<String, Map<String, Entry>> live, int version, boolean interne) {
            // Chemin interne depuis l'historique vivant : conversion des
            // Entry mutables vers la forme plate sérialisable.
            Map<String, Map<String, long[]>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Entry>> e : live.entrySet()) {
                Map<String, long[]> compartment = new LinkedHashMap<>();
                for (Map.Entry<String, Entry> id : e.getValue().entrySet()) {
                    Entry entry = id.getValue();
                    compartment.put(id.getKey(),
                            new long[]{entry.count, entry.lastAcceptedSeq});
                }
                copy.put(e.getKey(), compartment);
            }
            this.byLanguage = copy;
            this.version = version;
        }

        /** La version du format au moment de la sauvegarde. */
        public int version() {
            return version;
        }

        /**
         * Les données photographiées :
         * {@code {langage → {identifiant → [fréquence, séquence]}}} —
         * copie défensive profonde, muter la carte rendue n'affecte
         * ni la source ni les restores futurs.
         */
        public Map<String, Map<String, long[]>> entries() {
            Map<String, Map<String, long[]>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, long[]>> e : byLanguage.entrySet()) {
                Map<String, long[]> compartment = new LinkedHashMap<>();
                for (Map.Entry<String, long[]> id : e.getValue().entrySet()) {
                    compartment.put(id.getKey(), id.getValue().clone());
                }
                copy.put(e.getKey(), compartment);
            }
            return copy;
        }
    }

    /** Borne de la composante fraîcheur (l'encodage du score). */
    private static final long RECENCY_BITS = 0x1FFFFFL;

    /** Une entrée d'historique : fréquence + fraîcheur. */
    private static final class Entry {
        int count;
        long lastAcceptedSeq;
    }

    /** Compartiments par langage — jamais null (les clés sont validées). */
    private final Map<String, Map<String, Entry>> byLanguage = new HashMap<>();

    /** Compteur monotone d'acceptations (fraîcheur). */
    private long seq = 0;

    /**
     * Télémétrie (4.6.0) : acceptations comptabilisées depuis la
     * construction ou le dernier {@link #clear()} total — la purge par
     * langage ne les touche pas (mesure de session, pas un état par
     * langage). Les no-op (bascule fermée, entrées vides) ne comptent
     * pas, et les compteurs ne voyagent PAS dans le snapshot : ils
     * mesurent les acceptations VÉCUES par cette instance.
     */
    private long recordedAcceptances = 0;

    /**
     * Télémétrie : sous-ensemble des acceptations dont le candidat
     * était DÉJÀ appris au moment d'accepter — celles que le boost
     * avait fait remonter. Le rapport boosted/recorded mesure ce que
     * l'apprentissage RAPPORTE avant de l'enrichir (fuzzy récent,
     * boost par type de symbole…).
     */
    private long boostedAcceptances = 0;

    private boolean enabled = true;

    /**
     * Active ou désactive l'apprentissage (défaut : vrai). La
     * désactivation coupe la collecte ET le boost ; l'historique déjà
     * collecté est conservé (une réactivation reprend où il était).
     *
     * @param enabled {@code true} pour apprendre des acceptations
     */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** L'état courant de la bascule d'apprentissage. */
    public synchronized boolean isEnabled() {
        return enabled;
    }

    /**
     * Enregistre une acceptation. Appelé par le popup après insertion
     * effective du candidat ; sans effet si désactivé, si le langage
     * ou l'identifiant est vide/null (les libellés sans identifiant de
     * tête ne peuvent pas être ré-appariés plus tard).
     *
     * @param language   le nom canonique du langage (clé de compartiment)
     * @param identifier l'identifiant de tête du candidat accepté
     */
    public synchronized void record(String language, String identifier) {
        if (!enabled || language == null || language.isEmpty()
                || identifier == null || identifier.isEmpty()) {
            return;
        }
        Map<String, Entry> compartment = byLanguage.get(language);
        if (compartment == null) {
            compartment = new HashMap<>();
            byLanguage.put(language, compartment);
        }
        Entry entry = compartment.get(identifier);
        // Télémétrie AVANT la comptabilisation : une entrée existante
        // (count ≥ 1) signifie que le candidat accepté était déjà appris
        // — le boost l'avait remonté dans les listes précédentes.
        if (entry != null) {
            boostedAcceptances++;
        }
        recordedAcceptances++;
        if (entry == null) {
            entry = new Entry();
            compartment.put(identifier, entry);
        }
        // Comptabiliser AVANT l'éviction : l'éviction compare les
        // scores, une entrée à moitié initialisée (0, 0) serait le
        // faux plus faible — c'est l'ancienne acceptation unique qui
        // doit partir, jamais celle qu'on vient d'écrire.
        entry.count++;
        entry.lastAcceptedSeq = ++seq;
        if (compartment.size() > PER_LANGUAGE_CAPACITY) {
            evictWeakest(compartment);
        }
    }

    /**
     * Le score d'apprentissage d'un candidat — 0 pour un inconnu.
     * Décroissant de force : le nombre d'acceptations domine, la
     * fraîcheur départage les ex æquo. Un tri croissant sur
     * {@code -boost(...)} place les candidats appris en tête à
     * l'intérieur d'un couple (palier, groupe).
     *
     * @param language   le nom canonique du langage
     * @param identifier l'identifiant de tête du candidat
     * @return le score, 0 si inconnu ou apprentissage désactivé
     */
    public synchronized long boost(String language, String identifier) {
        if (!enabled || language == null || identifier == null) {
            return 0L;
        }
        Map<String, Entry> compartment = byLanguage.get(language);
        if (compartment == null) {
            return 0L;
        }
        Entry entry = compartment.get(identifier);
        if (entry == null) {
            return 0L;
        }
        return ((long) entry.count << 21) | (entry.lastAcceptedSeq & RECENCY_BITS);
    }

    /**
     * Purge TOUT l'historique, tous langages — branché sur le réglage
     * « effacer l'apprentissage » de l'hôte. Remet AUSSI les compteurs
     * de télémétrie à zéro (une purge est un nouveau départ de
     * l'apprentissage, donc de sa mesure).
     */
    public synchronized void clear() {
        byLanguage.clear();
        recordedAcceptances = 0;
        boostedAcceptances = 0;
    }

    /**
     * Purge l'historique d'un seul langage — utile quand l'hôte
     * change le langage d'un document et veut repartir d'un classement
     * vierge.
     *
     * @param language le nom canonique du langage à purger
     */
    public synchronized void clear(String language) {
        if (language != null) {
            byLanguage.remove(language);
        }
    }

    /**
     * Le nombre d'identifiants appris, tous langages — pour les
     * tableaux de bord de diagnostic.
     */
    public synchronized int size() {
        int total = 0;
        for (Map<String, Entry> compartment : byLanguage.values()) {
            total += compartment.size();
        }
        return total;
    }

    /**
     * Télémétrie : le nombre d'acceptations comptabilisées depuis la
     * construction ou le dernier {@link #clear()} total. Les no-op
     * (bascule fermée, langage ou identifiant vide) ne comptent pas,
     * et les compteurs ne voyagent PAS dans le snapshot — ils mesurent
     * les acceptations vécues par cette instance.
     *
     * @return les acceptations comptabilisées
     * @since 4.6.0
     */
    public synchronized long recordedAcceptanceCount() {
        return recordedAcceptances;
    }

    /**
     * Télémétrie : parmi les acceptations comptabilisées, celles dont
     * le candidat était DÉJÀ appris au moment d'accepter — le boost
     * les avait remontées dans les listes précédentes. Le rapport
     * {@code boostedAcceptanceCount() / recordedAcceptanceCount()}
     * mesure ce que l'apprentissage rapporte réellement avant
     * d'enrichir le mécanisme.
     *
     * @return les acceptations de candidats déjà appris
     * @since 4.6.0
     */
    public synchronized long boostedAcceptanceCount() {
        return boostedAcceptances;
    }

    /**
     * Photographie l'état appris — l'hôte la sérialise (JSON,
     * DataStore…) dans son {@code onStop} et la repasse à
     * {@link #restore(Snapshot)} au démarrage suivant. Fonctionne
     * même quand la collecte est désactivée (on photographie des
     * données, on ne collecte pas).
     *
     * @return un snapshot immuable, versionné, vide si rien d'appris
     * @since 4.6.0
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(byLanguage, Snapshot.FORMAT_VERSION, true);
    }

    /**
     * Remplace INTÉGRALEMENT l'historique courant par un snapshot —
     * le chemin de restauration au démarrage de l'hôte. No-op
     * silencieux si le snapshot est null ou d'une version inconnue
     * (un format du futur n'est jamais interprété à l'aveugle).
     *
     * <p>Garde-fous :</p>
     * <ul>
     *   <li>les entrées malformées (fréquence &lt; 1, séquence &lt; 1,
     *       arité ≠ 2, valeurs nulles) sont ignorées une à une — les
     *       entrées saines passent ;</li>
     *   <li>l'invariant capacitaire est rétabli DÈS le restore (les
     *       compartiments trop grands ne gardent que les plus forts
     *       scores) ;</li>
     *   <li>le compteur de fraîcheur est REBASSÉ au maximum des
     *       séquences restaurées — la première acceptation fraîche
     *       après restore ne peut jamais paraître plus vieille qu'une
     *       entrée restaurée.</li>
     * </ul>
     *
     * <p>La bascule {@link #setEnabled(boolean)} n'interdit pas le
     * restore : les données reviennent, le boost reste coupé tant que
     * la bascule est fermée. La bascule elle-même ne fait pas partie
     * du snapshot (c'est un réglage, pas une donnée apprise).</p>
     *
     * @param snapshot la photographie à restaurer (null → no-op)
     * @since 4.6.0
     */
    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null
                || snapshot.version != Snapshot.FORMAT_VERSION) {
            return;
        }
        Map<String, Map<String, Entry>> restauré = new LinkedHashMap<>();
        long maxSeq = seq;
        for (Map.Entry<String, Map<String, long[]>> e : snapshot.byLanguage.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null) {
                continue;
            }
            Map<String, Entry> compartment = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> id : e.getValue().entrySet()) {
                long[] data = id.getValue();
                if (id.getKey() == null || id.getKey().isEmpty()
                        || data == null || data.length != 2
                        || data[0] < 1 || data[1] < 1) {
                    continue;
                }
                Entry entry = new Entry();
                entry.count = (int) Math.min(data[0], Integer.MAX_VALUE);
                entry.lastAcceptedSeq = data[1];
                compartment.put(id.getKey(), entry);
                maxSeq = Math.max(maxSeq, data[1]);
            }
            if (!compartment.isEmpty()) {
                restauré.put(e.getKey(), compartment);
            }
        }
        // Rétablir l'invariant capacitaire AVANT de publier : un
        // snapshot forgé à la main (ou une rétrogradation de capacité)
        // ne gonfle jamais la mémoire au-delà de la promesse.
        for (Map<String, Entry> compartment : restauré.values()) {
            while (compartment.size() > PER_LANGUAGE_CAPACITY) {
                evictWeakest(compartment);
            }
        }
        byLanguage.clear();
        byLanguage.putAll(restauré);
        // Rebase de fraîcheur : le point subtil — sans lui, une
        // acceptation toute fraîche (seq faible) serait classée derrière
        // des entrées restaurées au seq élevé, inversant l'ordre de
        // fraîcheur à fréquence égale.
        seq = maxSeq;
    }

    /** Évince l'entrée au score le plus faible (fréquence, puis fraîcheur). */
    private void evictWeakest(Map<String, Entry> compartment) {
        String weakest = null;
        long weakestScore = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : compartment.entrySet()) {
            long score = ((long) e.getValue().count << 21)
                    | (e.getValue().lastAcceptedSeq & RECENCY_BITS);
            if (score < weakestScore) {
                weakestScore = score;
                weakest = e.getKey();
            }
        }
        if (weakest != null) {
            compartment.remove(weakest);
        }
    }
}
