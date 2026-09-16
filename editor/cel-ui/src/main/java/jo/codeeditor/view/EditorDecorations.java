package jo.codeeditor.view;

/**
 * v3.36.0 — Value classes of the plugin decoration system (roadmap item 9,
 * port of CodeAssist v3.20's {@code DecorationStyles} +
 * {@code textDecorations}/{@code gutterMarks}/{@code pluginInlays}).
 *
 * <p>Decorations are produced per frame by registered
 * {@link EditorDecorationPainter}s and drawn by the renderer:</p>
 * <ul>
 *   <li>{@link TextDecoration} — colored underline / box / strike-through
 *       over a document range (in the text area, above squiggles);</li>
 *   <li>{@link GutterMark} — a thin colored vertical bar at the right edge
 *       of the gutter's line-number area (VCS-blame style);</li>
 *   <li>{@link PluginInlay} — phantom text after the line end, offset-aware
 *       (dimmed, never interactive).</li>
 * </ul>
 *
 * @since v3.36.0
 */
public final class EditorDecorations {

    private EditorDecorations() {}

    /** How a {@link TextDecoration} renders its range. */
    public static final class DecorationStyles {
        /** Thick colored line under the text (2.5dp). */
        public static final int UNDERLINE = 0;
        /** 1dp stroked rounded rectangle around the text. */
        public static final int BOX = 1;
        /** Horizontal line through the middle of the text. */
        public static final int STRIKE_THROUGH = 2;

        private DecorationStyles() {}
    }

    /** A colored range decoration in the text area. */
    public static final class TextDecoration {
        /** Start offset (inclusive, document space). */
        public final int start;
        /** End offset (exclusive, document space). */
        public final int end;
        /** ARGB color. */
        public final int color;
        /** One of {@link DecorationStyles} constants. */
        public final int style;

        public TextDecoration(int start, int end, int color, int style) {
            this.start = start;
            this.end = end;
            this.color = color;
            this.style = style;
        }
    }

    /** A colored bar on the gutter for one document line. */
    public static final class GutterMark {
        /** Document line (0-based). */
        public final int line;
        /** ARGB color. */
        public final int color;

        public GutterMark(int line, int color) {
            this.line = line;
            this.color = color;
        }
    }

    /** Phantom text drawn after the line that contains {@code offset}. */
    public static final class PluginInlay {
        /** Document offset — the inlay decorates the line containing it. */
        public final int offset;
        /** Phantom text (single line, drawn at 85% size). */
        public final String text;
        /** ARGB color. */
        public final int color;

        public PluginInlay(int offset, String text, int color) {
            this.offset = offset;
            this.text = text != null ? text : "";
            this.color = color;
        }
    }
}
