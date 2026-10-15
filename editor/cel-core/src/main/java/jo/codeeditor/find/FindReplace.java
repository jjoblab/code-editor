package jo.codeeditor.find;

import java.util.*;
import java.util.regex.*;

/**
 * Opérations de recherche/remplacement gérant les modes insensible à la
 * casse, mot entier et regex.
 * Recherche/remplacement avec expressions régulières.
 *
 * <p><b>D-05 (audit 3.69.0) — recherche regex SÛRE</b> : le mode regex
 * est enveloppé d'un garde-fou de budget temps et d'un plafond de
 * résultats. Une expression à retour sur soi-même catastrophique
 * ({@code (a+)+b} sur une longue suite de « a ») ne fige plus le thread
 * appelant : la vue garde-fou interrompt le moteur au-delà du budget
 * ({@link #REGEX_BUDGET_MS}) et le résultat porte l'état
 * {@code tooCostly} ; au-delà de {@link #MAX_MATCHES} correspondances,
 * la collecte s'arrête et le résultat porte {@code truncated}. L'état
 * est exposé pour signal à l'UI (barre de recherche).</p>
 */
public final class FindReplace {

    private FindReplace() {}

    /**
     * Plafond de correspondances collectées — au-delà, la recherche est
     * tronquée (état {@code truncated} dans {@link SearchResult}).
     */
    public static final int MAX_MATCHES = 10_000;

    /**
     * Budget temps du mode regex (par recherche) — au-delà, la
     * correspondance est abandonnée (état {@code tooCostly}). Borné
     * très en dessous du seuil d'ANR (5 s) : la frappe reste fluide.
     */
    public static final long REGEX_BUDGET_MS = 250;

    /**
     * Résultat d'une recherche sûre : correspondances (bornées par
     * {@link #MAX_MATCHES}) + signaux d'état pour l'UI.
     */
    public static final class SearchResult {
        /** Correspondances trouvées (au plus {@link #MAX_MATCHES}). */
        public final List<Match> matches;
        /** Vrai si la collecte s'est arrêtée au plafond de résultats. */
        public final boolean truncated;
        /** Vrai si l'expression a dépassé le budget temps (résultats partiels ou vides). */
        public final boolean tooCostly;

        SearchResult(List<Match> matches, boolean truncated, boolean tooCostly) {
            this.matches = matches;
            this.truncated = truncated;
            this.tooCostly = tooCostly;
        }

        @Override
        public String toString() {
            return "SearchResult(" + matches.size() + " correspondance(s)"
                    + (truncated ? ", tronqué" : "")
                    + (tooCostly ? ", trop coûteux" : "") + ")";
        }
    }

    /**
     * Exception du garde-fou temps — levée par les matchers gardés
     * ({@link #guardedMatcher}) quand l'expression dépasse le budget
     * ou que le thread est interrompu. Publique : les passes de
     * REMPLACEMENT (contrôleur) l'attrapent pour avorter proprement.
     */
    public static final class RegexBudgetExceededException extends RuntimeException {
        RegexBudgetExceededException(String message) {
            super(message);
        }
    }

    /**
     * Vue garde-fou du texte parcouru par le moteur regex : vérifie
     * l'échéance (et l'interruption du thread) pendant le
     * backtracking. Le contrôle est échantillonné (tous les
     * {@link #CHECK_EVERY} accès) : le surcoût est constant et
     * négligeable, l'échéance reste respectée à l'échelle de la
     * milliseconde.
     */
    private static final class GuardedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadlineNanos;
        private int checksSinceLast;

        /** Fréquence d'échantillonnage du contrôle d'échéance. */
        private static final int CHECK_EVERY = 1024;

        GuardedCharSequence(CharSequence delegate, long budgetMs) {
            this.delegate = delegate;
            this.deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        }

        private void checkBudget() {
            // Interruption : la recherche est annulée proprement.
            if (Thread.interrupted()) {
                throw new RegexBudgetExceededException(
                        "recherche interrompue");
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexBudgetExceededException(
                        "expression trop coûteuse : budget de " + REGEX_BUDGET_MS
                                + " ms dépassé");
            }
        }

