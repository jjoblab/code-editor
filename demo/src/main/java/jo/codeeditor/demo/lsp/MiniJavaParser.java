package jo.codeeditor.demo.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyseur syntaxique Java simplifié du mini serveur LSP de la démo.
 *
 * <p><b>Objectif :</b> produire des diagnostics <i>vrais et visibles</i> —
 * pas une analyse sémantique complète. Le contrat de la démo est « tout ce
 * qui s'affiche fonctionne » : chaque règle ci-dessous est donc
 * <b>conservatrice</b> — elle ne se déclenche que sur des situations
 * structurellement invalides, jamais sur une mise en forme inhabituelle.
 * Un faux positif (soulignement rouge sur du code correct) serait pire
 * qu'un faux négatif.</p>
 *
 * <p><b>Règles couvertes :</b></p>
 * <ul>
 *   <li><b>unclosed-bracket</b> — accolade / parenthèse / crochet ouvert et
 *       jamais fermé : signalé à SA position d'ouverture (« ouvert ici
 *       n'est jamais fermé ») ;</li>
 *   <li><b>unexpected-close</b> — fermante sans ouvrante correspondante
 *       (« aucun bloc ouvert ne correspond ») ;</li>
 *   <li><b>missing-semicolon</b> — instruction terminée (la ligne finit
 *       par identifiant / littéral / {@code )} / {@code ]}) et la ligne
 *       suivante <i>significative</i> démarre une nouvelle déclaration ou
 *       instruction — le point-virgule est signalé en fin de ligne ;</li>
 *   <li><b>orphan-else</b> / <b>orphan-catch</b> / <b>orphan-finally</b> —
 *       branchement de contrôle sans tête ({@code if} / {@code try}) à la
 *       même profondeur d'imbrication.</li>
 * </ul>
 *
 * <p><b>Méthode :</b> un unique balayage caractère par caractère masque
 * commentaires et littéraux (chaînes, caractères, text blocks) en
 * <i>conservant les positions</i> (les offsets restent exacts), construit
 * la pile des délimiteurs, puis une passe par lignes pose les règles
 * instruction/contrôle. Les positions de ligne/colonne produites sont
 * 0-based (convention LSP), en unités de caractères UTF-16.</p>
 *
 * <p>Pur JVM — aucune dépendance Android : testable directement en JUnit
 * (voir {@code MiniJavaParserTest}).</p>
 */
public final class MiniJavaParser {

    /** Constructeur privé — classe utilitaire à méthode unique. */
    private MiniJavaParser() {
    }

    /** Un diagnostic brut du parseur — positions 0-based (convention LSP). */
    public static final class Issue {
        /** Ligne de début (0-based). */
        public final int line;
        /** Colonne de début (0-based, UTF-16). */
        public final int character;
        /** Ligne de fin (0-based, incluse). */
        public final int endLine;
        /** Colonne de fin (0-based, exclusive). */
        public final int endCharacter;
        /** Message en français, prêt à l'affichage. */
        public final String message;
        /** Identifiant stable de la règle (le {@code code} LSP). */
        public final String code;

        Issue(int line, int character, int endLine, int endCharacter,
                String message, String code) {
            this.line = line;
            this.character = character;
            this.endLine = endLine;
            this.endCharacter = endCharacter;
            this.message = message;
            this.code = code;
        }

        @Override
        public String toString() {
            return code + " @" + (line + 1) + ":" + (character + 1)
                    + " — " + message;
        }
    }

    /** Délimiteurs ouvrants connus. */
    private static final String OPENERS = "{([";

    /**
     * Mots de tête : une ligne significative qui en démarre un nouveau
     * après une ligne d'instruction complète ⇒ point-virgule manquant.
     */
    private static final String[] STATEMENT_HEADS = {
            "public", "private", "protected", "static", "final", "abstract",
            "class", "interface", "enum", "record", "extends", "implements",
            "import", "package", "if", "else", "for", "while", "do", "switch",
            "case", "default", "return", "break", "continue", "throw", "throws",
            "try", "catch", "finally", "new", "assert", "yield", "synchronized",
            "void", "int", "long", "short", "byte", "char", "float", "double",
            "boolean", "var", "native", "transient", "volatile", "strictfp",
    };

