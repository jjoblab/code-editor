package jo.codeeditor.lsp;

import jo.codeeditor.view.EditorView;

import org.eclipse.lsp4j.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Tir client du surlignage sémantique pour un document ouvert : programme
 * les requêtes {@code textDocument/semanticTokens/full}, les exécute sur un
 * exécuteur dédié puis décode les deltas et alimente la session de l'éditeur.
 * Extrait de {@link LspEditor} pour isoler cette responsabilité (throttle,
 * génération anti-obsolescence, décodage) du reste du pont éditeur↔serveur.
 */
class LspSemanticTokensPuller {

    private final LspEditor editor;

    /** Exécuteur dédié aux tirs textDocument/semanticTokens/full (daemon). */
    private static final java.util.concurrent.ExecutorService SEMANTIC_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cel-lsp-semantic");
                t.setDaemon(true);
                return t;
            });

    /** Génération anti-obsolescence : toute nouvelle demande invalide la précédente. */
    private final java.util.concurrent.atomic.AtomicInteger semanticGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Intervalle minimal entre deux tirs complets. */
    private static final long SEMANTIC_MIN_INTERVAL_MS = 700;

    private volatile long lastSemanticPullMs = 0;

    LspSemanticTokensPuller(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * Programme un tir {@code textDocument/semanticTokens/full} throttlé.
     * No-op total si le serveur connecté n'annonce PAS de provider
     * (capabilities nulles pendant l'init, serveurs tiers sans support).
     */
    void scheduleSemanticTokensPull() {
        if (!editor.isConnected()) return;
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null || wrapper.getServer() == null) return;
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) return;
        try {
            org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
            if (caps == null || caps.getSemanticTokensProvider() == null) return;
        } catch (Throwable t) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastSemanticPullMs;
        if (elapsed < SEMANTIC_MIN_INTERVAL_MS) {
            // Un seul re-tir en fin de fenêtre — jamais de rafale sous la frappe.
            final int genAtSchedule = semanticGeneration.get();
            final long delay = SEMANTIC_MIN_INTERVAL_MS - elapsed;
            editorView.postDelayed(() -> {
                if (editor.isConnected() && semanticGeneration.get() == genAtSchedule) {
                    doPullSemanticTokens();
                }
            }, delay);
            return;
        }
        doPullSemanticTokens();
    }

    /** Tir async — ne bloque AUCUN thread UI ; réponse la plus récente gagne. */
    private void doPullSemanticTokens() {
        lastSemanticPullMs = System.currentTimeMillis();
        final int gen = semanticGeneration.incrementAndGet();
        SEMANTIC_EXECUTOR.execute(() -> {
            try {
                if (!editor.isConnected()) return;
                LanguageServerWrapper wrapper = editor.currentWrapper();
                if (wrapper == null || wrapper.getServer() == null) return;
                org.eclipse.lsp4j.SemanticTokensParams params =
                        new org.eclipse.lsp4j.SemanticTokensParams(
                                editor.getTextDocumentIdentifier());
                var future = wrapper.getServer().getTextDocumentService()
                        .semanticTokensFull(params);
                org.eclipse.lsp4j.SemanticTokens result;
                try {
                    result = future.get(12, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    return;
                } catch (java.util.concurrent.ExecutionException ee) {
                    return;
                }
                if (gen != semanticGeneration.get()) return; // obsolète
                if (result == null || result.getData() == null
                        || result.getData().isEmpty()) return;
                final List<Integer> flat = result.getData();
                // Conversion offset → appliquée sur le THREAD UI (session).
                EditorView editorView = editor.getEditorView();
                if (editorView != null) {
                    editorView.post(() -> applySemanticTokens(gen, flat));
                }
            } catch (Throwable t) {
                android.util.Log.w("LspEditor", "semanticTokens failed: " + t);
            }
        });
    }

    /**
     * Décode les deltas LSP (line/char cumulés) vers des offsets éditeur sur
     * le document VIVANT et alimente {@code EditorSession.setSemanticTokens}
     * — fait le pont avec le rendu drawCachedSemSpans existant.
     * Appelé EXCLUSIVEMENT sur le thread UI.
     */
    private void applySemanticTokens(int gen, List<Integer> flat) {
        if (!editor.isConnected()) return;
        EditorView editorView = editor.getEditorView();
        if (editorView == null || editorView.getSession() == null) return;
        if (gen != semanticGeneration.get()) return;
        List<jo.codeeditor.shift.DiagnosticShift.SemanticToken> tokens =
                new ArrayList<>(Math.max(8, flat.size() / 5));
        try {
            int line = 0, col = 0;
            for (int i = 0; i + 4 < flat.size(); i += 5) {
                int deltaLine = flat.get(i);
                int deltaChar = flat.get(i + 1);
                int length = Math.max(1, flat.get(i + 2));
                int type = flat.get(i + 3);
                line += deltaLine;
                col = deltaLine == 0 ? col + deltaChar : deltaChar;
                if (line < 0 || col < 0) continue;
                int start = editor.positionToOffset(new Position(line, col));
                tokens.add(new jo.codeeditor.shift.DiagnosticShift.SemanticToken(
                        start, length, type));
            }
        } catch (Throwable ignored) {
            // Positions hors bornes (buffer divergé) — token partiel gardé.
        }
        try {
            editorView.getSession().setSemanticTokens(tokens);
            editorView.invalidate();
            android.util.Log.i("LspEditor", "semanticTokens applied: " + tokens.size());
        } catch (Throwable t) {
            android.util.Log.w("LspEditor", "setSemanticTokens failed: " + t);
        }
    }
}
