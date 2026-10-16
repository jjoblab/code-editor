package jo.codeeditor.textmate.internal;

/**
 * Traduction des regex du dialecte Oniguruma (TextMate) vers
 * {@link java.util.regex.Pattern} (C3-bis, compatibilité
 * grammaires tierces réelles).
 *
 * <p>Deux écarts signalés par les grammaires réelles :</p>
 * <ul>
 *   <li><b>commentaires inline {@code (?# …)}</b> — supportés par
 *       Oniguruma (python tierce en usage), REJETÉS par
 *       java.util.regex ({@code Unknown inline modifier}) : retirés
 *       (groupe non capturant de largeur nulle) ;</li>
 *   <li><b>accolade seule {@code {}</b> — littérale en Oniguruma
 *       (java tierce l'utilise pour les initialiseurs),
 *       « Illegal repetition » en java.util.regex : échappée en
 *       {@code \\{} SAUF quand elle ouvre un quantificateur valide
 *       ({@code {n}}, {@code {n,}}, {@code {n,m}}) ou qu'elle est
 *       dans une classe de caractères.</li>
 * </ul>
 *
 * <p>Le reste du dialecte utilisé par les grammaires de l'écosystème
 * ({@code (?x)} mode étendu, quantificateurs possessifs
 * {@code \\s*+}, lookaround, backreferences, {@code \\A \\z \\G}) est
 * déjà compatible java.util.regex et passe inchangé.</p>
 */
public final class RegexCompat {

    private RegexCompat() {}

    /**
     * Traduit une regex Oniguruma vers java.util.regex. Conservatrice :
     * ne touche QUE les écarts documentés — toute regex déjà
     * compatible ressort identique (octet pour octet si aucun écart).
     */
    public static String translate(String regex) {
        if (regex == null
                || (regex.indexOf("(?#") < 0 && regex.indexOf('{') < 0
                        && regex.indexOf("[:") < 0
                        && (!startsWithExtended(regex) || regex.indexOf('#') < 0))) {
            return regex;
        }
        // Mode étendu (?x) en tête : Java y traite un « # » DANS une
        // classe de caractères comme un début de commentaire (classe
        // jamais refermée) alors qu'Oniguruma le protège — python de
        // tierce en usage (« [-+#0 ] »). Les « # » de classe sont
        // échappés.
        boolean extended = startsWithExtended(regex);
        StringBuilder out = new StringBuilder(regex.length() + 8);
        int i = 0;
        boolean inClass = false; // dans une classe de caractères […]
        while (i < regex.length()) {
            char c = regex.charAt(i);
            if (c == '\\') {
                // Échappement : copier le caractère suivant tel quel.
                if (i + 1 < regex.length()) {
                    out.append(c).append(regex.charAt(i + 1));
                    i += 2;
                } else {
                    out.append(c);
                    i++;
                }
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                    out.append(c);
                } else if (c == '#' && extended) {
                    out.append("\\#");
                } else if (c == '[' && regex.startsWith(":", i + 1)) {
                    // Classe POSIX [:alpha:] (python tierce) —
                    // java.util.regex ne les connait PAS dans [\[…]] :
                    // traduites en \p{…} (la fermante de la classe POSIX
                    // ne referme donc pas la classe englobante).
                    int close = regex.indexOf(":]", i + 2);
                    if (close > 0) {
                        String posix = regex.substring(i + 2, close);
                        String java = posixClass(posix);
                        if (java != null) {
                            out.append(java);
                            i = close + 2;
                            continue;
                        }
                    }
                    out.append(c);
                } else {
                    out.append(c);
                }
                i++;
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                i++;
                continue;
            }
            // Commentaire inline (?# …) : jusqu'à la fermante.
            if (c == '(' && i + 2 < regex.length() && regex.charAt(i + 1) == '?'
                    && regex.charAt(i + 2) == '#') {
                int close = regex.indexOf(')', i + 3);
                if (close < 0) {
                    // Malformée : laisser java.util.regex la rejeter.
                    out.append(c);
                    i++;
                    continue;
                }
                i = close + 1;
                continue;
            }
            // Accolade ouvrante : littérale sauf quantificateur valide.
            if (c == '{') {
                if (isOpenQuantifier(regex, i)) {
                    out.append(c);
                } else {
                    out.append("\\{");
                }
                i++;
                continue;
            }
            // Accolade fermante orpheline : littérale dans les deux
            // dialectes — copie telle quelle.
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Vrai si la regex COMMENCE par le drapeau mode étendu (?x) —
     * la convention des grammaires réelles (python, docblock JS). */
    private static boolean startsWithExtended(String regex) {
        int i = 0;
        while (i < regex.length() && Character.isWhitespace(regex.charAt(i))) i++;
        return regex.startsWith("(?x)", i);
    }

    /**
     * Traduit un nom de classe POSIX vers la classe java.util.regex
     * équivalente — null si inconnu (copié tel quel).
     */
    private static String posixClass(String posix) {
        switch (posix) {
            case "alpha": return "\\p{Alpha}";
            case "digit": return "\\p{Digit}";
            case "alnum": return "\\p{Alnum}";
            case "upper": return "\\p{Upper}";
            case "lower": return "\\p{Lower}";
            case "space": return "\\p{Space}";
            case "punct": return "\\p{Punct}";
            case "xdigit": return "\\p{XDigit}";
            case "blank": return "\\p{Blank}";
            case "cntrl": return "\\p{Cntrl}";
            case "graph": return "\\p{Graph}";
            case "print": return "\\p{Print}";
            case "word": return "a-zA-Z0-9_";
            default: return null;
        }
    }

    /**
     * Vrai si l'accolade en i ouvre un quantificateur
     * {@code {n}}/{@code {n,}}/{@code {n,m}}.
     */
    private static boolean isOpenQuantifier(String regex, int i) {
        int j = i + 1;
        boolean digits = false;
        while (j < regex.length() && Character.isDigit(regex.charAt(j))) {
            digits = true;
            j++;
        }
        if (!digits) return false;
        if (j < regex.length() && regex.charAt(j) == '}') return true;
        if (j < regex.length() && regex.charAt(j) == ',') {
            j++;
            while (j < regex.length() && Character.isDigit(regex.charAt(j))) j++;
            if (j < regex.length() && regex.charAt(j) == '}') return true;
        }
        return false;
    }
}
