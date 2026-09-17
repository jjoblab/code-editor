package jo.codeeditor.lsp;

import jo.codeeditor.view.EditorView;

import java.util.ArrayList;
import java.util.List;

/**
 * Tir client des plis de code serveur pour un document ouvert : programme
 * les requêtes {@code textDocument/foldingRange}, les exécute sur un
 * exécuteur dédié puis convertit les plages en régions de pli appliquées à
 * la session de l'éditeur. Extrait de {@link LspEditor} pour isoler cette
 * responsabilité (throttle, génération anti-obsolescence, garde
 * anti-course avec le detectFolds lexical) du reste du pont éditeur↔serveur.
 */
class LspFoldPuller {

    private final LspEditor editor;

    /** Exécuteur dédié aux tirs textDocument/foldingRange (daemon). */
    private static final java.util.concurrent.ExecutorService FOLD_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cel-lsp-folds");
                t.setDaemon(true);
                return t;
            });

    /** Génération anti-obsolescence : toute nouvelle demande invalide la précédente. */
    private final java.util.concurrent.atomic.AtomicInteger foldGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Intervalle minimal entre deux tirs complets. */
    private static final long FOLD_MIN_INTERVAL_MS = 900;

    private volatile long lastFoldPullMs = 0;

    LspFoldPuller(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * Programme un tir {@code textDocument/foldingRange} throttlé. No-op si
     * le serveur n'annonce PAS le provider, ou tant que le language SPI
     * n'est pas posé (garde anti-course avec le detectFolds lexical de
     * setLanguage).
     */
    void scheduleFoldPull() {
        if (!editor.isConnected()) return;
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null || wrapper.getServer() == null) return;
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) return;
        if (!editor.isLanguageApplied()) return; // setLanguage pas encore appliqué
        try {
            org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
            if (caps == null || caps.getFoldingRangeProvider() == null) return;
        } catch (Throwable t) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastFoldPullMs;
        if (elapsed < FOLD_MIN_INTERVAL_MS) {
            final int genAtSchedule = foldGeneration.get();
            final long delay = FOLD_MIN_INTERVAL_MS - elapsed;
            editorView.postDelayed(() -> {
                if (editor.isConnected() && editor.isLanguageApplied()
                        && foldGeneration.get() == genAtSchedule) {
                    doPullFolds();
                }
            }, delay);
            return;
        }
        doPullFolds();
    }

    /** Tir async — ne bloque AUCUN thread UI ; réponse la plus récente gagne. */
    private void doPullFolds() {
        lastFoldPullMs = System.currentTimeMillis();
        final int gen = foldGeneration.incrementAndGet();
        FOLD_EXECUTOR.execute(() -> {
            try {
                if (!editor.isConnected()) return;
                LanguageServerWrapper wrapper = editor.currentWrapper();
                if (wrapper == null || wrapper.getServer() == null) return;
                org.eclipse.lsp4j.FoldingRangeRequestParams params =
                        new org.eclipse.lsp4j.FoldingRangeRequestParams(
                                editor.getTextDocumentIdentifier());
                var future = wrapper.getServer().getTextDocumentService()
                        .foldingRange(params);
                List<org.eclipse.lsp4j.FoldingRange> result;
                try {
                    result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException
                        | java.util.concurrent.ExecutionException te) {
                    return;
                }
                if (gen != foldGeneration.get()) return; // obsolète
                if (result == null) return;
                EditorView editorView = editor.getEditorView();
                if (editorView != null) {
                    editorView.post(() -> applyFolds(gen, result));
                }
            } catch (Throwable t) {
                android.util.Log.w("LspEditor", "foldingRange failed: " + t);
            }
        });
    }

    /**
     * Convertit les FoldingRange (ligne/caractère) en FoldRegion offsets sur
     * le document VIVANT et alimente {@code session.applyCodeFolds} —
     * l'état utilisateur (régions repliées) est préservé, et le groupe
     * d'imports ({@code kind="imports"}) se replie PAR DÉFAUT la première
     * fois. Appelé EXCLUSIVEMENT sur le thread UI.
     */
    private void applyFolds(int gen, List<org.eclipse.lsp4j.FoldingRange> ranges) {
        if (!editor.isConnected()) return;
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) return;
        if (gen != foldGeneration.get()) return;
        if (!editor.isLanguageApplied()) return;
        try {
            List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> regions =
                    new ArrayList<>(ranges.size());
            for (org.eclipse.lsp4j.FoldingRange r : ranges) {
                if (r == null) continue;
                org.eclipse.lsp4j.Position start = new org.eclipse.lsp4j.Position(
                        r.getStartLine(),
                        r.getStartCharacter() != null ? r.getStartCharacter()
                                : Integer.valueOf(0));
                org.eclipse.lsp4j.Position end = new org.eclipse.lsp4j.Position(
                        r.getEndLine(),
                        r.getEndCharacter() != null ? r.getEndCharacter()
                                : Integer.valueOf(0));
                int startOffset = editor.positionToOffset(start);
                int endOffset = editor.positionToOffset(end);
                if (endOffset <= startOffset) continue;
                String placeholder = r.getCollapsedText() != null
                        ? r.getCollapsedText() : "…";
                boolean collapsedByDefault = "imports".equals(r.getKind());
                regions.add(new jo.codeeditor.shift.DiagnosticShift.FoldRegion(
                        startOffset, endOffset, placeholder,
                        r.getKind() != null ? r.getKind() : "block",
                        false, collapsedByDefault));
            }
            editorView.getSession().applyCodeFolds(regions);
            editorView.invalidate();
            android.util.Log.i("LspEditor", "folds applied: " + regions.size());
        } catch (Throwable t) {
            android.util.Log.w("LspEditor", "applyFolds failed: " + t);
        }
    }
}
