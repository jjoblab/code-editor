package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tokéniseur générique « à syntaxe C » : le cœur du parser maison. Extrait
 * de SyntaxHighlighter pour isoler la machine à états partagée par toute la
 * famille C (java, kotlin, javascript, typescript, c, cpp, go, rust, ruby,
 * php, swift, dart, groovy) — chaque langage y injecte ses mots-clés via
 * le registre des langages.
 */
public final class CLikeTokenizer {

    /**
     * Tokéniseur générique à syntaxe C (le cœur du « parser maison »).
     *
     * <p>Gère tous les langages de la famille C qui partagent une structure
     * lexicale commune : commentaires de ligne (double barre oblique),
     * commentaires de bloc (slash-astérisque ... astérisque-slash), chaînes
     * à guillemets doubles ou simples, littéraux de caractère, annotations
     * (arobase), nombres (décimaux, hexadécimaux, octaux, binaires, avec
     * suffixes), littéraux de couleur (hash-RRGGBB), opérateurs,
     * ponctuation et détection d'appel de fonction (identifiant suivi de
     * parenthèse).
     *
     * <p>Les différences propres à chaque langage sont gérées via
     * {@link #getKeywords} (renvoie le bon ensemble de mots-clés par
     * langage) et via des vérifications en ligne du paramètre
     * {@code language} (ex. chaînes brutes Kotlin).
     *
     * <p>Langages routés ici : java, kotlin, javascript, typescript,
     * c, cpp, go, rust, ruby, php, swift, dart, groovy.
     */
    public static StyledLine styleCLike(String line, int entryState, String language) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;
        Set<String> keywords = getKeywords(language);

        while (pos < line.length()) {
            // Gestion des états inter-lignes
            if (state == LexState.BLOCK_COMMENT) {
                int end = line.indexOf("*/", pos);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                    state = LexState.NORMAL;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                }
                continue;
            }

            if (state == LexState.XML_STRING) {
                int end = SpanUtils.findXmlStringEnd(line, pos);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                    pos = end + 1;
                    state = LexState.NORMAL;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            if (state == LexState.KT_RAW_STRING) {
                int end = line.indexOf("\"\"\"", pos);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                    pos = end + 3;
                    state = LexState.NORMAL;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            char ch = line.charAt(pos);

            // Blancs — à ignorer
            if (Character.isWhitespace(ch)) {
                pos++;
                continue;
            }

            // Commentaire de ligne
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '/') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Début de commentaire de bloc
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int end = line.indexOf("*/", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // Littéral de chaîne
            if (ch == '"') {
                if ("kotlin".equals(language) && pos + 2 < line.length()
                    && line.charAt(pos + 1) == '"' && line.charAt(pos + 2) == '"') {
                    // Chaîne brute Kotlin
                    int end = line.indexOf("\"\"\"", pos + 3);
                    if (end >= 0) {
                        spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                        pos = end + 3;
                    } else {
                        spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                        pos = line.length();
                        state = LexState.KT_RAW_STRING;
                    }
                } else {
                    int end = SpanUtils.findStringEnd(line, pos + 1, '"');
                    if (end >= 0) {
                        spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                        pos = end + 1;
                    } else {
                        spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                        pos = line.length();
                    }
                }
                continue;
            }

            // Littéral de caractère
            if (ch == '\'') {
                int end = SpanUtils.findStringEnd(line, pos + 1, '\'');
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // ★ Template literals JS/TS : `texte ${expr} suite`.
            // Reconnaît le délimiteur backtick et découpe les
            // interpolations ${...} en VARIABLE pour les distinguer du
            // texte littéral (sans cela elles tomberaient en PUNCT gris,
            // illisibles).
            //
            // Limitation connue : pas de support multi-lignes (les
            // template literals peuvent s'étendre sur plusieurs lignes
            // en JS). On colorie la première ligne en STRING si non
            // fermée, et le reste suit le chemin normal — acceptable car
            // le cas multi-lignes est rare en code UI et la mise en
            // évidence du délimiteur ` est déjà un gain net.
            if (ch == '`'
                    && ("javascript".equals(language) || "typescript".equals(language)
                        || "js".equals(language) || "ts".equals(language))) {
                int close = SpanUtils.findStringEnd(line, pos + 1, '`');
                if (close >= 0) {
                    addTemplateLiteralSpans(spans, line, pos, close + 1);
                    pos = close + 1;
                    continue;
                }
                // Pas fermé sur cette ligne — span STRING jusqu'à EOF,
                // pas d'état multi-ligne (voir commentaire ci-dessus).
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
                continue;
            }

            // Annotation
            if (ch == '@') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                pos = end;
                continue;
            }

            // Nombre
            if (Character.isDigit(ch)) {
                int end = pos + 1;
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (Character.isDigit(c) || c == '.' || c == '_' || c == 'x' || c == 'X'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                        || c == 'L' || c == 'l' || c == 'f' || c == 'd') {
                        end++;
                    } else {
                        break;
                    }
                }
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }


            // Détection de littéral de couleur (#RGB, #RRGGBB, #RRGGBBAA, #AARRGGBB)
            if (ch == '#' && pos + 1 < line.length()) {
                int colorEnd = pos + 1;
                while (colorEnd < line.length() && SpanUtils.isHexChar(line.charAt(colorEnd))) colorEnd++;
                int colorLen = colorEnd - pos - 1;
                if (colorLen == 3 || colorLen == 4 || colorLen == 6 || colorLen == 8) {
                    spans.add(new LineSpan(pos, colorEnd, TokenType.NUMBER));
                    pos = colorEnd;
                    continue;
                }
            }

            // Identifiant / mot-clé
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
                    end++;
                }
                String word = line.substring(pos, end);
                TokenType type;
                if (keywords.contains(word)) {
                    type = TokenType.KEYWORD;
                } else if (KeywordTables.JAVA_TYPES.contains(word)) {
                    type = TokenType.TYPE;
                } else if (SpanUtils.isAllCaps(word) && word.length() > 1) {
                    // Identifiants ALL_CAPS → CONSTANT
                    type = TokenType.CONSTANT;
                } else if (end < line.length() && line.charAt(end) == '(') {
                    type = TokenType.FUNC;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    type = TokenType.TYPE;
                } else if (pos > 0 && line.charAt(pos - 1) == '.') {
                    // Identifiant après un point → PROPERTY
                    type = TokenType.PROPERTY;
                } else {
                    type = TokenType.PLAIN;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Détection des opérateurs (distincts de PUNCT)
            if (SpanUtils.OPERATORS.indexOf(ch) >= 0) {
                // Consomme les opérateurs multi-caractères (==, !=, <=, >=, ->, ::, etc.)
                int end = pos + 1;
                while (end < line.length() && SpanUtils.OPERATORS.indexOf(line.charAt(end)) >= 0) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            // Ponctuation (crochets, virgules, points-virgules, etc.)
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }

        return new StyledLine(spans, entryState, state);
    }

