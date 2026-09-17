package jo.codeeditor.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Registre + collecteur par frame des painters de plugins (port de
 * l'{@code EditorPainterHost} de CodeAssist).
 *
 * <p>L'hôte possède les {@link EditorDecorationPainter} enregistrés et,
 * une fois par passe de rendu, appelle chacun avec un
 * {@link EditorPaintContext} neuf pour collecter les décorations de la
 * frame (décorations de texte, marques de gutter, inlays de plugins). Le
 * renderer de l'éditeur les dessine ensuite par-dessus ses propres
 * couches.</p>
 *
 * <p><b>Fiabilité :</b> un painter qui lève quoi que ce soit ({@link Throwable},
 * pas seulement RuntimeException) est RETIRÉ du registre et signalé aux
 * {@link Listener} — l'éditeur continue de se dessiner. C'est la politique
 * la plus importante du painter host héritée de CodeAssist : un plugin
 * défaillant dégrade en « aucune décoration », jamais en éditeur planté.</p>
 *
 * <pre>{@code
 * EditorPainterHost host = view.getPainterHost();
 * host.register(new TodoPainter());
 * host.loadFromClasspath();   // plugins SPI META-INF/services/...
 * }</pre>
 *
 * <p>Non thread-safe — enregistrer/désenregistrer sur le thread UI (la
 * passe de rendu itère aussi la liste des painters sur le thread UI).</p>
 */
public final class EditorPainterHost {

    /** Notifié quand un painter est retiré pour avoir levé une exception. */
    public interface Listener {
        void onPainterRemoved(EditorDecorationPainter painter, Throwable error);
    }

    /** Décorations collectées d'une frame (consommées par le renderer). */
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

    /** Enregistre un painter (dédupliqué par {@link EditorDecorationPainter#id()}). */
    public void register(EditorDecorationPainter painter) {
        if (painter == null) return;
        synchronized (painters) {
            if (!knownIds.add(painter.id())) return; // déjà enregistré
            painters.add(painter);
        }
    }

    /** Désenregistre un painter (par identité). Renvoie true si retiré. */
    public boolean unregister(EditorDecorationPainter painter) {
        if (painter == null) return false;
        synchronized (painters) {
            boolean removed = painters.removeIf(p -> p == painter);
            if (removed) knownIds.remove(painter.id());
            return removed;
        }
    }

    /** Les painters enregistrés (instantané non modifiable). */
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
     * Charge les painters depuis les inscriptions
     * {@code META-INF/services/jo.codeeditor.view.EditorDecorationPainter}
     * du classpath (Java SPI / {@link ServiceLoader}). Les providers qui
     * échouent à s'instancier sont ignorés. Les identifiants déjà
     * enregistrés ne sont pas dupliqués.
     */
    public void loadFromClasspath() {
        try {
            ServiceLoader<EditorDecorationPainter> loader =
                    ServiceLoader.load(EditorDecorationPainter.class);
            for (EditorDecorationPainter p : loader) {
                try {
                    register(p);
                } catch (RuntimeException ignored) {
                    // une factory de provider défaillante ne doit pas casser les autres
                }
            }
        } catch (Throwable t) {
            // ServiceLoader lui-même peut lever (classpath cassé) — dégrade
            // en « aucun painter de classpath ».
        }
    }

    /**
     * Exécute chaque painter et collecte les décorations de la frame. Un
     * painter qui lève est retiré et signalé ; la frame conserve ce que les
     * autres painters ont produit.
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
                // ★ Politique du painter host de CodeAssist : un painter qui
                // lève est retiré du registre — ne jamais planter l'éditeur
                // pour le bug d'un plugin.
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
                        // un listener qui lève ne doit pas casser le retrait
                    }
                }
            }
        }
        return new Frame(ctx);
    }
}
