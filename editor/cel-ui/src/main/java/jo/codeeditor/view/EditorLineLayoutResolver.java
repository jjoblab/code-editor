package jo.codeeditor.view;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;

/**
 * Résolution de layout par ligne et correspondance de colonnes consciente
 * des inlays.
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>construction des entrées du {@link LineRenderCache}</b> — sur un
 *       miss de cache, filtre les inlay hints et tokens sémantiques par
 *       ligne depuis les seaux de la session (tri stable des inlays par
 *       colonne pour le tissage), construit les tables colonnes
 *       brutes↔visuelles et stocke l'entrée validée par triple-stamp
 *       (rev texte + rev inlay + rev sém) ;</li>
 *   <li><b>correspondance rawCol↔visualCol</b> — un hint ancré À
 *       {@code rawCol} occupe les colonnes visuelles qui suivent
 *       {@code rawToVisual[rawCol]} ; repli sur l'identité quand la ligne
 *       n'a pas d'inlays, est un composite de pli replié ou que le cache
 *       n'a pas d'entrée ;</li>
 *   <li><b>requête de pli replié</b> — la région de pli réduite qui
 *       COMMENCE à une ligne doc (les hints n'y sont jamais tissés) ;</li>
 *   <li><b>conversion de token sémantique</b> — superpose les types du
 *       serveur de langage (method, class, enum, …) sur les
 *       {@link TokenType} de la coloration lexicale.</li>
 * </ul>
 *
 * <p>EditorView conserve les relais package-privés (peintres, prefetcher,
 * tap resolver et tests les appellent) ; le cache
 * {@code renderCache} reste un champ de la vue (également lu par le pont
 * de langage et le listener de décalage de lignes).</p>
 */
class EditorLineLayoutResolver {

    private final EditorView view;

    EditorLineLayoutResolver(EditorView view) {
        this.view = view;
    }

    /**
     * Retourne la région de pli repliée qui COMMENCE à {@code docLine}, ou null.
     */
    DiagnosticShift.FoldRegion collapsedFoldStartingAtLine(int docLine) {
        for (DiagnosticShift.FoldRegion r : view.session.getFoldRegions()) {
            if (!r.collapsed) continue;
            if (view.session.getDocument().lineForOffset(r.start) == docLine) return r;
        }
        return null;
    }

    /**
     * Superpose les tokens sémantiques par-dessus les spans lexicaux de
     * coloration syntaxique. Les tokens sémantiques viennent du serveur de
     * langage (method, class, enum, etc.) et surchargent la couleur au
     * mieux-devinée du lexer quand présents.
     */
    static TokenType semanticTypeToTokenType(int semType) {
        // Types de tokens sémantiques façon LSP : 0=namespace 1=type
        // 2=class 3=enum 4=interface 5=struct 6=parameter 7=variable
        // 8=property 9=method 10=function 11=keyword 12=number 13=string
        // 14=comment ...
        // Couverture élargie aux tokens alimentés par le serveur Java
        // (enums, params, locaux, champs) via la légende canonique de
        // JdtSemanticHighlighter.TOKEN_TYPES.
        switch (semType) {
            case 1: case 2: case 3: case 5:
                return TokenType.TYPE;
            case 4:
                return TokenType.ANNOTATION;
            case 6: case 7:
                return TokenType.VARIABLE;
            case 8:
                return TokenType.PROPERTY;
            case 9: case 10: return TokenType.FUNC;
            case 11: return TokenType.KEYWORD;
            case 12: return TokenType.NUMBER;
            case 13: return TokenType.STRING;
            case 14: return TokenType.COMMENT;
            default: return null;
        }
    }

