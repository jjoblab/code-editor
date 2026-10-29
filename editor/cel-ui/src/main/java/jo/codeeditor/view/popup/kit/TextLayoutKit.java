package jo.codeeditor.view.popup.kit;

import java.util.ArrayList;
import java.util.List;

/**
 * Outils de mise en ligne du texte des popups — classe PURE : aucune
 * dépendance Android, la mesure de texte est injectée via
 * {@link Measurer} (adaptateur {@code Paint.measureText} côté vue).
 * La logique se teste donc sans Canvas ni Robolectric.
 *
 * <p>Remplace les copies historiques du retour à la ligne
 * (mesure caractère par caractère — O(n²) — et absence de coupe d'un
 * mot plus large que la ligne) et les boucles d'ellipse à retrait de
 * DEUX caractères (sur-coupe systématique d'un caractère utile). Les
 * recherches de point de coupe sont DICHOTOMIQUES : une mesure par
 * itération au lieu d'une par caractère.</p>
 */
public final class TextLayoutKit {

    /** Ellipse unique des popups — jamais trois points ASCII. */
    public static final String ELLIPSIS = "…";

    /**
     * Mesure de la largeur d'un texte (px). L'implémentation de la vue
     * délègue au paint courant — la mesure suit l'état du paint au
     * moment de l'appel (taille posée par l'appelant, jamais résiduelle).
     */
    public interface Measurer {
        float width(String text);
    }

    /** Mode de troncature avec ellipse. */
    public enum Truncate { TAIL, HEAD, MIDDLE }

    private TextLayoutKit() {}

    // ════════════════════════════════════════════════════════════════
    // Retour à la ligne
    // ════════════════════════════════════════════════════════════════