        @Override
        public int length() {
            if (++checksSinceLast >= CHECK_EVERY) {
                checksSinceLast = 0;
                checkBudget();
            }
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++checksSinceLast >= CHECK_EVERY) {
                checksSinceLast = 0;
                checkBudget();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            if (++checksSinceLast >= CHECK_EVERY) {
                checksSinceLast = 0;
                checkBudget();
            }
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /**
     * Trouve toutes les occurrences de la requête dans le texte —
     * entrée SÛRE (D-05) : budget temps en mode regex, plafond de
     * résultats dans tous les modes, états {@code truncated} /
     * {@code tooCostly} exposés pour signal à l'UI.
     */
    public static SearchResult find(CharSequence text, String query, FindOptions opts) {
        if (query == null || query.isEmpty()) {
            return new SearchResult(Collections.emptyList(), false, false);
        }

        List<Match> matches = new ArrayList<>();
        boolean truncated = false;
        boolean tooCostly = false;

        if (opts.regex) {
            tooCostly = findRegexGuarded(text, query, opts, matches);
            // La collecte s'est arrêtée au plafond (ou l'a atteint juste
            // avant l'interruption) : signaler la troncature.
            truncated = matches.size() >= MAX_MATCHES;
        } else if (opts.wholeWord) {
            truncated = findWholeWord(text, query, opts, matches);
        } else {
            truncated = findPlain(text, query, opts, matches);
        }

        return new SearchResult(matches, truncated, tooCostly);
    }

    /**
     * Trouve toutes les occurrences de la requête dans le texte
     * (historique) — délègue à {@link #find} : la liste est bornée par
     * {@link #MAX_MATCHES}, les états d'avertissement ne sont pas
     * transportés.
     */
    public static List<Match> findMatches(CharSequence text, String query, FindOptions opts) {
        return find(text, query, opts).matches;
    }

    private static boolean findPlain(CharSequence text, String query, FindOptions opts, List<Match> out) {
        String haystack = text.toString();
        String needle = query;
        if (!opts.caseSensitive) {
            haystack = haystack.toLowerCase(java.util.Locale.ROOT);
            needle = needle.toLowerCase(java.util.Locale.ROOT);
        }
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) break;
            out.add(new Match(idx, idx + needle.length()));
            if (out.size() >= MAX_MATCHES) return true;   // plafond : tronqué
            from = idx + 1;
        }
        return false;
    }

    private static boolean findWholeWord(CharSequence text, String query, FindOptions opts, List<Match> out) {
        String haystack = text.toString();
        String needle = query;
        if (!opts.caseSensitive) {
            haystack = haystack.toLowerCase(java.util.Locale.ROOT);
            needle = needle.toLowerCase(java.util.Locale.ROOT);
        }
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) break;
            // Vérifie les frontières de mot
            boolean leftBound = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            boolean rightBound = idx + needle.length() >= haystack.length()
                || !Character.isLetterOrDigit(haystack.charAt(idx + needle.length()));
            if (leftBound && rightBound) {
                out.add(new Match(idx, idx + needle.length()));
                if (out.size() >= MAX_MATCHES) return true;   // plafond : tronqué
            }
            from = idx + 1;
        }
        return false;
    }

    /**
     * Mode regex GARDE-FOU (D-05) : le texte est enveloppé dans la vue
     * d'échéance — un backtracking catastrophique est interrompu au
     * budget. Retourne {@code true} si l'expression s'est avérée trop
     * coûteuse (résultats partiels ou vides).
     */
    private static boolean findRegexGuarded(CharSequence text, String query,
            FindOptions opts, List<Match> out) {
        try {
            Pattern pattern = compilePattern(query, opts);
            Matcher matcher = pattern.matcher(new GuardedCharSequence(text, REGEX_BUDGET_MS));
            while (matcher.find()) {
                out.add(new Match(matcher.start(), matcher.end()));
                // Plafond : la collecte s'arrête — find() émettra
                // truncated au vu de la taille atteinte.
                if (out.size() >= MAX_MATCHES) return false;
            }
        } catch (PatternSyntaxException e) {
            // Regex invalide : aucune correspondance renvoyée
        } catch (RegexBudgetExceededException e) {
            return true;
        }
        return false;
    }

    /**
     * Construction du {@link Pattern} — L'unique endroit (D-05) :
     * historiquement dupliquée entre le moteur de recherche et la
     * barre (flags dérivés des options : MULTILINE systématique,
     * CASE_INSENSITIVE selon l'option).
     */
    public static Pattern compilePattern(String query, FindOptions opts) {
        int flags = Pattern.MULTILINE;
        if (opts != null && !opts.caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
        return Pattern.compile(query, flags);
    }

    /**
     * Matcher GARDE-FOU pour les passes de remplacement (D-05) — même
     * budget que la recherche : un motif catastrophique interrompt la
     * passe par {@link RegexBudgetExceededException} au lieu de figer
     * le thread appelant (l'appelant attrape et avorte proprement).
     */
    public static Matcher guardedMatcher(Pattern pattern, CharSequence text) {
        return pattern.matcher(new GuardedCharSequence(text, REGEX_BUDGET_MS));
    }

    /**
     * Trouve l'index de la correspondance à ou après la position du caret
     * donnée. Boucle au début si aucune correspondance n'est trouvée après
     * le caret.
     *
     * @return index de correspondance, ou -1 si aucune
     */
    public static int matchIndexFrom(List<Match> matches, int caret) {
        if (matches.isEmpty()) return -1;
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).start >= caret) return i;
        }
        return 0; // bouclage
    }
}
