package jo.codeeditor.lsp;

import jo.codeeditor.lang.host.EditorHost;

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
        EditorHost host = editor.getHost();
        if (host == null || host.session() == null) return;
        try {
            org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
            if (caps == null || caps.getSemanticTokensProvider() == null) return;
        } catch (Exception t) {
            // Le serveur a refusé/échoué la requête (capability mensongère,
            // timeout) — repli : pas de tokens cette fois.
            jo.codeeditor.util.EditorLog.warn("LspEditor", "semanticTokens request failed: " + t);
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastSemanticPullMs;
        if (elapsed < SEMANTIC_MIN_INTERVAL_MS) {
            // Un seul re-tir en fin de fenêtre — jamais de rafale sous la frappe.
            final int genAtSchedule = semanticGeneration.get();
            final long delay = SEMANTIC_MIN_INTERVAL_MS - elapsed;
            host.postDelayed(() -> {
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
        editor.submitBackground(() -> {
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
                EditorHost host = editor.getHost();
                if (host != null) {
                    host.post(() -> applySemanticTokens(gen, flat));
                }
            } catch (Exception t) {
                jo.codeeditor.util.EditorLog.warn("LspEditor", "semanticTokens failed: " + t);
            }
        });
    }

    /**
     * Décode les deltas LSP (line/char cumulés) vers des offsets éditeur sur
     * le document VIVANT et alimente {@code EditorSession.setSemanticTokens}
     * — fait le pont avec le rendu drawCachedSemSpans existant.
     * Appelé EXCLUSIVEMENT sur le thread UI.
     *
     * <p><b>Modificateurs (4.4.0)</b> : le 5ᵉ entier du quintuplet
     * (bitmask serveur) est résolu PAR NOM via la légende annoncée à
     * l'initialisation ({@code tokenModifiers}) vers le masque canonique
     * de {@code DiagnosticShift.SemanticToken} — l'ordre de la légende
     * étant propre à chaque serveur, un bit ne signifie RIEN sans elle.
     * Sans légende (provider muet, serveur silencieux) : masque 0,
     * aucun modificateur interprété.</p>
     */
    private void applySemanticTokens(int gen, List<Integer> flat) {
        if (!editor.isConnected()) return;
        EditorHost host = editor.getHost();
        if (host == null || host.session() == null) return;
        if (gen != semanticGeneration.get()) return;
        List<jo.codeeditor.shift.DiagnosticShift.SemanticToken> tokens =
                new ArrayList<>(Math.max(8, flat.size() / 5));
        int[] canonicalByBit = semanticModifierLegend(editor.currentWrapper());
        try {
            int line = 0, col = 0;
            for (int i = 0; i + 4 < flat.size(); i += 5) {
                int deltaLine = flat.get(i);
                int deltaChar = flat.get(i + 1);
                int length = Math.max(1, flat.get(i + 2));
                int type = flat.get(i + 3);
                int rawModifiers = flat.get(i + 4);
                line += deltaLine;
                col = deltaLine == 0 ? col + deltaChar : deltaChar;
                if (line < 0 || col < 0) continue;
                int start = editor.positionToOffset(new Position(line, col));
                tokens.add(new jo.codeeditor.shift.DiagnosticShift.SemanticToken(
                        start, length, type, canonicalMask(canonicalByBit, rawModifiers)));
            }
        } catch (Exception ignored) {
            // Positions hors bornes (buffer divergé) — token partiel gardé.
            // Frontière tolérante : l'écart est attendu, pas journalisé.
        }
        try {
            host.session().setSemanticTokens(tokens);
            host.invalidate();
            jo.codeeditor.util.EditorLog.info("LspEditor", "semanticTokens applied: " + tokens.size());
        } catch (Exception t) {
            jo.codeeditor.util.EditorLog.warn("LspEditor", "setSemanticTokens failed: " + t);
        }
    }

    /**
     * Légende des modificateurs du serveur courant, résolue une fois par
     * tir : {@code canonicalByBit[b]} = masque canonique du bit b de la
     * légende (0 si le nom n'est pas interprété). Table vide (longueur 0)
     * quand aucune légende n'est disponible — tout bitmask devient 0.
     *
     * <p>Visible du package pour les tests directs (résolution par nom).</p>
     */
    static int[] semanticModifierLegend(LanguageServerWrapper wrapper) {
        try {
            if (wrapper == null) return new int[0];
            org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
            if (caps == null || caps.getSemanticTokensProvider() == null) return new int[0];
            org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions options =
                    caps.getSemanticTokensProvider();
            if (options == null || options.getLegend() == null) return new int[0];
            List<String> names = options.getLegend().getTokenModifiers();
            if (names == null || names.isEmpty()) return new int[0];
            int[] out = new int[names.size()];
            for (int b = 0; b < names.size(); b++) {
                out[b] = canonicalBitForName(names.get(b));
            }
            return out;
        } catch (Exception t) {
            return new int[0];
        }
    }

    /** Nom de modificateur de légende (insensible à la casse) → bit canonique. */
    static int canonicalBitForName(String name) {
        if (name == null) return 0;
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "deprecated":
                return jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_DEPRECATED;
            case "static":
                return jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_STATIC;
            case "abstract":
                return jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_ABSTRACT;
            default:
                return 0;   // modificateur transporté mais non interprété
        }
    }

    /** Bitmask serveur → masque canonique via la table de légende. */
    static int canonicalMask(int[] canonicalByBit, int rawBits) {
        if (rawBits == 0 || canonicalByBit.length == 0) return 0;
        int mask = 0;
        for (int b = 0; b < canonicalByBit.length && (rawBits >> b) != 0; b++) {
            if ((rawBits & (1 << b)) != 0) mask |= canonicalByBit[b];
        }
        return mask;
    }
}
