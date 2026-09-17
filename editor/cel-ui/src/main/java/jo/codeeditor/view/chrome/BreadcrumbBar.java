package jo.codeeditor.view.chrome;

import jo.codeeditor.view.EditorView;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Barre de navigation à fil d'Ariane — affiche la chaîne de portées
 * courante (fichier › classe › méthode) au-dessus de l'éditeur, à la
 * manière d'IntelliJ/CodeAssist.
 *
 * <p>Suit la position du caret et met à jour les segments du fil d'Ariane
 * via le {@link jo.codeeditor.lang.provider.SymbolProvider} lorsqu'il est
 * disponible.
 *
 * <p>Inspirée de l'EditorBreadcrumbBar de CodeAssist.
 */
public class BreadcrumbBar extends View {

    private String[] segments = new String[0];
    private final Paint textPaint;
    private final Paint chevronPaint;
    private final float density;
    private int barHeightPx;
    private EditorView editorView;
    private Runnable updateRunnable;
    private long lastUpdate = 0;
    private static final long DEBOUNCE_MS = 200;

    public BreadcrumbBar(Context context) {
        this(context, null);
    }

    public BreadcrumbBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        barHeightPx = (int) (28 * density);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(12 * density);
        textPaint.setTypeface(Typeface.MONOSPACE);
        chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chevronPaint.setTextSize(10 * density);
        setWillNotDraw(false);
    }

    /**
     * Lie cette barre de fil d'Ariane à un EditorView. La barre suit la
     * position du caret et met à jour les segments via le SymbolProvider.
     */
    public void bind(EditorView view) {
        this.editorView = view;
        // Utilise addOnSelectionChangedListener (et non un setter) pour ne pas
        // remplacer le listener principal (ex. barre d'état de MainActivity).
        view.addOnSelectionChangedListener((line, col, isCursor) -> {
            long now = System.currentTimeMillis();
            if (now - lastUpdate < DEBOUNCE_MS) return;
            lastUpdate = now;
            postDelayed(this::updateSegments, DEBOUNCE_MS);
        });
    }

    /** Met à jour les segments du fil d'Ariane depuis le SymbolProvider du langage courant. */
    private void updateSegments() {
        if (editorView == null || editorView.getSession() == null) {
            segments = new String[0];
            invalidate();
            return;
        }
        String fileName = editorView.getSession().getLanguage() != null
            ? editorView.getSession().getLanguage() : "file";
        // Tente d'obtenir les symboles depuis le SPI Language.
        jo.codeeditor.lang.Language lang = editorView.getLanguage();
        if (lang != null && lang.getSymbolProvider() != null) {
            try {
                java.util.List<jo.codeeditor.lang.model.Symbol> symbols =
                    lang.getSymbolProvider().symbols(editorView.getSession().getText());
                int caret = editorView.getSession().getSelection().start;
                // Recherche les symboles englobants (offset <= caret < end).
                java.util.List<jo.codeeditor.lang.model.Symbol> enclosing = new java.util.ArrayList<>();
                for (jo.codeeditor.lang.model.Symbol s : symbols) {
                    if (s.offset <= caret) {
                        enclosing.add(s);
                    }
                }
                // Tri par offset (le plus externe en premier).
                enclosing.sort((a, b) -> Integer.compare(a.offset, b.offset));
                // Construit les segments : fichier › classe › méthode.
                java.util.List<String> segs = new java.util.ArrayList<>();
                segs.add(fileName);
                for (jo.codeeditor.lang.model.Symbol s : enclosing) {
                    segs.add(s.name);
                }
                segments = segs.toArray(new String[0]);
            } catch (Exception e) {
                segments = new String[]{fileName};
            }
        } else {
            segments = new String[]{fileName};
        }
        invalidate();
    }

    /** Définit les segments directement (usage manuel sans SymbolProvider). */
    public void setSegments(String[] segs) {
        this.segments = segs != null ? segs : new String[0];
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(barHeightPx, MeasureSpec.EXACTLY));
    }

    // Paints préalloués — onDraw allouait auparavant deux Paint par frame
    // (lint DrawAllocation), provoquant du GC churn sur appareils modestes
    // chaque fois que le fil d'Ariane était visible.
    private final Paint bgPaint = new Paint();
    private final Paint borderPaint = new Paint();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (segments.length == 0) return;
        float padX = 12 * density;
        float padY = (getHeight() - textPaint.getTextSize()) * 0.5f
            + textPaint.getTextSize() * 0.35f;
        // Arrière-plan.
        bgPaint.setColor(0xFF1E1E1E); // sombre
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        // Bordure inférieure.
        borderPaint.setColor(0xFF323232);
        borderPaint.setStrokeWidth(1f);
        canvas.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1, borderPaint);

        float x = padX;
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                // Dessine le chevron ›.
                chevronPaint.setColor(0xFF808080);
                canvas.drawText("\u203A", x, padY, chevronPaint);
                x += chevronPaint.measureText("\u203A") + 6 * density;
            }
            // Dessine le texte du segment.
            boolean isLast = (i == segments.length - 1);
            textPaint.setColor(isLast ? 0xFFD4D4D4 : 0xFF858585);
            textPaint.setTypeface(isLast ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                : Typeface.MONOSPACE);
            canvas.drawText(segments[i], x, padY, textPaint);
            x += textPaint.measureText(segments[i]) + 6 * density;
        }
    }
}
