package jo.codeeditor.lsp;

import jo.codeeditor.lang.host.EditorHost;
import jo.codeeditor.lang.model.Diagnostic;

import org.eclipse.lsp4j.PublishDiagnosticsParams;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Réception et diffusion des diagnostics serveur pour un document ouvert :
 * convertit les diagnostics LSP en offsets éditeur, les met en cache puis
 * pousse la liste à la session de l'hôte d'édition sur le thread UI. Extrait de
 * {@link LspEditor} pour isoler cette responsabilité du reste du pont
 * éditeur↔serveur.
 */
class LspDiagnosticsManager {

    private final LspEditor editor;

    private volatile List<Diagnostic> cachedDiagnostics = new CopyOnWriteArrayList<>();

    /**
     * Diagnostics LSP BRUTS (tels que poussés par le serveur, identité
     * complète : plage, sévérité, code, données structurées, source,
     * tags) — conservés tels quels pour reconstituer fidèlement le
     * contexte d'une future requête textDocument/codeAction : le serveur
     * dérive ses quick-fixes de cette identité, la reconstruire à la
     * main depuis le modèle offset l'amputerait.
     */
    private volatile List<org.eclipse.lsp4j.Diagnostic> cachedLspDiagnostics =
            new CopyOnWriteArrayList<>();