    private static Set<String> getKeywords(String language) {
        // Recherche pilotée par le registre : les langages intégrés
        // conservent leurs ensembles de mots-clés exacts (tables dans
        // BuiltinLanguages), et les langages personnalisés enregistrés par
        // l'hôte via LanguageRegistry.register() sont également honorés.
        // Les identifiants inconnus retombent sur les mots-clés Java.
        if (language == null) return KeywordTables.JAVA_KEYWORDS;
        LanguageProfile profile = LanguageRegistry.forName(language);
        return profile != null ? profile.keywords : KeywordTables.JAVA_KEYWORDS;
    }

    /**
     * Découpe un JS/TS template literal en spans.
     *
     * <p>Un template literal ressemble à :
     * <pre>`Hello ${name}, you are ${age} years old`</pre>
     *
     * <p>On veut colorier :
     * <ul>
     *   <li>Le tout premier backtick `` ` `` en {@link TokenType#STRING}</li>
     *   <li>Les segments de texte littéral en {@link TokenType#STRING}</li>
     *   <li>Les séquences d'échappement (e.g. {@code \n}, {@code \t}) en
     *       {@link TokenType#ESCAPE}</li>
     *   <li>Les interpolations {@code ${expr}} en {@link TokenType#VARIABLE}
     *       (pour les distinguer visuellement du texte — pratique pour
     *       debug une template React qui rate)</li>
     *   <li>Le backtick fermant en {@link TokenType#STRING}</li>
     * </ul>
     *
     * <p>L'algorithme marche en single pass : on scanne entre start et end,
     * on cherche les marqueurs `${`, on split. À l'intérieur d'une
     * interpolation on continue jusqu'au `}` fermant (en comptant les
     * accolades imbriquées pour gérer les objets `${ {a: 1}.a }`).
     *
     * <p>Note : on ne RE-tokenize pas l'intérieur de l'interpolation
     * (pas d'analyse lexicale récursive). C'est volontaire — l'effet
     * visuel "VARIABLE bleuté" suffit pour distinguer l'interpolation
     * du texte littéral, et la complexité d'une tokenization récursive
     * juste pour les template literals n'est pas justifiée.
     */
    private static void addTemplateLiteralSpans(List<LineSpan> spans, String line, int start, int end) {
        int segStart = start;
        int pos = start + 1;  // saute le backtick ouvrant
        while (pos < end - 1) {  // end-1 = backtick fermant
            char c = line.charAt(pos);
            // Séquence d'échappement
            if (c == '\\' && pos + 1 < end - 1) {
                if (pos > segStart) {
                    spans.add(new LineSpan(segStart, pos, TokenType.STRING));
                }
                int escEnd = pos + 2;
                if (pos + 1 < end - 1) {
                    char next = line.charAt(pos + 1);
                    if (next == 'u' && pos + 5 < end - 1) escEnd = pos + 6;
                    else if (next == 'x' && pos + 3 < end - 1) escEnd = pos + 4;
                }
                spans.add(new LineSpan(pos, Math.min(escEnd, end), TokenType.ESCAPE));
                pos = escEnd;
                segStart = pos;
                continue;
            }
            // Début d'interpolation : ${
            if (c == '$' && pos + 1 < end - 1 && line.charAt(pos + 1) == '{') {
                // Vide le segment de chaîne précédent.
                if (pos > segStart) {
                    spans.add(new LineSpan(segStart, pos, TokenType.STRING));
                }
                // Trouve le } correspondant (gère l'imbrication).
                int depth = 1;
                int interpEnd = pos + 2;
                while (interpEnd < end - 1 && depth > 0) {
                    char ic = line.charAt(interpEnd);
                    if (ic == '{') depth++;
                    else if (ic == '}') depth--;
                    if (depth == 0) break;
                    interpEnd++;
                }
                // Émet l'interpolation ${...} en VARIABLE.
                spans.add(new LineSpan(pos, interpEnd + 1, TokenType.VARIABLE));
                pos = interpEnd + 1;
                segStart = pos;
                continue;
            }
            pos++;
        }
        // Segment de chaîne final (entre la dernière interpolation et le backtick fermant).
        if (segStart < end) {
            spans.add(new LineSpan(segStart, end, TokenType.STRING));
        }
    }
    private CLikeTokenizer() {}
}
