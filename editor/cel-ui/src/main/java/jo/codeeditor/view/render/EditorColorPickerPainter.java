package jo.codeeditor.view.render;

import jo.codeeditor.view.ColorPicker;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorColorPickerPopup;
import jo.codeeditor.view.popup.EditorPopupAnchors;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Painter du sélecteur de couleur (B26) — carte de verre ancrée au
 * code hexadécimal tapé : en-tête (swatch d'origine → swatch courant
 * + libellés), champ saturation/valeur (dégradés imbriqués blanc →
 * teinte, transparent → noir), bande de teinte (spectre 0..360°) et
 * palette de teintes courantes. Les markers (cercle du champ, curseur
 * de teinte) reflètent l'état {@code colorPickerCurrent} en cours
 * d'édition, mis à jour vivant par le drag.
 *
 * <p>La géométrie vient de
 * {@link EditorPopupAnchors#colorPickerMetrics(EditorView)} (source
 * de vérité unique — painter, hit-test et tests). Les shaders de
 * dégradé sont mis en cache et reconstruits uniquement quand la
 * teinte ou le rectangle change (le drag de teinte invalide, le drag
 * saturation/valeur ne les touche pas).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorColorPickerPainter {

    private final EditorView view;

    /** Paint de remplissage dédié (le pipeline partage textPaint — AGENT.md). */
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Paint de trait dédié (markers, bordures). */
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Paint de libellés dédié (hex de l'en-tête). */
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Cache des shaders (rebuilt si teinte/rectangle change) ──
    private LinearGradient svShader;
    private float svShaderHue = -1f;
    private final RectF svShaderRect = new RectF();
    /** Voile vertical transparent → noir du champ SV (shader dédié). */
    private final Paint svFadePaint = new Paint();
    private LinearGradient svFadeShader;
    private final RectF svFadeRect = new RectF();
    private LinearGradient hueShader;
    private final RectF hueShaderRect = new RectF();
    /** HSV de travail réutilisé (zéro allocation par frame). */
    private final float[] hsv = new float[3];

    /** Rectangle de travail réutilisé (zéro allocation par frame). */
    private final RectF scratch = new RectF();

    public EditorColorPickerPainter(EditorView view) {
        this.view = view;
    }

    /** Point d'entrée — appelé par le pipeline de rendu. */
    public void drawColorPicker(Canvas canvas) {
        if (!view.colorPickerVisible) return;
        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        if (m == null) return;

        // ── Carte de verre (motif drawNavMenu) ───────────────────
        scratch.set(m.x, m.y, m.x + m.w, m.y + m.h);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(scratch, 8 * m.density, 8 * m.density, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(scratch, 8 * m.density, 8 * m.density, strokePaint);

        drawHeader(canvas, m);
        drawSvField(canvas, m);
        drawHueStrip(canvas, m);
        drawPalette(canvas, m);
    }

    // ── En-tête : origine → courant ─────────────────────────────

    private void drawHeader(Canvas canvas,
            EditorPopupAnchors.ColorPickerMetrics m) {
        float swatch = 22 * m.density;
        float top = m.y + 12 * m.density + (m.headerH - swatch) * 0.5f;
        labelPaint.setTypeface(Typeface.DEFAULT);
        labelPaint.setTextSize(13 * m.density);

        // Swatch d'ORIGINE + son libellé hex.
        scratch.set(m.x + 12 * m.density, top,
                m.x + 12 * m.density + swatch, top + swatch);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(view.colorPickerOriginal);
        canvas.drawRoundRect(scratch, 4 * m.density, 4 * m.density, fillPaint);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(scratch, 4 * m.density, 4 * m.density, strokePaint);
        String originalHex = ColorPicker.format(
                view.colorPickerOriginal, view.colorPickerDigits);
        labelPaint.setColor(view.theme.textColor);
        canvas.drawText(originalHex,
                scratch.right + 6 * m.density,
                top + swatch - (swatch - labelPaint.getTextSize()) * 0.5f,
                labelPaint);

        // Swatch COURANT (vivant pendant le drag) + son libellé hex,
        // alignés à droite de la carte.
        String currentHex = ColorPicker.format(
                view.colorPickerCurrent, view.colorPickerDigits);
        float labelW = labelPaint.measureText(currentHex);
        scratch.set(m.x + m.w - 12 * m.density - swatch - 6 * m.density - labelW,
                top, m.x + m.w - 12 * m.density - 6 * m.density - labelW,
                top + swatch);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(view.colorPickerCurrent);
        canvas.drawRoundRect(scratch, 4 * m.density, 4 * m.density, fillPaint);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(scratch, 4 * m.density, 4 * m.density, strokePaint);
        labelPaint.setColor(view.theme.textColor);
        canvas.drawText(currentHex, scratch.right + 6 * m.density,
                top + swatch - (swatch - labelPaint.getTextSize()) * 0.5f,
                labelPaint);
    }

    // ── Champ saturation/valeur ─────────────────────────────────

    private void drawSvField(Canvas canvas,
            EditorPopupAnchors.ColorPickerMetrics m) {
        Color.colorToHSV(view.colorPickerCurrent, hsv);
        float hue = hsv[0];

        // Fond : dégradé horizontal blanc → teinte courante.
        if (svShader == null || svShaderHue != hue
                || svShaderRect.width() != m.sv.width()
                || svShaderRect.height() != m.sv.height()) {
            int pure = Color.HSVToColor(new float[]{hue, 1f, 1f});
            svShader = new LinearGradient(m.sv.left, m.sv.top,
                    m.sv.right, m.sv.top,
                    Color.WHITE, pure, Shader.TileMode.CLAMP);
            svShaderHue = hue;
            svShaderRect.set(m.sv);
        }
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(svShader);
        canvas.drawRect(m.sv, fillPaint);
        fillPaint.setShader(null);
        // Voile vertical : transparent → noir (la valeur décroît vers
        // le bas — l'ordonnée du marker suit le même axe). Shader
        // dédié mis en cache (ne dépend pas de la teinte).
        if (svFadeShader == null || svFadeRect.width() != m.sv.width()
                || svFadeRect.height() != m.sv.height()) {
            svFadeShader = new LinearGradient(m.sv.left, m.sv.top,
                    m.sv.left, m.sv.bottom,
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
            svFadeRect.set(m.sv);
        }
        svFadePaint.setShader(svFadeShader);
        canvas.drawRect(m.sv, svFadePaint);

        // Marker : double cercle (liseré sombre + blanc) lisible sur
        // tout fond.
        float cx = m.sv.left + hsv[1] * m.sv.width();
        float cy = m.sv.top + (1f - hsv[2]) * m.sv.height();
        float r = 6 * m.density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f);
        strokePaint.setColor(0xFF1F1F1F);
        canvas.drawCircle(cx, cy, r, strokePaint);
        strokePaint.setStrokeWidth(1.5f);
        strokePaint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, r - 1.5f * m.density, strokePaint);
        // Bordure du champ.
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawRect(m.sv, strokePaint);
    }

    // ── Bande de teinte ─────────────────────────────────────────

    private void drawHueStrip(Canvas canvas,
            EditorPopupAnchors.ColorPickerMetrics m) {
        if (hueShader == null || hueShaderRect.width() != m.hue.width()
                || hueShaderRect.height() != m.hue.height()) {
            hueShader = new LinearGradient(m.hue.left, m.hue.top,
                    m.hue.right, m.hue.top,
                    new int[]{Color.RED, Color.YELLOW, Color.GREEN,
                            Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED},
                    new float[]{0f, 1f / 6f, 2f / 6f, 3f / 6f,
                            4f / 6f, 5f / 6f, 1f},
                    Shader.TileMode.CLAMP);
            hueShaderRect.set(m.hue);
        }
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(hueShader);
        canvas.drawRect(m.hue, fillPaint);
        fillPaint.setShader(null);

        Color.colorToHSV(view.colorPickerCurrent, hsv);
        float cx = m.hue.left + hsv[0] / 360f * m.hue.width();
        // Curseur : deux traits verticaux encadrant la position.
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f);
        strokePaint.setColor(0xFF1F1F1F);
        canvas.drawLine(cx, m.hue.top - 2 * m.density,
                cx, m.hue.bottom + 2 * m.density, strokePaint);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(Color.WHITE);
        canvas.drawLine(cx, m.hue.top - 2 * m.density,
                cx, m.hue.bottom + 2 * m.density, strokePaint);
        // Bordure de la bande.
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawRect(m.hue, strokePaint);
    }

    // ── Palette de teintes ──────────────────────────────────────

    private void drawPalette(Canvas canvas,
            EditorPopupAnchors.ColorPickerMetrics m) {
        float r = 4 * m.density;
        for (int i = 0; i < EditorColorPickerPopup.PALETTE.length; i++) {
            int col = i % m.paletteCols;
            int row = i / m.paletteCols;
            float left = m.palette.left + col * m.cell;
            float top = m.palette.top + row * m.paletteRowH;
            scratch.set(left, top, left + m.cell, top + m.cell);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(EditorColorPickerPopup.PALETTE[i]);
            canvas.drawRoundRect(scratch, r, r, fillPaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            // La teinte sélectionnée : liseré accent ; les autres : bordure discrète.
            if (sameTint(EditorColorPickerPopup.PALETTE[i], view.colorPickerCurrent)) {
                strokePaint.setStrokeWidth(2f);
                strokePaint.setColor(view.theme.caret);
            } else {
                strokePaint.setStrokeWidth(1f);
                strokePaint.setColor(view.theme.glassBorder);
            }
            canvas.drawRoundRect(scratch, r, r, strokePaint);
        }
    }

    /** Deux couleurs partagent-elles la même teinte RGB (alpha exclu) ? */
    private static boolean sameTint(int a, int b) {
        return (a & 0x00FFFFFF) == (b & 0x00FFFFFF);
    }
}
