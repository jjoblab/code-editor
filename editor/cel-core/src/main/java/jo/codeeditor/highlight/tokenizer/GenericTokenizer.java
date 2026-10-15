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
 * Tokéniseur GÉNÉRIQUE piloté par un {@link TokenizerTemplate}
 * déclaratif (ADR-002, chantier C2) : la grammaire cesse d'être du
 * code Java dédié par famille, elle devient une table de règles.
 *
 * <p><b>Machine de l'étape C2-é1</b> (grammaires clé=valeur,
 * reprise paramétrée de l'algorithme Properties historique —
 * comportement octet pour octet pour le template
 * {@link TokenizerTemplate#PROPERTIES}) :</p>
 * <ol>
 *   <li>un préfixe de {@link TokenizerTemplate#lineComments} en tête
 *       de ligne (avant tout séparateur) commente la ligne entière ;</li>
 *   <li>avant le séparateur : la clé s'étend jusqu'au prochain
 *       séparateur ou blanc ({@link TokenizerTemplate#keyType}) ;</li>
 *   <li>le séparateur lui-même ({@link TokenizerTemplate#separatorType}) ;</li>
 *   <li>après : la valeur ({@link TokenizerTemplate#valueType}) jusqu'à
 *       la fin de ligne, avec les échappements backslash
 *       ({@code \\n}, {@code \\t}, {@code \\uXXXX} — 6 caractères) en
 *       ESCAPE si {@link TokenizerTemplate#escapesInValue} ;</li>
 *   <li>pas d'état inter-lignes : l'état de sortie est NORMAL.</li>
 * </ol>
 *
 * <p><b>Extensions de l'étape C2-é2</b> (migration TOML — comportement
 * de {@code TomlTokenizer} repris octet pour octet) :</p>
 * <ol>
 *   <li>le préfixe de commentaire s'applique aussi en zone valeur si
 *       {@link TokenizerTemplate#commentsAnywhere} (un {@code #} en fin
 *       de ligne TOML commente, jamais en Properties) ;</li>
 *   <li>en-tête de section {@code [nom]}/{@code [[nom]]} avant le
 *       séparateur : nom en
 *       {@link TokenizerTemplate#sectionNameType}, crochets fermants
 *       en un jeton {@link TokenizerTemplate#sectionBracketType} ;</li>
 *   <li>si le template déclare une règle de jeton de valeur
 *       ({@link TokenizerTemplate#tokenizesValue()}), la zone valeur
 *       est tokénisée jeton par jeton : chaînes quotées (échappements
 *       internes en ESCAPE, guillemet orphelin en repli), nombres
 *       (chiffres + {@link TokenizerTemplate#valueNumberExtras}, le
 *       {@code -} ancré à un chiffre ouvre un nombre), mots (les
 *       {@link TokenizerTemplate#valueKeywords} reconnus en
 *       {@link TokenizerTemplate#valueKeywordType}, les autres en
 *       {@link TokenizerTemplate#valueType}) — tout le reste en
 *       ponctuation de repli ;</li>
 *   <li>sans règle de jeton, la valeur reste le run mono-jeton de
 *       l'étape C2-é1 (Properties inchangé).</li>
 * </ol>
 *
 * <p><b>Extensions de l'étape C2-é3</b> (migration Shell/Bash —
 * comportement de {@code ShellTokenizer} repris octet pour octet) :
 * le mode <b>flux libre</b> ({@link TokenizerTemplate#freeForm})
 * tokénise la ligne en jetons sans zones clé/valeur :</p>
 * <ol>
 *   <li>littéral inter-lignes non fermé à la ligne précédente
 *       ({@link TokenizerTemplate#interLineQuote}) : consommé jusqu'à
 *       la fermante, sinon propagé ({@link TokenizerTemplate#interLineState}) ;</li>
 *   <li>commentaire ancré ({@link TokenizerTemplate#commentRequiresBoundary}) :
 *       préfixe en début, après un blanc ou après un caractère
 *       d'ancrage — jamais au milieu d'un mot ;</li>
 *   <li>variables ({@link TokenizerTemplate#variablePrefixes}) :
 *       {@code $var}, {@code ${var}}, {@code $?}, {@code $1} ;</li>
 *   <li>chaîne double ({@link TokenizerTemplate#plainStringQuotes}) :
 *       échappements sautés, un seul jeton, non fermée → fin de ligne ;</li>
 *   <li>littéral simple : paire → jeton, orpheline → fin de ligne + état ;</li>
 *   <li>substitution par paires
 *       ({@link TokenizerTemplate#commandQuotes}) ;</li>
 *   <li>nombre en frontière de mot ;</li>
 *   <li>mot : mots-clés, appel de fonction, initiale majuscule, repli ;</li>
 *   <li>course d'opérateurs
 *       ({@link TokenizerTemplate#operatorChars}) ;</li>
 *   <li>repli : un caractère en PUNCT.</li>
 * </ol>
 *
 * <p><b>Extensions de l'étape C2-é5</b> (migration JSON —
 * comportement de {@code JsonTokenizer} repris octet pour octet) :</p>
 * <ol>
 *   <li>les chaînes quotées vivent aussi en flux libre
 *       ({@link TokenizerTemplate#valueQuotes}) : fermante trouvée
 *       en sautant les échappements, séquences internes en ESCAPE,
 *       orpheline → fin de ligne et état inter-lignes propagé ;</li>
 *   <li>le lookahead de clé
 *       ({@link TokenizerTemplate#stringKeyLookahead}) requalifie une
 *       chaîne fermée suivie d'un séparateur en clé (la clé JSON) ;</li>
 *   <li>mots de lettres pures
 *       ({@link TokenizerTemplate#wordConsumesDigits} à faux) : un
 *       chiffre collé ouvre un nombre, sans frontière
 *       ({@link TokenizerTemplate#numberRequiresBoundary} à faux) ;</li>
 *   <li>le signe moins ancré à un chiffre ouvre un nombre si déclaré
 *       ({@link TokenizerTemplate#numberLeadingMinus}) ;</li>
 *   <li>les opérateurs mono-caractère
 *       ({@link TokenizerTemplate#operatorSingleChars}) ne fusionnent
 *       jamais en course ({@code :}, {@code ,}).</li>
 * </ol>
 *
 * <p><b>Extensions de l'étape C2-é6</b> (migration de la famille C
 * — comportement de {@code CLikeTokenizer} repris octet pour octet) :</p>
 * <ol>
 *   <li>littéraux à délimiteurs
 *       ({@link TokenizerTemplate#blockLiterals}) : commentaires de
 *       bloc et de documentation, chaîne brute Kotlin — ouverture en
 *       boucle, continuation en tête, fermante cherchée à l'offset
 *       déclaré ({@code /**&#47;} se referme) ;</li>
 *   <li>littéraux de caractère
 *       ({@link TokenizerTemplate#charQuotes}) et template literals
 *       JS/TS à interpolations
 *       ({@link TokenizerTemplate#templateLiteralLanguages}) ;</li>
 *   <li>annotations et couleurs hexadécimales en flux libre ;</li>
 *   <li>mots-clés du REGISTRE par langage
 *       ({@link TokenizerTemplate#registryKeywords}, repli Java) et
 *       raffinement des mots : contrôle, modificateur, type intégré,
 *       tout-en-majuscules, identifiant après point.</li>
 * </ol>
 *
 * <p>Les migrations suivantes du cliquet étendront la machine
 * tokéniseur par tokéniseur, chaque suite existante restant verte
 * sans modification de ses assertions.</p>
 */
public final class GenericTokenizer {

    /**
     * Tokénise une ligne selon le template. Les grammaires clé=valeur
     * sans état inter-lignes sortent en NORMAL (comportement
     * Properties/TOML historique).
     */
    public static StyledLine styleLine(TokenizerTemplate template, String line,
            int entryState) {
        return styleLine(template, line, entryState, null);
    }

    /**
     * Tokénise une ligne selon le template, pour le langage demandé
     * (C2-é6, famille C) : porte les règles par langage (chaîne brute
     * Kotlin, template literals JS/TS) et la résolution des mots-clés
     * du registre. Null pour les grammaires sans langage.
     */
    public static StyledLine styleLine(TokenizerTemplate template, String line,
            int entryState, String language) {
        // Troisième saveur (C2-é12) : classifieur de ligne — la ligne
        // entière reçoit le type de la PREMIÈRE règle qui matche
        // (consoles, logs : aucune tokénisation par caractère).
        if (!template.lineRules.isEmpty()) {
            return styleClassifiedLine(template, line, entryState);
        }
        if (template.freeForm) {
            return styleFreeFormLine(template, line, entryState, language);
        }
        return styleZonedLine(template, line, entryState);
    }

    /**
     * Mode classifieur de ligne (C2-é12, langage « log ») : règles
     * contient/commence/finit évaluées dans l'ordre de déclaration,
     * premier match gagnant, la ligne entière devient UNE span du type
     * déclaré ; aucune règle → PLAIN sans span (chemin rapide — les
     * consoles reçoivent des milliers de lignes). Les règles sont
     * insensibles à la casse par défaut (minuscule Locale.ROOT), une
     * règle déclarée sensible matche sur la ligne originale.
     */
    private static StyledLine styleClassifiedLine(TokenizerTemplate template,
            String line, int entryState) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        for (TokenizerTemplate.LineRule rule : template.lineRules) {
            String subject = rule.caseSensitive ? line : lower;
            boolean hit;
            switch (rule.kind) {
                case STARTS_WITH:
                    hit = subject.startsWith(rule.text);
                    break;
                case ENDS_WITH:
                    hit = subject.endsWith(rule.text);
                    break;
                default:
                    hit = subject.contains(rule.text);
                    break;
            }
            if (hit) {
                List<LineSpan> spans = new ArrayList<>(1);
                spans.add(new LineSpan(0, line.length(), rule.type));
                return new StyledLine(spans, entryState, LexState.NORMAL);
            }
        }
        return new StyledLine(java.util.Collections.emptyList(),
                entryState, LexState.NORMAL);
    }

    /**
     * Mode zones clé=valeur (Properties, TOML, YAML) : commentaire,
     * marqueurs, ancres, section, séparateur, clé (lookahead), valeur
     * mono-jeton ou à jetons, repli scalaire ou ponctuation.
     */
    private static StyledLine styleZonedLine(TokenizerTemplate template,
            String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean seenSeparator = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Commentaire : en zone clé toujours ; en zone valeur
            // uniquement si le template le déclare (un # après la
            // valeur commente en TOML/YAML, jamais en Properties).
            if ((template.commentsAnywhere || !seenSeparator)
                    && commentAt(template, line, pos) != null) {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            if (Character.isWhitespace(ch)) {
                pos++;
                continue;
            }

            // Marqueur de document (---) — n'importe où.
            String marker = documentMarkerAt(template, line, pos);
            if (marker != null) {
                spans.add(new LineSpan(pos, pos + marker.length(),
                        template.documentMarkerType));
                pos += marker.length();
                continue;
            }

            // Ancre / alias / tag (&a, *a, !a) — n'importe où.
            if (template.annotationPrefixes.contains(ch)) {
                int end = pos + 1;
                while (end < line.length()
                        && (Character.isLetterOrDigit(line.charAt(end))
                                || line.charAt(end) == '_'
                                || line.charAt(end) == '-')) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, template.annotationType));
                pos = end;
                continue;
            }

            // Chaîne quotée AVANT le séparateur (YAML : les chaînes
            // vivent dans les deux zones) ; une chaîne fermée peut
            // réouvrir la zone clé (réinitialisation afterColon
            // historique). Orpheline : retombée plus bas.
            if (template.stringsInKeyZone && template.valueQuotes.contains(ch)) {
                int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1,
                            template.valueType);
                    pos = end + 1;
                    if (template.stringReopensKeyZone) seenSeparator = false;
                    continue;
                }
            }

            // En-tête de section [nom] / [[nom]] — uniquement AVANT le
            // séparateur ; un [ en zone valeur est de la ponctuation.
            if (!seenSeparator && template.hasSections()
                    && ch == template.sectionOpen) {
                int closeCount = 0;
                while (pos < line.length() && line.charAt(pos) == template.sectionOpen) {
                    closeCount++;
                    pos++;
                }
                int end = pos;
                while (end < line.length() && line.charAt(end) != template.sectionClose) {
                    end++;
                }
                if (end < line.length()) {
                    spans.add(new LineSpan(pos, end, template.sectionNameType));
                    spans.add(new LineSpan(end, end + closeCount,
                            template.sectionBracketType));
                    pos = end + closeCount;
                    continue;
                }
                // Section non fermée : les ouvrants restent consommés et
                // la suite retombe dans la règle de clé (historique).
            }

            // Séparateur clé/valeur.
            if (!seenSeparator && template.keyValueSeparators.contains(ch)) {
                spans.add(new LineSpan(pos, pos + 1, template.separatorType));
                pos++;
                seenSeparator = true;
                continue;
            }

            // Indicateur de scalaire de bloc (| >) — zone clé.
            if (!seenSeparator && template.blockIndicators.contains(ch)) {
                spans.add(new LineSpan(pos, pos + 1, template.blockIndicatorType));
                pos++;
                continue;
            }

            // Clé : jusqu'au prochain séparateur, blanc ou stop ; si le
            // template filtre les départs, un caractère hors jeu
            // n'ouvre pas une clé. Avec lookahead (YAML), la clé
            // n'est confirmée que si un séparateur la suit — sans
            // quoi le candidat retombe en scalaire et la suite de la
            // ligne devient zone valeur.
            if (!seenSeparator && (template.keyStartExtras.isEmpty()
                    || Character.isLetter(ch)
                    || template.keyStartExtras.contains(ch))) {
                int end = pos + 1;
                while (end < line.length()
                        && !template.keyValueSeparators.contains(line.charAt(end))
                        && !Character.isWhitespace(line.charAt(end))
                        && !template.keyStopChars.contains(line.charAt(end))) {
                    end++;
                }
                if (template.keyLookahead) {
                    int lookAhead = end;
                    while (lookAhead < line.length()
                            && Character.isWhitespace(line.charAt(lookAhead))) {
                        lookAhead++;
                    }
                    if (lookAhead < line.length()
                            && template.keyValueSeparators.contains(
                                    line.charAt(lookAhead))) {
                        spans.add(new LineSpan(pos, end, template.keyType));
                    } else {
                        spans.add(new LineSpan(pos, end, template.valueType));
                        seenSeparator = true;
                    }
                } else {
                    spans.add(new LineSpan(pos, end, template.keyType));
                }
                pos = end;
                continue;
            }

            if (template.tokenizesValue() && seenSeparator) {
                // Valeur à jetons : chaque jeton reçoit son type déclaré.
                if (template.valueQuotes.contains(ch)) {
                    // Chaîne quotée ; orpheline → repli (scalaire/mot).
                    int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                    if (end >= 0) {
                        SpanUtils.addStringSpans(spans, line, pos, end + 1,
                                template.valueType);
                        pos = end + 1;
                        continue;
                    }
                }

                if (template.numbersInValue
                        && (Character.isDigit(ch)
                                || (ch == '-' && pos + 1 < line.length()
                                        && Character.isDigit(line.charAt(pos + 1))))) {
                    // Nombre : chiffres + jeu étendu (dates TOML), le
                    // '-' ancré à un chiffre ouvre un nombre.
                    int end = pos + 1;
                    while (end < line.length()
                            && (Character.isDigit(line.charAt(end))
                                    || template.valueNumberExtras.contains(
                                            line.charAt(end)))) {
                        end++;
                    }
                    spans.add(new LineSpan(pos, end, template.valueNumberType));
                    pos = end;
                    continue;
                }

                if (Character.isLetter(ch)
                        || (template.valueWordConsumesDigits && Character.isDigit(ch))
                        || template.valueWordStartExtras.contains(ch)) {
                    // Mot de valeur : jeu de départ et de suite déclarés
                    // (YAML : chiffres, . - _ : /) ; classification
                    // numérique des mots (YAML) puis mots-clés, sinon
                    // type de valeur.
                    int end = pos + 1;
                    while (end < line.length()
                            && (Character.isLetter(line.charAt(end))
                                    || (template.valueWordConsumesDigits
                                            && Character.isDigit(line.charAt(end)))
                                    || template.valueWordExtras.contains(
                                            line.charAt(end)))) {
                        end++;
                    }
                    String word = line.substring(pos, end);
                    TokenType type;
                    if (template.valueWordNumbers && startsLikeNumber(word)) {
                        type = template.valueNumberType;
                    } else if (template.valueKeywords.contains(word)) {
                        type = template.valueKeywordType;
                    } else {
                        type = template.valueType;
                    }
                    spans.add(new LineSpan(pos, end, type));
                    pos = end;
                    continue;
                }
            } else if (!template.tokenizesValue() && seenSeparator) {
                // Valeur mono-jeton — échappements puis texte jusqu'au
                // prochain backslash (grammaire Properties historique).
                if (template.escapesInValue && ch == '\\') {
                    int end = pos + 2;
                    if (pos + 1 < line.length()) {
                        char next = line.charAt(pos + 1);
                        if (next == 'u' && pos + 5 < line.length()) end = pos + 6;
                    }
                    spans.add(new LineSpan(pos, Math.min(end, line.length()),
                            TokenType.ESCAPE));
                    pos = end;
                    continue;
                }

                int end = pos + 1;
                while (end < line.length()
                        && !(template.escapesInValue && line.charAt(end) == '\\')) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, template.valueType));
                pos = end;
                continue;
            }

            // Ponctuation de flux ([ ] { }) — les deux zones.
            if (template.flowPunct.contains(ch)) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                pos++;
                continue;
            }

            // Repli : scalaire brut (jusqu'à blanc/séparateur/stop) ou
            // ponctuation mono-caractère (crochets de flux TOML,
            // guillemet orphelin…).
            if (template.scalarFallback) {
                int end = pos + 1;
                while (end < line.length()
                        && !template.keyValueSeparators.contains(line.charAt(end))
                        && !Character.isWhitespace(line.charAt(end))
                        && !template.scalarStopChars.contains(line.charAt(end))) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, template.valueType));
                pos = end;
                seenSeparator = true;
                continue;
            }
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    /** Le marqueur de document déclaré démarrant à pos, ou null. */
    private static String documentMarkerAt(TokenizerTemplate template,
            String line, int pos) {
        for (String marker : template.documentMarkers) {
            if (line.startsWith(marker, pos)) return marker;
        }
        return null;
    }

    /** Vrai si le mot commence comme un nombre (chiffre ou ±chiffre). */
    private static boolean startsLikeNumber(String word) {
        if (word.isEmpty()) return false;
        char c0 = word.charAt(0);
        if (Character.isDigit(c0)) return true;
        return word.length() > 1
                && (c0 == '-' || c0 == '+')
                && Character.isDigit(word.charAt(1));
    }

    /** Le préfixe de commentaire déclaré démarrant à pos, ou null. */
    private static String commentAt(TokenizerTemplate template, String line, int pos) {
        for (String prefix : template.lineComments) {
            if (line.startsWith(prefix, pos)) return prefix;
        }
        return null;
    }

    /**
     * Mode flux libre (Shell) : jetons sur toute la ligne, sans zones.
     * Reprise paramétrée de l'algorithme ShellTokenizer historique —
     * comportement octet pour octet pour le template
     * {@link TokenizerTemplate#SHELL}.
     */
    private static StyledLine styleFreeFormLine(TokenizerTemplate template,
            String line, int entryState, String language) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;
        // Mots-clés : table du template, ou REGISTRE des langages
        // (famille C — repli mots-clés Java, règle historique).
        java.util.Set<String> keywords = template.registryKeywords
                ? keywordsFor(language) : template.valueKeywords;

        // Littéral à délimiteurs non fermé à la ligne précédente
        // (commentaire de bloc, documentation, chaîne brute) : consommé
        // jusqu'au délimiteur fermant, sinon propagé.
        for (TokenizerTemplate.BlockLiteral bl : template.blockLiterals) {
            if (state == bl.state) {
                int end = line.indexOf(bl.close, pos);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + bl.close.length(), bl.type));
                    pos = end + bl.close.length();
                    state = LexState.NORMAL;
                } else {
                    spans.add(new LineSpan(pos, line.length(), bl.type));
                    pos = line.length();
                }
                break;
            }
        }

        // Littéral inter-lignes non fermé à la ligne précédente :
        // consommé jusqu'à la fermante (bash autorise le multi-ligne).
        // C2-é11 (CSS) : plusieurs guillemets peuvent déclarer l'état —
        // la fermeture tombe au premier guillemet de l'ordre DÉCLARÉ
        // qui existe sur la ligne (« " » avant « ' »), même si l'autre
        // apparaît plus tôt (garde historique CSS).
        if (state == template.interLineState
                && (template.interLineQuote != 0
                        || !template.interLineQuotes.isEmpty())) {
            int end = -1;
            if (template.interLineQuote != 0) {
                end = line.indexOf(template.interLineQuote, pos);
            } else {
                for (char q : template.interLineQuotes) {
                    end = line.indexOf(q, pos);
                    if (end >= 0) break;
                }
            }
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, template.valueType));
                pos = end + 1;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), template.valueType));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) {
                pos++;
                continue;
            }

            // Littéral à délimiteurs (C2-é6, famille C) : /** et /*
            // ouvrent les commentaires de documentation et de bloc,
            // """ (kotlin seulement) une chaîne brute — le plus long
            // préfixe d'abord (/** avant /*) ; la fermante est cherchée
            // à l'offset déclaré, absente elle porte l'état
            // inter-lignes.
            //
            // Évalué AVANT le commentaire de ligne (C2-é7, Lua) : un
            // littéral peut PARTAGER son préfixe avec lui (« --[[ »
            // commence par « -- ») — l'ordre de déclaration tranche,
            // et le littéral, plus long, doit gagner.
            TokenizerTemplate.BlockLiteral opened = null;
            for (TokenizerTemplate.BlockLiteral bl : template.blockLiterals) {
                if (line.startsWith(bl.open, pos) && bl.appliesTo(language)) {
                    opened = bl;
                    break;
                }
            }
            if (opened != null) {
                int end = line.indexOf(opened.close, pos + opened.searchOffset);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + opened.close.length(),
                            opened.type));
                    pos = end + opened.close.length();
                } else {
                    spans.add(new LineSpan(pos, line.length(), opened.type));
                    pos = line.length();
                    state = opened.state;
                }
                continue;
            }

            // Commentaire ancré : en début de ligne, après un blanc ou
            // après un caractère d'ancrage — jamais au milieu d'un mot
            // (foo#bar n'est pas un commentaire).
            if (commentStartsAt(template, line, pos)) {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Variable : $var, ${var}, $?, $1… (${...} peut contenir
            // un # sans ouvrir de commentaire). C2-é11 (CSS) : le mot
            // de la variable accepte des caractères déclarés
            // ($primary-color).
            if (template.variablePrefixes.contains(ch)) {
                int end = pos + 1;
                if (end < line.length() && line.charAt(end) == '{') {
                    end++;
                    while (end < line.length() && line.charAt(end) != '}') end++;
                    if (end < line.length()) end++;
                } else if (end < line.length()
                        && (Character.isLetter(line.charAt(end))
                                || line.charAt(end) == '_'
                                || template.variableWordChars.contains(
                                        line.charAt(end)))) {
                    while (end < line.length()
                            && (Character.isLetterOrDigit(line.charAt(end))
                                    || line.charAt(end) == '_'
                                    || template.variableWordChars.contains(
                                            line.charAt(end)))) {
                        end++;
                    }
                } else if (end < line.length() && line.charAt(end) == '?') {
                    end++;
                } else if (end < line.length()
                        && Character.isDigit(line.charAt(end))) {
                    end++;
                }
                if (end > pos + 1) {
                    spans.add(new LineSpan(pos, end, template.variableType));
                    pos = end;
                    continue;
                }
            }

            // Chaîne à préfixe (C2-é9, Python) : une lettre déclarée
            // (f, r, b…) immédiatement suivie d'un guillemet de valeur
            // ouvre une chaîne DONT le préfixe fait partie — échappements
            // apparents, orpheline → fin de ligne sans état.
            if (template.stringPrefixes.contains(ch)
                    && pos + 1 < line.length()
                    && template.valueQuotes.contains(line.charAt(pos + 1))) {
                char quote = line.charAt(pos + 1);
                int end = SpanUtils.findStringEnd(line, pos + 2, quote);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1,
                            template.valueType);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(),
                            template.valueType));
                    pos = line.length();
                }
                continue;
            }

            // Chaîne à guillemets doubles : échappements sautés, un
            // seul jeton ; non fermée → jusqu'en fin de ligne.
            // C2-é8 (SQL) : le type du jeton est DÉCLARABLE — la
            // sémantique « simple » (échappements avalés, pas de
            // sous-span) reste celle du Shell, seul le type change
            // (identifiant quoté SQL → PROPERTY).
            if (template.plainStringQuotes.contains(ch)) {
                int end = pos + 1;
                while (end < line.length()
                        && !template.plainStringQuotes.contains(line.charAt(end))) {
                    if (line.charAt(end) == '\\' && end + 1 < line.length()) end++;
                    end++;
                }
                if (end < line.length()) end++;
                spans.add(new LineSpan(pos, end,
                        template.plainStringType != null
                                ? template.plainStringType
                                : template.valueType));
                pos = end;
                continue;
            }

            // Chaîne quotée à échappements apparents (C2-é5, JSON) :
            // la fermante est recherchée en sautant les échappements,
            // les séquences internes reçoivent ESCAPE ; le lookahead
            // de clé requalifie une chaîne fermée suivie d'un
            // séparateur ; orpheline → fin de ligne et l'état
            // inter-lignes se propage si le guillemet le déclare.
            // C2-é10 (Smali) : dérogation tombante — une orpheline
            // n'est PAS consommée, les règles suivantes reprennent à
            // pos (garde historique Smali : « " » → PUNCT, re-lexée).
            if (template.valueQuotes.contains(ch)) {
                int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    TokenType type = template.valueType;
                    if (template.stringKeyLookahead) {
                        int after = end + 1;
                        while (after < line.length()
                                && Character.isWhitespace(line.charAt(after))) {
                            after++;
                        }
                        if (after < line.length()
                                && template.keyValueSeparators.contains(
                                        line.charAt(after))) {
                            type = template.keyType;
                        }
                    }
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, type);
                    pos = end + 1;
                    continue;
                }
                if (!template.stringOrphanFallsThrough) {
                    spans.add(new LineSpan(pos, line.length(), template.valueType));
                    pos = line.length();
                    if (ch == template.interLineQuote
                            || template.interLineQuotes.contains(ch)) {
                        state = template.interLineState;
                    }
                    continue;
                }
                // Orpheline tombante : ne pas consommer, les règles
                // suivantes reprennent à pos.
            }

            // Littéral à guillemet simple : paire → jeton ; orpheline →
            // fin de ligne et l'état inter-lignes se propage.
            if (template.interLineQuote != 0 && ch == template.interLineQuote) {
                int end = line.indexOf(template.interLineQuote, pos + 1);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, template.valueType));
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), template.valueType));
                    pos = line.length();
                    state = template.interLineState;
                }
                continue;
            }

            // Littéral de caractère (C2-é6, famille C) : paire → un
            // jeton du type déclaré ('a' → CHAR) ; orpheline → fin de
            // ligne, SANS état inter-lignes (garde historique C).
            if (template.charQuotes.contains(ch)) {
                int end = pos + 1;
                while (end < line.length()
                        && !template.charQuotes.contains(line.charAt(end))) {
                    if (line.charAt(end) == '\\' && end + 1 < line.length()) end++;
                    end++;
                }
                if (end < line.length()) end++;
                spans.add(new LineSpan(pos, end, template.charType));
                pos = end;
                continue;
            }

            // Substitution de commande par paires ; orpheline → repli.
            if (template.commandQuotes.contains(ch)) {
                int end = line.indexOf(ch, pos + 1);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, template.commandQuoteType));
                    pos = end + 1;
                    continue;
                }
            }

            // Template literal JS/TS (C2-é6, famille C) : backtick +
            // interpolations ${…} découpées en VARIABLE pour les
            // distinguer du texte littéral — pas d'état multi-lignes
            // (limite historique documentée). Porte par langage.
            if (ch == '`'
                    && template.templateLiteralLanguages.contains(language)) {
                int close = SpanUtils.findStringEnd(line, pos + 1, '`');
                if (close >= 0) {
                    addTemplateLiteralSpans(spans, line, pos, close + 1);
                    pos = close + 1;
                    continue;
                }
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
                continue;
            }

            // Annotation (C2-é6, famille C) : préfixe + identifiant en
            // un jeton (@Override → ANNOTATION). C2-é9 (Python) : le
            // mot peut inclure des caractères déclarés (@app.route) et
            // le préfixe peut être BORNÉ — évalué seulement en début de
            // ligne ou après un blanc (a@b n'est pas un décorateur).
            // C2-é10 (Smali/CSS) : le JETON entier (préfixe compris)
            // peut être raffiné par une table de mots-clés (.class →
            // KEYWORD, .foo → ANNOTATION ; @media → KEYWORD, @var →
            // VARIABLE).
            if (template.annotationPrefixes.contains(ch)
                    && (!template.annotationRequiresBoundary
                            || pos == 0
                            || Character.isWhitespace(line.charAt(pos - 1)))) {
                int end = pos + 1;
                while (end < line.length()
                        && (Character.isLetterOrDigit(line.charAt(end))
                                || line.charAt(end) == '_'
                                || template.annotationWordChars.contains(
                                        line.charAt(end)))) {
                    end++;
                }
                TokenType type = template.annotationType;
                if (!template.annotationKeywords.isEmpty()
                        && template.annotationKeywords.contains(
                                line.substring(pos, end))) {
                    type = template.annotationKeywordType;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Descripteur de type (C2-é10, Smali) : L…; ou [L…; — la
            // fermante « ; » doit exister à moins de maxSpan
            // caractères de l'ouverture ; sinon la règle de mot
            // reprend (garde historique).
            if (template.typeDescriptorPrefix != 0
                    && (ch == template.typeDescriptorPrefix
                        || (template.typeDescriptorBracket != 0
                            && ch == template.typeDescriptorBracket
                            && pos + 1 < line.length()
                            && line.charAt(pos + 1) == template.typeDescriptorPrefix))) {
                int end = line.indexOf(template.typeDescriptorTerminator, pos);
                if (end >= 0 && end - pos < template.typeDescriptorMaxSpan) {
                    spans.add(new LineSpan(pos, end + 1,
                            template.typeDescriptorType));
                    pos = end + 1;
                    continue;
                }
            }

            // Nombre : chiffre en frontière de mot (pas collé à un
            // mot) sauf dérogation (JSON historique colle aux mots) ;
            // le signe moins ancré à un chiffre ouvre aussi un nombre
            // si le template le déclare (JSON : -4.2) ; le POINT ancré
            // à un chiffre de même (C2-é8, SQL : .5) — ces deux
            // ouvertures dérogatoires ignorent la frontière (a.5 est
            // un nombre en SQL, pas une ponctuation).
            boolean numberAtBoundary = !template.numberRequiresBoundary
                    || pos == 0
                    || !Character.isLetterOrDigit(line.charAt(pos - 1));
            boolean digitStart = Character.isDigit(ch) && numberAtBoundary;
            boolean leadingMinus = template.numberLeadingMinus && ch == '-'
                    && pos + 1 < line.length()
                    && Character.isDigit(line.charAt(pos + 1));
            boolean leadingDot = template.numberLeadingDot && ch == '.'
                    && pos + 1 < line.length()
                    && Character.isDigit(line.charAt(pos + 1));
            // C2-é10 (Smali) : signes inconditionnels — « - »/« + »
            // ouvrent TOUJOURS un nombre, même seuls.
            boolean signStart = template.numberSignChars.contains(ch);
            if (digitStart || leadingMinus || leadingDot || signStart) {
                int end = pos + 1;
                while (end < line.length()
                        && (Character.isDigit(line.charAt(end))
                                || template.valueNumberExtras.contains(
                                        line.charAt(end)))) {
                    end++;
                }
                // C2-é11 (CSS) : l'unité (lettres, %) est ABSORBÉE dans
                // le nombre — 12px, 1.5em, 100% en un seul jeton.
                if (template.numberUnitSuffix) {
                    while (end < line.length()
                            && (Character.isLetter(line.charAt(end))
                                    || line.charAt(end) == '%')) {
                        end++;
                    }
                }
                spans.add(new LineSpan(pos, end, template.valueNumberType));
                pos = end;
                continue;
            }

            // Littéral de couleur (C2-é6, famille C) : préfixe suivi de
            // 3/4/6/8 chiffres hexadécimaux → type déclaré ; toute
            // autre longueur retombe dans les règles suivantes.
            if (template.colorPrefix != 0 && ch == template.colorPrefix
                    && pos + 1 < line.length()) {
                int colorEnd = pos + 1;
                while (colorEnd < line.length()
                        && SpanUtils.isHexChar(line.charAt(colorEnd))) {
                    colorEnd++;
                }
                int colorLen = colorEnd - pos - 1;
                if (colorLen == 3 || colorLen == 4 || colorLen == 6 || colorLen == 8) {
                    spans.add(new LineSpan(pos, colorEnd, template.colorType));
                    pos = colorEnd;
                    continue;
                }
            }

            // Mot : mot-clé, appel de fonction, initiale majuscule,
            // repli — caractères supplémentaires déclarés inclus ;
            // les chiffres participent au mot sauf dérogation (JSON :
            // mots de lettres pures, le chiffre collé ouvre un nombre).
            // C2-é11 (CSS) : des caractères peuvent ouvrir le mot SANS
            // le continuer (« & » SCSS : a&b est DEUX mots).
            if (Character.isLetter(ch) || template.wordExtraChars.contains(ch)
                    || template.wordStartExtras.contains(ch)) {
                int end = pos + 1;
                while (end < line.length()
                        && wordContinues(template, line.charAt(end))) {
                    end++;
                }
                String word = line.substring(pos, end);
                TokenType type;
                if (keywords.contains(word)) {
                    // Raffinement des mots-clés (famille C) : contrôle
                    // et modificateur via les tables déclarées, sinon
                    // le type de mot-clé du template.
                    if (!template.wordControlKeywords.isEmpty()
                            && template.wordControlKeywords.contains(word)) {
                        type = template.wordControlType;
                    } else if (!template.wordModifierKeywords.isEmpty()
                            && template.wordModifierKeywords.contains(word)) {
                        type = template.wordModifierType;
                    } else {
                        type = template.valueKeywordType;
                    }
                } else if (!template.wordTypeKeywords.isEmpty()
                        && template.wordTypeKeywords.contains(word)) {
                    type = template.wordTypeKeywordType;
                } else if (template.wordAllCapsType != null
                        && SpanUtils.isAllCaps(word) && word.length() > 1) {
                    type = template.wordAllCapsType;
                } else if (!template.wordFuncContainsChars.isEmpty()
                        && containsAny(word, template.wordFuncContainsChars)) {
                    // C2-é10 (Smali) : un mot CONTENANT un caractère
                    // déclaré est un opcode (invoke-direct → FUNC).
                    type = TokenType.FUNC;
                } else if (!template.wordFuncSuffixChars.isEmpty()
                        && endsWithAny(word, template.wordFuncSuffixChars)) {
                    // C2-é10 (Smali) : un mot SE TERMINANT par un
                    // caractère déclaré est un chemin (array/ → FUNC).
                    type = TokenType.FUNC;
                } else if (!template.wordFuncAfterCase
                        && end < line.length()
                        && template.wordFuncFollowChars.contains(line.charAt(end))) {
                    type = TokenType.FUNC;
                } else if (template.wordUpperCaseType != null
                        && Character.isUpperCase(word.charAt(0))) {
                    type = template.wordUpperCaseType;
                } else if (template.wordFuncAfterCase
                        && end < line.length()
                        && template.wordFuncFollowChars.contains(line.charAt(end))) {
                    // C2-é9 (Python) : la CASSE est évaluée avant la
                    // parenthèse d'appel — Foo( est TYPE, pas FUNC.
                    type = TokenType.FUNC;
                } else if (template.wordAfterDotType != null && pos > 0
                        && line.charAt(pos - 1) == '.') {
                    type = template.wordAfterDotType;
                } else {
                    type = template.wordFallbackType;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Course d'opérateurs (|&, ;;, &&…).
            if (template.operatorChars.contains(ch)) {
                int end = pos + 1;
                while (end < line.length()
                        && template.operatorChars.contains(line.charAt(end))) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            // Opérateurs mono-caractère (C2-é5, JSON) : jamais
            // fusionnés en course — ':' et ',' restent des spans
            // distincts même adjacents.
            if (template.operatorSingleChars.contains(ch)) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                continue;
            }

            // Repli : un caractère en ponctuation.
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    /**
     * Mots-clés du REGISTRE des langages pour le langage demandé
     * (C2-é6, famille C) : les langages intégrés conservent leurs
     * ensembles exacts, les langages enregistrés par l'hôte sont
     * honorés, les identifiants inconnus retombent sur les mots-clés
     * Java — reprise de la règle historique de
     * {@code CLikeTokenizer.getKeywords}.
     */
    private static Set<String> keywordsFor(String language) {
        if (language == null) return KeywordTables.JAVA_KEYWORDS;
        LanguageProfile profile = LanguageRegistry.forName(language);
        return profile != null ? profile.keywords : KeywordTables.JAVA_KEYWORDS;
    }

    /**
     * Caractère de suite d'un mot en flux libre : le template décide
     * si les chiffres participent au mot (Shell) ou si les mots sont
     * de lettres pures et laissent les chiffres ouvrir des nombres
     * (JSON).
     */
    private static boolean wordContinues(TokenizerTemplate template, char c) {
        if (template.wordConsumesDigits) {
            return Character.isLetterOrDigit(c)
                    || template.wordExtraChars.contains(c);
        }
        return Character.isLetter(c) || template.wordExtraChars.contains(c);
    }

    /** Vrai si le mot contient l'un des caractères déclarés. */
    private static boolean containsAny(String word,
            java.util.Set<Character> chars) {
        for (int i = 0; i < word.length(); i++) {
            if (chars.contains(word.charAt(i))) return true;
        }
        return false;
    }

    /** Vrai si le mot se termine par l'un des caractères déclarés. */
    private static boolean endsWithAny(String word,
            java.util.Set<Character> chars) {
        return !word.isEmpty() && chars.contains(word.charAt(word.length() - 1));
    }

    /**
     * Découpe un JS/TS template literal en spans (C2-é6, porté octet
     * pour octet de {@code CLikeTokenizer}).
     *
     * <p>Un template literal ressemble à :
     * <pre>`Hello ${name}, you are ${age} years old`</pre>
     *
     * <p>On veut colorier :
     * <ul>
     *   <li>le texte littéral (backticks compris) en
     *       {@link TokenType#STRING} ;</li>
     *   <li>les séquences d'échappement ({@code \n}, {@code \t}) en
     *       {@link TokenType#ESCAPE} ;</li>
     *   <li>les interpolations {@code ${expr}} en
     *       {@link TokenType#VARIABLE} (pour les distinguer
     *       visuellement du texte littéral — pratique pour debug une
     *       template React qui rate).</li>
     * </ul>
     *
     * <p>Algorithme en une passe : on cherche les marqueurs
     * {@code ${}, on split ; à l'intérieur d'une interpolation on
     * continue jusqu'au {@code }} fermant (en comptant les accolades
     * imbriquées pour gérer les objets {@code ${ {a: 1}.a }}).
     * L'intérieur de l'interpolation n'est PAS re-tokénisé — effet
     * visuel « VARIABLE bleuté » suffisant, la tokenisation récursive
     * ne serait pas justifiée.</p>
     */
    private static void addTemplateLiteralSpans(List<LineSpan> spans,
            String line, int start, int end) {
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
        // Segment de chaîne final (entre la dernière interpolation et
        // le backtick fermant).
        if (segStart < end) {
            spans.add(new LineSpan(segStart, end, TokenType.STRING));
        }
    }

    /**
     * Le préfixe de commentaire démarre en pos ET, si le template
     * exige une ancre, pos est une frontière (début de ligne, blanc,
     * ou caractère d'ancrage déclaré).
     */
    private static boolean commentStartsAt(TokenizerTemplate template,
            String line, int pos) {
        if (commentAt(template, line, pos) == null) return false;
        if (!template.commentRequiresBoundary) return true;
        return pos == 0 || Character.isWhitespace(line.charAt(pos - 1))
                || template.commentAnchors.contains(line.charAt(pos - 1));
    }

    private GenericTokenizer() {}
}