    /**
     * Mots-clés qui terminent légalement une ligne : l'instruction
     * continue à la ligne suivante (modificateurs seuls, {@code return},
     * {@code new}, {@code import}…). Conservateur : tout mot-clé final
     * est réputé continuation.
     */
    private static final String[] CONTINUATION_KEYWORDS = {
            "public", "private", "protected", "static", "final", "abstract",
            "class", "interface", "enum", "record", "extends", "implements",
            "import", "package", "new", "return", "throw", "throws", "else",
            "do", "try", "instanceof", "assert", "yield", "case", "default",
            "synchronized", "native", "transient", "volatile", "strictfp",
            "int", "long", "short", "byte", "char", "float", "double",
            "boolean", "void", "var", "this", "super", "if", "while", "for",
            "switch", "catch",
    };

    /**
     * Analyse le texte Java et retourne les problèmes trouvés.
     *
     * <p>Garanties : jamais null (liste vide = aucun problème) ; l'ordre
     * suit l'ordre du document ; aucune exception — un texte bizarre
     * produit au pire zéro diagnostic, jamais un crash du serveur.</p>
     *
     * @param text le texte source (null = texte vide)
     * @return les diagnostics bruts, ordonnés par position
     */
    public static List<Issue> parse(String text) {
        List<Issue> issues = new ArrayList<>();
        if (text == null || text.isEmpty()) return issues;

        // Passe 1 : masque commentaires et littéraux, positions intactes.
        String masked = maskLiteralsAndComments(text);
        // Passe 2 : pile des délimiteurs — ouvrants non fermés, fermantes
        // orphelines.
        scanBrackets(masked, issues);
        // Passe 3 : règles par ligne — point-virgule manquant,
        // branchements de contrôle orphelins.
        scanLines(masked, issues);

        issues.sort((a, b) -> {
            if (a.line != b.line) return Integer.compare(a.line, b.line);
            return Integer.compare(a.character, b.character);
        });
        return issues;
    }

    // ── Passe 1 : masquage ──────────────────────────────────────────

