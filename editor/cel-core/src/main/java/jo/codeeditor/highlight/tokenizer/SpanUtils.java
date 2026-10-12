package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.TokenType;

import java.util.List;

/**
 * Primitives de scan lexical et d'assemblage de {@link LineSpan} partagées
 * par tous les tokéniseurs du présent package. Regroupe les utilitaires
 * sans état (recherche de fin de chaîne, détection ALL_CAPS/hexadécimal,
 * découpage des chaînes avec échappements) afin que chaque tokéniseur se
 * concentre sur la grammaire de son propre langage.
 */
public final class SpanUtils {

    private SpanUtils() {}

    /** Opérateurs disposant de leur propre type de jeton. */
    public static final String OPERATORS = "+-*/%=!<>&|^~?:";

    /**
     * Ajoute des portions de chaîne avec mise en évidence des séquences
     * d'échappement. La chaîne globale reçoit {@code stringType}, mais les
     * séquences d'échappement (n, t, uXXXX) internes reçoivent
     * {@link TokenType#ESCAPE}.
     */
    public static void addStringSpans(List<LineSpan> spans, String line, int start, int end, TokenType stringType) {
        int pos = start;
        while (pos < end) {
            if (line.charAt(pos) == '\\' && pos + 1 < end) {
                // Émet le texte de chaîne avant l'échappement.
                if (pos > start) {
                    spans.add(new LineSpan(start, pos, stringType));
                }
                // Émet la séquence d'échappement (2-6 caractères : n, t, uXXXX, x-NN).
                int escEnd = pos + 2;
                if (pos + 1 < end) {
                    char next = line.charAt(pos + 1);
                    if (next == 'u' && pos + 5 < end) escEnd = pos + 6; // uXXXX
                    else if (next == 'x' && pos + 3 < end) escEnd = pos + 4; // x-NN
                }
                spans.add(new LineSpan(pos, Math.min(escEnd, end), TokenType.ESCAPE));
                pos = escEnd;
                start = pos;
            } else {
                pos++;
            }
        }
        // Émet le texte de chaîne restant.
        if (start < end) {
            spans.add(new LineSpan(start, end, stringType));
        }
    }

    /**
     * Trouve la fin d'un littéral de chaîne, en gérant les séquences d'échappement.
     */
    public static int findStringEnd(String line, int start, char quote) {
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++; // saute le caractère échappé
            } else if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Trouve la fin d'une chaîne d'attribut XML (gère les entités &amp;).
     */
    public static int findXmlStringEnd(String line, int start) {
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') return i;
        }
        return -1;
    }

    /** Renvoie true si le mot est en ALL_CAPS (au moins 2 caractères, tout en majuscules + chiffres + _). */
    public static boolean isAllCaps(String word) {
        if (word.length() < 2) return false;
        boolean hasLetter = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c)) return false;
            } else if (c != '_' && !Character.isDigit(c)) {
                return false;
            }
        }
        return hasLetter;
    }

    /** Renvoie true si le caractère est un chiffre hexadécimal (0-9, a-f, A-F). */
    public static boolean isHexChar(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
