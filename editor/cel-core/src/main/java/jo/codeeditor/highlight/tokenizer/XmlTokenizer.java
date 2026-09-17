package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur XML/HTML du parser maison : balises, attributs, commentaires,
 * instructions de traitement, CDATA et entités, avec états inter-lignes
 * (XML_TAG, XML_STRING). Extrait de SyntaxHighlighter pour isoler cette
 * machine à états du reste du dispatcher de coloration.
 */
public final class XmlTokenizer {

    // ── Tokéniseur XML ────────────────────────────────────

    /**
     * Tokénise une ligne XML. Prend en charge :
     * <ul>
     *   <li>Balises : {@code <tag>}, {@code </tag>}, {@code <tag attr="val">}</li>
     *   <li>Attributs : {@code android:text="hello"}</li>
     *   <li>Commentaires : {@code <!-- ... -->} (inter-lignes)</li>
     *   <li>Instructions de traitement : {@code <?xml ... ?>}</li>
     *   <li>CDATA : {@code <![CDATA[ ... ]]>} (inter-lignes)</li>
     *   <li>Valeurs de chaîne avec mise en évidence des échappements</li>
     * </ul>
     * <p>Les noms de balise reçoivent {@link TokenType#TYPE}, les noms
     * d'attribut {@link TokenType#PROPERTY}, les valeurs de chaîne
     * {@link TokenType#STRING}.
     */
    public static StyledLine styleXml(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Gestion du commentaire XML inter-lignes.
        if (state == LexState.BLOCK_COMMENT) {
            int end = line.indexOf("-->", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 3, TokenType.COMMENT));
                pos = end + 3;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
            }
        }

        // Gestion du CDATA inter-lignes.
        if (state == LexState.KT_RAW_STRING) { // réutilisé pour CDATA
            int end = line.indexOf("]]>", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                pos = end + 3;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        // Gestion de la chaîne d'attribut inter-lignes.
        if (state == LexState.XML_STRING) {
            int end = SpanUtils.findXmlStringEnd(line, pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                pos = end + 1;
                state = LexState.XML_TAG; // de retour dans la liste d'attributs de la balise
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        // Gestion de l'état XML_TAG inter-lignes — nous sommes dans la
        // liste d'attributs d'une balise (la ligne précédente avait `<tag`
        // sans `>`). Poursuit l'analyse des attributs jusqu'à `>` ou `/>`.
        if (state == LexState.XML_TAG) {
            int[] nextState = new int[]{ LexState.XML_TAG };
            pos = parseXmlAttributes(line, pos, spans, nextState);
            state = nextState[0];
            // Vérifie si la balise a été fermée sur cette ligne.
            if (pos < line.length()) {
                char c = line.charAt(pos);
                if (c == '>') {
                    spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                    pos++;
                    state = LexState.NORMAL;
                } else if (c == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '>') {
                    spans.add(new LineSpan(pos, pos + 2, TokenType.PUNCT));
                    pos += 2;
                    state = LexState.NORMAL;
                }
            }
            // Si l'état est NORMAL maintenant, continue pour analyser le
            // texte/la balise suivante. Sinon, toute la ligne a été consommée.
            if (state != LexState.NORMAL) {
                return new StyledLine(spans, entryState, state);
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Blancs.
            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Commentaire XML <!-- ... -->
            if (ch == '<' && pos + 3 < line.length() && line.charAt(pos + 1) == '!'
                && line.charAt(pos + 2) == '-' && line.charAt(pos + 3) == '-') {
                int end = line.indexOf("-->", pos + 4);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 3, TokenType.COMMENT));
                    pos = end + 3;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // CDATA <![CDATA[ ... ]]>
            if (ch == '<' && pos + 8 < line.length() && line.startsWith("![CDATA[", pos + 1)) {
                int end = line.indexOf("]]>", pos + 9);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                    pos = end + 3;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.KT_RAW_STRING;
                }
                continue;
            }

            // Instruction de traitement <?xml ... ?>
            if (ch == '<' && pos + 1 < line.length() && line.charAt(pos + 1) == '?') {
                int end = line.indexOf("?>", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.ANNOTATION));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.ANNOTATION));
                    pos = line.length();
                }
                continue;
            }

            // Balise fermante </tag>
            if (ch == '<' && pos + 1 < line.length() && line.charAt(pos + 1) == '/') {
                int end = pos + 2;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == ':' || line.charAt(end) == '-' || line.charAt(end) == '.')) end++;
                spans.add(new LineSpan(pos, pos + 2, TokenType.PUNCT)); // </
                spans.add(new LineSpan(pos + 2, end, TokenType.TYPE)); // nom de balise
                if (end < line.length() && line.charAt(end) == '>') {
                    spans.add(new LineSpan(end, end + 1, TokenType.PUNCT)); // >
                    end++;
                }
                pos = end;
                continue;
            }

            // Balise ouvrante <tag ...>
            if (ch == '<') {
                int end = pos + 1;
                // Lit le nom de balise.
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == ':' || line.charAt(end) == '-' || line.charAt(end) == '.')) end++;
                spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT)); // <
                if (end > pos + 1) {
                    spans.add(new LineSpan(pos + 1, end, TokenType.TYPE)); // nom de balise
                }
                pos = end;

                // Analyse les attributs jusqu'à > ou />
                int[] nextState = new int[]{ LexState.XML_TAG };
                pos = parseXmlAttributes(line, pos, spans, nextState);
                // Vérifie si la balise a été fermée sur cette ligne.
                if (pos < line.length()) {
                    char c2 = line.charAt(pos);
                    if (c2 == '>') {
                        spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                        pos++;
                        state = LexState.NORMAL;
                    } else if (c2 == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '>') {
                        spans.add(new LineSpan(pos, pos + 2, TokenType.PUNCT)); // />
                        pos += 2;
                        state = LexState.NORMAL;
                    } else {
                        // Non reconnu — laisse l'état XML_TAG positionné pour que
                        // la ligne suivante poursuive l'analyse des attributs.
                        state = nextState[0];
                    }
                } else {
                    // Fin de ligne atteinte sans `>` fermant.
                    // nextState[0] vaut XML_STRING si une chaîne déborde sur
                    // la ligne suivante, ou XML_TAG s'il n'y a plus d'attributs.
                    state = nextState[0];
                }
                continue;
            }

            // Contenu textuel entre balises.
            if (ch == '&') {
                // Entité XML : &amp; &lt; etc.
                int end = line.indexOf(';', pos);
                if (end >= 0 && end - pos < 10) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.ESCAPE));
                    pos = end + 1;
                    continue;
                }
            }

            // Contenu textuel brut.
            int textStart = pos;
            while (pos < line.length() && line.charAt(pos) != '<') pos++;
            if (pos > textStart) {
                spans.add(new LineSpan(textStart, pos, TokenType.PLAIN));
            }
        }
        return new StyledLine(spans, entryState, state);
    }

    /**
     * Analyse les attributs XML (paires nom="valeur") au sein d'une balise,
     * à partir de {@code pos}. S'arrête — sans consommer — au {@code >}
     * ou {@code />} fermant. Renvoie la nouvelle position.
     * <p>Si la valeur de chaîne d'un attribut déborde sur la ligne
     * suivante, écrit {@link LexState#XML_STRING} dans
     * {@code nextState[0]} ; sinon le laisse inchangé.
     * <p>Extrait de {@link #styleXml} afin que le chemin de continuation
     * XML_TAG inter-lignes et le chemin de balise ouvrante en ligne
     * partagent exactement la même logique d'analyse des attributs —
     * sans cela, les noms d'attributs des lignes de continuation
     * seraient rendus en PLAIN.
     */
    private static int parseXmlAttributes(String line, int pos, List<LineSpan> spans,
                                    int[] nextState) {
        while (pos < line.length()) {
            char c2 = line.charAt(pos);
            if (Character.isWhitespace(c2)) { pos++; continue; }
            if (c2 == '>' || c2 == '/') {
                // Laisse l'appelant gérer > et />.
                break;
            }
            // Nom d'attribut.
            int attrStart = pos;
            while (pos < line.length() && line.charAt(pos) != '=' && line.charAt(pos) != '>'
                && line.charAt(pos) != '/' && !Character.isWhitespace(line.charAt(pos))) {
                pos++;
            }
            if (pos > attrStart) {
                spans.add(new LineSpan(attrStart, pos, TokenType.PROPERTY)); // nom d'attribut
            }
            // Ignore les blancs avant =.
            while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) pos++;
            // Signe =.
            if (pos < line.length() && line.charAt(pos) == '=') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                // Ignore les blancs après =.
                while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) pos++;
                // Valeur de chaîne.
                if (pos < line.length() && (line.charAt(pos) == '"' || line.charAt(pos) == '\'')) {
                    int strEnd = SpanUtils.findXmlStringEnd(line, pos + 1);
                    if (strEnd >= 0) {
                        SpanUtils.addStringSpans(spans, line, pos, strEnd + 1, TokenType.STRING);
                        pos = strEnd + 1;
                    } else {
                        // La valeur de chaîne déborde sur la ligne suivante.
                        spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                        pos = line.length();
                        nextState[0] = LexState.XML_STRING;
                        return pos;
                    }
                }
            }
        }
        return pos;
    }
    private XmlTokenizer() {}
}
