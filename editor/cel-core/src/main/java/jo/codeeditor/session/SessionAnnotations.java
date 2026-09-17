package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * État des annotations externes de la session : diagnostics, jetons
 * sémantiques et inlay hints. Existe pour isoler de {@link EditorSession}
 * la détention de ces trois listes, de leurs révisions globales et des
 * index par ligne mémoïsés utilisés par le chemin de rendu.
 * <p>
 * Les listes sont volatiles et remplacées (jamais mutées) à chaque setter
 * et à chaque édition : l'identité de référence sert donc de clé
 * d'invalidation fiable pour les index mémoïsés, et un lecteur itérant une
 * ancienne référence voit toujours un instantané cohérent.
 */
final class SessionAnnotations {

    // Volatile pour que les lectures inter-threads (thread lecteur LSP →
    // thread UI) voient toujours la dernière référence de champ après un
    // appel setXxx(). Les listes elles-mêmes sont remplacées (jamais mutées)
    // à chaque setter : un lecteur itérant une ancienne référence voit donc
    // toujours un instantané cohérent.
    private volatile List<DiagnosticShift.Diagnostic> diagnostics = new ArrayList<>();
    private volatile List<DiagnosticShift.SemanticToken> semanticTokens = new ArrayList<>();
    private volatile List<DiagnosticShift.InlayHint> inlayHints = new ArrayList<>();

    // Incrémenté quand setInlayHints / setSemanticTokens est appelé (la liste
    // globale est remplacée). Sur une édition de texte, les positions
    // inlay/sémantiques sont DÉCALÉES en place (DiagnosticShift.shift*) —
    // cela n'incrémente pas ces révisions car les entrées du cache sont
    // décalées en parallèle via onLinesShifted.
    private int inlayHintsRev = 0;
    private int semTokensRev = 0;

    // ── Indexes par ligne (mémoïsation sur l'identité de liste) ──

    /**
     * Index de compartiments mémoïsé : ligne d'un hint → hints commençant sur
     * cette ligne. Reconstruit paresseusement quand la référence de la liste
     * source change (la liste est remplacée — jamais mutée — par chaque
     * setter/chemin d'édition, donc l'identité est une clé d'invalidation
     * fiable). Re-pousser la même liste est gratuit ; une édition coûte UNE
     * reconstruction O(H) au lieu d'un filtrage de liste complète par ligne
     * en cache-miss dans le chemin de rendu.
     */
    private volatile Map<Integer, List<DiagnosticShift.InlayHint>> inlayHintsByLine;
    private volatile List<DiagnosticShift.InlayHint> inlayHintsIndexedFor = Collections.emptyList();

    /** Même mémoïsation pour les jetons sémantiques (un token peut couvrir plusieurs lignes). */
    private volatile Map<Integer, List<DiagnosticShift.SemanticToken>> semTokensByLine;
    private volatile List<DiagnosticShift.SemanticToken> semTokensIndexedFor = Collections.emptyList();

    /** Même mémoïsation pour les diagnostics groupés par ligne de début. */
    private volatile Map<Integer, List<DiagnosticShift.Diagnostic>> diagnosticsByLine;
    private volatile List<DiagnosticShift.Diagnostic> diagnosticsIndexedFor = Collections.emptyList();

    // ── Listes brutes ──────────────────────────────────────────

    /** La liste de diagnostics courante (jamais mutée après affectation). */
    List<DiagnosticShift.Diagnostic> diagnostics() { return diagnostics; }

    void setDiagnostics(List<DiagnosticShift.Diagnostic> diagnostics) {
        this.diagnostics = new ArrayList<>(diagnostics);
    }

    List<DiagnosticShift.SemanticToken> semanticTokens() { return semanticTokens; }

    void setSemanticTokens(List<DiagnosticShift.SemanticToken> tokens) {
        this.semanticTokens = new ArrayList<>(tokens);
        // Incrémenter la révision globale des jetons sémantiques pour que le
        // cache de rendu de la vue soit en cache-miss sur chaque ligne qui
        // avait une liste de spans sémantiques en cache.
        semTokensRev++;
    }

    List<DiagnosticShift.InlayHint> inlayHints() { return inlayHints; }

    void setInlayHints(List<DiagnosticShift.InlayHint> hints) {
        this.inlayHints = new ArrayList<>(hints);
        // Incrémenter la révision globale des inlays pour que le cache de rendu
        // de la vue soit en cache-miss sur chaque ligne qui avait une liste
        // d'inlays en cache et la reconstruise depuis la nouvelle liste
        // globale.
        inlayHintsRev++;
    }

    // ── Révisions globales ─────────────────────────────────────

    int inlayHintsRevision() { return inlayHintsRev; }

    int semanticTokensRevision() { return semTokensRev; }

    // ── Décalage sur édition ──────────────────────────────────

    /**
     * Décale les trois listes d'annotations pour une édition de texte.
     * Comme le décalage en place des positions ne change pas les clés du
     * cache de rendu (les entrées sont décalées en parallèle via
     * onLinesShifted), les révisions globales ne sont PAS incrémentées ici.
     */
    void shift(EditSpan span) {
        diagnostics = DiagnosticShift.shiftDiagnostics(diagnostics, span);
        semanticTokens = DiagnosticShift.shiftSemanticTokens(semanticTokens, span);
        inlayHints = DiagnosticShift.shiftInlayHints(inlayHints, span);
    }