    /**
     * Remplace par des espaces le contenu des commentaires, chaînes,
     * caractères et text blocks — offsets préservés (les retours à la
     * ligne restent intacts : la structure des lignes doit survivre).
     *
     * <p>Une chaîne non fermée n'est PAS signalée : la règle serait
     * bruyante en cascade sur les lignes suivantes — on préfère
     * l'abstention (contrat « zéro faux positif »).</p>
     */
    private static String maskLiteralsAndComments(String text) {
        char[] chars = text.toCharArray();
        int n = chars.length;
        int i = 0;
        while (i < n) {
            char c = chars[i];
            char next = i + 1 < n ? chars[i + 1] : '\0';
            if (c == '/' && next == '/') {
                while (i < n && chars[i] != '\n') {
                    chars[i] = ' ';
                    i++;
                }
            } else if (c == '/' && next == '*') {
                chars[i] = ' ';
                chars[i + 1] = ' ';
                i += 2;
                while (i < n && !(chars[i] == '*' && i + 1 < n
                        && chars[i + 1] == '/')) {
                    if (chars[i] != '\n') chars[i] = ' ';
                    i++;
                }
                if (i + 1 < n) {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i += 2;
                }
            } else if (c == '"' && next == '"' && i + 2 < n
                    && chars[i + 2] == '"') {
                // Text block Java (""" … """).
                i += 3;
                while (i < n && !(chars[i] == '"' && i + 2 < n
                        && chars[i + 1] == '"' && chars[i + 2] == '"')) {
                    if (chars[i] == '\\' && i + 1 < n) {
                        chars[i] = ' ';
                        i++;
                    }
                    if (i < n && chars[i] != '\n') chars[i] = ' ';
                    i++;
                }
                if (i + 2 < n) i += 3;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                chars[i] = ' ';
                i++;
                while (i < n && chars[i] != quote && chars[i] != '\n') {
                    if (chars[i] == '\\' && i + 1 < n) {
                        chars[i] = ' ';
                        i++;
                    }
                    if (i < n) chars[i] = ' ';
                    i++;
                }
                if (i < n && chars[i] == quote) {
                    chars[i] = ' ';
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(chars);
    }

    // ── Passe 2 : délimiteurs ───────────────────────────────────────

    /**
     * Pile des ouvrants : à la fermante, le sommet doit correspondre.
     * Les erreurs sont signalées au point le plus utile — l'OUVERTURE
     * pour un bloc jamais fermé, la FERMANTE pour une orpheline.
     */
    private static void scanBrackets(String masked, List<Issue> issues) {
        char[] chars = masked.toCharArray();
        int n = chars.length;
        int line = 0;
        int lineStart = 0;
        int[] stack = new int[256];
        int[] stackLine = new int[256];
        int[] stackCol = new int[256];
        int top = 0;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (c == '\n') {
                line++;
                lineStart = i + 1;
                continue;
            }
            if (OPENERS.indexOf(c) >= 0) {
                if (top < stack.length) {
                    stack[top] = c;
                    stackLine[top] = line;
                    stackCol[top] = i - lineStart;
                    top++;
                }
            } else if (c == '}' || c == ')' || c == ']') {
                char expected = c == '}' ? '{' : c == ')' ? '(' : '[';
                if (top == 0) {
                    issues.add(new Issue(line, i - lineStart,
                            line, i - lineStart + 1,
                            "« " + c + " » inattendu — aucun bloc ouvert ne correspond",
                            "unexpected-close"));
                } else if (stack[top - 1] != expected) {
                    char crossed = (char) stack[top - 1];
                    issues.add(new Issue(line, i - lineStart,
                            line, i - lineStart + 1,
                            "« " + c + " » inattendu — « " + crossed
                                    + " » ouvert ligne " + (stackLine[top - 1] + 1)
                                    + " attend « " + mirror(crossed) + " »",
                            "unexpected-close"));
                    // Fermant consommé : l'ouvrant croisé reste sur la
                    // pile (signalé en fin de fichier s'il manque).
                    top--;
                } else {
                    top--;
                }
            }
        }
        for (int k = top - 1; k >= 0; k--) {
            String label = stack[k] == '{' ? "accolade"
                    : stack[k] == '(' ? "parenthèse" : "crochet";
            issues.add(new Issue(stackLine[k], stackCol[k],
                    stackLine[k], stackCol[k] + 1,
                    "« " + stack[k] + " » ouvert ici n'est jamais fermé ("
                            + label + " non fermée)",
                    "unclosed-bracket"));
        }
    }

    private static char mirror(char open) {
        return open == '{' ? '}' : open == '(' ? ')' : ']';
    }

    // ── Passe 3 : règles par ligne ──────────────────────────────────

    private static void scanLines(String masked, List<Issue> issues) {
        String[] lines = masked.split("\n", -1);
        int n = lines.length;

        // File des têtes de contrôle (0 = if, 1 = try) en vie avec la
        // profondeur d'accolades et la LIGNE où elles vivent. Une tête
        // meurt quand la profondeur redescend strictement en-dessous.
        int[] ctrlKind = new int[64];
        int[] ctrlDepth = new int[64];
        int[] ctrlLine = new int[64];
        int ctrlTop = 0;

        // Sémantique d'adjacence : un « else » ne peut suivre que (a) la
        // fermante du bloc du « if » (même ligne ou ligne précédente),
        // ou (b) un corps sans accolades. On retient donc la profondeur
        // atteinte par la fermante de la précédente ligne significative
        // (MIN_VALUE = pas de fermante).
        int prevCloseDepth = Integer.MIN_VALUE;

        int depth = 0;
        for (int i = 0; i < n; i++) {
            String raw = lines[i];
            String t = raw.trim();
            if (t.isEmpty()) continue;

            // Profondeur effective de la ligne : après ses fermantes,
            // avant ses ouvrants (« } else { » vit au niveau du bloc
            // fermé, pas du bloc ouvert).
            int effDepth = depth - countChars(raw, '}');

            // Retire les têtes mortes.
            while (ctrlTop > 0 && ctrlDepth[ctrlTop - 1] > effDepth) {
                ctrlTop--;
            }

            String first = firstWord(t);

            // ── Branchements orphelins ──
            if ("else".equals(first)) {
                if (!têteIfAdjointe(ctrlKind, ctrlDepth, ctrlLine, ctrlTop,
                        effDepth, i, raw, prevCloseDepth, lines)) {
                    int col = indexOfWord(t, "else");
                    issues.add(new Issue(i, col, i, col + 4,
                            "« else » sans « if » correspondant à ce niveau",
                            "orphan-else"));
                } else {
                    ctrlTop--;
                    // Chaîne « else if » : une nouvelle tête if vit à
                    // cette profondeur (le prochain else la vise).
                    if (restAfterElse(t).startsWith("if") && ctrlTop < ctrlKind.length) {
                        ctrlKind[ctrlTop] = 0;
                        ctrlDepth[ctrlTop] = effDepth;
                        ctrlLine[ctrlTop] = i;
                        ctrlTop++;
                    }
                }
            } else if ("catch".equals(first)) {
                if (!têteTryAdjointe(ctrlKind, ctrlDepth, ctrlLine, ctrlTop,
                        effDepth, i, raw, prevCloseDepth, lines)) {
                    int col = indexOfWord(t, "catch");
                    issues.add(new Issue(i, col, i, col + 5,
                            "« catch » sans « try » correspondant à ce niveau",
                            "orphan-catch"));
                }
                // La tête try survit aux catch intermédiaires (finally
                // peut suivre) — elle meurt par la profondeur.
            } else if ("finally".equals(first)) {
                if (!têteTryAdjointe(ctrlKind, ctrlDepth, ctrlLine, ctrlTop,
                        effDepth, i, raw, prevCloseDepth, lines)) {
                    int col = indexOfWord(t, "finally");
                    issues.add(new Issue(i, col, i, col + 7,
                            "« finally » sans « try » correspondant à ce niveau",
                            "orphan-finally"));
                } else {
                    ctrlTop--;
                }
            } else if (("if".equals(first) || "try".equals(first))
                    && ctrlTop < ctrlKind.length) {
                ctrlKind[ctrlTop] = "if".equals(first) ? 0 : 1;
                ctrlDepth[ctrlTop] = effDepth;
                ctrlLine[ctrlTop] = i;
                ctrlTop++;
            }

            // Profondeur de la fermante de CETTE ligne pour l'adjacence
            // du prochain branchement.
            prevCloseDepth = raw.indexOf('}') >= 0 ? effDepth : Integer.MIN_VALUE;

            // ── Point-virgule manquant ──
            if (endsWithStatementValue(t)) {
                int next = nextMeaningfulLine(lines, i + 1);
                String nextTrimmed = next >= 0 ? lines[next].trim() : null;
                String nextFirst = nextTrimmed != null
                        ? firstWord(nextTrimmed) : null;
                boolean nextClosesBlock = nextTrimmed != null
                        && nextTrimmed.startsWith("}");
                if (nextClosesBlock || isStatementHead(nextFirst)) {
                    int col = t.length();
                    issues.add(new Issue(i, col, i, col,
                            "« ; » manquant en fin d'instruction — la ligne suivante démarre « "
                                    + (nextClosesBlock ? "}" : nextFirst) + " »",
                            "missing-semicolon"));
                } else if (nextFirst == null && !t.endsWith("}")) {
                    // Dernière ligne du fichier : un fichier bien formé
                    // se termine par une fermante — une instruction nue
                    // en dernière ligne est incomplète.
                    int col = t.length();
                    issues.add(new Issue(i, col, i, col,
                            "« ; » manquant en fin d'instruction (fin de fichier)",
                            "missing-semicolon"));
                }
            }

            depth += countChars(raw, '{') - countChars(raw, '}');
            if (depth < 0) depth = 0;
        }
    }

    /**
     * Le sommet de la file est-il un « if » ADJOINT à ce « else » ?
     * Sémantique Java stricte : le else suit (a) la fermante du bloc du
     * if — sur sa propre ligne (« } else { ») ou sur la ligne
     * précédente — ou (b) un corps sans accolades. Un « else » séparé
     * de son « if » par d'autres instructions est orphelin.
     */
    private static boolean têteIfAdjointe(int[] ctrlKind, int[] ctrlDepth,
            int[] ctrlLine, int ctrlTop, int effDepth, int currentLine,
            String raw, int prevCloseDepth, String[] lines) {
        if (ctrlTop == 0) return false;
        int k = ctrlTop - 1;
        if (ctrlKind[k] != 0 || ctrlDepth[k] != effDepth) return false;
        if (raw.indexOf('}') >= 0 || prevCloseDepth == effDepth) return true;
        return corpsSansAccolades(ctrlLine[k], currentLine, lines);
    }

    /** Même contrat que {@link #têteIfAdjointe} pour « catch »/« finally ». */
    private static boolean têteTryAdjointe(int[] ctrlKind, int[] ctrlDepth,
            int[] ctrlLine, int ctrlTop, int effDepth, int currentLine,
            String raw, int prevCloseDepth, String[] lines) {
        if (ctrlTop == 0) return false;
        int k = ctrlTop - 1;
        if (ctrlKind[k] != 1 || ctrlDepth[k] != effDepth) return false;
        if (raw.indexOf('}') >= 0 || prevCloseDepth == effDepth) return true;
        return corpsSansAccolades(ctrlLine[k], currentLine, lines);
    }

    /**
     * Entre la tête (ligne {@code headLine}) et le branchement
     * ({@code currentLine}), aucune ligne significative ne porte
     * d'accolade — le corps était sans bloc, le branchement est légal.
     */
    private static boolean corpsSansAccolades(int headLine, int currentLine,
            String[] lines) {
        for (int j = headLine + 1; j < currentLine; j++) {
            String t = lines[j].trim();
            if (t.isEmpty()) continue;
            if (t.indexOf('{') >= 0 || t.indexOf('}') >= 0) return false;
        }
        return true;
    }

    /**
     * La ligne se termine-t-elle par une VALEUR d'instruction (identifiant,
     * littéral, {@code )}, {@code ]}, {@code ++}, {@code --}) ? Tout le
     * reste (opérateurs, virgules, mots-clés de continuation, ouvrants)
     * est réputé « l'instruction continue à la ligne suivante ».
     */
    private static boolean endsWithStatementValue(String t) {
        if (t.isEmpty()) return false;
        if (t.startsWith("@")) return false; // annotation seule
        char last = t.charAt(t.length() - 1);
        if (Character.isJavaIdentifierPart(last)) {
            String lastW = lastWord(t);
            return lastW != null && !isKeywordIn(lastW, CONTINUATION_KEYWORDS);
        }
        return last == ')' || last == ']'
                || t.endsWith("++") || t.endsWith("--");
    }

    /** Prochaine ligne non vide à partir de {@code from} (-1 = plus rien). */
    private static int nextMeaningfulLine(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) return i;
        }
        return -1;
    }

