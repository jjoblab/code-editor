package jo.codeeditor.view.chrome;

import jo.codeeditor.view.EditorMetrics;
import jo.codeeditor.view.EditorView;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Dessine les numéros de ligne et les points de diagnostic dans la zone du
 * gutter.
 *
 * <p>Sensible aux replis : quand un {@link #setHiddenLineChecker(IntPredicate)
 * vérificateur de lignes masquées} est défini, les lignes masquées sont
 * ignorées et les numéros restants sont remontés pour combler le trou —
 * en cohérence avec le mappage Y sensible aux replis de la zone de texte
 * dans {@link EditorView#docLineToY(int)}. Sans cela, replier une région
 * désalignait les numéros du gutter par rapport au texte.
 */
public class GutterView {

    private final EditorMetrics metrics;
    private EditorTheme theme;

    // Données de diagnostic : sévérité par ligne (0=aucune, 1=info, 2=avertissement, 3=erreur)
    private int[] diagnostics = new int[0];

    /** Renvoie true si la ligne de document donnée est masquée par un repli fermé. */
    private IntPredicate hiddenLineChecker;

    /** Densité d'écran (px/dp), utilisée pour dimensionner le point de diagnostic. */
    private float density = 1f;

    /**
     * Marques de gutter des plugins : ligne → couleur ARGB. Dessinées en
     * barre verticale fine au bord DROIT de la zone des numéros de ligne
     * (style VCS-blame) — coin opposé aux points de diagnostic, de sorte
     * que les deux n'entrent jamais en collision. Définies par le renderer
     * depuis la frame du painter host.
     */
    private Map<Integer, Integer> pluginMarks = new HashMap<>(0);

    public GutterView(EditorMetrics metrics, EditorTheme theme) {
        this.metrics = metrics;
        this.theme = theme;
    }

    public void setTheme(EditorTheme theme) {
        this.theme = theme;
    }

    /**
     * Définit la densité d'écran (px par dp). Sert à dimensionner et
     * positionner le point de diagnostic. Si jamais appelée, la valeur par
     * défaut est 1 (les dp sont traités comme des px — pour les tests).
     */
    public void setDensity(float density) {
        this.density = density;
    }

    /**
     * Définit la sévérité de diagnostic pour chaque ligne.
     */
    public void setDiagnostics(int[] diagnostics) {
        this.diagnostics = diagnostics != null ? diagnostics : new int[0];
    }

    /**
     * Définit le prédicat indiquant si une ligne de document est
     * actuellement masquée par une région de repli fermée. Quand il est
     * défini, {@link #draw} ignore les lignes masquées et compacte les
     * numéros restants pour que le gutter reste aligné avec la zone de
     * texte sensible aux replis.
     *
     * @param checker prédicat renvoyant true pour les lignes masquées,
     *                ou null pour désactiver le rendu sensible aux replis
     */
    public void setHiddenLineChecker(IntPredicate checker) {
        this.hiddenLineChecker = checker;
    }

    /**
     * Définit les marques de gutter des plugins (ligne → couleur ARGB).
     * Une map vide ou nulle les efface.
     */
    public void setPluginMarks(Map<Integer, Integer> lineToColor) {
        this.pluginMarks = lineToColor != null ? lineToColor : new HashMap<>(0);
    }

    /**
     * Dessine le gutter : arrière-plan, ligne séparatrice, numéros de ligne
     * et points de diagnostic.
     * <p>
     * Les numéros de ligne sont alignés à droite à
     * {@code gutterWidth - foldStripWidth - 0.5*charWidth} (c.-à-d. à la fin
     * de la zone des numéros, AVANT la bande de repli) ; un alignement à
     * {@code gutterWidth - 0.5*charWidth} les posait sur le chevron de repli.
     * <p>
     * Quand un vérificateur de lignes masquées est défini, les lignes
     * masquées sont ignorées et le Y de chaque ligne visible est calculé en
     * parcourant les lignes du document et en ne comptant que les visibles
     * (en cohérence avec EditorView.docLineToY).
     * <p>
     * L'arrière-plan du gutter est semi-transparent (effet verre) pour que
     * l'utilisateur devine le texte qui défile derrière. L'alpha est ajusté
     * pour rester lisible (numéros toujours lisibles) tout en laissant
     * transparaître subtilement la couleur du texte.
     */
    public void draw(Canvas canvas, float scrollTop, float viewHeight, int totalLines, int currentLine) {
        float gutterWidth = metrics.getGutterWidth();
        float foldStripWidth = metrics.getFoldStripWidth();
        float lineNumberAreaRight = gutterWidth - foldStripWidth;
        float lineHeight = metrics.getLineHeight();
        float paddingTop = metrics.getPadTop();

        // Arrière-plan du gutter verre/semi-transparent : au lieu d'un
        // remplissage totalement opaque, un alpha d'environ 88 % laisse
        // faiblement apparaître le texte qui défile derrière — effet verre
        // dépoli similaire au gutter translucide de VS Code.
        Paint bgPaint = new Paint();
        bgPaint.setColor(applyAlpha(theme.gutterBg, 0.88f));
        canvas.drawRect(0, 0, gutterWidth, viewHeight, bgPaint);

        // Ligne séparatrice
        Paint sepPaint = new Paint();
        sepPaint.setColor(theme.gutterBorder);
        sepPaint.setStrokeWidth(1f);
        canvas.drawLine(gutterWidth, 0, gutterWidth, viewHeight, sepPaint);

        // Numéros de ligne — alignés à droite à la fin de la zone des numéros
        // (AVANT la bande de repli), avec 0,5 caractère de marge à droite.
        Paint numberPaint = new Paint(metrics.getGutterPaint());
        numberPaint.setColor(theme.gutterText);

        float textX = lineNumberAreaRight - metrics.getCharWidth() * 0.5f;

        if (hiddenLineChecker != null) {
            // Chemin sensible aux replis : parcourt toutes les lignes du
            // document, ignore les masquées et dessine chaque ligne visible
            // à (visibleRowSoFar × lineHeight).
            int visibleRow = 0;
            for (int i = 0; i < totalLines; i++) {
                if (hiddenLineChecker.test(i)) continue;
                float y = paddingTop + visibleRow * lineHeight - scrollTop;
                visibleRow++;
                // Ignore les lignes au-dessus du viewport (mais continue de
                // compter visibleRow pour que le Y reste correct).
                if (y + lineHeight < 0) continue;
                if (y > viewHeight) break;
                drawLineNumber(canvas, numberPaint, textX, y, lineHeight, i, currentLine, lineNumberAreaRight);
            }
        } else {
            // Chemin historique : pas de replis, chaque ligne du document est visible.
            int firstLine = (int) (scrollTop / lineHeight);
            int lastLine = Math.min(totalLines - 1, (int) ((scrollTop + viewHeight) / lineHeight));
            for (int i = firstLine; i <= lastLine; i++) {
                float y = paddingTop + i * lineHeight - scrollTop;
                drawLineNumber(canvas, numberPaint, textX, y, lineHeight, i, currentLine, lineNumberAreaRight);
            }
        }
    }

    /** Dessine un numéro de ligne + son point de diagnostic au Y donné.
     *
     *  <p>Le point est épinglé au bord GAUCHE du gutter (centre à
     *  {@code 5dp + dotR} du bord gauche), le numéro étant aligné à droite
     *  à l'autre extrémité — point et numéro sont donc aux deux bouts,
     *  séparés par ~50-60dp d'espace vide, comme le layout de CodeAssist.
     *  Le point est un disque plein (pas de halo interne — CodeAssist
     *  dessine un cercle simple).
     */
    private void drawLineNumber(Canvas canvas, Paint numberPaint, float textX,
                                 float y, float lineHeight, int lineIdx, int currentLine,
                                 float lineNumberAreaRight) {
        String lineStr = String.valueOf(lineIdx + 1);
        float lineNumberWidth = numberPaint.measureText(lineStr);

        if (lineIdx == currentLine) {
            numberPaint.setFakeBoldText(true);
            numberPaint.setColor(theme.textColor);
        }
        canvas.drawText(lineStr, textX - lineNumberWidth,
            y + lineHeight * 0.75f, numberPaint);
        if (lineIdx == currentLine) {
            numberPaint.setFakeBoldText(false);
            numberPaint.setColor(theme.gutterText);
        }

        // Point de diagnostic style CodeAssist — épinglé au bord GAUCHE du
        // gutter (pas à côté du numéro). Centre x = 5dp + dotR depuis le bord
        // gauche. Disque plein. Rayon maintenu à 3dp : au-dessus des 2.5dp de
        // CodeAssist mais visuellement plus léger à côté des numéros de ligne.
        if (lineIdx < diagnostics.length && diagnostics[lineIdx] > 0) {
            // Ne dessine des points que pour les erreurs (3) et les
            // avertissements (2) — CodeAssist ne dessine aucun point de
            // gutter pour les infos (sévérité 1). Les infos reçoivent un
            // gribouillis dans la zone de texte mais aucun indicateur de
            // gutter.
            int sev = diagnostics[lineIdx];
            if (sev < 2) return; // ignore les infos — comme CodeAssist
            float dotR = 3.0f * density; // 3dp
            float dotCenterX = 5f * density + dotR;
            float dotY = y + lineHeight * 0.5f;
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(getDiagnosticColor(sev));
            dotPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(dotCenterX, dotY, dotR, dotPaint);
        }

        // Marque de gutter des plugins : barre verticale fine au bord DROIT
        // de la zone des numéros (2dp de large, 60 % de la hauteur de la
        // rangée), style VCS-blame. Dessinée en dernier pour rester visible
        // sur l'arrière-plan verre.
        Integer markColor = pluginMarks.get(lineIdx);
        if (markColor != null) {
            Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            markPaint.setColor(markColor);
            markPaint.setStyle(Paint.Style.FILL);
            float barX = lineNumberAreaRight - 2.5f * density;
            float barY = y + lineHeight * 0.2f;
            canvas.drawRect(barX, barY, barX + 2f * density,
                    barY + lineHeight * 0.6f, markPaint);
        }
    }

    private int getDiagnosticColor(int severity) {
        switch (severity) {
            case 3: return theme.error;
            case 2: return theme.warning;
            case 1: return theme.info;
            default: return 0;
        }
    }

    /**
     * Applique un multiplicateur d'alpha à une couleur ARGB. Utilisé par
     * l'arrière-plan du gutter à effet verre.
     */
    private static int applyAlpha(int color, float alpha) {
        int a = (color >>> 24) & 0xFF;
        int newA = (int) (a * alpha);
        return (newA << 24) | (color & 0x00FFFFFF);
    }
}
