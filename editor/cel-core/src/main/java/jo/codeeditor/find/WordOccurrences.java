package jo.codeeditor.find;

import java.util.ArrayList;
import java.util.List;

/**
 * Occurrences entières du mot sous le caret — moteur de calcul pur de la
 * surbrillance « mot courant » (4.4.0).
 *
 * <p>Trois briques réutilisées par le collaborateur de vue
 * {@code EditorWordOccurrences} :</p>
 * <ul>
 *   <li>{@link #wordAt(CharSequence, int)} — la plage du mot que touche le
 *   caret, dans les DEUX sens (caret au milieu, juste après ou juste avant
 *   le mot) ; {@code null} si le caret ne touche aucun caractère
 *   d'identifiant (blanc, ponctuation, texte vide) ;</li>
 *   <li>{@link #isEligibleWord(String)} — le mot mérite-t-il la
 *   surbrillance : uniquement des caractères d'identifiant ET premier
 *   caractère non numérique (les littéraux numériques et la ponctuation
 *   ne s'allument pas — un « ; » ou un « 42 » ne doit pas teinter tout le
 *   viewport) ;</li>
 *   <li>{@link #findAll(CharSequence, String)} — toutes les occurrences
 *   ENTIÈRES du mot, frontières = caractères non identifiants ou
 *   extrémités du texte, casse EXACTE (deux identifiants de casses
 *   différentes sont deux mots différents), collecte plafonnée à
 *   {@link #MAX_OCCURRENCES}.</li>
 * </ul>
 *
 * <p>Les frontières « mot entier » suivent la convention identifiant du
 * reste du moteur (lettre, chiffre, underscore, {@code $}) : contrairement
 * au mode « mot entier » de la recherche, {@code _} et {@code $} NE sont
 * PAS des frontières — {@code mon_mot} est UN mot.</p>
 */
public final class WordOccurrences {

    /** Plafond de collecte — miroir de {@link FindReplace#MAX_MATCHES}. */
    public static final int MAX_OCCURRENCES = 10_000;

    private WordOccurrences() {}

    /** Caractère d'identifiant : lettre, chiffre, underscore ou {@code $}. */
    static boolean isIdentChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    /**
     * Le mot est-il éligible à la surbrillance ? Un mot d'identifiant : au
     * moins un caractère, uniquement des caractères d'identifiant, et un
     * PREMIER caractère non numérique.
     */
    public static boolean isEligibleWord(String word) {
        if (word == null || word.isEmpty()) return false;
        if (Character.isDigit(word.charAt(0))) return false;
        for (int i = 0; i < word.length(); i++) {
            if (!isIdentChar(word.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Plage {@code [start, end)} du mot que touche le caret — le caractère
     * d'identifiant juste AVANT le caret a priorité (le caret posé en fin
     * de mot y reste rattaché), sinon celui juste APRÈS (caret posé juste
     * avant le mot).
     *
     * @return la plage, ou {@code null} si le caret ne touche aucun
     *         caractère d'identifiant (blanc, ponctuation, texte vide)
     */
    public static int[] wordAt(CharSequence text, int caret) {
        int len = text.length();
        if (len == 0) return null;
        int start;
        if (caret > 0 && isIdentChar(text.charAt(caret - 1))) {
            start = caret - 1;
            while (start > 0 && isIdentChar(text.charAt(start - 1))) start--;
        } else if (caret < len && isIdentChar(text.charAt(caret))) {
            start = caret;
        } else {
            return null;
        }
        int end = start + 1;
        while (end < len && isIdentChar(text.charAt(end))) end++;
        return new int[]{start, end};
    }

    /**
     * Toutes les occurrences ENTIÈRES du mot dans le texte (casse exacte,
     * frontières = caractères non identifiants ou extrémités), en ordre du
     * document et plafonnées à {@link #MAX_OCCURRENCES}.
     */
    public static List<Match> findAll(CharSequence text, String word) {
        List<Match> out = new ArrayList<>();
        if (word == null || word.isEmpty()) return out;
        String haystack = text.toString();
        int len = haystack.length();
        int wlen = word.length();
        int from = 0;
        while (from <= len - wlen) {
            int idx = haystack.indexOf(word, from);
            if (idx < 0) break;
            boolean leftBound = idx == 0 || !isIdentChar(haystack.charAt(idx - 1));
            boolean rightBound = idx + wlen >= len
                    || !isIdentChar(haystack.charAt(idx + wlen));
            if (leftBound && rightBound) {
                out.add(new Match(idx, idx + wlen));
                if (out.size() >= MAX_OCCURRENCES) break;
            }
            from = idx + 1;
        }
        return out;
    }

    /**
     * Index de l'occurrence contenant l'offset ({@code -1} si aucune) —
     * utilisé pour teinter en « courante » l'occurrence du mot sous le
     * caret.
     */
    public static int indexOfContaining(List<Match> matches, int offset) {
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            if (offset >= m.start && offset < m.end) return i;
        }
        return -1;
    }
}
