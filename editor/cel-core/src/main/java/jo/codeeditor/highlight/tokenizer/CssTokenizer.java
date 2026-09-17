package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur CSS (et SCSS/LESS) du parser maison : règles at, propriétés,
 * sélecteurs, variables, couleurs hexadécimales, nombres avec unités,
 * chaînes et commentaires multi-lignes. Extrait de SyntaxHighlighter pour
 * isoler cette grammaire du dispatcher de coloration.
 */
public final class CssTokenizer {

    // ── Tokéniseur CSS / SCSS / LESS ─────────────────────

    /**
     * Tokénise une ligne CSS (ou SCSS/LESS).
     *
     * <p>Reconnaît :
     * <ul>
     *   <li>Règles at ({@code @media}, {@code @import}, {@code @keyframes}, …)
     *       → KEYWORD</li>
     *   <li>Noms de propriété ({@code color}, {@code background}, …) → PROPERTY</li>
     *   <li>Valeurs de chaîne ({@code "…" / '…'}) → STRING</li>
     *   <li>Nombres avec unités ({@code 12px}, {@code 1.5em}, {@code 100%}) → NUMBER</li>
     *   <li>Couleurs hexadécimales ({@code #fff}, {@code #aabbcc}) → NUMBER</li>
     *   <li>Commentaires (slash-astérisque ... astérisque-slash, peuvent s'étendre sur plusieurs lignes) → COMMENT</li>
     *   <li>Sélecteurs (début de ligne, avant un {@code {}) → TYPE</li>
     *   <li>Interpolations de variables ({@code $var}, {@code @var}) → VARIABLE</li>
     * </ul>
     *
     * <p>La machine à états est volontairement simple — pas de tentative
     * d'analyse complète sélecteurs vs déclarations. Heuristique : les
     * jetons façon identifiant en début de ligne qui ne sont pas une
     * propriété connue sont traités comme un sélecteur (TYPE). Dans une
     * déclaration (après propriété-deux-points), tout ce qui suit jusqu'au
     * point-virgule est une valeur (STRING/NUMBER/PLAIN).
     */
    public static StyledLine styleCss(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Gestion du commentaire de bloc inter-lignes.
        if (state == LexState.CSS_COMMENT) {
            int end = line.indexOf("*/", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                pos = end + 2;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
            }
        }

        // Gestion de la chaîne inter-lignes (rare mais valide).
        if (state == LexState.CSS_STRING) {
            int end = line.indexOf('"', pos);
            if (end < 0) end = line.indexOf('\'', pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                pos = end + 1;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Commentaire de bloc
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int end = line.indexOf("*/", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.CSS_COMMENT;
                }
                continue;
            }

            // Commentaire de ligne (// — valide seulement en SCSS/LESS, mais inoffensif en CSS)
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '/') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Règle at (@media, @import, @keyframes, etc.)
            if (ch == '@') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '-' || line.charAt(end) == '_')) {
                    end++;
                }
                String word = line.substring(pos, end);
                if (KeywordTables.CSS_AT_RULES.contains(word)) {
                    spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                } else {
                    // Peut être une interpolation @var — VARIABLE
                    spans.add(new LineSpan(pos, end, TokenType.VARIABLE));
                }
                pos = end;
                continue;
            }

            // Variable SCSS ($var)
            if (ch == '$') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-')) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.VARIABLE));
                pos = end;
                continue;
            }

            // Couleur hexadécimale (#fff, #aabbcc, #aabbccff)
            if (ch == '#' && pos + 1 < line.length() && SpanUtils.isHexChar(line.charAt(pos + 1))) {
                int end = pos + 1;
                while (end < line.length() && SpanUtils.isHexChar(line.charAt(end))) end++;
                int len = end - pos - 1;
                if (len == 3 || len == 4 || len == 6 || len == 8) {
                    spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                    pos = end;
                    continue;
                }
            }

            // Littéral de chaîne
            if (ch == '"' || ch == '\'') {
                int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.CSS_STRING;
                }
                continue;
            }

            // Nombre avec unité optionnelle
            if (Character.isDigit(ch) || (ch == '.' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
                // Suffixe d'unité (px, em, rem, %, vh, vw, deg, s, ms, etc.)
                while (end < line.length() && (Character.isLetter(line.charAt(end)) || line.charAt(end) == '%')) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifiant — nom de propriété, sélecteur ou valeur possible
            if (Character.isLetter(ch) || ch == '_' || ch == '-' || ch == '.' || ch == '&') {
                int end = pos + 1;
                while (end < line.length()
                        && (Character.isLetterOrDigit(line.charAt(end))
                            || line.charAt(end) == '_' || line.charAt(end) == '-'
                            || line.charAt(end) == '.')) {
                    end++;
                }
                String word = line.substring(pos, end);
                TokenType type;
                if (KeywordTables.CSS_PROPERTIES.contains(word)) {
                    type = TokenType.PROPERTY;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    // Nom de classe de sélecteur ou valeur constante
                    type = TokenType.TYPE;
                } else {
                    // Plain — mot-clé de valeur possible (inherit, auto, etc.)
                    type = TokenType.PLAIN;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Opérateurs (=, :, ;)
            if (ch == ':' || ch == ';' || ch == '{' || ch == '}' || ch == ',' || ch == '!' || ch == '>' || ch == '+' || ch == '~' || ch == '*' || ch == '=') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                continue;
            }

            // Ponctuation
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    private CssTokenizer() {}
}
