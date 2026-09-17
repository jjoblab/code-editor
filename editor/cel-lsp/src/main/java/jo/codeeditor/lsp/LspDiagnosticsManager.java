package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.Diagnostic;
import jo.codeeditor.view.EditorView;

import org.eclipse.lsp4j.PublishDiagnosticsParams;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Réception et diffusion des diagnostics serveur pour un document ouvert :
 * convertit les diagnostics LSP en offsets éditeur, les met en cache puis
 * pousse la liste à la session de l'EditorView sur le thread UI. Extrait de
 * {@link LspEditor} pour isoler cette responsabilité du reste du pont
 * éditeur↔serveur.
 */
class LspDiagnosticsManager {

    private final LspEditor editor;

    private volatile List<Diagnostic> cachedDiagnostics = new CopyOnWriteArrayList<>();

    LspDiagnosticsManager(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * Reçoit les diagnostics du serveur (appelée via
     * {@link LspEditor#publishDiagnostics}). Met à jour la liste en cache et
     * invalide la vue.
     */
    void publishDiagnostics(PublishDiagnosticsParams params) {
        List<Diagnostic> diags = new ArrayList<>();
        if (params.getDiagnostics() != null) {
            for (org.eclipse.lsp4j.Diagnostic lspDiag : params.getDiagnostics()) {
                int start = editor.positionToOffset(lspDiag.getRange().getStart());
                int end = editor.positionToOffset(lspDiag.getRange().getEnd());
                int severity = lspDiag.getSeverity() != null
                    ? lspDiag.getSeverity().ordinal() + 1 : 2; // sévérité LSP : 1=Erreur, 2=Avertissement, 3=Info, 4=Hint
                // Mappe la sévérité LSP (1=Erreur, 2=Avertissement, 3=Info, 4=Hint) vers la nôtre (3=erreur, 2=avertissement, 1=info)
                int ourSeverity = severity == 1 ? 3 : severity == 2 ? 2 : 1;
                diags.add(new Diagnostic(start, end, ourSeverity,
                    lspDiag.getMessage(), lspDiag.getCode() != null ? lspDiag.getCode().getLeft() : ""));
            }
        }
        cachedDiagnostics = new CopyOnWriteArrayList<>(diags);
        android.util.Log.i("LspEditor", "publishDiagnostics: " + diags.size() + " diagnostics received");
        final LspLogSink sink = editor.getProject().getLogSink();
        if (sink != null) {
            sink.log("publishDiagnostics: " + diags.size() + " diagnostics received");
            for (Diagnostic d : diags) {
                sink.log("  - [" + d.severity + "] " + d.message);
            }
        }
        EditorView editorView = editor.getEditorView();
        if (editorView != null) {
            // Poste setDiagnostics sur le thread UI pour éviter la concurrence
            // avec le chemin de rendu. L'exécuteur LSP4J appelle
            // publishDiagnostics sur un thread de fond — les champs
            // d'EditorSession ne sont pas synchronisés pour la mutation.
            final List<Diagnostic> diagsFinal = diags;
            editorView.post(() -> {
                EditorView view = editor.getEditorView();
                if (view != null && view.getSession() != null) {
                    view.getSession().setDiagnostics(
                        convertToLegacyDiagnostics(diagsFinal));
                    view.invalidate();
                }
            });
            // ★ R3 : chaque publication de diagnostics est
            // le signal naturel pour rafraîchir les couleurs sémantiques —
            // un seul déclencheur, déjà cadencé par le debounce didChange,
            // capacité vérifiée (les serveurs sans provider sont ignorés).
            editor.scheduleSemanticTokensPull();
            // Même signal pour le FOLDING — tir
            // textDocument/foldingRange throttlé, appliqué via
            // session.applyCodeFolds (état utilisateur préservé).
            editor.scheduleFoldPull();
        }
    }

    /** Convertit notre liste de Diagnostic vers l'ancien format DiagnosticShift.Diagnostic. */
    private List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> convertToLegacyDiagnostics(List<Diagnostic> diags) {
        List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diags) {
            out.add(new jo.codeeditor.shift.DiagnosticShift.Diagnostic(
                d.start, d.end, d.severity, d.message));
        }
        return out;
    }

    /** Retourne les diagnostics en cache venant du serveur. */
    List<Diagnostic> getCachedDiagnostics() {
        return new ArrayList<>(cachedDiagnostics);
    }
}
