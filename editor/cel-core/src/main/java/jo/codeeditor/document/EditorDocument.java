package jo.codeeditor.document;

import jo.codeeditor.rope.Rope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Modèle de texte indexé par ligne, adossé à une Rope.
 * <p>
 * Maintient un tableau parallèle d'offsets de début de ligne pour des
 * recherches de ligne en O(log n). La méthode replace() splice
 * l'index de lignes de façon incrémentale : réutilise le préfixe
 * inchangé, scanne les retours à la ligne dans le remplacement, et
 * décale le suffixe du delta d'édition.
 */
public final class EditorDocument {

    private Rope rope;
    private int[] lineStarts;
    private int revision;
    private String cachedText;
    private int cachedRevision = -1;

    private EditorDocument(Rope rope, int[] lineStarts, int revision) {
        this.rope = rope;
        this.lineStarts = lineStarts;
        this.revision = revision;
    }

    /**
     * Crée un nouvel EditorDocument à partir du texte donné.
     */
    public static EditorDocument of(String text) {
        if (text == null) text = "";
        Rope rope = Rope.fromString(text);
        int[] starts = computeLineStarts(text);
        return new EditorDocument(rope, starts, 0);
    }

    /**
     * Calcule les offsets de début de ligne pour le texte donné.
     * lineStarts[0] = 0, et chaque entrée suivante est l'offset après un '\n'.
     */
    private static int[] computeLineStarts(String text) {
        if (text.isEmpty()) return new int[]{0};
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    // ── Accesseurs ────────────────────────────────────────────────

    /** Renvoie le texte complet du document (mis en cache par révision). */
    public String getText() {
        if (cachedRevision != revision) {
            cachedText = rope.toString();
            cachedRevision = revision;
        }
        return cachedText;
    }

    /** Renvoie le nombre total de caractères. */
    public int length() {
        return rope.length();
    }

    /** Renvoie le nombre de lignes. */
    public int lineCount() {
        return lineStarts.length;
    }

    /** Renvoie le caractère à l'offset donné. */
    public char charAt(int offset) {
        return rope.charAt(offset);
    }

    /** Renvoie le numéro de révision courant. */
    public int getRevision() {
        return revision;
    }

    // ── Requêtes de ligne ─────────────────────────────────────────

    /**
     * Renvoie le numéro de ligne pour l'offset de caractère donné.
     * Utilise une recherche binaire sur lineStarts.
     */
    public int lineForOffset(int offset) {
        if (offset < 0) return 0;
        if (offset >= rope.length()) return lineStarts.length - 1;

        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * Renvoie l'offset de début de la ligne donnée (index 0, borné).
     */
    public int lineStart(int line) {
        line = clampLine(line);
        return lineStarts[line];
    }

    /**
     * Renvoie l'offset de fin de la ligne donnée (exclusif, borné).
     * C'est l'offset du caractère de retour à la ligne, ou la longueur
     * du document pour la dernière ligne.
     */
    public int lineEnd(int line) {
        line = clampLine(line);
        if (line + 1 < lineStarts.length) {
            // La fin est juste avant le début de la ligne suivante (le '\n')
            return lineStarts[line + 1] - 1;
        }
        return rope.length();
    }

    /**
     * Renvoie le contenu texte de la ligne donnée (sans le retour à la
     * ligne final).
     */
    public String lineText(int line) {
        int start = lineStart(line);
        int end = lineEnd(line);
        if (start >= end) return "";
        return getText().substring(start, end);
    }

    private int clampLine(int line) {
        return Math.max(0, Math.min(line, lineStarts.length - 1));
    }

    // ── Mutation ──────────────────────────────────────────────────

    /**
     * Remplace le texte dans [start, end) par l'insertion donnée.
     * Renvoie un nouvel EditorDocument avec l'index de lignes splice-é
     * de façon incrémentale.
     *
     * @param start     offset de début (inclusif)
     * @param end       offset de fin (exclusif)
     * @param insertion le texte de remplacement
     * @return nouvel EditorDocument avec l'édition appliquée
     */
    public EditorDocument replace(int start, int end, String insertion) {
        if (start < 0 || end > rope.length() || start > end) {
            throw new IndexOutOfBoundsException(
                "start=" + start + ", end=" + end + ", length=" + rope.length());
        }
        if (start == end && insertion.isEmpty()) return this;

        // Applique à la rope
        Rope newRope = rope.replace(start, end, insertion);

        // Splice incrémental de l'index de lignes (algorithme identique à CodeAssist)
        int delta = insertion.length() - (end - start);

        int firstLine = lineForOffset(start);
        int lastLine = (end > start) ? lineForOffset(end) : firstLine;

        // Compte les retours à la ligne dans le remplacement
        int breaks = 0;
        for (int i = 0; i < insertion.length(); i++) {
            if (insertion.charAt(i) == '\n') breaks++;
        }

        int tailCount = lineStarts.length - 1 - lastLine;
        int[] newLineStarts = new int[firstLine + 1 + breaks + tailCount];

        // Préfixe inchangé : lineStarts[0..firstLine]
        System.arraycopy(lineStarts, 0, newLineStarts, 0, firstLine + 1);

        // Débuts créés à l'intérieur du remplacement
        int w = firstLine + 1;
        for (int i = 0; i < insertion.length(); i++) {
            if (insertion.charAt(i) == '\n') {
                newLineStarts[w++] = start + i + 1;
            }
        }

        // Suffixe décalé : lignes après lastLine
        for (int r = lastLine + 1; r < lineStarts.length; r++) {
            newLineStarts[w++] = lineStarts[r] + delta;
        }

        return new EditorDocument(newRope, newLineStarts, revision + 1);
    }

    @Override
    public String toString() {
        return "EditorDocument(lines=" + lineCount() + ", len=" + length() + ", rev=" + revision + ")";
    }

    // ── Vérification gros document ────────────────────────────────

    private static final int CHAR_LIMIT = 2_500_000;
    private static final int LINE_LIMIT = 50_000;

    /**
     * Renvoie true si ce document dépasse les seuils « gros document » :
     * 2,5 M de caractères ou 50 k lignes.
     */
    public boolean isLarge() {
        return rope.length() > CHAR_LIMIT || lineStarts.length > LINE_LIMIT;
    }
}
