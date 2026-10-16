package jo.codeeditor.demo.lsp;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import jo.codeeditor.lang.model.Diagnostic;
import jo.codeeditor.lsp.LspEditor;
import jo.codeeditor.lsp.LspLogSink;
import jo.codeeditor.lsp.LspProject;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.LspEditorViewHost;

/**
 * Chef d'orchestre LSP de la démo : possède le {@link LspProject}, le
 * mini serveur Java, et la bascule « moteur maison ↔ moteur LSP ».
 *
 * <p>C'est la SEULE classe de la démo qui connaît le module
 * {@code :cel-lsp} — le reste de l'activité ne voit que trois méthodes
 * ({@link #connect}, {@link #disconnect}, {@link #diagnostics}) et un
 * état. Une intégration réelle dans une application hôte suit
 * exactement ce motif : un projet, une définition de serveur, un hôte
 * par éditeur.</p>
 *
 * <p><b>Vocabulaire d'état :</b></p>
 * <ul>
 *   <li>{@code OFF} — le moteur maison alimente l'éditeur ;</li>
 *   <li>{@code CONNECTING} — poignée de main initialize en cours ;</li>
 *   <li>{@code CONNECTED} — le serveur pousse les diagnostics à
 *       chaque édition.</li>
 * </ul>
 */
public final class DemoLspController implements LspLogSink {

    /** État du moteur LSP vu par la barre d'état et la bascule. */
    public enum State { OFF, CONNECTING, CONNECTED }

    /** Récepteur des événements du contrôleur (thread UI garanti). */
    public interface Listener {
        /** L'état moteur a changé (bascule, connexion terminée, échec). */
        void onEngineStateChanged(State state);

        /** Une ligne de journal LSP est arrivée (panneau Journal). */
        void onLogLine(String line);

        /** Les diagnostics ont été republiés (panneau Diagnostics). */
        void onDiagnosticsChanged();
    }

    private static final String WORKSPACE_DIR = "workspace";
    private static final String JAVA_FILE = "Atelier.java";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private LspProject project;
    private LspEditor lspEditor;
    private State state = State.OFF;
    private java.util.concurrent.CompletableFuture<Void> lastConnect;

    /**
     * Attends la fin de la dernière connexion lancée (usage test) :
     * vrai si l'état final est CONNECTED dans le délai imparti.
     */
    public boolean awaitConnected(long timeoutMs) {
        java.util.concurrent.CompletableFuture<Void> future = lastConnect;
        if (future == null) return state == State.CONNECTED;
        try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        // L'état définitif passe par le thread UI (main.post) — sous
        // Robolectric (looper en pause) il peut rester à CONNECTING :
        // la connexion EFFECTIVE se juge sur l'éditeur LSP lui-même.
        return state == State.CONNECTED
                || (lspEditor != null && lspEditor.isConnected());
    }

