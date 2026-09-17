package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * État des régions de pliage d'une session : liste courante, révision
 * monotone (invalidation des index dérivés de la vue) et application des
 * défauts serveur. Existe pour isoler de {@link EditorSession} la
 * responsabilité de pliage (détection, bascule, expansion, application
 * LSP) sans en changer le comportement.
 */
final class FoldRegions {

    /**
     * Volatile pour que les lectures inter-threads (thread lecteur LSP →
     * thread UI) voient toujours la dernière référence de champ après un
     * appel setXxx(). La liste est remplacée (jamais mutée) à chaque
     * setter : un lecteur itérant une ancienne référence voit donc
     * toujours un instantané cohérent.
     */
    private volatile List<DiagnosticShift.FoldRegion> foldRegions = new ArrayList<>();

    /**
     * Révision monotone du jeu de plis. TOUTE mutation de {@link #foldRegions}
     * (remplacement par {@link #set}, {@code set()} en place par
     * {@link #toggleAtLine} / {@link #expandAt}, réaffectation après
     * {@code DiagnosticShift.shiftFoldRegions} sur édition) incrémente ce
     * compteur. La vue mémoïse son index de sommes préfixes de plis sur
     * {@code (identité session, foldRev, identité doc)} — une simple comparaison
     * de référence de liste ne fonctionne PAS car le getter de session
     * encapsule une NOUVELLE vue non modifiable à chaque appel, et car
     * {@link #toggleAtLine} mute la liste sous-jacente en place.
     */
    private volatile int foldRev;

    /**
     * Les {@code collapsedByDefault} serveur (imports) ont-ils été appliqués
     * une première fois pour CE document ? (portage de
     * {@code defaultFoldsApplied} de CodeAssist — cf. applyCodeFolds.)
     */
    private boolean foldDefaultsApplied = false;

    /** La liste de régions courante (jamais mutée après affectation). */
    List<DiagnosticShift.FoldRegion> regions() { return foldRegions; }

    void set(List<DiagnosticShift.FoldRegion> regions) {
        this.foldRegions = new ArrayList<>(regions);
        // Invalider l'index de sommes préfixes de plis de la vue.
        foldRev++;
    }

    /** Révision du jeu de plis (incrémentée à chaque mutation). */
    int revision() {
        return foldRev;
    }

    /**
     * Nouveau document/langage : les défauts de pliage serveur (imports
     * repliés) peuvent à nouveau s'appliquer une première fois.
     */
    void resetDefaults() {
        foldDefaultsApplied = false;
    }

    /**
     * Décale les offsets des régions après une édition de texte et invalide
     * la révision (l'index de la vue est périmé).
     */
    void shift(EditSpan span) {
        foldRegions = DiagnosticShift.shiftFoldRegions(foldRegions, span);
        foldRev++;
    }

    /**
     * Détecte les régions de pliage pour le document + langage donnés.
     * Préserve l'état replié des plis existants.
     */
    void detect(EditorDocument doc, String language) {
        List<DiagnosticShift.FoldRegion> folds =
            jo.codeeditor.highlight.FoldDetector.detect(doc.getText(), language);
        // Préserver l'état replié des plis existants.
        // Itérer sur `folds` (la liste fraîche) et remplacer les entrées
        // correspondant aux anciens plis repliés — PAS sur foldRegions.
        for (int i = 0; i < folds.size(); i++) {
            DiagnosticShift.FoldRegion fresh = folds.get(i);
            for (DiagnosticShift.FoldRegion old : foldRegions) {
                if (old.start == fresh.start && old.end == fresh.end && old.collapsed) {
                    folds.set(i, new DiagnosticShift.FoldRegion(
                        fresh.start, fresh.end, fresh.placeholder,
                        fresh.kind, true));
                    break;
                }
            }
        }
        set(folds);
    }

