package jo.codeeditor.textmate.regex;

import jo.codeeditor.util.EditorLog;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Budget et journal des essais de correspondance (plan 10 §3.2, D4).
 *
 * <p>Chaque essai de {@link LineMatcher} est budgété : au-delà du
 * délai, le moteur interrompt l'essai et la couture le replie en
 * non-correspondance. La valeur est une PROPOSITION D4 (100 ms — la
 * référence industrielle est 300 ms, mobile : plus réactif), calée au
 * banc en P3 sans nouveau plan.</p>
 *
 * <p>Le journal est THROTTLÉ par source de motif : une source ne
 * s'exprime qu'une fois (jamais par ligne), l'ensemble est borné — un
 * flux adversarial ne remplit pas les logs.</p>
 */
public final class MatchBudget {

    /**
     * Budget par essai de correspondance, en nanosecondes
     * (proposition D4 : 100 ms — cf. plan 10 §10).
     */
    public static final long MATCH_TIMEOUT_NS = 100_000_000L;

    /**
     * Bornage du throttle : au-delà de MAX_LOGGED_SOURCES sources
     * distinctes journalisées, le tampon se vide (les sources déjà
     * vues peuvent re-parler une fois) — un motif adversarial ne
     * construit pas un ensemble infini.
     */
    private static final int MAX_LOGGED_SOURCES = 256;

    private static final Set<String> LOGGED =
            Collections.synchronizedSet(new HashSet<>());

    private MatchBudget() {
    }

    /** Essai interrompu par le budget (une fois par source de motif). */
    static void logInterrupted(String patternSource, String lineText) {
        if (!claim(patternSource)) return;
        String extrait = lineText.length() > 60
                ? lineText.substring(0, 60) + "…" : lineText;
        EditorLog.get().warn("cel-textmate",
                "budget de correspondance écoulé (100 ms) — motif « "
                        + extrait(patternSource) + " » sur « " + extrait
                        + " » : essai replié en non-correspondance");
    }

    /** Exception du moteur pendant un essai (une fois par source). */
    static void logMatchFailure(String patternSource, RuntimeException e) {
        if (!claim(patternSource)) return;
        EditorLog.get().warn("cel-textmate",
                "échec de correspondance du moteur — motif « "
                        + extrait(patternSource) + " » : " + e.getMessage()
                        + " (essai replié en non-correspondance)", e);
    }

    /** Échec de compilation d'une fermante résolue (une fois par source). */
    public static void logEndCompileFailure(String patternSource, RuntimeException e) {
        if (!claim(patternSource)) return;
        EditorLog.get().warn("cel-textmate",
                "fermante au dialecte intraduisible — motif « "
                        + extrait(patternSource) + " » : " + e.getMessage()
                        + " (le cadre ne se referme jamais)");
    }

    private static boolean claim(String patternSource) {
        synchronized (LOGGED) {
            if (LOGGED.size() >= MAX_LOGGED_SOURCES) {
                LOGGED.clear();
            }
            return LOGGED.add(patternSource);
        }
    }

    private static String extrait(String s) {
        return s != null && s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
