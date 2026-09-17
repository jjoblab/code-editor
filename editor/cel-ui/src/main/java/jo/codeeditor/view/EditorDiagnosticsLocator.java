package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;

/**
 * Localisateur de diagnostics — requêtes sur les diagnostics de la session
 * consommées par le dessin (chips, ampoule) et le tap (feuille/popup).
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>groupe de chip par ligne</b> — les diagnostics Error/Warning
 *       dont le début se trouve sur la ligne, le plus sévère d'abord
 *       (les seaux de ligne de départ de la session sont triés par
 *       sévérité décroissante) ;</li>
 *   <li><b>hit-tests</b> — la chip (avec son groupe) sous un point écran,
 *       le diagnostic à un offset, celui qui COMMENCE sur une ligne ;</li>
 *   <li><b>garde ampoule</b> — une ligne porte-t-elle un diagnostic
 *       Error/Warning (réserve l'ampoule de code actions aux lignes de
 *       diagnostic, parité CodeAssist).</li>
 * </ul>
 *
 * <p>EditorView conserve les relais package-privés (peintres, tap resolver
 * et tests les appellent) ; le type
 * {@link EditorView.DiagnosticChipHit} reste imbriqué dans la vue.</p>
 */
class EditorDiagnosticsLocator {

    private final EditorView view;

    EditorDiagnosticsLocator(EditorView view) {
        this.view = view;
    }

    /**
     * Les diagnostics Error/Warning dont le début se trouve sur
     * {@code line}, le plus sévère d'abord — le groupe de la chip.
     * Utilise les seaux de ligne de départ mémoïsés de la session
     * (getDiagnosticsForLine), pour qu'un appel par frame soit
     * O(lignes visibles), pas O(diagnostics totaux).
     */
    List<DiagnosticShift.Diagnostic> chipDiagnosticsForLine(int line) {
        if (view.session == null) return new ArrayList<>(0);
        List<DiagnosticShift.Diagnostic> all = view.session.getDiagnosticsForLine(line);
        // Les seaux sont triés par sévérité décroissante, donc le
        // préfixe Error/Warning va jusqu'au premier Info/Hint.
        int n = 0;
        while (n < all.size() && all.get(n).severity >= 2) n++;
        return new ArrayList<>(all.subList(0, n));
    }

    /**
     * La chip de diagnostic (avec son groupe) sous le point écran (x, y),
     * ou null — pilote l'interaction tap chip → feuille groupée → popup
     * de détail.
     */
    EditorView.DiagnosticChipHit findDiagnosticChipHitAt(float x, float y) {
        if (!view.diagnosticChipsEnabled || view.session == null) return null;
        EditorDocument doc = view.session.getDocument();
        int line = view.docLineForScreenY(y);
        if (line < 0 || line >= doc.lineCount()) return null;
        if (view.isLineFoldedCached(line)) return null;
        List<DiagnosticShift.Diagnostic> group = chipDiagnosticsForLine(line);
        if (group.isEmpty()) return null;
        float[] m = view.diagnosticChipMetrics(group.get(0), line, group.size());
        if (m == null) return null;
        if (x >= m[0] && x <= m[0] + m[2] && y >= m[1] && y <= m[1] + m[3]) {
            return new EditorView.DiagnosticChipHit(line, group);
        }
        return null;
    }

    /**
     * Le diagnostic Error/Warning le plus sévère dont le début se trouve
     * sur {@code line} — celui qui a une chip (les Info/Hint restent en
     * souligné + gouttière uniquement). Exposé pour le hit-test de chip.
     */
    DiagnosticShift.Diagnostic chipDiagnosticForLine(int line) {
        if (view.session == null) return null;
        EditorDocument doc = view.session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return null;
        List<DiagnosticShift.Diagnostic> group = chipDiagnosticsForLine(line);
        return group.isEmpty() ? null : group.get(0);
    }

    /**
     * La chip de diagnostic sous le point écran (x, y), ou null — pilote
     * l'interaction tap chip → feuille.
     *
     * <p>Conservé pour compatibilité (tests + chemin rapide
     * mono-diagnostic) — le flux de tap utilise désormais
     * {@link #findDiagnosticChipHitAt} qui porte tout le groupe.</p>
     */
    DiagnosticShift.Diagnostic findDiagnosticChipAt(float x, float y) {
        EditorView.DiagnosticChipHit hit = findDiagnosticChipHitAt(x, y);
        return hit != null ? hit.primary() : null;
    }

    /**
     * Retourne true si la ligne donnée porte un diagnostic Error/Warning.
     * Utilisé par le dessin de l'ampoule + le hit-test pour réserver
     * l'ampoule aux lignes de diagnostic uniquement (comportement
     * aligné sur CodeAssist).
     */
    boolean lineHasDiagnostic(int line) {
        if (view.session == null) return false;
        for (DiagnosticShift.Diagnostic d : view.session.getDiagnostics()) {
            if (d.severity != 3 && d.severity != 2) continue; // seulement error/warning
            int ln = view.session.getDocument().lineForOffset(d.start);
            if (ln == line) return true;
        }
        return false;
    }

    /**
     * Trouve le diagnostic à l'offset donné, ou null. N'est plus utilisé
     * par handleTap — le soulignement ondulé n'est pas tapable (parité
     * CodeAssist : seule la chip et le point de gouttière ouvrent la
     * feuille). Conservé pour les tests et d'éventuelles intégrations
     * appui long / quick doc.
     */
    DiagnosticShift.Diagnostic findDiagnosticAt(int offset) {
        if (view.session == null) return null;
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        for (DiagnosticShift.Diagnostic d : diags) {
            if (offset >= d.start && offset <= d.end) {
                return d;
            }
        }
        return null;
    }

    /**
     * Trouve le premier diagnostic qui COMMENCE sur la ligne document
     * donnée, ou null. Utilisé par handleTap pour ouvrir le popup de
     * diagnostic quand l'utilisateur tape n'importe où sur une ligne qui
     * porte un diagnostic (pas seulement sur la plage du soulignement).
     * Miroir du garde {@link #lineHasDiagnostic(int)} mais retourne
     * l'objet Diagnostic au lieu d'un booléen.
     *
     * <p>Les erreurs (sévérité 3) sont préférées aux avertissements
     * (sévérité 2), eux-mêmes préférés aux infos (sévérité 1) — si une
     * ligne porte à la fois une erreur et un avertissement, le popup
     * montre l'erreur en premier.</p>
     */
    DiagnosticShift.Diagnostic findDiagnosticAtLine(int line) {
        if (view.session == null) return null;
        EditorDocument doc = view.session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return null;
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        DiagnosticShift.Diagnostic best = null;
        for (DiagnosticShift.Diagnostic d : diags) {
            // Le diagnostic commence sur cette ligne si son offset de
            // début est dans [lineStart, lineEnd].
            if (d.start < lineStart || d.start > lineEnd) continue;
            if (best == null || d.severity > best.severity) {
                best = d;
            }
        }
        return best;
    }
}