    /**
     * Découpe {@code text} pour tenir dans {@code maxWidth} (px) :
     * accumulation gloutonne mot à mot (UNE mesure par mot), coupe par
     * caractères d'un mot plus large que la ligne (dichotomie),
     * retours à la ligne explicites respectés. Un paragraphe vide
     * (double {@code \n}) produit une ligne vide de respiration ; les
     * paragraphes vides de QUEUE sont ignorés (motif de l'ancien
     * découpage par {@code split}). Les espaces ne migrent jamais en
     * tête de ligne coupée.
     *
     * @param text le texte à découper (null → liste vide)
     * @param maxWidth la largeur maximale d'une ligne (px)
     * @param m la mesure de texte injectée
     * @return les lignes (jamais null, vide pour un texte vide)
     */
    public static List<String> wrap(String text, float maxWidth, Measurer m) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int n = text.length();
        int paraStart = 0;
        while (paraStart < n) {
            int nl = text.indexOf('\n', paraStart);
            int paraEnd = nl < 0 ? n : nl;
            wrapParagraph(text, paraStart, paraEnd, maxWidth, m, out);
            if (nl < 0) break;
            paraStart = nl + 1;
        }
        // Paragraphes vides de queue ignorés (compatibilité avec
        // l'ancien split("\n") : un \n final ne crée pas de ligne vide).
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * Découpe le paragraphe [{@code start}, {@code end}) du texte :
     * accumulation gloutonne mot à mot ; un mot plus large que la
     * largeur est coupé par caractères, sa queue devient la ligne
     * courante (le mot suivant peut la rejoindre).
     */
    private static void wrapParagraph(String text, int start, int end,
            float maxWidth, Measurer m, List<String> out) {
        if (start >= end) {
            out.add("");
            return;
        }
        // Paragraphe tout blanc : une ligne, telle quelle (le wrap ne
        // sait pas couper ce qui ne contient aucun mot).
        boolean blanc = true;
        for (int i = start; i < end; i++) {
            if (!estEspace(text.charAt(i))) { blanc = false; break; }
        }
        if (blanc) {
            out.add(text.substring(start, end));
            return;
        }
        StringBuilder line = new StringBuilder();
        int i = start;
        while (i < end) {
            // Espaces séparateurs : au plus un entre deux mots conservés.
            while (i < end && estEspace(text.charAt(i))) i++;
            if (i >= end) break;
            int motStart = i;
            while (i < end && !estEspace(text.charAt(i))) i++;

            // Mot plus large que la LIGNE : la ligne courante part
            // d'abord, puis le mot est coupé par caractères.
            if (mesure(m, text, motStart, i) > maxWidth) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                int ws = motStart;
                while (ws < i) {
                    int cut = plusLongPrefixe(text, ws, i, maxWidth, m);
                    if (cut <= ws) cut = ws + 1; // garde-fou : 1 caractère
                    if (cut >= i) {
                        // La queue du mot ouvre la ligne courante : le
                        // mot suivant pourra la rejoindre.
                        line.append(text, ws, i);
                        break;
                    }
                    out.add(text.substring(ws, cut));
                    ws = cut;
                }
                continue;
            }

            // Accumulation gloutonne : le mot rejoint la ligne s'il y
            // tient AVEC le séparateur, sinon la ligne part et le mot
            // ouvre la suivante.
            int lineLenAvant = line.length();
            if (lineLenAvant > 0) line.append(' ');
            line.append(text, motStart, i);
            if (lineLenAvant > 0
                    && mesure(m, line, 0, line.length()) > maxWidth) {
                line.setLength(lineLenAvant);
                out.add(line.toString());
                line.setLength(0);
                line.append(text, motStart, i);
            }
        }
        if (line.length() > 0) out.add(line.toString());
    }

    // ════════════════════════════════════════════════════════════════
    // Ellipse
    // ════════════════════════════════════════════════════════════════

    /**
     * Tronque {@code text} avec ellipse pour tenir dans
     * {@code maxWidth} (px), dans le mode demandé :
     * <ul>
     *   <li>{@link Truncate#TAIL} — la fin cède : {@code "préfixe…"}</li>
     *   <li>{@link Truncate#HEAD} — le début cède : {@code "…suffixe"}</li>
     *   <li>{@link Truncate#MIDDLE} — le milieu cède, équilibré :
     *       {@code "tête…queue"}</li>
     * </ul>
     * Un texte tenant déjà est retourné TEL QUEL (aucune coupe). Une
     * largeur nulle ou inférieure à l'ellipse renvoie la chaîne vide ;
     * la coupe est DICHOTOMIQUE (le plus de texte possible est gardé,
     * là où l'ancienne boucle à retrait de 2 caractères perdait un
     * caractère utile par itération).
     *
     * @param text le texte (null → "")
     * @param maxWidth la largeur disponible, ellipse comprise (px)
     * @param mode le mode de troncature
     * @param m la mesure de texte injectée
     * @return le texte ellipsé (jamais null)
     */
    public static String ellipsize(String text, float maxWidth,
            Truncate mode, Measurer m) {
        if (text == null) return "";
        if (text.isEmpty()) return text;
        if (m.width(text) <= maxWidth) return text;
        float ellW = m.width(ELLIPSIS);
        if (maxWidth < ellW) return "";
        switch (mode) {
            case TAIL: {
                int k = plusLongPrefixe(text, 0, text.length(),
                        maxWidth - ellW, m);
                return k <= 0 ? ELLIPSIS : text.substring(0, k) + ELLIPSIS;
            }
            case HEAD: {
                int k = plusCourtSuffixe(text, 0, text.length(),
                        maxWidth - ellW, m);
                return k >= text.length()
                        ? ELLIPSIS + text
                        : ELLIPSIS + text.substring(k);
            }
            case MIDDLE:
            default: {
                // Nombre total de caractères conservés n : la tête garde
                // ceil(n/2) caractères, la queue floor(n/2).
                int lo = 0, hi = text.length();
                while (lo < hi) {
                    int n = (lo + hi + 1) >>> 1;
                    if (têteQueueTient(text, n, maxWidth, m)) lo = n;
                    else hi = n - 1;
                }
                if (lo <= 0) return ELLIPSIS;
                if (lo >= text.length()) return text;
                return têteQueue(text, lo);
            }
        }
    }

    /** La coupe équilibrée de n caractères conservés : tête + … + queue. */
    private static String têteQueue(String text, int n) {
        int h = (n + 1) / 2;
        return text.substring(0, h) + ELLIPSIS
                + text.substring(text.length() - (n - h));
    }

    private static boolean têteQueueTient(String text, int n,
            float maxWidth, Measurer m) {
        return m.width(têteQueue(text, n)) <= maxWidth;
    }

    // ════════════════════════════════════════════════════════════════
    // Recherches dichotomiques de point de coupe
    // ════════════════════════════════════════════════════════════════

    /**
     * Plus grand {@code k ≤ end} tel que la largeur de
     * {@code text[begin..k)} soit ≤ {@code maxW} (dichotomie — la
     * largeur est croissante avec le préfixe).
     */
    private static int plusLongPrefixe(String text, int begin, int end,
            float maxW, Measurer m) {
        if (maxW <= 0f) return begin;
        int lo = begin, hi = end;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (mesure(m, text, begin, mid) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /**
     * Plus petit {@code k ≥ begin} tel que la largeur de
     * {@code text[k..end)} soit ≤ {@code maxW} (dichotomie).
     */
    private static int plusCourtSuffixe(String text, int begin, int end,
            float maxW, Measurer m) {
        int lo = begin, hi = end;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (mesure(m, text, mid, end) <= maxW) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** Mesure d'une plage du texte (une seule sous-chaîne allouée). */
    private static float mesure(Measurer m, CharSequence text,
            int begin, int end) {
        if (begin >= end) return 0f;
        String s = text.subSequence(begin, end).toString();
        return m.width(s);
    }

    private static boolean estEspace(char c) {
        return Character.isWhitespace(c);
    }
}
