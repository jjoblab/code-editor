package jo.codeeditor.view;

/**
 * Classes de valeurs du système de décorations pour plugins.
 *
 * <p>Les décorations sont produites à chaque frame par les
 * {@link EditorDecorationPainter}s enregistrés puis dessinées par le
 * renderer :</p>
 * <ul>
 *   <li>{@link TextDecoration} — soulignement / encadré / barré coloré sur
 *       une plage du document (dans la zone de texte, au-dessus des
 *       soulignements ondulés) ;</li>
 *   <li>{@link GutterMark} — fine barre verticale colorée au bord droit de
 *       la zone de numéros de ligne de la gouttière (style VCS blame) ;</li>
 *   <li>{@link PluginInlay} — texte fantôme après la fin de ligne, indexé
 *       par offset (atténué, jamais interactif).</li>
 * </ul>
 */
public final class EditorDecorations {

    private EditorDecorations() {}

    /** Manière dont un {@link TextDecoration} rend sa plage. */
    public static final class DecorationStyles {
        /** Ligne colorée épaisse sous le texte (2,5 dp). */
        public static final int UNDERLINE = 0;
        /** Rectangle arrondi de contour 1 dp autour du texte. */
        public static final int BOX = 1;
        /** Ligne horizontale traversant le texte en son milieu. */
        public static final int STRIKE_THROUGH = 2;

        private DecorationStyles() {}
    }

    /** Décoration colorée sur une plage dans la zone de texte. */
    public static final class TextDecoration {
        /** Offset de début (inclus, espace document). */
        public final int start;
        /** Offset de fin (exclu, espace document). */
        public final int end;
        /** Couleur ARGB. */
        public final int color;
        /** Une des constantes de {@link DecorationStyles}. */
        public final int style;

        public TextDecoration(int start, int end, int color, int style) {
            this.start = start;
            this.end = end;
            this.color = color;
            this.style = style;
        }
    }

    /** Barre colorée sur la gouttière pour une ligne du document. */
    public static final class GutterMark {
        /** Ligne du document (base 0). */
        public final int line;
        /** Couleur ARGB. */
        public final int color;

        public GutterMark(int line, int color) {
            this.line = line;
            this.color = color;
        }
    }

    /** Texte fantôme dessiné après la ligne contenant {@code offset}. */
    public static final class PluginInlay {
        /** Offset dans le document — l'inlay décore la ligne qui le contient. */
        public final int offset;
        /** Texte fantôme (une ligne, dessiné à 85 % de la taille). */
        public final String text;
        /** Couleur ARGB. */
        public final int color;

        public PluginInlay(int offset, String text, int color) {
            this.offset = offset;
            this.text = text != null ? text : "";
            this.color = color;
        }
    }
}
