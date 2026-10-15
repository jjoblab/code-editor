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
 * <p>Les migrations suivantes du cliquet étendront la machine
 * (états inter-lignes, variables, opérateurs) tokéniseur par
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
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean seenSeparator = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Commentaire : en zone clé toujours ; en zone valeur
            // uniquement si le template le déclare (un # après la
            // valeur commente en TOML, jamais en Properties).
            if ((template.commentsAnywhere || !seenSeparator)
                    && commentAt(template, line, pos) != null) {
                spans.add(new LineSpan(pos, line.length(),
                        jo.codeeditor.highlight.TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            if (Character.isWhitespace(ch)) {
                pos++;
                continue;
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

            if (!seenSeparator) {
                // Clé : jusqu'au prochain séparateur ou blanc.
                int end = pos + 1;
                while (end < line.length()
                        && !template.keyValueSeparators.contains(line.charAt(end))
                        && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, template.keyType));
                pos = end;
                continue;
            }

            if (template.tokenizesValue()) {
                // Valeur à jetons : chaque jeton reçoit son type déclaré.
                if (template.valueQuotes.contains(ch)) {
                    // Chaîne quotée ; orpheline → repli (PUNCT + mots).
                    int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                    if (end >= 0) {
                        SpanUtils.addStringSpans(spans, line, pos, end + 1,
                                template.valueType);
                        pos = end + 1;
                        continue;
                    }
                }

                if (Character.isDigit(ch)
                        || (ch == '-' && pos + 1 < line.length()
                                && Character.isDigit(line.charAt(pos + 1)))) {
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

                if (Character.isLetter(ch)) {
                    // Mot : reconnu → type de mot-clé, sinon type de valeur.
                    int end = pos + 1;
                    while (end < line.length()
                            && Character.isLetter(line.charAt(end))) {
                        end++;
                    }
                    String word = line.substring(pos, end);
                    TokenType type = template.valueKeywords.contains(word)
                            ? template.valueKeywordType : template.valueType;
                    spans.add(new LineSpan(pos, end, type));
                    pos = end;
                    continue;
                }
            } else {
                // Valeur mono-jeton — échappements puis texte jusqu'au
                // prochain backslash (grammaire Properties historique).
                if (template.escapesInValue && ch == '\\') {
                    int end = pos + 2;
                    if (pos + 1 < line.length()) {
                        char next = line.charAt(pos + 1);
                        if (next == 'u' && pos + 5 < line.length()) end = pos + 6;
                    }
                    spans.add(new LineSpan(pos, Math.min(end, line.length()),
                            jo.codeeditor.highlight.TokenType.ESCAPE));
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

            // Ponctuation de repli (crochets de flux, virgules,
            // guillemet orphelin…).
            spans.add(new LineSpan(pos, pos + 1,
                    jo.codeeditor.highlight.TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    /** Le préfixe de commentaire déclaré démarrant à pos, ou null. */
    private static String commentAt(TokenizerTemplate template, String line, int pos) {
        for (String prefix : template.lineComments) {
            if (line.startsWith(prefix, pos)) return prefix;
        }
        return null;
    }

    private GenericTokenizer() {}
}