    /** @param context pour localiser l'espace de travail (filesDir). */
    public DemoLspController(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Définit le récepteur d'événements (appellé au thread UI). */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** L'état courant du moteur. */
    public State getState() {
        return state;
    }

    /** Le moteur LSP est-il actif sur le document courant ? */
    public boolean isLspActive() {
        return state != State.OFF;
    }

    /**
     * Connecte l'éditeur courant au mini serveur Java.
     *
     * <p>Séquence (vitrine fidèle d'une intégration réelle) :</p>
     * <ol>
     *   <li> matérialise l'espace de travail sur disque
     *        ({@code filesDir/workspace/}) et y écrit le document ;</li>
     *   <li> crée le {@link LspProject} au premier usage, enregistre la
     *        définition du mini serveur, branche le journal ;</li>
     *   <li> crée le {@link LspEditor} pour l'URI, pose l'hôte
     *        (adaptateur {@code EditorView}) et lance
     *        {@code connect()} — la poignée de main, le didOpen et la
     *        pose du {@code LspLanguage} sur l'éditeur sont pris en
     *        charge par le cadre ;</li>
     *   <li> notifie l'état {@code CONNECTED} quand le futur se
     *        complète (thread UI).</li>
     * </ol>
     *
     * @param view l'EditorView affichant le document
     * @param session la session liée (texte source de vérité)
     * @return vrai si la connexion est lancée
     */
    public boolean connect(EditorView view, EditorSession session) {
        if (state != State.OFF) return false;
        try {
            File workspace = new File(context.getFilesDir(), WORKSPACE_DIR);
            if (!workspace.exists() && !workspace.mkdirs()) return false;
            File javaFile = new File(workspace, JAVA_FILE);
            // java.nio.file exige l'API 26 — minSdk 24 : I/O classique.
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(javaFile)) {
                out.write(session.getText().getBytes(StandardCharsets.UTF_8));
            }
            String uri = Uri.fromFile(javaFile).toString();

            if (project == null) {
                project = new LspProject(workspace.getAbsolutePath());
                project.setLogSink(this);
                project.addServerDefinition(new MiniJavaServerDefinition());
            }
            lspEditor = project.createEditor(uri);
            lspEditor.setHost(new LspEditorViewHost(view));
            setState(State.CONNECTING);
            lastConnect = lspEditor.connect();
            lastConnect.whenComplete((ok, err) -> main.post(() -> {
                if (err == null && lspEditor.isConnected()) {
                    setState(State.CONNECTED);
                } else {
                    setState(State.OFF);
                    if (listener != null) {
                        listener.onLogLine(err == null
                                ? " connexion interrompue"
                                : " échec : " + err.getMessage());
                    }
                }
            }));
            return true;
        } catch (IOException e) {
            setState(State.OFF);
            return false;
        }
    }

    /**
     * Déconnecte le moteur LSP et rend la main au moteur maison.
     *
     * <p>Envoie {@code didClose} (le serveur vide ses diagnostics pour
     * l'URI) puis purge les diagnostics LSP restés en session —
     * l'appelant rebranche ensuite son analyseur maison, dont la
     * première passe remplace la liste.</p>
     */
    public void disconnect(EditorSession session) {
        if (state == State.OFF || lspEditor == null) {
            setState(State.OFF);
            return;
        }
        try {
            lspEditor.disconnect();
        } catch (RuntimeException e) {
            // Extinction best-effort : un serveur déjà mort ne doit pas
            // empêcher la bascule retour au moteur maison.
        }
        if (session != null) {
            session.setDiagnostics(Collections.emptyList());
        }
        setState(State.OFF);
    }

    /**
     * Les diagnostics LSP courants (convertis vers le modèle éditeur) —
     * vide quand le moteur est arrêté.
     */
    public List<Diagnostic> diagnostics() {
        if (state == State.OFF || lspEditor == null) {
            return Collections.emptyList();
        }
        List<Diagnostic> cached = lspEditor.getCachedDiagnostics();
        return cached == null ? Collections.emptyList() : cached;
    }

    /**
     * Ferme le projet LSP (activity détruite) : arrête le serveur et
     * ses threads. Idempotent.
     */
    public void shutdown() {
        if (project != null) {
            try {
                project.shutdown();
            } catch (RuntimeException ignored) {
                // Extinction best-effort au cycle de vie.
            }
            project = null;
        }
        lspEditor = null;
        setState(State.OFF);
    }

    private void setState(State next) {
        if (state == next) return;
        state = next;
        if (listener != null) main.post(() -> listener.onEngineStateChanged(next));
    }

    /**
     * Récepteur du journal du projet : chaque ligne reçoit un horodatage
     * compact puis part vers le panneau Journal (thread UI).
     */
    @Override
    public void log(String message) {
        if (listener == null) return;
        String stamp = java.text.DateFormat.getTimeInstance(
                java.text.DateFormat.MEDIUM, java.util.Locale.FRANCE)
                .format(new java.util.Date());
        main.post(() -> listener.onLogLine("[" + stamp + "] " + message));
    }
}