    // ── Index par ligne ───────────────────────────────────────

    /**
     * Tous les diagnostics dont l'offset de DÉBUT se situe sur la ligne du
     * document donnée. Le compartiment est trié par sévérité décroissante
     * (puis par offset de début), donc le premier élément est le diagnostic
     * « principal » de la ligne — celui affiché par la puce.
     */
    List<DiagnosticShift.Diagnostic> diagnosticsForLine(int line, EditorDocument doc) {
        List<DiagnosticShift.Diagnostic> source = diagnostics;
        Map<Integer, List<DiagnosticShift.Diagnostic>> idx = diagnosticsByLine;
        if (idx == null || diagnosticsIndexedFor != source) {
            idx = buildDiagnosticBuckets(source, doc);
            diagnosticsByLine = idx;
            diagnosticsIndexedFor = source;
        }
        List<DiagnosticShift.Diagnostic> bucket = idx.get(line);
        return bucket != null ? bucket : Collections.emptyList();
    }

    private Map<Integer, List<DiagnosticShift.Diagnostic>> buildDiagnosticBuckets(
            List<DiagnosticShift.Diagnostic> source, EditorDocument doc) {
        if (source.isEmpty()) return Collections.emptyMap();
        Map<Integer, List<DiagnosticShift.Diagnostic>> m = new HashMap<>(source.size() * 2);
        for (DiagnosticShift.Diagnostic d : source) {
            int start = Math.max(0, Math.min(d.start, doc.length()));
            int line = doc.lineForOffset(start);
            m.computeIfAbsent(line, k -> new ArrayList<>(2)).add(d);
        }
        for (List<DiagnosticShift.Diagnostic> bucket : m.values()) {
            bucket.sort((a, b) -> a.severity != b.severity
                    ? b.severity - a.severity : a.start - b.start);
        }
        return m;
    }

    /**
     * Les inlay hints tombant sur la ligne du document donnée (offset du
     * hint dans [lineStart, lineEnd]). O(compartiment) au lieu d'un
     * filtrage O(total hints) sur la liste complète par ligne.
     */
    List<DiagnosticShift.InlayHint> inlayHintsForLine(int line, EditorDocument doc) {
        List<DiagnosticShift.InlayHint> source = inlayHints;
        Map<Integer, List<DiagnosticShift.InlayHint>> idx = inlayHintsByLine;
        if (idx == null || inlayHintsIndexedFor != source) {
            idx = buildInlayBuckets(source, doc);
            inlayHintsByLine = idx;
            inlayHintsIndexedFor = source;
        }
        List<DiagnosticShift.InlayHint> bucket = idx.get(line);
        return bucket != null ? bucket : Collections.emptyList();
    }

    /**
     * Les jetons sémantiques intersectant la ligne du document donnée (un
     * token couvrant les lignes 5..9 apparaît dans chaque compartiment de
     * 5 à 9). O(compartiment) au lieu de O(total tokens) par ligne.
     */
    List<DiagnosticShift.SemanticToken> semanticTokensForLine(int line, EditorDocument doc) {
        List<DiagnosticShift.SemanticToken> source = semanticTokens;
        Map<Integer, List<DiagnosticShift.SemanticToken>> idx = semTokensByLine;
        if (idx == null || semTokensIndexedFor != source) {
            idx = buildSemBuckets(source, doc);
            semTokensByLine = idx;
            semTokensIndexedFor = source;
        }
        List<DiagnosticShift.SemanticToken> bucket = idx.get(line);
        return bucket != null ? bucket : Collections.emptyList();
    }

    private Map<Integer, List<DiagnosticShift.InlayHint>> buildInlayBuckets(
            List<DiagnosticShift.InlayHint> source, EditorDocument doc) {
        if (source.isEmpty()) return Collections.emptyMap();
        Map<Integer, List<DiagnosticShift.InlayHint>> m = new HashMap<>(source.size() * 2);
        for (DiagnosticShift.InlayHint h : source) {
            int line = doc.lineForOffset(Math.max(0, Math.min(h.offset, doc.length())));
            m.computeIfAbsent(line, k -> new ArrayList<>(2)).add(h);
        }
        return m;
    }

    private Map<Integer, List<DiagnosticShift.SemanticToken>> buildSemBuckets(
            List<DiagnosticShift.SemanticToken> source, EditorDocument doc) {
        if (source.isEmpty()) return Collections.emptyMap();
        Map<Integer, List<DiagnosticShift.SemanticToken>> m = new HashMap<>(source.size() * 2);
        for (DiagnosticShift.SemanticToken t : source) {
            int start = Math.max(0, Math.min(t.start, doc.length()));
            int end = Math.max(start, Math.min(t.start + t.length, doc.length()));
            int startLine = doc.lineForOffset(start);
            int endLine = doc.lineForOffset(Math.max(start, end - 1));
            for (int line = startLine; line <= endLine; line++) {
                m.computeIfAbsent(line, k -> new ArrayList<>(2)).add(t);
            }
        }
        return m;
    }
}
