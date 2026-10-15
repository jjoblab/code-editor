package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;

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
 * <p>Les migrations suivantes du cliquet étendront la machine
 * (sections, nombres, guillemets, états inter-lignes) tokéniseur par
 * tokéniseur, chaque suite existante restant verte sans modification
 * de ses assertions.</p>
 */
public final class GenericTokenizer {

    /**
     * Tokénise une ligne selon le template. La grammaire clé=valeur
     * n'a pas d'état inter-lignes : l'état de sortie est NORMAL quel
     * que soit l'entrée (comportement Properties historique).
     */
    public static StyledLine styleLine(TokenizerTemplate template, String line,
            int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean seenSeparator = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Commentaire : un préfixe déclaré en tête de ligne,
            // uniquement AVANT le séparateur (un # en valeur n'est pas
            // un commentaire — comportement Properties historique).
            if (!seenSeparator && commentAt(template, line, pos) != null) {
                spans.add(new LineSpan(pos, line.length(),
                        jo.codeeditor.highlight.TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            if (Character.isWhitespace(ch)) {
                pos++;
                continue;
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

            // Valeur — échappements puis texte jusqu'au prochain backslash.
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
