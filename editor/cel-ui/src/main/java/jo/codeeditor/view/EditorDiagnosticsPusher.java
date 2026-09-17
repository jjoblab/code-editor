package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;
import jo.codeeditor.view.chrome.GutterView;

/**
 * Poussée des diagnostics vers l'UI : exécution débouncée (600 ms) du
 * {@code DiagnosticsProvider} du langage, conversion en diagnostics de
 * session, reconstruction du tableau de sévérité par ligne pour la
 * gouttière, et notification de l'hôte
 * ({@link EditorView.OnDiagnosticsPublishedListener}).
 *
 * <p>Extrait d'EditorView : {@code notifyDiagnosticsChanged()} reste un
 * relais public (appelé depuis n'importe quel thread, typiquement le
 * lecteur JSON-RPC du LSP) ; l'attachement du provider se fait par
 * {@link #attach} depuis {@code setLanguage}.</p>
 */
final class EditorDiagnosticsPusher {

    private final EditorView view;

    private static final int DIAGNOSTICS_DEBOUNCE_MS = 600;

    private jo.codeeditor.lang.provider.DiagnosticsProvider providerSpi;
    private Runnable task;
    private EditorView.OnDiagnosticsPublishedListener listener;

    EditorDiagnosticsPusher(EditorView view) {
        this.view = view;
    }

    /**
     * Branche le provider SPI et (re-)branche la tâche de calcul
     * débouncée, puis lance un run initial (débouncé). Appelé par
     * {@code setLanguage} quand le langage expose un
     * {@code DiagnosticsProvider}.
     */
    void attach(jo.codeeditor.lang.provider.DiagnosticsProvider provider) {
        providerSpi = provider;
        task = () -> {
            if (view.session == null || providerSpi == null) return;
            java.util.List<jo.codeeditor.lang.model.Diagnostic> diags =
                providerSpi.computeDiagnostics(view.session.getText());
            java.util.List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> legacy =
                new java.util.ArrayList<>();
            for (jo.codeeditor.lang.model.Diagnostic d : diags) {
                legacy.add(new jo.codeeditor.shift.DiagnosticShift.Diagnostic(
                    d.start, d.end, d.severity, d.message));
            }
            view.session.setDiagnostics(legacy);
            // Pousse le tableau de sévérité par ligne vers le
            // GutterView pour qu'il puisse dessiner le point de
            // diagnostic rouge/jaune devant le numéro de ligne.
            pushToGutter();
            view.invalidate();
        };
        // Run initial (débouncé).
        schedule();
    }

    /**
     * Détache le provider : efface les diagnostics de la session et les
     * points de diagnostic de la gouttière.
     */
    void detach() {
        providerSpi = null;
        task = null;
        view.session.setDiagnostics(new java.util.ArrayList<>());
        // Efface aussi les points de diagnostic de la gouttière.
        pushToGutter();
    }

    /** Le {@code DiagnosticsProvider} branché, ou null. */
    jo.codeeditor.lang.provider.DiagnosticsProvider getProvider() {
        return providerSpi;
    }

    /**
     * Planifie un rafraîchissement débouncé des diagnostics.
     * Appelé depuis {@code setLanguage} (initial) et {@code onTextChanged}
     * (à chaque édition). Annule tout run en attente et re-poste après
     * {@link #DIAGNOSTICS_DEBOUNCE_MS} ms.
     */
    void schedule() {
        if (task == null) return;
        if (view.getHandler() != null) {
            view.getHandler().removeCallbacks(task);
            view.getHandler().postDelayed(task, DIAGNOSTICS_DEBOUNCE_MS);
        } else {
            // Vue non attachée — exécute immédiatement.
            task.run();
        }
    }

    /**
     * Corps thread-safe de {@link EditorView#notifyDiagnosticsChanged()} :
     * si l'appel n'est pas sur le thread UI, le travail réel est posté
     * vers le {@link android.os.Handler} de la vue.
     */
    void notifyChanged() {
        if (view.getHandler() != null
                && Thread.currentThread() != view.getHandler().getLooper().getThread()) {
            // Appel multi-thread : poste vers le Handler de la vue pour que
            // la reconstruction de gouttière + invalidate s'exécutent sur le
            // thread UI.
            view.getHandler().post(this::pushToGutterAndInvalidate);
        } else {
            pushToGutterAndInvalidate();
        }
    }

    void setListener(EditorView.OnDiagnosticsPublishedListener l) {
        this.listener = l;
    }

    private void pushToGutterAndInvalidate() {
        pushToGutter();
        // Notifie l'hôte (onglet Problèmes live du bottom sheet).
        if (listener != null && view.session != null) {
            try {
                listener.onDiagnosticsPublished(
                        view.session.getDiagnostics());
            } catch (Throwable ignored) {
            }
        }
        view.invalidate();
    }

    /**
     * Construit un tableau de sévérité par ligne à partir des diagnostics
     * de la session et le pousse vers le GutterView pour qu'il puisse
     * dessiner le point de diagnostic rouge/jaune devant le numéro de
     * ligne. Si plusieurs diagnostics tombent sur la même ligne, la
     * sévérité la plus haute gagne (error bat warning bat info).
     */
    private void pushToGutter() {
        if (view.session == null || view.gutterView == null) {
            return;
        }
        EditorDocument doc = view.session.getDocument();
        if (doc == null) {
            view.gutterView.setDiagnostics(new int[0]);
            return;
        }
        int lineCount = doc.lineCount();
        int[] severityPerLine = new int[lineCount];
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        for (DiagnosticShift.Diagnostic d : diags) {
            int line = doc.lineForOffset(d.start);
            if (line < 0 || line >= lineCount) continue;
            // Sévérité max gagne (3=error > 2=warning > 1=info > 0=aucune).
            if (d.severity > severityPerLine[line]) {
                severityPerLine[line] = d.severity;
            }
        }
        view.gutterView.setDiagnostics(severityPerLine);
    }
}