    LspDiagnosticsManager(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * Reçoit les diagnostics du serveur (appelée via
     * {@link LspEditor#publishDiagnostics}). Met à jour la liste en cache et
     * invalide la vue.
     */
    void publishDiagnostics(PublishDiagnosticsParams params) {
        // ★ 3.74.0 : les publications tardives d'une session fermée
        // (serveur qui n'a pas encore traité le didClose) décrivent le
        // fichier FERMÉ — elles ne doivent ni marquer le document du
        // nouvel onglet ni nourrir un cache obsolète. La fenêtre
        // didOpen→connected reste ouverte (closed passe à false dès
        // connect(), avant la poignée de main).
        if (editor.isClosed()) {
            jo.codeeditor.util.EditorLog.info("LspEditor",
                "publishDiagnostics ignoré (session fermée)");
            return;
        }
        List<Diagnostic> diags = new ArrayList<>();
        if (params.getDiagnostics() != null) {
            for (org.eclipse.lsp4j.Diagnostic lspDiag : params.getDiagnostics()) {
                int start = editor.positionToOffset(lspDiag.getRange().getStart());
                int end = editor.positionToOffset(lspDiag.getRange().getEnd());
                // Sévérité LSP : 1=Erreur, 2=Avertissement, 3=Info, 4=Hint.
                // Absente → Information (3), pas Warning : la spec 3.17 laisse
                // le choix au client ; un diagnostic sans sévérité n'a pas
                // prouvé son urgence — le traiter en avertissement peint des
                // jaunes injustifiés (Android Studio affiche « information »
                // quand l'inspection ne précise pas).
                int severity = lspDiag.getSeverity() != null
                    ? lspDiag.getSeverity().ordinal() + 1 : 3;
                // Mappe la sévérité LSP (1=Erreur, 2=Avertissement, 3=Info, 4=Hint) vers la nôtre (3=erreur, 2=avertissement, 1=info)
                int ourSeverity = severity == 1 ? 3 : severity == 2 ? 2 : 1;
                // Tag Unnecessary (code inutilisé) — peinture estompée de la
                // plage au lieu du soulignement (grisé Android Studio).
                boolean unnecessary = lspDiag.getTags() != null
                        && lspDiag.getTags().contains(
                                org.eclipse.lsp4j.DiagnosticTag.Unnecessary);
                // D4 (lot 2, J5) : code ET source transportés jusqu'au
                // client (en-tête enrichi des popups de diagnostic).
                diags.add(new Diagnostic(start, end, ourSeverity,
                    lspDiag.getMessage(), lspDiag.getCode() != null ? lspDiag.getCode().getLeft() : "",
                    lspDiag.getSource() != null ? lspDiag.getSource() : "",
                    unnecessary));
            }
        }
        cachedDiagnostics = new CopyOnWriteArrayList<>(diags);
        cachedLspDiagnostics = params.getDiagnostics() != null
                ? new CopyOnWriteArrayList<>(params.getDiagnostics())
                : new CopyOnWriteArrayList<>();
        jo.codeeditor.util.EditorLog.info("LspEditor", "publishDiagnostics: " + diags.size() + " diagnostics received");
        final LspLogSink sink = editor.getProject().getLogSink();
        if (sink != null) {
            sink.log("publishDiagnostics: " + diags.size() + " diagnostics received");
            for (Diagnostic d : diags) {
                sink.log("  - [" + d.severity + "] " + d.message);
            }
        }
        EditorHost host = editor.getHost();
        if (host != null) {
            final List<Diagnostic> diagsFinal = diags;
            Runnable push = () -> {
                // ★ 3.74.0 : re-contrôle à l'exécution — la déconnexion a
                // pu survenir entre la réception et le passage effectif
                // sur le thread UI.
                if (editor.isClosed()) return;
                EditorHost current = editor.getHost();
                if (current != null && current.session() != null) {
                    current.session().setDiagnostics(
                        convertToLegacyDiagnostics(diagsFinal));
                    // P2-11c — voie UNIQUE de sortie : la publication LSP
                    // emprunte le même chemin que la voie locale
                    // (notifyDiagnosticsChanged) — reconstruction du tableau
                    // de sévérité par ligne pour la gouttière (les points
                    // restaient périmés après une publication LSP),
                    // notification de l'hôte (OnDiagnosticsPublishedListener,
                    // resté muet côté LSP — le futur onglet Problèmes de
                    // l'hôte en dépend), invalidation comprise.
                    current.notifyDiagnosticsChanged();
                }
            };
            // Pattern applyServerEdit : hôte ATTACHÉ → thread UI
            // (la mutation de session est sérialisée avec le rendu) ;
            // hôte NON attaché → exécution EN LIGNE — un post resterait
            // en file d'actions et ne serait jamais exécuté avant
            // l'attachement : une publication serveur ne doit pas rester
            // pendante.
            if (host.isAttachedToWindow()) host.post(push); else push.run();
            // ★ R3 : chaque publication de diagnostics reste UN signal
            // de rafraîchissement des couleurs sémantiques — cadencé par
            // le debounce didChange, capacité vérifiée (les serveurs sans
            // provider sont ignorés). ★ 4.9.0 (écart n°2) : il n'est
            // plus le SEUL — la connexion (LspEditor.connect) et chaque
            // didChange envoyé (LspDocumentSynchronizer) tirent aussi,
            // le throttle du puller déduplique.
            editor.scheduleSemanticTokensPull();
            // Même signal pour le FOLDING — tir
            // textDocument/foldingRange throttlé, appliqué via
            // session.applyCodeFolds (état utilisateur préservé).
            editor.scheduleFoldPull();
        }
    }

    /**
     * ★ 3.74.0 — vide les caches (déconnexion) : les diagnostics du
     * fichier fermé ne doivent plus être exposés par
     * {@link LspDiagnosticsProvider} après la déconnexion.
     */
    void clear() {
        cachedDiagnostics = new CopyOnWriteArrayList<>();
        cachedLspDiagnostics = new CopyOnWriteArrayList<>();
    }

    /** Convertit notre liste de Diagnostic vers l'ancien format DiagnosticShift.Diagnostic. */
    private List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> convertToLegacyDiagnostics(List<Diagnostic> diags) {
        List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diags) {
            // D4 : code et source voyagent avec le décalage incrémental.
            out.add(new jo.codeeditor.shift.DiagnosticShift.Diagnostic(
                d.start, d.end, d.severity, d.message, d.unnecessary, d.code, d.source));
        }
        return out;
    }

    /** Retourne les diagnostics en cache venant du serveur. */
    List<Diagnostic> getCachedDiagnostics() {
        return new ArrayList<>(cachedDiagnostics);
    }

    /**
     * Les diagnostics LSP bruts en cache (identité complète) — copie
     * défensive : la liste de travail reste immuable pour ses lecteurs.
     */
    List<org.eclipse.lsp4j.Diagnostic> getCachedLspDiagnostics() {
        return new ArrayList<>(cachedLspDiagnostics);
    }
}
