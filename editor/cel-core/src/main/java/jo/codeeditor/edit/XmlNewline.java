package jo.codeeditor.edit;

import java.util.ArrayList;
import java.util.List;

/**
 * Gestionnaire de saut de ligne XML/HTML : indentation structurelle issue de
 * l'imbrication des éléments (porté du {@code XmlNewlineHandler} de CodeAssist).
 * Isolé d'EditOps car il repose sur un balayage propre aux balises (pile
 * d'éléments ouverts, alignement d'attributs) sans rapport avec les langages C.
 */
final class XmlNewline {

    private XmlNewline() {}

    /**
     * Gestionnaire de saut de ligne XML : indentation structurelle issue de
     * l'imbrication des éléments.
     * <p>Porté depuis le {@code XmlNewlineHandler} de CodeAssist. Utilise un
     * balayage avant avec une pile d'indentations de lignes d'ouverture pour
     * calculer la profondeur d'imbrication correcte — plutôt que l'heuristique
     * « indenter plus profond après {@code >} » qui se déclenchait à tort
     * après les balises auto-fermantes et après les balises fermantes.
     * <p>Trois cas :
     * <ol>
     *   <li><b>Dans une balise de début non fermée</b> (curseur entre
     *       {@code <tag} et {@code >}) : aligner l'attribut replié sous le
     *       premier attribut (ou garder l'indentation courante si déjà sur
     *       une ligne repliée).</li>
     *   <li><b>Expansion de paire de balises</b> ({@code <Foo>|</Foo>}) :
     *       corps sur une ligne plus profonde, balise fermante désindentée
     *       sous {@code <Foo>}.</li>
     *   <li><b>Défaut</b> : indentation structurelle — un niveau plus profond
     *       que la ligne d'ouverture de l'élément ouvert le plus interne.</li>
     * </ol>
     */
    static RangeEdit xmlNewline(CharSequence text, int pos, String language) {
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);
        String unit = IndentDetection.detectIndentUnit(text);

        // Cas 1 : dans une balise de début non fermée → aligner l'attribut replié.
        int tagOpen = enclosingStartTag(text, pos);
        if (tagOpen >= 0) {
            String pad;
            if (EditTextUtils.lineStartForOffset(text, tagOpen) == lineStart) {
                // Ouvreur de balise sur cette ligne → aligner sous le premier attribut.
                pad = " ".repeat(attributeAlignColumn(text, tagOpen, unit.length()));
            } else {
                // Ouvreur de balise sur une ligne antérieure → cette ligne est déjà
                // un attribut replié ; garder son indentation.
                pad = currentIndent;
            }
            return new RangeEdit(pos, pos, "\n" + pad, pos + 1 + pad.length());
        }

        // Calculer l'indentation structurelle pour une nouvelle ligne à pos.
        String base = xmlIndentAt(text, pos, unit);

        // Cas 2 : expansion de paire de balises — <Foo …>|</Foo>
        int gt = prevNonBlankOnLine(text, pos);
        int closeLt = nextNonBlankOnLine(text, pos);
        if (gt >= 0 && text.charAt(gt) == '>'
            && (gt == 0 || text.charAt(gt - 1) != '/')
            && closeLt >= 0 && text.charAt(closeLt) == '<'
            && closeLt + 1 < text.length() && text.charAt(closeLt + 1) == '/') {
            String mid = "\n" + base;
            int start = pos;
            while (start > lineStart && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t')) {
                start--;
            }
            String closeIndent = dropIndentLevel(base, unit);
            return new RangeEdit(start, closeLt, mid + "\n" + closeIndent,
                start + mid.length());
        }