    private static boolean isStatementHead(String word) {
        return word != null && isKeywordIn(word, STATEMENT_HEADS);
    }

    private static boolean isKeywordIn(String word, String[] set) {
        for (String k : set) {
            if (k.equals(word)) return true;
        }
        return false;
    }

    /**
     * Premier mot d'une ligne, en sautant une fermante de tête
     * (« } else { » → « else »).
     */
    private static String firstWord(String t) {
        int i = 0;
        while (i < t.length() && (t.charAt(i) == '}' || t.charAt(i) == ' ')) i++;
        int start = i;
        while (i < t.length() && Character.isJavaIdentifierPart(t.charAt(i))) i++;
        if (i == start) return "";
        return t.substring(start, i);
    }

    private static String lastWord(String t) {
        int end = t.length();
        while (end > 0 && !Character.isJavaIdentifierPart(t.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(t.charAt(start - 1))) start--;
        if (start == end) return null;
        return t.substring(start, end);
    }

    /** Le texte qui suit le mot « else » (pour détecter « else if »). */
    private static String restAfterElse(String t) {
        int idx = indexOfWord(t, "else");
        if (idx < 0) return "";
        return t.substring(idx + 4).trim();
    }

    private static int indexOfWord(String line, String word) {
        int idx = line.indexOf(word);
        return idx < 0 ? 0 : idx;
    }

    private static int countChars(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}
