package jo.codeeditor.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;

import java.util.List;

/**
 * Conversion Position LSP → offset et application d'éditions sur un
 * contenu texte HORS éditeur (fichiers tiers du renommage et des
 * WorkspaceEdit). Implémentation unique — l'ancien code la dupliquait
 * dans {@code LspWorkspaceEditApplier} et {@code LspRenameProvider}.
 *
 * <p>Sémantique LSP (spécification 3.17) : la colonne compte les
 * <b>unités de code UTF-16</b> — un caractère hors BMP (surrogate pair)
 * vaut DEUX colonnes ; les positions sont bornées à la fin de ligne
 * (avant le {@code \n} ; le {@code \r} d'une fin CRLF appartient à la
 * ligne).</p>
 */
final class LspTextUtils {

    private LspTextUtils() {
        // Classe utilitaire.
    }

    /**
     * Position LSP (ligne, colonne) → offset dans le texte.
     *
     * @return l'offset, ou {@code -1} si la position est incohérente
     *         (ligne au-delà de la fin, position null)
     */
    static int positionToOffset(String text, Position pos) {
        if (text == null || pos == null) return -1;
        int line = Math.max(0, pos.getLine());
        int lineStart = 0;
        for (int i = 0; i < line; i++) {
            int nl = text.indexOf('\n', lineStart);
            if (nl < 0) return -1; // ligne au-delà de la fin du texte
            lineStart = nl + 1;
        }
        if (lineStart > text.length()) return -1;
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        int offset = lineStart + Math.max(0, pos.getCharacter());
        return Math.min(offset, lineEnd);
    }

    /**
     * Applique une liste de TextEdits LSP à un contenu texte : trie par
     * offset de début DÉCROISSANT (les éditions postérieures ne décalent
     * pas les offsets antérieures), vérifie le non-chevauchement et les
     * bornes.
     *
     * @return le nouveau contenu, ou {@code null} si les éditions sont
     *         invalides (chevauchement, plage hors bornes, texte null)
     */
    static String applyEdits(String content, List<TextEdit> edits) {
        if (content == null) return null;
        if (edits == null || edits.isEmpty()) return content;
        // Résout toutes les plages d'abord — validation complète avant
        // toute mutation (tout-ou-rien par fichier).
        int n = edits.size();
        int[] starts = new int[n];
        int[] ends = new int[n];
        String[] texts = new String[n];
        for (int i = 0; i < n; i++) {
            TextEdit te = edits.get(i);
            if (te == null || te.getRange() == null) return null;
            int start = positionToOffset(content, te.getRange().getStart());
            int end = positionToOffset(content, te.getRange().getEnd());
            if (start < 0 || end < start || end > content.length()) return null;
            starts[i] = start;
            ends[i] = end;
            texts[i] = te.getNewText() == null ? "" : te.getNewText();
        }
        // Ordre de début croissant pour le contrôle de chevauchement,
        // puis application en ordre décroissant.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(starts[a], starts[b]));
        for (int k = 1; k < n; k++) {
            int prev = order[k - 1], cur = order[k];
            if (starts[cur] < ends[prev]) {
                return null; // chevauchement — interdit par le protocole
            }
        }
        StringBuilder sb = new StringBuilder(content);
        for (int k = n - 1; k >= 0; k--) { // décroissant
            int i = order[k];
            sb.replace(starts[i], ends[i], texts[i]);
        }
        return sb.toString();
    }

    /**
     * Offset → Position LSP (ligne, colonne) calculé sur le TEXTE fourni
     * (unités de code UTF-16, convention de {@link #positionToOffset}).
     * Indépendant de toute vue : la conversion reste correcte même quand
     * aucun éditeur n'est lié (chemins de test et d'arrière-plan).
     *
     * <p>L'offset est borné à la longueur du texte ; la colonne compte
     * depuis le dernier {@code \n} (le {@code \r} d'une fin CRLF
     * appartient à la ligne au-dessus).</p>
     */
    static Position offsetToPosition(String text, int offset) {
        if (text == null || text.isEmpty()) return new Position(0, 0);
        int borne = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < borne; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, borne - lineStart);
    }

    /**
     * Offset de début (inclus) de la ligne 0-based demandée — borne au
     * texte : une ligne au-delà de la dernière ramène le début de la
     * dernière ligne existante.
     */
    static int lineStartOffset(String text, int line) {
        if (text == null || text.isEmpty() || line <= 0) return 0;
        int lineStart = 0;
        for (int i = 0; i < line; i++) {
            int nl = text.indexOf('\n', lineStart);
            if (nl < 0) break;
            lineStart = nl + 1;
        }
        return lineStart;
    }

    /**
     * Ordre lexicographique des positions LSP (ligne, puis colonne) —
     * aux offsets UTF-16 près.
     */
    static int comparePositions(Position a, Position b) {
        if (a == null || b == null) return 0;
        int c = Integer.compare(a.getLine(), b.getLine());
        return c != 0 ? c : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    /**
     * Les deux plages se chevauchent-elles (bornes INCLUSES : une plage
     * vide au caret intersecte le diagnostic qui couvre ce caret —
     * c'est la sémantique d'ancrage d'une action au point d'invocation).
     */
    static boolean rangesIntersect(org.eclipse.lsp4j.Range a, org.eclipse.lsp4j.Range b) {
        if (a == null || b == null) return false;
        return comparePositions(a.getEnd(), b.getStart()) >= 0
                && comparePositions(a.getStart(), b.getEnd()) <= 0;
    }

    /**
     * Décide si des éditions renvoyées par le serveur sont PÉRIMÉES par
     * rapport à la version du document synchronisée.
     *
     * <p>Politique : {@code null} (LSP 3.13, « changes » sans version)
     * → aucun contrôle possible, considéré frais ; version identique →
     * frais ; tout écart (le client a avancé — l'utilisateur a tapé — ou
     * le serveur annonce une version inconnue) → périmé : les positions
     * ne sont plus fiables, l'édition est REFUSÉE plutôt que corrompre
     * le texte. La rebase automatique des positions serait risquée (elle
     * n'est sûre que si les zones éditées ne se chevauchent pas) : le
     * refus est le comportement déterministe et sûr.</p>
     *
     * @param versionFromServer version portée par l'édition (peut être null)
     * @param currentVersion    version courante du document côté client
     * @return true si l'édition doit être refusée
     */
    static boolean isStaleEdit(Integer versionFromServer, int currentVersion) {
        if (versionFromServer == null) return false;
        return versionFromServer != currentVersion;
    }
}
