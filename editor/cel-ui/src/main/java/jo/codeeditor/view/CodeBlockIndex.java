package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

/**
 * Cache des paires d'accolades {@code { … }} et des régions
 * d'indentation, mémoïsé par révision du document.
 *
 * <p><b>Modèle de calcul :</b> les blocs
 * ({@code CodeBlock}) sont calculés à la demande et mémoïsés
 * par révision du document — pas d'analyseur de langage
 * asynchrone requis : on calcule les blocs à la demande et on les
 * cache par révision du document. La clé de cache est
 * {@code (identité session, doc.getRevision(), identité doc)}.
 * Une simple comparaison de référence NE PEUT PAS suffire car
 * la rope est remplacée à chaque édition.</p>
 *
 * <p><b>Deux modes selon la famille lexicale :</b></p>
 * <ul>
 *   <li><b>C_LIKE</b> (Java, Kotlin, C/C++, Go, Rust, JS/TS,
 *       etc.) : les blocs sont des paires d'accolades
 *       {@code { … }}. Le {@code startColumn} est la position
 *       réelle du {@code {} dans la ligne. Le guide est centré
 *       à cette position (centrage sur l'accolade).</li>
 *   <li><b>Non C_LIKE</b> (Python, YAML, etc.) : les blocs sont
 *       des régions d'indentation calculées par l'algorithme
 *       {@code IndentRange} (balayage bas-vers-haut, mode
 *       {@code offSide} pour Python). Le
 *       {@code startColumn} est le compte d'espaces
 *       d'indentation de la ligne de début. Le guide est
 *       positionné à {@code startColumn * spaceWidth} (compte
 *       d'espaces d'indentation).</li>
 * </ul>
 *
 * <p><b>Recherche binaire :</b> les blocs sont triés par
 * {@code startLine}, ce qui permet une recherche binaire pour
 * trouver le premier bloc visible en O(log blocs) au lieu
 * d'un balayage linéaire à chaque frame.</p>
 *
 * <p><b>Caractéristiques :</b></p>
 * <ul>
 *   <li>Le cache est intégré à la vue (pas besoin d'un
 *       analyseur de langage séparé) — fonctionne pour tous
 *       les langages enregistrés dans le registre, sans
 *       module de langage dédié.</li>
 *   <li>Le cache est invalidé par révision du document (pas
 *       par un signal d'analyseur) — plus simple et plus
 *       fiable.</li>
 *   <li>Les blocs non C_LIKE utilisent l'algorithme
 *       {@code IndentRange} — y compris le mode
 *       {@code offSide} pour Python.</li>
 * </ul>
 *
 * @author jjoblab
 */
final class CodeBlockIndex {

    private final EditorView view;

    // Cache : (session, docRevision, docIdentité) → blocs
    private EditorSession cachedSession;
    private int cachedDocRev = -1;
    private EditorDocument cachedDoc;
    private int[][] cachedBlocks = new int[0][];
    // Trié par startLine pour recherche binaire.
    private int[] sortedStartLines = new int[0];
    // Indice correspondant dans cachedBlocks.
    private int[] sortedIndices = new int[0];
    // Famille du langage au moment du calcul (pour savoir si
    // indentCountMode est true ou false).
    private boolean cachedIndentCountMode;

    CodeBlockIndex(EditorView view) {
        this.view = view;
    }

    /**
     * Retourne les blocs de code cachés, reconstruits si le
     * document a changé depuis le dernier calcul.
     *
     * @return tableau de {@code [startLine, startCol, endLine, endCol, toBottomOfEndLine]}
     *         triés par startLine, ou un tableau vide.
     */
    int[][] get() {
        EditorSession s = view.session;
        if (s == null) return new int[0][];
        EditorDocument d = s.getDocument();
        if (d == null) return new int[0][];
        int rev = d.getRevision();
        if (s != cachedSession || rev != cachedDocRev || d != cachedDoc) {
            cachedSession = s;
            cachedDocRev = rev;
            cachedDoc = d;
            computeBlocks(d, s);
        }
        return cachedBlocks;
    }

    /** Renvoie true si le dernier calcul était en mode
     *  indentCountMode (non C_LIKE), false sinon (C_LIKE). */
    boolean isIndentCountMode() {
        return cachedIndentCountMode;
    }

