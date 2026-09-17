package jo.codeeditor.view;

/**
 * SPI de painter pour plugins.
 *
 * <p>Enregistrer les implémentations sur un éditeur via
 * {@code EditorView.getPainterHost().register(painter)} — ou les fournir
 * comme services Java {@link java.util.ServiceLoader}
 * ({@code META-INF/services/jo.codeeditor.view.EditorDecorationPainter})
 * et appeler {@link EditorPainterHost#loadFromClasspath()}.</p>
 *
 * <p>{@link #paint(EditorPaintContext)} s'exécute une fois par passe de rendu
 * sur le thread UI ; ne décorer que la fenêtre visible et limiter les
 * allocations. Un painter qui lève une exception est retiré de son hôte afin
 * qu'un plugin défaillant ne puisse jamais faire planter l'éditeur
 * (politique du {@code EditorPainterHost}).</p>
 *
 * <p>Exemple — souligner chaque TODO :</p>
 * <pre>{@code
 * public class TodoPainter implements EditorDecorationPainter {
 *     public void paint(EditorPaintContext ctx) {
 *         EditorDocument doc = ctx.getDocument();
 *         for (int line = ctx.getFirstVisibleLine();
 *                 line <= ctx.getLastVisibleLine(); line++) {
 *             String text = doc.lineText(line);
 *             int i = text.indexOf("TODO");
 *             if (i >= 0) {
 *                 int start = doc.lineStart(line) + i;
 *                 ctx.addTextDecoration(start, start + 4,
 *                         0xFFFFB300, EditorDecorations.DecorationStyles.UNDERLINE);
 *             }
 *         }
 *     }
 * }
 * }</pre>
 */
public interface EditorDecorationPainter {

    /**
     * Identifiant stable facultatif — sert à dédupliquer les painters fournis
     * via ServiceLoader. Par défaut : le nom de la classe.
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * Contribue des décorations pour la passe de rendu courante. S'exécute sur
     * le thread UI pendant le dessin — rester rapide. Lever une exception ici
     * retire le painter de son hôte.
     */
    void paint(EditorPaintContext ctx);
}
