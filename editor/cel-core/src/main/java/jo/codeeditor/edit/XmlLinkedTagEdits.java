package jo.codeeditor.edit;

/**
 * Renommage lié des balises XML (4.5.0).
 *
 * <p>Pendant l'édition du NOM d'une balise OUVRANTE — frappe d'un
 * caractère ou backspace, caret au milieu ou en fin de nom — la
 * balise FERMANTE correspondante est mise à jour en miroir : même
 * caractère inséré/supprimé à la position alignée du nom fermant.
 * Les deux éditions voyagent dans UN SEUL lot d'annulation (une
 * étape d'undo), appliquées par offsets décroissants.</p>
 *
 * <p>L'appariement ouvrante → fermante est un empilement par NOM :
 * les balises homonymes imbriquées sont respectées
 * ({@code <div><div></div></div>}), les balises auto-fermantes
 * ({@code <br/>}) ne comptent pas, et le scan saute les commentaires
 * ({@code <!-- -->}), sections CDATA et processing instructions
 * ({@code <? ?>}) — borné à {@value #MATCH_SCAN_LIMIT} caractères en
 * avant (même garde que le guide d'indentation XML).</p>
 *
 * <p>Édition du nom FERMANT : aucun miroir (la liaison est
 * ouvrante → fermante, jamais l'inverse — l'utilisateur qui édite la
 * fermante sait ce qu'il fait). Les guillemets d'attributs sont
 * respectés lors du passage de la balise ouvrante.</p>
 */
final class XmlLinkedTagEdits {

    private XmlLinkedTagEdits() {}

    /** Bornes du scan avant (appariement de la fermante). */
    private static final int MATCH_SCAN_LIMIT = 200_000;

    /** Bornes du scan arrière (recherche du « < » ouvrant). */
    private static final int OPEN_SCAN_LIMIT = 10_000;

    /**
     * L'édition liée d'une frappe : les deux offsets d'insertion du
     * caractère — dans le nom ouvrant (le caret) et à la position
     * alignée du nom fermant — ou null si le caret n'est pas dans un
     * nom de balise ouvrante appariée.
     *
     * @param text  le texte complet du document
     * @param caret le caret (fin de frappe, dans le nom ouvrant)
     * @param ch    le caractère frappé
     * @return {insertionOuvrante, insertionFermante}, ou null
     */
    static int[] typeCharEdit(CharSequence text, int caret, char ch) {
        Targets t = find(text, caret);
        if (t == null) return null;
        return new int[]{caret, t.closeNameStart + (caret - t.nameStart)};
    }

    /**
     * L'édition liée d'un backspace : les débuts des deux plages de
     * suppression (un caractère chacun) — dans le nom ouvrant et à la
     * position alignée du nom fermant — ou null (caret au début du
     * nom, hors nom, pas de fermante appariée).
     *
     * @param text  le texte complet du document
     * @param caret le caret (le caractère À supprimer est avant)
     * @return {débutSuppressionOuvrante, débutSuppressionFermante}, ou null
     */
    static int[] backspaceEdit(CharSequence text, int caret) {
        Targets t = find(text, caret);
        if (t == null) return null;
        // Il faut un caractère du nom à supprimer : caret après le début.
        if (caret <= t.nameStart) return null;
        int delta = caret - t.nameStart;
        return new int[]{caret - 1, t.closeNameStart + delta - 1};
    }

    /** Les cibles appariées : noms ouvrant et fermant. */
    private static final class Targets {
        final int nameStart;
        final int nameEnd;
        final int closeNameStart;
        final int closeNameEnd;

        Targets(int nameStart, int nameEnd, int closeNameStart, int closeNameEnd) {
            this.nameStart = nameStart;
            this.nameEnd = nameEnd;
            this.closeNameStart = closeNameStart;
            this.closeNameEnd = closeNameEnd;
        }
    }