        // Cas 3 : défaut — indentation structurelle.
        return new RangeEdit(pos, pos, "\n" + base, pos + 1 + base.length());
    }

    // ── Aides newline XML (portées depuis Newline.kt de CodeAssist) ─────

    private static final int XML_INDENT_SCAN_LIMIT = 200_000;

    /**
     * Retourne l'offset du {@code <} ouvrant la balise de début non fermée la
     * plus interne à ou avant {@code pos}, ou -1 si le caret n'est pas dans
     * une balise de début. Une balise de début est « non fermée » si aucun
     * {@code >} n'a été vu depuis son {@code <} (c.-à-d. que nous sommes dans
     * sa liste d'attributs).
     * <p>Sensible aux guillemets : un {@code >} dans une valeur d'attribut ne
     * ferme pas la balise.
     */
    private static int enclosingStartTag(CharSequence text, int pos) {
        int i = pos - 1;
        boolean inQuote = false;
        char quote = ' ';
        while (i >= 0) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
                i--;
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
                i--;
                continue;
            }
            if (c == '>') return -1; // balise précédente fermée
            if (c == '<') {
                // `<` trouvé. Est-ce une balise de début ?
                if (i + 1 < text.length()) {
                    char after = text.charAt(i + 1);
                    if (after == '/' || after == '!' || after == '?') return -1;
                }
                return i;
            }
            i--;
        }
        return -1;
    }

    /**
     * La colonne (indexée à 0) où commence le premier attribut de la balise
     * démarrant à {@code tagOpen}, pour l'alignement d'attribut au repli.
     * Retombe sur {@code tagOpenCol + unitLen + 1} (juste après le nom de
     * la balise) quand la balise n'a pas encore d'attributs.
     */
    private static int attributeAlignColumn(CharSequence text, int tagOpen, int unitLen) {
        int i = tagOpen + 1;
        // Sauter le nom de la balise.
        while (i < text.length() && isXmlNameChar(text.charAt(i))) i++;
        // Sauter les blancs entre le nom et le premier attribut.
        int wsStart = i;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i > wsStart && i < text.length() && text.charAt(i) != '>' && text.charAt(i) != '/') {
            // Il y a un premier attribut — aligner dessous.
            int tagLineStart = EditTextUtils.lineStartForOffset(text, tagOpen);
            return i - tagLineStart;
        }
        // Pas encore d'attributs — aligner à une unité d'indentation après la colonne du nom de balise.
        int tagLineStart = EditTextUtils.lineStartForOffset(text, tagOpen);
        int tagCol = tagOpen - tagLineStart;
        return tagCol + 1 + (i - (tagOpen + 1)) + unitLen;
    }

    /**
     * L'indentation structurelle pour une nouvelle ligne à {@code pos} : une
     * {@code unit} plus profonde que la ligne d'ouverture de l'élément ouvert
     * le plus interne, ou "" à la racine. Le balayage avant maintient une
     * pile d'indentations de lignes d'ouverture (les balises ouvrantes
     * empilent, les fermantes dépilent, les auto-fermantes non), en sautant
     * les commentaires / CDATA / PI.
     */
    private static String xmlIndentAt(CharSequence text, int pos, String unit) {
        if (pos > XML_INDENT_SCAN_LIMIT) {
            return EditTextUtils.extractIndent(text, EditTextUtils.lineStartForOffset(text, pos));
        }
        List<String> stack = new ArrayList<>();
        int i = 0;
        while (i < pos) {
            if (text.charAt(i) != '<') { i++; continue; }
            if (EditTextUtils.startsWith(text, "<!--", i)) {
                i = indexAfter(text, "-->", i + 4, pos);
            } else if (EditTextUtils.startsWith(text, "<![CDATA[", i)) {
                i = indexAfter(text, "]]>", i + 9, pos);
            } else if (i + 1 < text.length() && text.charAt(i + 1) == '?') {
                i = indexAfter(text, "?>", i + 2, pos);
            } else if (i + 1 < text.length() && text.charAt(i + 1) == '!') {
                i = indexAfter(text, ">", i + 2, pos);
            } else if (i + 1 < text.length() && text.charAt(i + 1) == '/') {
                // Balise fermante → pop.
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                i = indexAfter(text, ">", i + 2, pos);
            } else if (i + 1 < text.length()
                && (Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) == '_')) {
                // Balise ouvrante ou auto-fermante.
                int gt = findTagEnd(text, i + 1, pos);
                if (gt < 0) {
                    // Balise non terminée avant le caret — nous sommes dedans.
                    i = pos;
                } else {
                    if (gt == 0 || text.charAt(gt - 1) != '/') {
                        // Pas auto-fermante → empiler son indentation de ligne d'ouverture.
                        stack.add(EditTextUtils.extractIndent(text, EditTextUtils.lineStartForOffset(text, i)));
                    }
                    i = gt + 1;
                }
            } else {
                i++;
            }
        }
        if (stack.isEmpty()) return "";
        return stack.get(stack.size() - 1) + unit;
    }

    /** Index du {@code >} terminant une balise dont le nom commence à {@code from}, en respectant les valeurs entre guillemets, ou -1. */
    private static int findTagEnd(CharSequence text, int from, int limit) {
        int i = from;
        boolean inQuote = false;
        char quote = ' ';
        while (i < limit) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == '>') {
                return i;
            } else if (c == '<') {
                return -1; // une nouvelle balise s'ouvre avant la fermeture de celle-ci → non terminée
            }
            i++;
        }
        return -1;
    }

    /** Offset juste après la première occurrence de needle dans [from, limit), ou limit si introuvable. */
    private static int indexAfter(CharSequence text, String needle, int from, int limit) {
        int idx = EditTextUtils.indexOf(text, needle, from);
        if (idx >= 0 && idx < limit) return idx + needle.length();
        return limit;
    }

    /** Index du caractère non blanc précédent pos sur la même ligne, ou -1. */
    private static int prevNonBlankOnLine(CharSequence text, int pos) {
        int i = pos - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i--;
        if (i >= 0 && text.charAt(i) != '\n') return i;
        return -1;
    }

    /** Index du caractère non blanc à ou après pos sur la même ligne, ou -1. */
    private static int nextNonBlankOnLine(CharSequence text, int pos) {
        int i = pos;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i < text.length() && text.charAt(i) != '\n') return i;
        return -1;
    }

    /** Retire une unité d'indentation de la chaîne d'indentation donnée (pour désindenter les balises fermantes). */
    private static String dropIndentLevel(String indent, String unit) {
        if (indent.endsWith(unit)) return indent.substring(0, indent.length() - unit.length());
        // Repli : retirer les blancs de fin correspondant à la longueur de l'unité.
        int drop = Math.min(indent.length(), unit.length());
        return indent.substring(0, indent.length() - drop);
    }

    private static boolean isXmlNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '.' || c == '-';
    }
}