    /**
     * Recherche binaire : premier bloc dont startLine >= firstVisible.
     * Retourne l'indice dans le tableau trié (0..n), ou n si aucun.
     */
    int firstVisibleBlock(int firstVisible) {
        int lo = 0, hi = sortedStartLines.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedStartLines[mid] < firstVisible) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Calcule les blocs pour le document courant. Pour C_LIKE,
     * détecte les paires d'accolades. Pour non C_LIKE, utilise
     * l'algorithme IndentRange.
     */
    private void computeBlocks(EditorDocument doc, EditorSession session) {
        jo.codeeditor.languages.LanguageProfile profile =
                session.getLanguageProfile();
        boolean isCLike = profile != null
                && profile.family == jo.codeeditor.languages.SyntaxFamily.C_LIKE;
        cachedIndentCountMode = !isCLike;

        if (isCLike) {
            computeBraceBlocks(doc);
        } else {
            computeIndentBlocks(doc);
        }
        // Trier par startLine pour la recherche binaire.
        sortByStartLine();
    }

    // ── C_LIKE : paires d'accolades { … } ────────────────────

    private void computeBraceBlocks(EditorDocument doc) {
        java.util.List<int[]> blocks = new java.util.ArrayList<>();
        CharSequence chars = doc.charSequence();
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();

        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;

        int lineCount = doc.lineCount();
        for (int li = 0; li < lineCount; li++) {
            int lineStart = doc.lineStart(li);
            int lineEnd = doc.lineEnd(li);
            inLineComment = false;

            for (int ci = lineStart; ci < lineEnd; ci++) {
                char c = chars.charAt(ci);
                char next = ci + 1 < chars.length() ? chars.charAt(ci + 1) : 0;

                if (inLineComment) break;
                if (inBlockComment) {
                    if (c == '*' && next == '/') { inBlockComment = false; ci++; }
                    continue;
                }
                if (inString) {
                    if (c == '\\') { ci++; continue; }
                    if (c == quoteChar) inString = false;
                    continue;
                }
                if (c == '/' && next == '/') { inLineComment = true; break; }
                if (c == '/' && next == '*') { inBlockComment = true; ci++; continue; }
                if (c == '"' || c == '\'') { inString = true; quoteChar = c; continue; }

                if (c == '{') {
                    int col = ci - lineStart;
                    stack.push(new int[]{li, col});
                } else if (c == '}' && !stack.isEmpty()) {
                    int[] open = stack.pop();
                    int endCol = ci - lineStart;
                    // toBottomOfEndLine : true si le '}' est le
                    // dernier caractère non-blanc de sa ligne.
                    boolean toBottom = isEndOfLineBrace(chars, ci);
                    blocks.add(new int[]{open[0], open[1], li, endCol,
                            toBottom ? 1 : 0});
                }
            }
        }
        cachedBlocks = blocks.toArray(new int[0][]);
    }

