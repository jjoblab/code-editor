package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

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
 * <p>Les migrations suivantes du cliquet étendront la machine
 * (marqueurs de document, clés à lookahead) tokéniseur par
 * tokéniseur, chaque suite existante restant verte sans modification
 * de ses assertions.</p>
 */
public final class GenericTokenizer {

    /**
     * Tokénise une ligne selon le template. Les grammaires clé=valeur
     * sans état inter-lignes sortent en NORMAL (comportement
     * Properties/TOML historique).
     */
    public static StyledLine styleLine(TokenizerTemplate template, String line,
            int entryState) {
        if (template.freeForm) {
            return styleFreeFormLine(template, line, entryState);
        }
        return styleZonedLine(template, line, entryState);
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
            String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Littéral inter-lignes non fermé à la ligne précédente :
        // consommé jusqu'à la fermante (bash autorise le multi-ligne).
        if (template.interLineQuote != 0 && state == template.interLineState) {
            int end = line.indexOf(template.interLineQuote, pos);
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

            // Commentaire ancré : en début de ligne, après un blanc ou
            // après un caractère d'ancrage — jamais au milieu d'un mot
            // (foo#bar n'est pas un commentaire).
            if (commentStartsAt(template, line, pos)) {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Variable : $var, ${var}, $?, $1… (${...} peut contenir
            // un # sans ouvrir de commentaire).
            if (template.variablePrefixes.contains(ch)) {
                int end = pos + 1;
                if (end < line.length() && line.charAt(end) == '{') {
                    end++;
                    while (end < line.length() && line.charAt(end) != '}') end++;
                    if (end < line.length()) end++;
                } else if (end < line.length()
                        && (Character.isLetter(line.charAt(end))
                                || line.charAt(end) == '_')) {
                    while (end < line.length()
                            && (Character.isLetterOrDigit(line.charAt(end))
                                    || line.charAt(end) == '_')) {
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

            // Chaîne à guillemets doubles : échappements sautés, un
            // seul jeton ; non fermée → jusqu'en fin de ligne.
            if (template.plainStringQuotes.contains(ch)) {
                int end = pos + 1;
                while (end < line.length()
                        && !template.plainStringQuotes.contains(line.charAt(end))) {
                    if (line.charAt(end) == '\\' && end + 1 < line.length()) end++;
                    end++;
                }
                if (end < line.length()) end++;
                spans.add(new LineSpan(pos, end, template.valueType));
                pos = end;
                continue;
            }

            // Chaîne quotée à échappements apparents (C2-é5, JSON) :
            // la fermante est recherchée en sautant les échappements,
            // les séquences internes reçoivent ESCAPE ; le lookahead
            // de clé requalifie une chaîne fermée suivie d'un
            // séparateur ; orpheline → fin de ligne et l'état
            // inter-lignes se propage si le guillemet le déclare.
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
                } else {
                    spans.add(new LineSpan(pos, line.length(), template.valueType));
                    pos = line.length();
                    if (ch == template.interLineQuote) {
                        state = template.interLineState;
                    }
                }
                continue;
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

            // Substitution de commande par paires ; orpheline → repli.
            if (template.commandQuotes.contains(ch)) {
                int end = line.indexOf(ch, pos + 1);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, template.commandQuoteType));
                    pos = end + 1;
                    continue;
                }
            }

            // Nombre : chiffre en frontière de mot (pas collé à un
            // mot) sauf dérogation (JSON historique colle aux mots) ;
            // le signe moins ancré à un chiffre ouvre aussi un nombre
            // si le template le déclare (JSON : -4.2).
            boolean numberAtBoundary = !template.numberRequiresBoundary
                    || pos == 0
                    || !Character.isLetterOrDigit(line.charAt(pos - 1));
            if ((Character.isDigit(ch)
                    || (template.numberLeadingMinus && ch == '-'
                            && pos + 1 < line.length()
                            && Character.isDigit(line.charAt(pos + 1))))
                    && numberAtBoundary) {
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

            // Mot : mot-clé, appel de fonction, initiale majuscule,
            // repli — caractères supplémentaires déclarés inclus ;
            // les chiffres participent au mot sauf dérogation (JSON :
            // mots de lettres pures, le chiffre collé ouvre un nombre).
            if (Character.isLetter(ch) || template.wordExtraChars.contains(ch)) {
                int end = pos + 1;
                while (end < line.length()
                        && wordContinues(template, line.charAt(end))) {
                    end++;
                }
                String word = line.substring(pos, end);
                TokenType type;
                if (template.valueKeywords.contains(word)) {
                    type = template.valueKeywordType;
                } else if (end < line.length()
                        && template.wordFuncFollowChars.contains(line.charAt(end))) {
                    type = TokenType.FUNC;
                } else if (template.wordUpperCaseType != null
                        && Character.isUpperCase(word.charAt(0))) {
                    type = template.wordUpperCaseType;
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
