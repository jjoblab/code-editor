package jo.codeeditor.textmate.regex;

import java.util.ArrayDeque;

/**
 * Normalisation du dialecte avant compilation (plan 10, règle d'or 3).
 *
 * <p>Le port Java du dialecte diverge de la référence (le moteur C,
 * binaire de développement hors distribution) sur des familles
 * MESURÉES par la suite de conformité ; la table réécrit chaque forme
 * vers un équivalent exact DANS LA RÉFÉRENCE AUSSI (jamais de
 * réécriture à l'aveugle) :</p>
 *
 * <ol>
 *   <li><b>Classes de mot ASCII</b> — {@code \w}/{@code \W} : le port
 *       donne le complément ASCII, la référence le mot Unicode.
 *       {@code \w} → {@code \p{Word}}, {@code \W} → {@code \p{^Word}}
 *       (formes par propriété, valides hors et dans classe).</li>
 *   <li><b>Classes de chiffres ASCII</b> — {@code \d}/{@code \D} :
 *       divergence mesurée sur les chiffres arabo-indiens (le port ne
 *       les voit pas, la référence oui). {@code \d} →
 *       {@code \p{Nd}}, {@code \D} → {@code \p{^Nd}}.</li>
 *   <li><b>Espaces ASCII</b> — {@code \s}/{@code \S} : le port donne
 *       l'espace ASCII ; la référence couvre la table complète
 *       mesurée sur l'oracle (tabulations, retours, séparateurs
 *       Unicode {\p Z}, NEL U+0085, ZWSP U+200B). Hors classe :
 *       {@code \s} → {@code (?:[\t\n\v\f\r\p{Z}…])} (et la négation
 *       pour {@code \S}) ; DANS une classe, {@code \s} est remplacé
 *       par la suite de MEMBRES ({@code \t\n\v\f\r\p{Z}…}) —
 *       l'équivalent membre n'existe pas pour {@code \S} en classe :
 *       il reste tel quel (divergence résiduelle documentée, aucun
 *       cas du corpus).</li>
 *   <li><b>Groupe alternatif de longueurs variables en lookbehind</b>
 *       — l'idiome {@code (^|[^\:])\s} est aplati en branches directes
 *       de longueurs fixes : {@code ^\s|[^\:]\s}. Uniquement dans un
 *       lookbehind (ailleurs, l'aplatissement changerait la précédence).</li>
 *   <li><b>Échappement hexadécimal borné en lookbehind</b> — l'idiome
 *       {@code \\(?:[0-9a-fA-F]{1,6}|.)} (2 à 7 caractères) est
 *       distribué en branches directes de longueurs fixes :
 *       {@code \\[hex]|\\[hex]{2}|…|\\[hex]{6}|\\.}. Uniquement dans
 *       un lookbehind.</li>
 * </ol>
 *
 * <p>Le scanner est conscient des échappements, des classes (pour les
 * formes membres), du mode étendu {@code (?x)} (les commentaires
 * {@code #…} ne comptent ni pour les groupes ni pour les idiomes) et
 * de la pile des groupes ouverts pour le contexte lookbehind. Les
 * entrées 4 et 5 retirent un groupe capturant de l'idiome réécrit —
 * vérifié : aucune carte de captures ni backref de fermante ne les
 * référence dans le corpus. Extensible UNIQUEMENT sur divergence
 * prouvée par la suite de conformité {@code RegexConformityTest}.</p>
 */
public final class DialectNormalizer {

    /**
     * Entrée 4 — groupe alternatif variable en lookbehind, aplati en
     * branches directes de longueurs fixes (1 et 2).
     */
    private static final String IDIOME_GROUPE = "(^|[^\\:])\\s";
    private static final String IDIOME_GROUPE_APLATI = "^\\s|[^\\:]\\s";

