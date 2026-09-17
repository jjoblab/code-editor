package jo.codeeditor.view;

import android.os.Handler;
import android.os.Looper;

import jo.codeeditor.lang.model.Diagnostic;
import jo.codeeditor.lang.provider.DiagnosticsProvider;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Balayage des diagnostics des ONGLETS OUVERTS (port de
 * l'{@code OpenTabDiagnosticsSweep} de CodeAssist).
 *
 * <p>L'éditeur rafraîchit déjà les diagnostics de l'onglet <b>actif</b> à
 * chaque changement de texte (avec anti-rebond). Mais un hôte multi-onglets
 * finit avec des points rouges périmés sur les onglets simplement quittés
 * par l'utilisateur — ce balayage comble ce manque. L'hôte appelle
 * {@link #start(List)} chaque fois qu'il veut rafraîchir tous les éditeurs
 * ouverts (changement d'onglet, enregistrement, focus fenêtre, veille…) :
 * le balayage parcourt la liste, ré-exécute le {@link DiagnosticsProvider}
 * de chaque onglet HORS du thread principal et applique le résultat sur le
 * thread principal — avec un intervalle de {@value #DEFAULT_GAP_MS} ms
 * entre onglets pour que le thread UI n'applique jamais plusieurs résultats
 * dans une même frame (intervalle de CodeAssist).</p>
 *
 * <p><b>Onglets ignorés</b> (parité CodeAssist) :</p>
 * <ul>
 *   <li>l'onglet qui a actuellement le <b>focus</b> — sa propre tâche avec
 *       anti-rebond en est responsable (et l'utilisateur y tape) ;</li>
 *   <li>les sessions <b>en lecture seule</b> — un fichier ouvert pour
 *       consultation n'est pas ré-analysé ;</li>
 *   <li>les documents <b>volumineux</b> ({@code EditorDocument.isLarge()})
 *       — le même filtrage que celui appliqué par l'éditeur à l'analyse
 *       sémantique ;</li>
 *   <li>les onglets sans provider de diagnostics branché ;</li>
 *   <li>les vues détachées (sans parent — l'hôte est en train de démonter
 *       l'onglet).</li>
 * </ul>
 *
 * <p>Le balayage n'applique un résultat que si la session de l'onglet est
 * toujours celle qui a été analysée (un changement d'onglet en plein vol
 * écarte le résultat périmé), et {@link #cancel()} arrête le parcours. Une
 * seule instance de balayage s'exécute à la fois ; relancer un balayage sur
 * les mêmes onglets est bon marché et sûr (les instances plus anciennes
 * meurent d'elles-mêmes ou sont annulées par l'hôte).</p>
 *
 * <pre>{@code
 * // hôte : rafraîchir les diagnostics de tous les onglets ouverts après un tout-enregistrer
 * OpenTabDiagnosticsSweep.start(openEditorViews);
 * }</pre>
 */
public final class OpenTabDiagnosticsSweep {

    /** Intervalle CodeAssist : 40 ms entre deux applications d'onglet. */
    public static final long DEFAULT_GAP_MS = 40L;

    /** Exécuteur partagé hors thread principal (daemon — ne bloque jamais
     * la sortie de la JVM). Typé Executor pour que les tests puissent
     * injecter un exécuteur direct par réflexion. */
    private static volatile Executor sharedExecutor;

    private final List<EditorView> tabs;
    private final Executor executor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long gapMs;
    private int index = 0;
    private int processed = 0;
    private int skipped = 0;
    private boolean cancelled = false;

    private OpenTabDiagnosticsSweep(List<EditorView> tabs, Executor executor, long gapMs) {
        this.tabs = new ArrayList<>(tabs);
        this.executor = executor;
        this.gapMs = gapMs;
    }

    /**
     * Démarre un balayage des éditeurs ouverts donnés (copie défensive —
     * l'hôte peut modifier sa liste d'onglets ensuite).
     */
    public static OpenTabDiagnosticsSweep start(List<EditorView> openTabs) {
        OpenTabDiagnosticsSweep sweep =
                new OpenTabDiagnosticsSweep(openTabs, sharedExecutor(), DEFAULT_GAP_MS);
        sweep.main.post(sweep::step);
        return sweep;
    }

    /** Arrête le parcours ; un résultat d'onglet en vol est écarté à l'application. */
    public void cancel() {
        cancelled = true;
    }

    /** Nombre d'onglets dont les diagnostics ont été recalculés (jusqu'ici). */
    public int processedCount() {
        return processed;
    }

    /** Nombre d'onglets ignorés par les règles d'éligibilité (jusqu'ici). */
    public int skippedCount() {
        return skipped;
    }

    private static Executor sharedExecutor() {
        Executor ex = sharedExecutor;
        if (ex == null) {
            synchronized (OpenTabDiagnosticsSweep.class) {
                ex = sharedExecutor;
                if (ex == null) {
                    ex = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "editor-diag-sweep");
                        t.setDaemon(true);
                        return t;
                    });
                    sharedExecutor = ex;
                }
            }
        }
        return ex;
    }

    private void step() {
        if (cancelled) return;
        while (index < tabs.size()) {
            EditorView tab = tabs.get(index++);
            if (!eligible(tab)) {
                skipped++;
                continue; // aucun travail effectué — pas d'intervalle nécessaire
            }
            analyze(tab);
            return; // l'étape suivante est planifiée après l'application de cet onglet
        }
        // Parcours terminé.
    }

    /** Éligibilité CodeAssist : ignore focus / lecture seule / volumineux / sans provider / détaché. */
    private static boolean eligible(EditorView tab) {
        if (tab == null) return false;
        if (tab.getParent() == null) return false;           // détachée
        if (tab.hasFocus()) return false;                    // possède son propre anti-rebond
        EditorSession session = tab.getSession();
        if (session == null) return false;
        if (session.isReadOnly()) return false;              // onglet consultation seule
        if (session.getDocument() != null
                && session.getDocument().isLarge()) return false; // filtré comme l'analyse sémantique
        return tab.getDiagnosticsProviderSpi() != null;
    }

    private void analyze(EditorView tab) {
        final EditorSession session = tab.getSession();
        final DiagnosticsProvider provider = tab.getDiagnosticsProviderSpi();
        final String text = session.getText();
        executor.execute(() -> {
            List<Diagnostic> diags;
            try {
                diags = provider.computeDiagnostics(text);
            } catch (RuntimeException e) {
                // Un provider qui lève ne doit pas tuer le balayage (même
                // politique qu'EditorPainterHost) : compté comme ignoré, le
                // parcours continue.
                main.post(() -> {
                    skipped++;
                    scheduleNext();
                });
                return;
            }
            final List<DiagnosticShift.Diagnostic> legacy =
                    new ArrayList<>(diags != null ? diags.size() : 0);
            if (diags != null) {
                for (Diagnostic d : diags) {
                    // Le Diagnostic historique du cœur n'a pas de champ code —
                    // le balayage alimente la même conversion à 4 arguments
                    // que le chemin de l'onglet actif (diagnosticsTask de
                    // EditorView.setLanguage).
                    legacy.add(new DiagnosticShift.Diagnostic(
                            d.start, d.end, d.severity, d.message));
                }
            }
            main.post(() -> {
                if (cancelled) return;
                // Garde anti-périmé : n'applique que si l'onglet montre
                // toujours la même session (l'hôte a pu changer de fichier
                // en plein vol).
                if (tab.getSession() == session && tab.getParent() != null) {
                    session.setDiagnostics(legacy);
                    tab.notifyDiagnosticsChanged();
                    processed++;
                } else {
                    skipped++;
                }
                scheduleNext();
            });
        });
    }

    private void scheduleNext() {
        if (cancelled) return;
        if (gapMs > 0) {
            main.postDelayed(this::step, gapMs);
        } else {
            main.post(this::step);
        }
    }
}
