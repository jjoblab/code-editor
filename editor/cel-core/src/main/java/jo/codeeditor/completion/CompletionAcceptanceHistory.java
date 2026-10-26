package jo.codeeditor.completion;

import java.util.HashMap;
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
     * « effacer l'apprentissage » de l'hôte.
     */
    public synchronized void clear() {
        byLanguage.clear();
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