    /**
     * Entrée 5 — échappement hexadécimal borné en lookbehind, distribué
     * en branches directes de longueurs fixes (2 à 7).
     */
    private static final String IDIOME_ESCAPE = "\\\\(?:[0-9a-fA-F]{1,6}|.)";
    private static final String IDIOME_ESCAPE_DISTRIBUE =
            "\\\\[0-9a-fA-F]|\\\\[0-9a-fA-F]{2}|\\\\[0-9a-fA-F]{3}"
            + "|\\\\[0-9a-fA-F]{4}|\\\\[0-9a-fA-F]{5}|\\\\[0-9a-fA-F]{6}|\\\\.";

    /**
     * Entrée 3 — la table d'espaces de la référence, mesurée sur
     * l'oracle : tabulations et retours ASCII, séparateurs Unicode
     * {\p Z}, NEL (U+0085), ZWSP (U+200B).
     */
    private static final String ESPACES_MEMBRES =
            "\\t\\n\\v\\f\\r\\p{Z}\u0085\u200B";
    private static final String ESPACES_GROUPE = "(?:[" + ESPACES_MEMBRES + "])";
    private static final String ESPACES_GROUPE_NEG = "(?:[^" + ESPACES_MEMBRES + "])";

    private DialectNormalizer() {
    }