    /**
     * Retourne le layout par ligne en cache (StyledLine + inlays filtrés +
     * spans sémantiques filtrés + tables colonnes brutes↔visuelles). Sur
     * un miss de cache, les listes filtrées sont calculées depuis les
     * listes globales de la session et stockées, pour que les frames
     * suivantes d'une ligne inchangée soient O(1).
     *
     * <p>La validation par triple-stamp (rev texte + rev inlay + rev sém)
     * fait qu'une seule frappe n'invalide que les lignes dont le texte a
     * réellement changé — pas tout le viewport. Éviction LRU à 512
     * entrées.
     */
    LineRenderCache.LineCacheEntry layoutForLine(int lineNum, String lineText) {
        if (view.session == null) return null;
        EditorDocument doc = view.session.getDocument();
        if (lineNum < 0 || lineNum >= doc.lineCount()) return null;
        int textRev = view.session.getLineTextRevision(lineNum);
        int inlayRev = view.session.getInlayHintsRevision();
        int semRev = view.session.getSemanticTokensRevision();
        LineRenderCache.LineCacheEntry entry = view.renderCache.get(lineNum, textRev, inlayRev, semRev);
        if (entry != null) return entry;
        // Miss de cache — calcule les inlays + spans sém par ligne filtrés.
        int lineStart = doc.lineStart(lineNum);
        int lineEnd = doc.lineEnd(lineNum);
        // Filtre depuis les SEAUX PAR LIGNE de la session au lieu
        // d'itérer les listes globales complètes (des getters qui copient
        // défensivement toute la liste à chaque appel coûteraient
        // O(hints + tokens) allocations par ligne manquée, i.e. par
        // scroll). L'accès au seau est O(taille du seau) ; l'index est
        // mémoïsé sur l'identité de la liste source et reconstruit une
        // fois par setInlayHints/setSemanticTokens ou édition.
        List<DiagnosticShift.InlayHint> lineHints = view.session.getInlayHintsForLine(lineNum);
        List<LineRenderCache.InlayPiece> inlays = new ArrayList<>(lineHints.size());
        for (DiagnosticShift.InlayHint h : lineHints) {
            int col = h.offset - lineStart;
            inlays.add(new LineRenderCache.InlayPiece(col, h.text));
        }
        // buildColumnMaps SUPPOSE les pièces triées par col (il avance un
        // seul index pendant que rawCol croît). La liste globale de hints
        // n'est PAS triée par ligne — le serveur ajoute les hints de type
        // de var APRÈS les hints de paramètre, donc une ligne comme
        // {@code var x = max(a, b);} reçoit [param@17, param@20, var@7]
        // et le hint var (col < index courant) serait silencieusement
        // ABANDONNÉ des tables de colonnes → le hint ne serait jamais
        // rendu et le texte après lui ne serait pas décalé. Un tri
        // (stable) répare le tissage.
        if (inlays.size() > 1) {
            inlays.sort((a, b) -> Integer.compare(a.col, b.col));
        }
        // Filtre les tokens sémantiques à ceux qui intersectent cette
        // ligne, et les convertit en SemSpans par ligne (relatifs à la
        // colonne + couleur ARGB).
        List<DiagnosticShift.SemanticToken> lineTokens = view.session.getSemanticTokensForLine(lineNum);
        List<LineRenderCache.SemSpan> semSpans = new ArrayList<>(lineTokens.size());
        for (DiagnosticShift.SemanticToken t : lineTokens) {
            int tokEnd = t.start + t.length;
            if (t.start >= lineEnd || tokEnd <= lineStart) continue;
            int startCol = EditorView.clamp(t.start - lineStart, 0, lineText.length());
            int endCol = EditorView.clamp(tokEnd - lineStart, 0, lineText.length());
            if (startCol >= endCol) continue;
            TokenType ttype = semanticTypeToTokenType(t.type);
            if (ttype == null) continue;
            int color = view.theme.colorForToken(ttype);
            semSpans.add(new LineRenderCache.SemSpan(startCol, endCol, color));
        }
        // Construit les tables colonnes brutes↔visuelles depuis les inlays.
        int[][] maps = LineRenderCache.buildColumnMaps(lineText.length(), inlays);
        StyledLine styled = null;
        List<StyledLine> styledLines = view.session.getStyledLines();
        if (lineNum < styledLines.size()) {
            styled = styledLines.get(lineNum);
        }
        LineRenderCache.LineCacheEntry newEntry = new LineRenderCache.LineCacheEntry(
            lineNum, textRev, inlayRev, semRev,
            inlays, semSpans, maps[0], maps[1], styled);
        view.renderCache.put(newEntry);
        return newEntry;
    }

    /** Retourne la StyledLine en cache pour la ligne donnée, ou null. */
    StyledLine cachedStyledFor(int lineNum, String lineText) {
        LineRenderCache.LineCacheEntry e = layoutForLine(lineNum, lineText);
        return e != null ? (StyledLine) e.layout : null;
    }

    /**
     * Colonne brute du document → colonne VISUELLE (tissée d'inlays) pour
     * la ligne donnée. Un hint ancré À {@code rawCol} occupe les colonnes
     * entre {@code rawToVisual[rawCol]} et
     * {@code rawToVisual[rawCol] + hintLen}, donc le caret pour
     * {@code rawCol} s'ancre juste AVANT le hint — exactement la
     * sémantique de {@code rawToVisual}.
     *
     * <p>Repli sur la colonne brute quand la ligne n'a pas d'inlays, est
     * un composite de pli replié (les hints n'y sont jamais tissés), ou
     * que le cache n'a pas d'entrée — les appelants peuvent donc
     * l'utiliser inconditionnellement dans les chemins de dessin.
     */
    int visualColFor(int line, int rawCol) {
        if (view.session == null) return rawCol;
        EditorDocument doc = view.session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return rawCol;
        LineRenderCache.LineCacheEntry e = layoutForLine(line, doc.lineText(line));
        if (e == null || e.inlays.isEmpty() || e.rawToVisual == null) return rawCol;
        if (collapsedFoldStartingAtLine(line) != null) return rawCol; // composite : identité
        if (rawCol < 0) return 0;
        if (rawCol >= e.rawToVisual.length) return e.rawToVisual[e.rawToVisual.length - 1];
        return e.rawToVisual[rawCol];
    }

    /**
     * Colonne VISUELLE (tissée d'inlays) → colonne brute du document. Un
     * hit à l'intérieur d'un hint s'aligne sur sa colonne d'ancrage
     * (sémantique {@code visualToRaw}) pour que taper un hint place le
     * caret sur le caractère hinté.
     */
    int rawColFor(int line, int visualCol) {
        if (view.session == null) return visualCol;
        EditorDocument doc = view.session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return visualCol;
        int lineLen = doc.lineEnd(line) - doc.lineStart(line);
        LineRenderCache.LineCacheEntry e = layoutForLine(line, doc.lineText(line));
        if (e == null || e.inlays.isEmpty() || e.visualToRaw == null) return visualCol;
        if (collapsedFoldStartingAtLine(line) != null) return visualCol;
        if (visualCol <= 0) return 0;
        if (visualCol >= e.visualToRaw.length) return lineLen;
        return e.visualToRaw[visualCol];
    }
}