    /**
     * Trouve le nom de balise ouvrante qui contient le caret (bornes
     * incluses) et sa fermante appariée — ou null.
     */
    private static Targets find(CharSequence text, int caret) {
        if (text == null || caret <= 0 || caret > text.length()) return null;

        // 1. Le « < » ouvrant le plus proche AVANT le caret (borné) :
        //    une espace, un guillemet ou un « > » avant de l'atteindre
        //    écarte la liaison (le caret est dans un attribut, une
        //    valeur ou du texte, pas dans un nom de balise).
        int lt = -1;
        for (int i = caret - 1, lim = Math.max(0, caret - OPEN_SCAN_LIMIT);
                i >= lim; i--) {
            char c = text.charAt(i);
            if (c == '<') {
                lt = i;
                break;
            }
            if (c == '>' || Character.isWhitespace(c) || c == '"' || c == '\'') {
                return null;
            }
        }
        if (lt < 0) return null;
        int nameStart = lt + 1;
        if (nameStart >= text.length()) return null;
        // Balise fermante «</» : la liaison est ouvrante → fermante
        // uniquement (éditer une fermante ne touche pas l'ouvrante).
        if (text.charAt(nameStart) == '/') return null;
        int nameEnd = nameStart;
        while (nameEnd < text.length() && isXmlNameChar(text.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == nameStart) return null; // «<» collé à un séparateur
        if (caret < nameStart || caret > nameEnd) return null;

        String name = text.subSequence(nameStart, nameEnd).toString();

        // 2. Passer la fin de la balise ouvrante (attributs, guillemets
        //    respectés) ; auto-fermante «/>» → pas de fermante à lier.
        int tagEnd = scanTagEnd(text, nameEnd);
        if (tagEnd < 0) return null;
        if (tagEnd > 0 && text.charAt(tagEnd - 1) == '/') return null;

        // 3. Empilement par nom jusqu'à la fermante appariée.
        int depth = 1;
        int p = tagEnd + 1;
        int limit = Math.min(text.length(), tagEnd + 1 + MATCH_SCAN_LIMIT);
        while (p < limit) {
            char c = text.charAt(p);
            if (c != '<') {
                p++;
                continue;
            }
            if (p + 1 < limit && text.charAt(p + 1) == '!') {
                // Commentaire ou CDATA : sauter jusqu'au terminateur.
                p = skipUntil(text, p + 2, limit, "-->", "]]>");
                continue;
            }
            if (p + 1 < limit && text.charAt(p + 1) == '?') {
                p = skipUntil(text, p + 2, limit, "?>", "?>");
                continue;
            }
            if (p + 1 < limit && text.charAt(p + 1) == '/') {
                // Balise fermante : homonyme → dépile.
                int cn = p + 2;
                int cnEnd = cn;
                while (cnEnd < limit && isXmlNameChar(text.charAt(cnEnd))) cnEnd++;
                if (text.subSequence(cn, cnEnd).toString().equals(name)) {
                    depth--;
                    if (depth == 0) {
                        return new Targets(nameStart, nameEnd, cn, cnEnd);
                    }
                }
                int end = scanTagEnd(text, Math.max(cnEnd, p + 2));
                if (end < 0) return null;
                p = end + 1;
                continue;
            }
            // Balise ouvrante : homonyme NON auto-fermante → empile.
            int on = p + 1;
            int onEnd = on;
            while (onEnd < limit && isXmlNameChar(text.charAt(onEnd))) onEnd++;
            int end = scanTagEnd(text, Math.max(onEnd, p + 1));
            if (end < 0) return null;
            boolean selfClosing = end > 0 && text.charAt(end - 1) == '/';
            if (!selfClosing && text.subSequence(on, onEnd).toString().equals(name)) {
                depth++;
            }
            p = end + 1;
        }
        return null; // fermante introuvable dans la borne
    }

    /** La position du « > » fermant la balise qui démarre à/after from, -1 si absent. */
    private static int scanTagEnd(CharSequence text, int from) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == '>') {
                return i;
            } else if (c == '<') {
                return -1; // balise malformée
            }
        }
        return -1;
    }

    /** Avance jusqu'au premier terminateur trouvé (commentaire/CDATA/PI). */
    private static int skipUntil(CharSequence text, int from, int limit,
            String terminatorA, String terminatorB) {
        for (int i = from; i + terminatorA.length() <= limit; i++) {
            if (regionMatches(text, i, terminatorA)
                    || regionMatches(text, i, terminatorB)) {
                return i + terminatorA.length();
            }
        }
        return limit;
    }

    private static boolean regionMatches(CharSequence text, int at, String s) {
        if (at + s.length() > text.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (text.charAt(at + i) != s.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isXmlNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '.' || c == '-';
    }
}
