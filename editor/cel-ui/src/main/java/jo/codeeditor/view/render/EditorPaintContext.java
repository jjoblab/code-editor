package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;

import jo.codeeditor.document.EditorDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Contexte transmis à chaque {@link EditorDecorationPainter} une fois par
 * passe de rendu.
 *
 * <p>Un painter lit la fenêtre qu'on lui demande de décorer puis pousse ses
 * décorations via les méthodes {@code add*}. Les décorations hors de la plage
 * de lignes visibles sont inoffensives (elles ne sont simplement pas
 * dessinées) mais coûteuses — un bon painter ne décore que la fenêtre
 * visible.</p>
 *
 * <p><b>Threads :</b> {@code paint} s'exécute sur le thread UI pendant la
 * passe de rendu — le garder rapide et économe en allocations. Un painter qui
 * lève une exception est RETIRÉ de son hôte plutôt que de faire planter
 * l'éditeur (politique du {@code EditorPainterHost}).</p>
 */
public final class EditorPaintContext {

    private final EditorView view;
    private final EditorDocument doc;
    private final int firstVisibleLine;
    private final int lastVisibleLine;
    private final float density;

    final List<EditorDecorations.TextDecoration> textDecorations = new ArrayList<>(0);
    final List<EditorDecorations.GutterMark> gutterMarks = new ArrayList<>(0);
    final List<EditorDecorations.PluginInlay> pluginInlays = new ArrayList<>(0);

    EditorPaintContext(EditorView view, EditorDocument doc,
            int firstVisibleLine, int lastVisibleLine, float density) {
        this.view = view;
        this.doc = doc;
        this.firstVisibleLine = firstVisibleLine;
        this.lastVisibleLine = lastVisibleLine;
        this.density = density;
    }

    /** Document en cours de rendu (utilisation en lecture seule). */
    public EditorDocument getDocument() {
        return doc;
    }

    /** Première ligne du document de la fenêtre visible (prise en compte des plis/retours à la ligne). */
    public int getFirstVisibleLine() {
        return firstVisibleLine;
    }

    /** Dernière ligne du document de la fenêtre visible (prise en compte des plis/retours à la ligne). */
    public int getLastVisibleLine() {
        return lastVisibleLine;
    }

    /** Densité de l'écran (px par dp). */
    public float getDensity() {
        return density;
    }

    /** Hauteur de ligne rendue en px (issue des métriques de l'éditeur). */
    public float getLineHeight() {
        return view.metrics.getLineHeight();
    }

    /** Largeur d'un caractère monospace en px (issue des métriques de l'éditeur). */
    public float getCharWidth() {
        return view.metrics.getCharWidth();
    }

    /** Ajoute une décoration colorée sur une plage (soulignement/encadré/barré) dans la zone de texte. */
    public void addTextDecoration(int start, int end, int color, int style) {
        if (end <= start) return;
        if (doc != null) {
            int len = doc.length();
            if (start < 0 || start >= len) return;
            if (end > len) end = len;
        }
        textDecorations.add(new EditorDecorations.TextDecoration(start, end, color, style));
    }

    /** Ajoute une barre colorée sur la gouttière de la ligne {@code line}. */
    public void addGutterMark(int line, int color) {
        if (line < 0) return;
        gutterMarks.add(new EditorDecorations.GutterMark(line, color));
    }

    /** Ajoute du texte fantôme après la ligne contenant {@code offset}. */
    public void addPluginInlay(int offset, String text, int color) {
        if (offset < 0 || text == null || text.isEmpty()) return;
        pluginInlays.add(new EditorDecorations.PluginInlay(offset, text, color));
    }
}
