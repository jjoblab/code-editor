package jo.codeeditor.view.chrome;

import jo.codeeditor.view.EditorMetrics;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

import jo.codeeditor.view.icons.EditorIcons;

import jo.codeeditor.view.EditorGutterActions;
import jo.codeeditor.view.GutterAction;

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

    // ★ B21d : paints réutilisables (un par couche du gutter) — les
    // couleurs se re-posent chaque frame ; plus aucune allocation par draw.
    private final Paint bgPaint = new Paint();
    private final Paint sepPaint = new Paint();
    private final Paint numberPaint = new Paint();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Données de diagnostic : sévérité par ligne (0=aucune, 1=info, 2=avertissement, 3=erreur)
    private int[] diagnostics = new int[0];

    /** Renvoie true si la ligne de document donnée est masquée par un repli fermé. */
    private IntPredicate hiddenLineChecker;

    // ── B12 (P1-4) : mode des numéros de ligne ──
    /**
     * Mode d'affichage des numéros — ABSOLUTE par défaut (comportement
     * historique inchangé). Voir {@link LineNumberStyle}.
     */
    private LineNumberStyle lineNumberStyle = LineNumberStyle.ABSOLUTE;

    /**
     * ★ B11+B20 — Géométrie de l'hôte ({@code EditorView.docLineToY} /
     * {@code docLineForScreenY}) : quand elle est définie, la gouttière
     * aligne ses numéros sur les positions Y RÉELLES du texte
     * (conscientes du retour à la ligne ET des plis repliés) et démarre
     * son itération à la première ligne visible (O(visible) au lieu de
     * O(lignes du document) par frame).
     */
    public interface HostGeometry {
        /** Coordonnée Y (espace contenu, avant scrollTop) du HAUT de la
         *  ligne de document — padding supérieur inclus. */
        float docLineToY(int docLine);
        /** Ligne de document contenant la coordonnée Y écran donnée. */
        int docLineForScreenY(float screenY);
    }

    private HostGeometry geometry;

    /**
     * Définit la géométrie hôte (voir {@link HostGeometry}). Sans
     * géométrie, la gouttière retombe sur ses chemins historiques
     * (comptage indépendant — utilisé par les tests unitaires
     * standalone).
     */
    public void setHostGeometry(HostGeometry geometry) {
        this.geometry = geometry;
    }

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

    // ── B9c (retour terrain n°6) : icône d'aperçu de la gouttière ──
    // « comme Android Studio » : les icônes de gutter (run, Compose
    // @Preview…) vivent dans la gouttière, ancrées à la ligne qu'elles
    // concernent. L'œil d'aperçu se pose sur la RANGÉE DU CARET (la ligne
    // que l'utilisateur regarde) — un tap bascule la feuille d'aperçu,
    // l'état ouvert s'allume de l'accent (parité avec l'œil du fil
    // d'Ariane B6).
    /** Icône d'aperçu visible (fichier prévisualisable). */
    private boolean previewActionVisible = false;
    /** Icône d'aperçu à l'état ACTIF (feuille ouverte — accent). */
    private boolean previewActionActive = false;
    /** Peinture dédiée de l'icône (couleur posée par frame — prêtée à
     * EditorIcons.draw qui gère style/épaisseur). */
    private final Paint previewIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Rectangle de la pill (réutilisé — pas d'allocation par frame). */
    private final RectF previewPillRect = new RectF();

    // ── B15 (P2-2) : actions de gouttière de l'HÔTE ──
    /** Registre des actions d'hôte (posé une fois par EditorView — la
     * liste se consulte à CHAQUE rendu : add → invalidate → redraw). */
    private EditorGutterActions hostActions;

    /**
     * B9c — définit l'état de l'action d'aperçu de la gouttière.
     *
     * @param visible vrai si le fichier courant est prévisualisable
     *                (l'icône se dessine sur la rangée du caret)
     * @param active  vrai si la feuille d'aperçu est OUVERTE (icône
     *                allumée à l'accent)
     */
    public void setPreviewAction(boolean visible, boolean active) {
        this.previewActionVisible = visible;
        this.previewActionActive = active;
    }

    /**
     * B9c — rectangle de la cible tactile de l'icône d'aperçu sur la
     * rangée du caret. DÉTERMINISTE (métriques + haut de rangée) : le
     * dessin et le hit-test du resolver calculent LE MÊME rectangle
     * sans partager d'état mutable.
     *
     * <p>Pill centrée horizontalement dans la zone des NUMÉROS (avant la
     * bande de repli), calée sur le centre vertical de la rangée. Côté
     * 26 dp (borné par la hauteur de ligne) — cible confortable, plus
     * large que le glyphe 20 dp.</p>
     *
     * @param metrics    métriques de l'éditeur (largeurs de gouttière)
     * @param density    densité d'écran (px/dp)
     * @param rowTop     Y ÉCRAN du HAUT de la rangée du caret
     * @param lineHeight hauteur d'une ligne (px)
     * @param out        rect de sortie (pill de l'icône)
     */
    public static void previewActionRect(EditorMetrics metrics, float density,
            float rowTop, float lineHeight, RectF out) {
        float numberArea = metrics.getGutterWidth() - metrics.getFoldStripWidth();
        float cx = numberArea * 0.5f;
        float cy = rowTop + lineHeight * 0.5f;
        float side = Math.min(26f * density, lineHeight);
        out.set(cx - side * 0.5f, cy - side * 0.5f,
                cx + side * 0.5f, cy + side * 0.5f);
    }

    /**
     * B9c — vrai si le point (x, y) tombe dans la cible tactile de
     * l'icône d'aperçu posée sur la rangée donnée.
     */
    public static boolean previewActionContains(EditorMetrics metrics, float density,
            float rowTop, float lineHeight, float x, float y) {
        RectF r = new RectF();
        previewActionRect(metrics, density, rowTop, lineHeight, r);
        return r.contains(x, y);
    }

    /**
     * B15 (P2-2) — pose le registre des actions de gouttière de
     * l'HÔTE (une seule fois, à la construction de l'EditorView). La
     * liste se consulte à CHAQUE rendu : {@code add/remove/clear}
     * invalident la vue, le draw relit — aucun re-pose nécessaire.
     * {@code null} désactive (tests standalone de GutterView).
     */
    public void setHostActions(EditorGutterActions hostActions) {
        this.hostActions = hostActions;
    }

    /**
     * B15 (P2-2) — rectangle de la cible tactile d'une action de
     * gouttière de l'HÔTE sur la rangée donnée. MÊME géométrie que
     * {@link #previewActionRect} (la pill B9c est la source unique :
     * le dessin des actions d'hôte, l'œil d'aperçu et le hit-test
     * partagent la formule déterministe).
     */
    public static void hostActionRect(EditorMetrics metrics, float density,
            float rowTop, float lineHeight, RectF out) {
        previewActionRect(metrics, density, rowTop, lineHeight, out);
    }

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
     * B12 (P1-4) — définit le mode d'affichage des numéros de ligne.
     *
     * <p>Voir {@link LineNumberStyle}. {@code null} retombe sur
     * {@link LineNumberStyle#ABSOLUTE} (l'hôte ne doit jamais pouvoir
     * casser le rendu avec un null accidentel).</p>
     *
     * @param style ABSOLUTE, RELATIVE ou HYBRID
     */
    public void setLineNumberStyle(LineNumberStyle style) {
        this.lineNumberStyle = style != null ? style : LineNumberStyle.ABSOLUTE;
    }

    /** Mode courant des numéros de ligne (jamais null). */
    public LineNumberStyle getLineNumberStyle() {
        return lineNumberStyle;
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
        // dépoli translucide.
        // ★ B21d : paints HOISTÉS en champs — 3 new Paint() par frame
        // (plus 1 par point de diagnostic) allouaient/garbageaient à chaque
        // redraw ; les couleurs se re-posent chaque frame, sans allocation.
        bgPaint.setColor(applyAlpha(theme.gutterBg, 0.88f));
        canvas.drawRect(0, 0, gutterWidth, viewHeight, bgPaint);

        // Ligne séparatrice
        sepPaint.setColor(theme.gutterBorder);
        sepPaint.setStrokeWidth(1f);
        canvas.drawLine(gutterWidth, 0, gutterWidth, viewHeight, sepPaint);

        // Numéros de ligne — alignés à droite à la fin de la zone des numéros
        // (AVANT la bande de repli), avec 0,5 caractère de marge à droite.
        numberPaint.set(metrics.getGutterPaint());
        numberPaint.setColor(theme.gutterText);

        float textX = lineNumberAreaRight - metrics.getCharWidth() * 0.5f;

        if (geometry != null) {
            // ★ B11+B20 : chemin canonique — Y de l'hôte (wrap + plis)
            // et départ à la première ligne visible.
            //
            // Lot 0 (audit v3.41.0) : docLineForScreenY prend une
            // coordonnée Y ÉCRAN (0 = haut du viewport) et AJOUTE
            // déjà view.vOffset en interne (cf. EditorWrapGeometry,
            // {@code contentY = screenY + view.vOffset - padTop}).
            // Passer {@code scrollTop} (= vOffset) comme argument
            // décalait donc le calcul deux fois — à mi-document,
            // {@code first} dépassait {@code totalLines} et la boucle
            // ne dessinait plus aucun numéro. Le renderer appelle
            // lui-même docLineForScreenY(0)/docLineForScreenY(height)
            // (EditorRenderer:106/109) — la gouttière doit suivre la
            // même convention. La marge -1 conserve la ligne
            // partiellement visible en haut du viewport.
            int first = Math.max(0, geometry.docLineForScreenY(0) - 1);
            for (int i = first; i < totalLines; i++) {
                if (hiddenLineChecker != null && hiddenLineChecker.test(i)) {
                    continue;
                }
                float y = geometry.docLineToY(i) - scrollTop;
                if (y + lineHeight < 0) continue;
                if (y > viewHeight) break;
                drawLineNumber(canvas, numberPaint, textX, y, lineHeight,
                        i, currentLine, lineNumberAreaRight);
            }
            return;
        }
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
            //
            // Lot 0 (audit v3.41.0) : ce chemin n'est PAS atteignable en
            // production — l'EditorView pose TOUJOURS la géométrie hôte
            // (cf. EditorView:1040, setHostGeometry appelé dans le
            // constructeur). Il ne sert qu'aux tests unitaires standalone
            // de GutterView. L'ancien calcul {@code (int)(scrollTop /
            // lineHeight)} ne tenait pas compte de {@code paddingTop},
            // ratant la ligne partiellement visible en haut du viewport
            // dès que {@code scrollTop > paddingTop}. Aligné sur le
            // chemin géométrie : marge -1 pour la ligne partiellement
            // visible, plus filtre {@code y + lineHeight < 0} par sécurité.
            int firstLine = Math.max(0,
                    (int) Math.floor((scrollTop - paddingTop) / lineHeight) - 1);
            int lastLine = Math.min(totalLines - 1,
                    (int) Math.floor((scrollTop + viewHeight - paddingTop) / lineHeight) + 1);
            for (int i = firstLine; i <= lastLine; i++) {
                float y = paddingTop + i * lineHeight - scrollTop;
                if (y + lineHeight < 0) continue;
                if (y > viewHeight) break;
                drawLineNumber(canvas, numberPaint, textX, y, lineHeight, i, currentLine, lineNumberAreaRight);
            }
        }
    }

    /**
     * B12 (P1-4) — libellé du numéro selon le mode courant.
     *
     * <p>ABSOLUTE : {@code lineIdx + 1}. RELATIVE : distance à la ligne
     * du caret (0 au caret). HYBRID : numéro réel au caret, distance
     * ailleurs. Un {@code currentLine} invalide (négatif — session sans
     * caret, test standalone) retombe sur ABSOLUTE : une distance vers
     * une ligne inconnue ne veut rien dire.</p>
     */
    private String lineNumberLabel(int lineIdx, int currentLine) {
        if (lineNumberStyle != LineNumberStyle.ABSOLUTE && currentLine >= 0) {
            if (lineNumberStyle == LineNumberStyle.RELATIVE
                    || lineIdx != currentLine) {
                return String.valueOf(Math.abs(lineIdx - currentLine));
            }
            // HYBRID sur la ligne du caret : le numéro réel.
        }
        return String.valueOf(lineIdx + 1);
    }

    /** Dessine un numéro de ligne + son point de diagnostic au Y donné.
     *
     *  <p>Le point est épinglé au bord GAUCHE du gutter (centre à
     *  {@code 5dp + dotR} du bord gauche), le numéro étant aligné à droite
     *  à l'autre extrémité — point et numéro sont donc aux deux bouts,
     *  séparés par ~50-60dp d'espace vide.
     *  Le point est un disque plein (pas de halo interne
     *  dessine un cercle simple).
     */
    private void drawLineNumber(Canvas canvas, Paint numberPaint, float textX,
                                 float y, float lineHeight, int lineIdx, int currentLine,
                                 float lineNumberAreaRight) {
        String lineStr = lineNumberLabel(lineIdx, currentLine);
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

        // Point de diagnostic — épinglé au bord GAUCHE du
        // gutter (pas à côté du numéro). Centre x = 5dp + dotR depuis le bord
        // gauche. Disque plein. Rayon maintenu à 3dp : au-dessus des 2.5dp de
        // mais visuellement plus léger à côté des numéros de ligne.
        if (lineIdx < diagnostics.length && diagnostics[lineIdx] > 0) {
            // Ne dessine des points que pour les erreurs (3) et les
            // avertissements (2) — aucun point de
            // gutter pour les infos (sévérité 1). Les infos reçoivent un
            // gribouillis dans la zone de texte mais aucun indicateur de
            // gutter.
            int sev = diagnostics[lineIdx];
            if (sev < 2) return; // ignore les diagnostics de sévérité info
            float dotR = 3.0f * density; // 3dp
            float dotCenterX = 5f * density + dotR;
            float dotY = y + lineHeight * 0.5f;
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

        // B9c : icône d'aperçu sur la rangée du CARET (pill + œil), façon
        // Android Studio. Dessinée en DERNIER : elle REMPLACE le numéro de
        // la rangée annotée (comme le run ▶ d'Android Studio remplace le
        // numéro de la ligne qu'il concerne).
        // ★ B15 (P2-2) : les actions de l'HÔTE passent AVANT l'œil — une
        // action posée sur la rangée du caret possède SA pill (l'œil,
        // fugace, suit le caret sur les autres lignes : la priorité va à
        // la donnée fixe de l'hôte).
        if (previewActionVisible && lineIdx == currentLine
                && (hostActions == null || hostActions.actionForLine(lineIdx) == null)) {
            drawActionPill(canvas, y, lineHeight, EditorIcons.Icon.EYE,
                    previewActionActive);
        }

        // ★ B15 (P2-2) : action de gouttière de l'HÔTE (point d'arrêt,
        // « exécuter ce test », flèche de diff…) — pill B9c + glyphe de
        // l'action, l'état actif étant relu au rendu (fournisseur vivant
        // de GutterAction). UNE action par ligne (actionForLine rend la
        // première posée — add retire la concurrente de la même ligne).
        if (hostActions != null) {
            GutterAction hostAction = hostActions.actionForLine(lineIdx);
            if (hostAction != null) {
                drawActionPill(canvas, y, lineHeight, hostAction.icon,
                        hostAction.isActive());
            }
        }
    }

    /**
     * B9c — dessine la pill + l'œil d'aperçu sur la rangée du caret.
     * ★ B15 : la MÊME pill sert les actions de gouttière de l'HÔTE
     * (drawActionPill) — géométrie et états partagés, seul le glyphe
     * change.
     *
     * <p>État INACTIF : pill de fond verre opaque (masque le numéro gras
     * dessous) + glyphe sobre (gutterText 0,9) ; état ACTIF (feuille
     * ouverte / action armée) : pill voilée d'accent + glyphe à
     * l'accent (theme.keyword — même accent que l'œil du fil d'Ariane
     * B6).</p>
     */
    private void drawActionPill(Canvas canvas, float rowTop, float lineHeight,
            EditorIcons.Icon icon, boolean active) {
        previewActionRect(metrics, density, rowTop, lineHeight, previewPillRect);
        float cx = previewPillRect.centerX();
        float cy = previewPillRect.centerY();
        float r = previewPillRect.height() * 0.5f;

        if (active) {
            bgPaint.setColor(theme.keyword);
            bgPaint.setAlpha(40); // voile discret de l'accent
        } else {
            bgPaint.setColor(theme.gutterBg);
            bgPaint.setAlpha(255);
        }
        canvas.drawRoundRect(previewPillRect, r, r, bgPaint);
        bgPaint.setAlpha(255);

        previewIconPaint.setColor(active
                ? theme.keyword
                : jo.codeeditor.view.EditorView.applyAlphaToColor(theme.gutterText, 0.9f));
        EditorIcons.draw(canvas, icon, cx, cy,
                Math.min(20f * density, previewPillRect.height() * 0.8f),
                previewIconPaint);
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
