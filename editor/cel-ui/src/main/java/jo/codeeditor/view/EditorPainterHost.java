package jo.codeeditor.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * v3.36.0 — Registry + per-frame collector for plugin painters (roadmap
 * item 9, port of CodeAssist v3.20's {@code EditorPainterHost}).
 *
 * <p>The host owns the registered {@link EditorDecorationPainter}s and,
 * once per render pass, calls each of them with a fresh
 * {@link EditorPaintContext} to collect that frame's decorations
 * (text decorations, gutter marks, plugin inlays). The editor's renderer
 * then draws them on top of its own layers.</p>
 *
 * <p><b>Fail-safe:</b> a painter that throws anything ({@link Throwable},
 * not just RuntimeException) is REMOVED from the registry and reported to
 * the {@link Listener}s — the editor itself keeps rendering. This is the
 * single most important CodeAssist policy of the painter host: a broken
 * plugin degrades to "no decorations", never to a crashed editor.</p>
 *
 * <pre>{@code
 * EditorPainterHost host = view.getPainterHost();
 * host.register(new TodoPainter());
 * host.loadFromClasspath();   // META-INF/services/... SPI plugins
 * }</pre>
 *
 * <p>Not thread-safe — register/unregister on the UI thread (the render
 * pass iterates the painter list on the UI thread too).</p>
 *
 * @since v3.36.0
 */
public final class EditorPainterHost {

    /** Notified when a painter is removed because it threw. */
    public interface Listener {
        void onPainterRemoved(EditorDecorationPainter painter, Throwable error);
    }

    /** One frame's collected decorations (consumed by the renderer). */
    static final class Frame {
        final List<EditorDecorations.TextDecoration> textDecorations;
        final List<EditorDecorations.GutterMark> gutterMarks;
        final List<EditorDecorations.PluginInlay> pluginInlays;

        Frame(EditorPaintContext ctx) {
            this.textDecorations = ctx.textDecorations;
            this.gutterMarks = ctx.gutterMarks;
            this.pluginInlays = ctx.pluginInlays;
        }

        boolean isEmpty() {
            return textDecorations.isEmpty() && gutterMarks.isEmpty()
                    && pluginInlays.isEmpty();
        }
    }

    private static final Frame EMPTY_FRAME = new Frame(new EditorPaintContext(
            null, null, 0, -1, 1f));

    private final List<EditorDecorationPainter> painters = new ArrayList<>(0);
    private final List<Listener> listeners = new ArrayList<>(0);
    private final Set<String> knownIds = new HashSet<>(0);

    /** Registers a painter (deduplicated by {@link EditorDecorationPainter#id()}). */
    public void register(EditorDecorationPainter painter) {
        if (painter == null) return;
        synchronized (painters) {
            if (!knownIds.add(painter.id())) return; // already registered
            painters.add(painter);
        }
    }

    /** Unregisters a painter (identity match). Returns true when removed. */
    public boolean unregister(EditorDecorationPainter painter) {
        if (painter == null) return false;
        synchronized (painters) {
            boolean removed = painters.removeIf(p -> p == painter);
            if (removed) knownIds.remove(painter.id());
            return removed;
        }
    }

    /** The registered painters (unmodifiable snapshot). */
    public List<EditorDecorationPainter> painters() {
        synchronized (painters) {
            return Collections.unmodifiableList(new ArrayList<>(painters));
        }
    }

    public void addListener(Listener l) {
        if (l != null) synchronized (listeners) { listeners.add(l); }
    }

    public void removeListener(Listener l) {
        if (l != null) synchronized (listeners) { listeners.remove(l); }
    }

    /**
     * Loads painters from the classpath's
     * {@code META-INF/services/jo.codeeditor.view.EditorDecorationPainter}
     * registrations (Java SPI / {@link ServiceLoader}). Providers that fail
     * to instantiate are skipped. Ids already registered are not
     * duplicated.
     */
    public void loadFromClasspath() {
        try {
            ServiceLoader<EditorDecorationPainter> loader =
                    ServiceLoader.load(EditorDecorationPainter.class);
            for (EditorDecorationPainter p : loader) {
                try {
                    register(p);
                } catch (RuntimeException ignored) {
                    // a bad provider factory must not break the others
                }
            }
        } catch (Throwable t) {
            // ServiceLoader itself can throw (broken classpath) — degrade
            // to "no classpath painters".
        }
    }

    /**
     * Runs every painter and collects this frame's decorations. A painter
     * that throws is removed and reported; the frame keeps whatever the
     * other painters produced.
     */
    Frame apply(EditorView view, int firstVisibleLine, int lastVisibleLine) {
        List<EditorDecorationPainter> snapshot;
        synchronized (painters) {
            if (painters.isEmpty()) return EMPTY_FRAME;
            snapshot = new ArrayList<>(painters);
        }
        EditorPaintContext ctx = new EditorPaintContext(
                view, view.session != null ? view.session.getDocument() : null,
                firstVisibleLine, lastVisibleLine,
                view.getResources().getDisplayMetrics().density);
        for (EditorDecorationPainter painter : snapshot) {
            try {
                painter.paint(ctx);
            } catch (Throwable t) {
                // ★ CodeAssist EditorPainterHost policy: a painter that
                // throws is removed from the registry — never crash the
                // editor for a plugin's bug.
                synchronized (painters) {
                    painters.removeIf(p -> p == painter);
                    knownIds.remove(painter.id());
                }
                List<Listener> ls;
                synchronized (listeners) {
                    ls = new ArrayList<>(listeners);
                }
                for (Listener l : ls) {
                    try {
                        l.onPainterRemoved(painter, t);
                    } catch (RuntimeException ignored) {
                        // a throwing listener must not break the removal
                    }
                }
            }
        }
        return new Frame(ctx);
    }
}