    private static boolean isEndOfLineBrace(CharSequence text, int braceOffset) {
        int lineEnd = braceOffset + 1;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;
        for (int i = braceOffset + 1; i < lineEnd; i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    // ── Non C_LIKE : algorithme IndentRange ──────────────────

    /**
     * Implémente l'algorithme IndentRange pour les
     * langages basés sur l'indentation (Python, YAML, etc.).
     *
     * <p>Balaye le document de bas en haut, en suivant
     * l'indentation. Quand l'indentation diminue, crée une
     * région de bloc de la ligne courante jusqu'à la ligne
     * précédente qui avait la même indentation.</p>
     *
     * <p>Mode {@code offSide} (Python) : les lignes vides sont
     * associées au bloc précédent (pas au suivant). Cela évite
     * que les lignes vides ne brisent les blocs.</p>
     */
    private void computeIndentBlocks(EditorDocument doc) {
        java.util.List<int[]> blocks = new java.util.ArrayList<>();
        CharSequence chars = doc.charSequence();
        int lineCount = doc.lineCount();

        // Détecter le mode offSide : Python utilise offSide=true
        // (lignes vides associées au bloc précédent), YAML non.
        jo.codeeditor.languages.LanguageProfile profile =
                cachedSession.getLanguageProfile();
        boolean offSide = profile != null
                && profile.family == jo.codeeditor.languages.SyntaxFamily.PYTHON;

        // Pile des régions précédentes : [indent, line, endAbove]
        java.util.List<int[]> previousRegions = new java.util.ArrayList<>();
        // Sentinelle.
        previousRegions.add(new int[]{-1, lineCount, lineCount});

        // Tab size : pour calculer l'indentation.
        int tabSize = jo.codeeditor.edit.EditOps.detectTabSize(chars);

        for (int line = lineCount - 1; line >= 0; line--) {
            int lineStart = doc.lineStart(line);
            int lineEnd = doc.lineEnd(line);
            int indent = computeIndentLevel(chars, lineStart, lineEnd, tabSize);
            int[] previous = previousRegions.get(previousRegions.size() - 1);

            if (indent == -1) {
                // Ligne vide (que des blancs).
                if (offSide) {
                    // Pour offSide, les lignes vides sont associées
                    // au bloc précédent.
                    previous[2] = line; // endAbove = line
                }
                continue;
            }

            if (previous[0] > indent) {
                // L'indentation a diminué — on remonte.
                do {
                    previousRegions.remove(previousRegions.size() - 1);
                    previous = previousRegions.get(previousRegions.size() - 1);
                } while (previous[0] > indent);

                // Nouvelle région de bloc.
                int endLineNumber = previous[2] - 1;
                if (endLineNumber - line >= 1) {
                    int startCol = computeStartColumn(chars, lineStart, lineEnd, tabSize);
                    blocks.add(new int[]{line, startCol, endLineNumber, startCol, 1});
                }
            }

            if (previous[0] == indent) {
                previous[2] = line; // endAbove = line
            } else {
                // previous[0] < indent : nouvelle région plus profonde.
                previousRegions.add(new int[]{indent, line, line});
            }
        }

        // Les blocs sont calculés de bas en haut — inverser pour
        // avoir l'ordre startLine croissant.
        java.util.Collections.reverse(blocks);
        cachedBlocks = blocks.toArray(new int[0][]);
    }

    /**
     * Calcule le niveau d'indentation d'une ligne.
     * @return -1 si la ligne ne contient que des blancs OU si la
     *         ligne est un commentaire (traité comme ligne vide).
     */
    private static int computeIndentLevel(CharSequence chars, int start, int end, int tabSize) {
        int indent = 0;
        int i = start;
        while (i < end) {
            char c = chars.charAt(i);
            if (c == ' ') indent++;
            else if (c == '\t') indent = indent - indent % tabSize + tabSize;
            else break;
            i++;
        }
        if (i == end) return -1; // ligne vide
        // Les lignes commentées sont traitées comme
        // des lignes vides (indentation déduite des voisines).
        // Détecter les préfixes de commentaire courants.
        if (i + 1 < end) {
            char c0 = chars.charAt(i);
            char c1 = chars.charAt(i + 1);
            // // commentaire de ligne (C-like, JS, etc.)
            if (c0 == '/' && c1 == '/') return -1;
            // # commentaire (Python, YAML, Shell, etc.)
            if (c0 == '#') return -1;
            // -- commentaire (Lua, SQL, etc.)
            if (c0 == '-' && c1 == '-') return -1;
        }
        return indent;
    }

    /**
     * Calcule la colonne de début (indentation) d'une ligne.
     * @return -1 si la ligne ne contient que des blancs.
     */
    private static int computeStartColumn(CharSequence chars, int start, int end, int tabSize) {
        int column = 0;
        int i = start;
        while (i < end) {
            char c = chars.charAt(i);
            if (c == ' ') column++;
            else if (c == '\t') column += tabSize;
            else break;
            i++;
        }
        if (i == end) return -1;
        return column;
    }

    // ── Tri par startLine pour recherche binaire ─────────────

    private void sortByStartLine() {
        int n = cachedBlocks.length;
        sortedStartLines = new int[n];
        sortedIndices = new int[n];
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Integer[] sorted = indices.clone();
        java.util.Arrays.sort(sorted, (a, b) -> Integer.compare(
                cachedBlocks[a][0], cachedBlocks[b][0]));
        for (int i = 0; i < n; i++) {
            sortedIndices[i] = sorted[i];
            sortedStartLines[i] = cachedBlocks[sorted[i]][0];
        }
    }
}
