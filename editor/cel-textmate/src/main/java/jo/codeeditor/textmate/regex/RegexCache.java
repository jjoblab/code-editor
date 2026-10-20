package jo.codeeditor.textmate.regex;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache LRU des motifs compilés (plan 10 §3.2) — clé : la source
 * APRÈS normalisation, ce qui couvre les fermantes et gardes {@code while}
 * résolues avec les backrefs du {@code begin} (deux ouvertures du même
 * cadre avec la même capture partagent le motif compilé).
 *
 * <p>LRU maison, aucune dépendance supplémentaire ; capacité D4
 * (proposition 512 — cf. plan 10 §10), synchronisé : le tokéniseur peut
 * être appelé depuis plusieurs threads de restyle.</p>
 */
public final class RegexCache {

    /**
     * Capacité du cache (proposition D4 — cf. plan 10 §10).
     */
    public static final int CAPACITY = 512;

    private static final Map<String, CompiledRegex> LRU =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CompiledRegex> eldest) {
                    return size() > CAPACITY;
                }
            };

    private RegexCache() {
    }

    /**
     * Motif compilé pour la source (normalisée en interne). Les échecs
     * de compilation ne sont PAS cachés : l'appelant décide du repli.
     */
    public static CompiledRegex getOrCompile(String rawSource) {
        String key = DialectNormalizer.normalize(rawSource);
        synchronized (LRU) {
            CompiledRegex hit = LRU.get(key);
            if (hit != null) return hit;
        }
        CompiledRegex compiled = CompiledRegex.compile(rawSource);
        synchronized (LRU) {
            LRU.put(compiled.source(), compiled);
        }
        return compiled;
    }

    /** Nombre d'entrées courantes (sonde de test). */
    static int size() {
        synchronized (LRU) {
            return LRU.size();
        }
    }
}
