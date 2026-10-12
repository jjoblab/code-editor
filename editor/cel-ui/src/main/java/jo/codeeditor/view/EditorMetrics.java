package jo.codeeditor.view;

import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Métriques de texte de l'éditeur : hauteur de ligne, largeur de caractère,
 * marges internes. Police monospace pour des dimensions de caractères
 * prévisibles.
 */
public class EditorMetrics {

    private float lineHeight;
    private float charWidth;
    private float padTop;
    private float padLeft;
    private float padRight;
    private float padBottom;
    private float gutterWidth;
    private float foldStripWidth;
    private float textSize;

    /**
     * Génération de police : incrémentée à CHAQUE changement de police ou de
     * taille de texte. Le cache de mises en page façonnées adressé par
     * contenu de la vue s'invalide sur cette révision : les propriétés de
     * police sont figées dans le TextPaint de chaque StaticLayout mémoïsé
     * au moment de sa construction.
     */
    private int fontRev;

    private final Paint textPaint;
    private final Paint gutterPaint;

    public EditorMetrics() {
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        setTextSize(14f);
    }

    /**
     * Recalcule toutes les métriques à partir de la taille de texte donnée
     * (en pixels).
     * <p>
     * {@code lineHeight} est dérivé de {@link Paint#getFontMetrics} afin de
     * correspondre à l'avance réelle entre deux rangées repliées d'un même
     * paragraphe — un calcul {@code textSize * 1.3} dérivait d'environ 1px et
     * l'écart s'accumulait en chevauchements/espacements visibles dès que le
     * retour à la ligne automatique intervenait.
     * <p>
     * La largeur du gutter INCLUT la bande de repli (zone des numéros de
     * ligne + bande de repli), de sorte que les chevrons de repli ne
     * chevauchent plus les numéros de ligne ; à l'origine la bande de repli
     * était dessinée dans la zone des numéros, le chevron recouvrait alors
     * le dernier chiffre.
     */
    public void setTextSize(float sizePx) {
        this.textSize = sizePx;
        fontRev++;   // génération de police changée — mises en page façonnées périmées.
        textPaint.setTextSize(sizePx);
        gutterPaint.setTextSize(sizePx * 0.85f);

        // Métriques réelles de la police — avance inter-rangées exacte au pixel.
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = (float) Math.ceil(fm.bottom - fm.top + fm.leading);
        // Monospace : tous les caractères ont la même largeur. measureText renvoie l'avance.
        charWidth = textPaint.measureText("M");
        padTop = lineHeight * 0.5f;
        padLeft = charWidth * 0.5f;
        padRight = charWidth * 0.5f;
        // Marge basse généreuse pour que la dernière ligne puisse défiler bien au-dessus de l'IME.
        padBottom = lineHeight * 6f;
        // La bande de repli est une colonne dédiée À DROITE des numéros de ligne.
        // Large d'environ 2 caractères pour laisser respirer le chevron.
        foldStripWidth = charWidth * 2f;
        // Gutter = zone des numéros de ligne (5 caractères : ~4 chiffres + 1
        // d'espacement) + bande de repli. Les numéros sont alignés à droite
        // dans la zone des numéros (c.-à-d. terminent à gutterWidth - foldStripWidth).
        gutterWidth = charWidth * 5 + foldStripWidth;
    }

    public float getLineHeight() { return lineHeight; }
    public float getCharWidth() { return charWidth; }
    public float getPadTop() { return padTop; }
    public float getPadLeft() { return padLeft; }
    public float getPadRight() { return padRight; }
    public float getPadBottom() { return padBottom; }
    public float getGutterWidth() { return gutterWidth; }
    public float getFoldStripWidth() { return foldStripWidth; }
    public float getTextSize() { return textSize; }

    // Alias historiques
    public float getPaddingTop() { return padTop; }
    public float getPaddingLeft() { return padLeft; }

    public Paint getTextPaint() { return textPaint; }
    public Paint getGutterPaint() { return gutterPaint; }
    public Typeface getTypeface() { return textPaint.getTypeface(); }
    /** Génération de police (incrémentée à chaque changement de police/taille de texte). */
    public int getFontRevision() { return fontRev; }
    public void setTypeface(Typeface tf) {
        fontRev++;   // génération de police changée — mises en page façonnées périmées.
        textPaint.setTypeface(tf);
        gutterPaint.setTypeface(tf);
    }

}