    /**
     * Applique un jeu de plis AUTORITATIF venu du serveur LSP (portage
     * d'{@code applyCodeFolds} de CodeAssist / EditorSession.kt) :
     * <ul>
     *   <li>une région PRÉCÉDEMMENT repliée (même [start, end]) le reste ;</li>
     *   <li>{@code collapsedByDefault} s'applique UNE SEULE FOIS par
     *       document — un re-tir serveur (après une édition) ne re-plie pas
     *       une région que l'utilisateur a dépliée ;</li>
     *   <li>une région fraîchement apparue suit son défaut.</li>
     * </ul>
     */
    void applyCodeFolds(List<DiagnosticShift.FoldRegion> fresh) {
        List<DiagnosticShift.FoldRegion> next = new ArrayList<>(fresh.size());
        for (DiagnosticShift.FoldRegion r : fresh) {
            boolean keep = false;
            for (DiagnosticShift.FoldRegion old : foldRegions) {
                if (old.collapsed && old.start == r.start && old.end == r.end) {
                    keep = true;
                    break;
                }
            }
            if (!keep && r.collapsedByDefault && !foldDefaultsApplied) {
                keep = true;
            }
            next.add(keep && !r.collapsed
                    ? new DiagnosticShift.FoldRegion(r.start, r.end,
                            r.placeholder, r.kind, true, r.collapsedByDefault)
                    : r);
        }
        foldDefaultsApplied = true;
        set(next);
    }

    /**
     * Bascule l'état replié de toute région de pliage DÉBUTANT à la ligne du
     * document donnée. Retourne vrai si un pli a été basculé.
     * <p>
     * Les régions de pliage sont appariées par ligne — l'appelant
     * (typiquement un tap sur la gouttière) passe la ligne doc du chevron
     * qu'il veut basculer.
     */
    boolean toggleAtLine(int docLine, EditorDocument doc) {
        int lineStart = doc.lineStart(docLine);
        for (int i = 0; i < foldRegions.size(); i++) {
            DiagnosticShift.FoldRegion r = foldRegions.get(i);
            int rStartLine = doc.lineForOffset(r.start);
            if (rStartLine == docLine) {
                DiagnosticShift.FoldRegion toggled = new DiagnosticShift.FoldRegion(
                    r.start, r.end, r.placeholder, r.kind, !r.collapsed);
                foldRegions.set(i, toggled);
                foldRev++;
                return true;
            }
            // Accepter aussi une région dont l'offset de début égale le début de
            // la ligne, même si lineForOffset a arrondi différemment.
            if (r.start == lineStart) {
                DiagnosticShift.FoldRegion toggled = new DiagnosticShift.FoldRegion(
                    r.start, r.end, r.placeholder, r.kind, !r.collapsed);
                foldRegions.set(i, toggled);
                foldRev++;
                return true;
            }
        }
        return false;
    }

    /**
     * Retourne les régions de pliage actuellement repliées (liste fraîche).
     * Utilisée par la vue pour construire un {@link jo.codeeditor.fold.FoldModel}
     * pour le rendu et la conversion offset↔ligne visuelle.
     */
    List<DiagnosticShift.FoldRegion> collapsed() {
        List<DiagnosticShift.FoldRegion> out = new ArrayList<>();
        for (DiagnosticShift.FoldRegion r : foldRegions) {
            if (r.collapsed) out.add(r);
        }
        return out;
    }

    /**
     * Retourne vrai si la ligne du document donnée est actuellement masquée
     * par une région de pliage repliée. Méthode de commodité — la vue s'en
     * sert pour sauter le dessin des lignes masquées.
     */
    boolean isLineFolded(int docLine, EditorDocument doc) {
        for (DiagnosticShift.FoldRegion r : foldRegions) {
            if (!r.collapsed) continue;
            int startLine = doc.lineForOffset(r.start);
            int endLine = doc.lineForOffset(r.end);
            if (docLine > startLine && docLine <= endLine) return true;
        }
        return false;
    }

    /**
     * Si le caret est dans une région de pliage repliée, la déplie. Utilisée
     * par la navigation programmatique (go-to-def, rename, résultats de
     * recherche) pour que le caret n'atterrisse jamais dans du texte masqué.
     */
    void expandAt(int offset) {
        for (int i = 0; i < foldRegions.size(); i++) {
            DiagnosticShift.FoldRegion r = foldRegions.get(i);
            if (r.collapsed && r.start < offset && offset < r.end) {
                foldRegions.set(i, new DiagnosticShift.FoldRegion(
                    r.start, r.end, r.placeholder, r.kind, false));
                foldRev++;
                return;
            }
        }
    }
}