    /**
     * Réécrit les formes divergentes du port. Conservatrice : ne touche
     * QUE la table — tout motif sans forme divergente ressort octet pour
     * octet identique.
     */
    public static String normalize(String source) {
        if (source == null) return null;
        if (!needsWork(source)) return source;
        StringBuilder out = new StringBuilder(source.length() + 32);
        // Pile des groupes ouverts : TRUE = lookbehind.
        ArrayDeque<Boolean> stack = new ArrayDeque<>();
        boolean inClass = false;
        boolean extended = false;
        int i = 0;
        final int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            // ── Échappements (la table) ────────────────────────────
            if (c == '\\' && i + 1 < n) {
                char next = source.charAt(i + 1);
                // Entrée 5 d'abord : l'idiome complet commence par \\.
                if (next == '\\' && !inClass && inLookbehind(stack)
                        && source.startsWith(IDIOME_ESCAPE, i)) {
                    out.append(IDIOME_ESCAPE_DISTRIBUE);
                    i += IDIOME_ESCAPE.length();
                    continue;
                }
                if (next == 'w') { out.append("\\p{Word}"); i += 2; continue; }
                if (next == 'W') { out.append("\\p{^Word}"); i += 2; continue; }
                if (next == 'd') { out.append("\\p{Nd}"); i += 2; continue; }
                if (next == 'D') { out.append("\\p{^Nd}"); i += 2; continue; }
                if (next == 's') {
                    out.append(inClass ? ESPACES_MEMBRES : ESPACES_GROUPE);
                    i += 2;
                    continue;
                }
                if (next == 'S') {
                    // En classe : pas d'équivalent membre — laissé tel
                    // quel (divergence résiduelle documentée, hors corpus).
                    if (!inClass) out.append(ESPACES_GROUPE_NEG);
                    else out.append("\\S");
                    i += 2;
                    continue;
                }
                out.append(c).append(next);
                i += 2;
                continue;
            }
            // ── Classes de caractères ───────────────────────────────
            if (c == '[' && !inClass) {
                out.append(c);
                i++;
                inClass = true;
                // `^` de négation et `]` littéral en tête : copiés tels
                // quels sans refermer la classe.
                if (i < n && source.charAt(i) == '^') { out.append('^'); i++; }
                if (i < n && source.charAt(i) == ']') { out.append(']'); i++; }
                continue;
            }
            if (c == ']' && inClass) {
                inClass = false;
                out.append(c);
                i++;
                continue;
            }
            // ── Commentaire du mode étendu : ligne entière ──────────
            if (c == '#' && extended && !inClass) {
                int nl = source.indexOf('\n', i);
                if (nl < 0) { out.append(source, i, n); break; }
                out.append(source, i, nl + 1);
                i = nl + 1;
                continue;
            }
            // ── Groupes ────────────────────────────────────────────
            if (c == '(') {
                // Entrée 4 : idiome complet — le groupe disparaît.
                if (!inClass && inLookbehind(stack)
                        && source.startsWith(IDIOME_GROUPE, i)) {
                    out.append(IDIOME_GROUPE_APLATI);
                    i += IDIOME_GROUPE.length();
                    continue;
                }
                if (!inClass && source.startsWith("(?#", i)) {
                    int close = source.indexOf(')', i + 3);
                    if (close < 0) { out.append(c); i++; continue; }
                    out.append(source, i, close + 1);
                    i = close + 1;
                    continue;
                }
                if (!inClass && isFlagConstruct(source, i)) {
                    // (?x) (?xi)… : pas un groupe ; arme le mode étendu
                    // si le drapeau x s'y trouve.
                    int end = skippedFlagsEnd(source, i);
                    if (flagsContain(source, i, 'x')) extended = true;
                    out.append(source, i, end);
                    i = end;
                    continue;
                }
                if (!inClass && (source.startsWith("(?<=", i) || source.startsWith("(?<!", i))) {
                    stack.push(Boolean.TRUE);
                    out.append(c);
                    i++;
                    continue;
                }
                if (!inClass) {
                    stack.push(Boolean.FALSE);
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == ')' && !inClass) {
                if (!stack.isEmpty()) stack.pop();
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Échappe un texte capturé pour sa réinjection littérale dans un
     * motif (substitution des backrefs des fermantes résolues).
     *
     * <p>Le dialecte n'a pas de citation générique : chaque caractère
     * non alphanumérique reçoit une barre oblique (un échappement inconnu
     * vaut le littéral). Les lettres et chiffres ASCII restent nus —
     * échapper un {@code w} changerait son sens.</p>
     */
    public static String escapeLiteral(String captured) {
        if (captured == null) return null;
        StringBuilder out = new StringBuilder(captured.length() * 2);
        for (int i = 0; i < captured.length(); i++) {
            char c = captured.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                out.append(c);
            } else {
                out.append('\\').append(c);
            }
        }
        return out.toString();
    }

    /** Pré-contrôle bon marché : la table ne peut rien toucher ici. */
    private static boolean needsWork(String source) {
        if (source.indexOf("\\w") >= 0 || source.indexOf("\\W") >= 0) return true;
        if (source.indexOf("\\d") >= 0 || source.indexOf("\\D") >= 0) return true;
        if (source.indexOf("\\s") >= 0 || source.indexOf("\\S") >= 0) return true;
        boolean lookbehind = source.indexOf("(?<=") >= 0 || source.indexOf("(?<!") >= 0;
        if (!lookbehind) return false;
        return source.indexOf('{') >= 0 || source.contains(IDIOME_GROUPE);
    }

    private static boolean inLookbehind(ArrayDeque<Boolean> stack) {
        return stack.contains(Boolean.TRUE);
    }

    /** (?x) (?xi)… : construct de flags se refermant en ')' ou ':'. */
    private static boolean isFlagConstruct(String s, int at) {
        if (!s.startsWith("(?", at) || at + 2 >= s.length()) return false;
        int j = at + 2;
        int flags = 0;
        while (j < s.length() && "imxsu-".indexOf(s.charAt(j)) >= 0) { j++; flags++; }
        return flags > 0 && j < s.length() && (s.charAt(j) == ')' || s.charAt(j) == ':');
    }

    /** Indice juste après le constructeur de flags (le ')' ou ':' inclus). */
    private static int skippedFlagsEnd(String s, int at) {
        int j = at + 2;
        while (j < s.length() && "imxsu-".indexOf(s.charAt(j)) >= 0) j++;
        return j + 1;
    }

    /** Le constructeur de flags en `at` contient-il le drapeau donné ? */
    private static boolean flagsContain(String s, int at, char flag) {
        int j = at + 2;
        while (j < s.length() && "imxsu-".indexOf(s.charAt(j)) >= 0) {
            if (s.charAt(j) == flag) return true;
            j++;
        }
        return false;
    }
}
