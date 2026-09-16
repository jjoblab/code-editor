package jo.codeeditor.view;

import android.os.Handler;
import android.os.Looper;

import jo.codeeditor.lang.Diagnostic;
import jo.codeeditor.lang.DiagnosticsProvider;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * v3.36.0 — Diagnostics sweep of the OPEN TABS (roadmap item 8, port of
 * CodeAssist v3.20's {@code OpenTabDiagnosticsSweep}).
 *
 * <p>The editor already refreshes diagnostics of the <b>focused</b> tab on
 * every text change (debounced). But a multi-tab host ends up with stale
 * red dots on the tabs the user merely switched away from — this sweep
 * closes that gap. The host calls {@link #start(List)} whenever it wants
 * a refresh of every open editor (tab switch, save, window focus, idle…):
 * the sweep walks the list, re-runs each tab's
 * {@link DiagnosticsProvider} OFF the main thread, and applies the result
 * on the main thread — with a {@value #DEFAULT_GAP_MS} ms gap between
 * tabs so the UI thread never batches several applies in one frame
 * (CodeAssist's gap).</p>
 *
 * <p><b>Skipped tabs</b> (CodeAssist parity):</p>
 * <ul>
 *   <li>the tab that currently <b>has focus</b> — its own debounced task
 *       owns it (and the user is typing there);</li>
 *   <li><b>read-only</b> sessions — a file the host opened for viewing
 *       does not get re-analyzed;</li>
 *   <li><b>large</b> documents ({@code EditorDocument.isLarge()}) — the
 *       same gating the editor applies to semantic analysis;</li>
 *   <li>tabs without a wired diagnostics provider;</li>
 *   <li>detached views (no parent — the host is tearing the tab down).</li>
 * </ul>
 *
 * <p>The sweep applies a result only if the tab's session is still the
 * one that was analyzed (a tab switch mid-flight discards the stale
 * result), and {@link #cancel()} stops the walk. Only ONE sweep runs at a
 * time per instance; starting a new sweep on the same tabs is cheap and
 * safe (older instances simply die out or are cancelled by the host).</p>
 *
 * <pre>{@code
 * // host: refresh every open tab's diagnostics after a save-all
 * OpenTabDiagnosticsSweep.start(openEditorViews);
 * }</pre>
 *
 * @since v3.36.0
 */
public final class OpenTabDiagnosticsSweep {

    /** CodeAssist gap: 40 ms between two tab applies. */
    public static final long DEFAULT_GAP_MS = 40L;

    /** Shared off-main executor (daemon — never blocks JVM exit). Typed
     * as Executor so tests can inject a direct executor via reflection. */
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
     * Starts a sweep over the given open editors (defensive copy — the
     * host may mutate its tab list afterwards).
     */
    public static OpenTabDiagnosticsSweep start(List<EditorView> openTabs) {
        OpenTabDiagnosticsSweep sweep =
                new OpenTabDiagnosticsSweep(openTabs, sharedExecutor(), DEFAULT_GAP_MS);
        sweep.main.post(sweep::step);
        return sweep;
    }

    /** Stops the walk; an in-flight tab result is discarded on apply. */
    public void cancel() {
        cancelled = true;
    }

    /** Number of tabs whose diagnostics were recomputed (so far). */
    public int processedCount() {
        return processed;
    }

    /** Number of tabs skipped by the eligibility rules (so far). */
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
                continue; // no work done — no gap needed
            }
            analyze(tab);
            return; // next step is scheduled after this tab's apply
        }
        // Walk finished.
    }

    /** CodeAssist eligibility: skip focused / read-only / large / provider-less / detached. */
    private static boolean eligible(EditorView tab) {
        if (tab == null) return false;
        if (tab.getParent() == null) return false;           // detached
        if (tab.hasFocus()) return false;                    // owns its debounce
        EditorSession session = tab.getSession();
        if (session == null) return false;
        if (session.isReadOnly()) return false;              // view-only tab
        if (session.getDocument() != null
                && session.getDocument().isLarge()) return false; // gated like sem analysis
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
                // A throwing provider must not kill the sweep (same policy
                // as EditorPainterHost): count as skipped, keep walking.
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
                    // Legacy core Diagnostic has no code field — the sweep
                    // feeds the same 4-arg conversion the focused-tab path
                    // (EditorView.setLanguage's diagnosticsTask) uses.
                    legacy.add(new DiagnosticShift.Diagnostic(
                            d.start, d.end, d.severity, d.message));
                }
            }
            main.post(() -> {
                if (cancelled) return;
                // Stale guard: only apply if the tab still shows the same
                // session (the host may have switched files mid-flight).
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
