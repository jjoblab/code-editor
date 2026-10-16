package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.icons.EditorIcons;
import jo.codeeditor.view.preview.EditorPreviewDrop;
import jo.codeeditor.view.preview.EditorPreviewDropGeometry;
import jo.codeeditor.view.preview.EditorPreviewHost;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Calendar;

/**
 * Painter de la carte d'aperçu « goutte d'eau » (B27) — le chrome
 * complet dessiné sur le canvas de l'éditeur : tige de liaison œil→
 * goutte, losange-goutte pivoté (coin asymétrique), carte de verre à
 * coin bas-gaubre arrondi en goutte, en-tête (titre + onglets du hôte
 * + fermeture), mini-téléphone (bezel + écran clair + barre d'état),
 * corps rendu par le hôte ({@code drawDropPreview}, clippé à l'écran),
 * badge d'exécution et pied à chips (rendu vivant + échelle).
 *
 * <p>Le placement vient de {@link EditorPreviewDrop#placement()}
 * (source unique — le hit-test du contrôleur partage la même formule).
 * Le compactage s'applique par une échelle du canvas autour du coin
 * haut-gauche de la carte ; le hôte dessine en coordonnées LOCALES et
 * l'échelle s'applique à tout (le « fit » de l'aperçu suit).</p>
 *
 * <p><b>Voile d'eau</b> (retour terrain B27) : quand la carte recouvre
 * encore un popup après tous les niveaux d'esquive, le contrôleur la
 * voile — le painter enveloppe losange + carte dans une couche alpha
 * animée (saveLayerAlpha). La TIGE reste opaque : l'ancre œil↔goutte
 * reste lisible quand l'eau devient translucide (le painter appelle
 * aussi updateVeil une fois par frame — le seul passage garanti).</p>
 *
 * <p>Peints dédiés (le pipeline partage textPaint — AGENT.md) ;
 * rectangles réutilisés, zéro allocation par frame en régime établi.</p>
 *
 * @author jjoblab
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorPreviewDropPainter {

    private final EditorView view;

    /** Paint de remplissage dédié (carte, pills, écran). */
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Paint de trait dédié (bordures, tige). */
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Paint de libellés dédié (titre, onglets, chips, badge). */
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Chemin des rectangles à coins asymétriques (Canvas n'expose pas
     *  drawRoundRect(RectF, float[], Paint) — Path.addRoundRect, oui). */
    private final Path shapePath = new Path();
    /** Radii de la carte (tl, tr, br, bl) — coin « goutte » bas-gauche. */
    private final float[] cardRadii = new float[8];
    /** Radii du losange-goutte (CSS 4/2/14/2 pivoté de 45°). */
    private final float[] dropRadii = new float[8];
    /** Rectangles réutilisés (zéro allocation par frame). */
    private final RectF scratch = new RectF();
    private final RectF phoneScreen = new RectF();
    /** Bornes de la couche de voile (marge pour l'ombre et le losange). */
    private final RectF veilRect = new RectF();

    /** Heure affichée dans la barre d'état du mini-téléphone (cache minute). */
    private String statusTime = "";
    private long statusTimeMinute = -1L;

    public EditorPreviewDropPainter(EditorView view) {
        this.view = view;
    }

    /** Point d'entrée — appelé par le pipeline de rendu (fondu B7). */
    public void drawCard(Canvas canvas) {
        EditorPreviewDrop drop = view.preview.drop();
        EditorPreviewDropGeometry.Placement p = drop.placement();
        if (p == null) return;
        // Voile d'eau : hystérésis mis à jour ici, une fois par frame —
        // le painter est le seul passage garanti quand la carte est
        // visible (le fondu B7 appelle ce peintre à chaque frame).
        drop.updateVeil(p);
        EditorPreviewHost host = view.preview.getHost();
        float density = view.getResources().getDisplayMetrics().density;
        float cardW = EditorPreviewDrop.CARD_W_DP * density;
        float cardH = EditorPreviewDrop.CARD_H_DP * density;

        // ── Tige de liaison (SOUS la carte, HORS voile : l'ancre ─────
        // reste opaque et lisible quand l'eau se voile) : de l'œil ──
        // vers la goutte. ───────────────────────────────────────────
        if (p.stemVisible) {
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(1f, 1.5f * density));
            strokePaint.setColor(view.applyAlphaToColor(view.theme.keyword, 0.5f));
            canvas.drawLine(p.stemX0, p.stemY0, p.stemX1, p.stemY1, strokePaint);
        }

        // ── Voile d'eau : losange + carte sous une couche alpha ─────
        // globale (la carte entière devient eau translucide — pas de ─
        // voile partiel en bande, plus lisible et plus simple). ──────
        float veil = drop.veilAlpha();
        int veilSave = -1;
        if (veil < 0.999f) {
            float margin = 24f * density; // l'ombre et le losange débordent
            float half = EditorPreviewDrop.DROP_HALF_DP * density * p.scale;
            veilRect.set(p.cardLeft - half - margin, p.cardTop - margin,
                    p.cardLeft + p.visualWidth() + margin,
                    p.cardTop + p.visualHeight() + margin);
            veilSave = canvas.saveLayerAlpha(veilRect, (int) (255f * veil));
        }

        // ── Échelle de compactage + translation vers la carte. ──────
        int save = canvas.save();
        canvas.translate(p.cardLeft, p.cardTop);
        canvas.scale(p.scale, p.scale);

        // ── Losange-goutte : carré pivoté 45°, coin arrondi vers l'œil. ──
        // Dessiné AVANT le corps : la carte recouvre sa moitié interne,
        // seule la pointe externe reste visible (z-index −1 maquette).
        float half = EditorPreviewDrop.DROP_HALF_DP * density;
        float dropCx = 0f; // le losange chevauche le bord gauche de la carte
        float dropCy = p.dropLocalY;
        int dropSave = canvas.save();
        canvas.translate(dropCx, dropCy);
        canvas.rotate(45f);
        scratch.set(-half, -half, half, half);
        setRadii(dropRadii, 4f * density, 2f * density, 14f * density, 2f * density);
        shapePath.reset();
        shapePath.addRoundRect(scratch, dropRadii, Path.Direction.CW);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(view.theme.glassBg);
        fillPaint.setShadowLayer(8f * density, 0f, 3f * density, 0x50000000);
        canvas.drawPath(shapePath, fillPaint);
        fillPaint.setShadowLayer(0f, 0f, 0f, 0);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawPath(shapePath, strokePaint);
        canvas.restoreToCount(dropSave);

        // ── Corps de la carte : verre, coins 18/18/18/4 (goutte). ────
        scratch.set(0f, 0f, cardW, cardH);
        setRadii(cardRadii, 18f * density, 18f * density, 18f * density, 4f * density);
        shapePath.reset();
        shapePath.addRoundRect(scratch, cardRadii, Path.Direction.CW);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(view.theme.glassBg);
        fillPaint.setShadowLayer(18f * density, 0f, 6f * density, 0x99000000);
        canvas.drawPath(shapePath, fillPaint);
        fillPaint.setShadowLayer(0f, 0f, 0f, 0);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawPath(shapePath, strokePaint);

        // ── En-tête : titre + onglets + fermeture. ───────────────────
        drawHeader(canvas, drop, density, cardW);

        // ── Mini-téléphone : bezel + écran + barre d'état + hôte. ────
        drawPhone(canvas, drop, host, density, cardW, cardH);

        // ── Pied : chips « rendu vivant » + échelle. ─────────────────
        drawFooter(canvas, density, cardW, cardH, p.scale);

        canvas.restoreToCount(save);
        // ── Fin du voile : la couche alpha retombe, la tige (hors ─────
        // couche) n'a jamais été voilée. ─────────────────────────────
        if (veilSave >= 0) {
            canvas.restoreToCount(veilSave);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // En-tête
    // ════════════════════════════════════════════════════════════════

    private void drawHeader(Canvas canvas, EditorPreviewDrop drop,
            float density, float cardW) {
        float headerH = EditorPreviewDrop.HEADER_H_DP * density;

        // Titre « APERÇU » — petites capitales espacées (maquette).
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextSize(10f * density);
        labelPaint.setLetterSpacing(0.12f);
        labelPaint.setColor(view.theme.gutterText);
        canvas.drawText("APERÇU", 12f * density, headerH * 0.62f, labelPaint);
        labelPaint.setLetterSpacing(0f);

        // Onglets du hôte — rects partagés avec le hit-test.
        RectF[] tabs = drop.tabRects();
        java.util.List<String> labels = drop.tabLabels();
        for (int i = 0; i < tabs.length && i < labels.size(); i++) {
            boolean active = i == drop.getSelectedTab();
            RectF r = tabs[i];
            // Pilule : voile d'accent si active, bordure discrète sinon.
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(active
                    ? EditorView.applyAlphaToColor(view.theme.keyword, 0.14f)
                    : 0x00000000);
            if (active) {
                fillPaint.setShadowLayer(0f, 0f, 0f, 0);
                canvas.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, fillPaint);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(1f);
                strokePaint.setColor(EditorView.applyAlphaToColor(view.theme.keyword, 0.35f));
                canvas.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, strokePaint);
            }
            labelPaint.setTextSize(10.5f * density);
            labelPaint.setColor(active ? view.theme.keyword : view.theme.gutterText);
            float textY = r.top + (r.height() + labelPaint.getTextSize()) * 0.5f - 2f * density;
            canvas.drawText(labels.get(i), r.left + 9f * density, textY, labelPaint);
        }

        // Séparateur bas d'en-tête.
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawLine(0f, headerH, cardW, headerH, strokePaint);

        // Fermeture ✕ — icône du moteur, cible 32dp (rect partagé).
        RectF close = drop.closeRect();
        strokePaint.setColor(EditorView.applyAlphaToColor(view.theme.gutterText, 0.85f));
        strokePaint.setStyle(Paint.Style.STROKE);
        EditorIcons.draw(canvas, EditorIcons.Icon.CLOSE,
                close.centerX(), close.centerY(), 14f * density, strokePaint);
    }

    // ════════════════════════════════════════════════════════════════
    // Mini-téléphone
    // ════════════════════════════════════════════════════════════════

    private void drawPhone(Canvas canvas, EditorPreviewDrop drop,
            EditorPreviewHost host, float density, float cardW, float cardH) {
        float margin = EditorPreviewDrop.PHONE_MARGIN_DP * density;
        float headerH = EditorPreviewDrop.HEADER_H_DP * density;
        float footerH = EditorPreviewDrop.FOOTER_H_DP * density;
        float phoneX = margin;
        float phoneY = headerH + margin;
        float phoneW = cardW - 2f * margin;
        float phoneH = cardH - headerH - footerH - 2f * margin;
        float bezel = Math.max(1f, 2f * density);
        float radius = 16f * density;

        // Bezel : bord épais neutre autour de l'écran.
        scratch.set(phoneX, phoneY, phoneX + phoneW, phoneY + phoneH);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xFF2A3038);
        canvas.drawRoundRect(scratch, radius, radius, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(scratch, radius, radius, strokePaint);

        // Écran : CLAIR (l'app prévisualisée vit sur fond clair —
        // contraste voulu avec la carte sombre, miroir maquette).
        phoneScreen.set(phoneX + bezel, phoneY + bezel,
                phoneX + phoneW - bezel, phoneY + phoneH - bezel);
        float inner = radius - bezel * 0.5f;
        if (inner < 0f) inner = 0f;
        fillPaint.setColor(0xFFF4F5F7);
        canvas.drawRoundRect(phoneScreen, inner, inner, fillPaint);

        // Corps du hôte — clippé à l'écran, coordonnées locales.
        if (host != null) {
            int save = canvas.save();
            canvas.clipRect(phoneScreen);
            canvas.drawRoundRect(phoneScreen, inner, inner, fillPaint);
            host.drawDropPreview(canvas, phoneScreen.left, phoneScreen.top,
                    phoneScreen.width(), phoneScreen.height(),
                    drop.getSelectedTab());
            canvas.restoreToCount(save);
        }

        // Barre d'état : bande pâle en haut de l'écran (heure réelle).
        float statusH = 12f * density;
        fillPaint.setColor(0xFFE8EAED);
        int saveStatus = canvas.save();
        canvas.clipRect(phoneScreen);
        scratch.set(phoneScreen.left, phoneScreen.top,
                phoneScreen.right, phoneScreen.top + statusH);
        canvas.drawRoundRect(scratch, inner, inner, fillPaint);
        canvas.drawRect(scratch.left, scratch.top + statusH * 0.5f,
                scratch.right, scratch.bottom, fillPaint);
        labelPaint.setTextSize(6.5f * density);
        labelPaint.setColor(0xFF5F6368);
        canvas.drawText(statusTime(density), phoneScreen.left + 8f * density,
                phoneScreen.top + statusH * 0.8f, labelPaint);
        // Glyphe batterie stylisé à droite.
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(0.75f, 0.75f * density));
        strokePaint.setColor(0xFF5F6368);
        float bx = phoneScreen.right - 8f * density - 4f * density;
        float by = phoneScreen.top + statusH * 0.5f - 2.5f * density;
        canvas.drawRoundRect(bx, by, bx + 4f * density, by + 5f * density,
                1f * density, 1f * density, strokePaint);
        canvas.restoreToCount(saveStatus);

        // Badge d'exécution : bas de l'écran, centré (hôte).
        if (host != null) {
            String badge = host.previewDropBadge(drop.getSelectedTab());
            if (badge != null && !badge.isEmpty()) {
                labelPaint.setTextSize(7.5f * density);
                labelPaint.setColor(0xFF5F6368);
                float textW = labelPaint.measureText(badge);
                float maxW = phoneW - 12f * density;
                if (textW > maxW) {
                    // Ellipse honnête : coupe au milieu avec points de
                    // suspension (le badge reste une seule ligne).
                    badge = ellipsizeMiddle(badge, labelPaint, maxW);
                    textW = labelPaint.measureText(badge);
                }
                float chipW = textW + 16f * density;
                scratch.set(phoneScreen.centerX() - chipW * 0.5f,
                        phoneScreen.bottom - 14f * density,
                        phoneScreen.centerX() + chipW * 0.5f,
                        phoneScreen.bottom - 14f * density + 11f * density);
                fillPaint.setColor(0xFFE8EAED);
                canvas.drawRoundRect(scratch, scratch.height() * 0.5f,
                        scratch.height() * 0.5f, fillPaint);
                canvas.drawText(badge, scratch.left + 8f * density,
                        scratch.bottom - 2.5f * density, labelPaint);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Pied à chips
    // ════════════════════════════════════════════════════════════════

    private void drawFooter(Canvas canvas, float density,
            float cardW, float cardH, float scale) {
        float footerH = EditorPreviewDrop.FOOTER_H_DP * density;
        float footerTop = cardH - footerH;
        float cy = footerTop + footerH * 0.5f;

        // Séparateur haut de pied.
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawLine(0f, footerTop, cardW, footerTop, strokePaint);

        labelPaint.setTextSize(9f * density);
        labelPaint.setColor(view.theme.gutterText);

        // Chip « rendu vivant » — pastille + point accent + texte,
        // bordure verte discrète (miroir maquette « ● rendu vivant »).
        String live = view.getResources().getString(
                jo.codeeditor.ui.R.string.cel_preview_drop_live);
        float liveW = labelPaint.measureText(live) + 8f * density + 16f * density;
        float chipH = 14f * density;
        scratch.set(12f * density, cy - chipH * 0.5f,
                12f * density + liveW, cy + chipH * 0.5f);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x00000000);
        canvas.drawRoundRect(scratch, chipH * 0.5f, chipH * 0.5f, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(EditorView.applyAlphaToColor(view.theme.logSuccess, 0.4f));
        canvas.drawRoundRect(scratch, chipH * 0.5f, chipH * 0.5f, strokePaint);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(view.theme.logSuccess);
        canvas.drawCircle(scratch.left + 8f * density, cy, 2.5f * density, fillPaint);
        canvas.drawText(live, scratch.left + 8f * density + 5f * density,
                cy + labelPaint.getTextSize() * 0.35f, labelPaint);

        // Chip d'échelle — « fit · NN % » (compactage courant).
        String fit = "fit · " + Math.round(scale * 100f) + " %";
        float fitW = labelPaint.measureText(fit) + 16f * density;
        scratch.set(cardW - 12f * density - fitW, cy - chipH * 0.5f,
                cardW - 12f * density, cy + chipH * 0.5f);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(EditorView.applyAlphaToColor(view.theme.gutterText, 0.08f));
        canvas.drawRoundRect(scratch, chipH * 0.5f, chipH * 0.5f, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        strokePaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(scratch, chipH * 0.5f, chipH * 0.5f, strokePaint);
        canvas.drawText(fit, scratch.left + 8f * density,
                cy + labelPaint.getTextSize() * 0.35f, labelPaint);
    }

    // ── Aides ──────────────────────────────────────────────────────

    /** Pose les 8 radii Android depuis les 4 coins (tl, tr, br, bl). */
    private static void setRadii(float[] out, float tl, float tr, float br, float bl) {
        out[0] = out[1] = tl;
        out[2] = out[3] = tr;
        out[4] = out[5] = br;
        out[6] = out[7] = bl;
    }

    /** Heure courante « HH:mm » (rafraîchie à la minute — cache). */
    private String statusTime(float density) {
        long minute = System.currentTimeMillis() / 60_000L;
        if (minute != statusTimeMinute || statusTime.isEmpty()) {
            statusTimeMinute = minute;
            Calendar cal = Calendar.getInstance();
            statusTime = String.format(java.util.Locale.ROOT, "%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        }
        return statusTime;
    }

    /** Coupe au milieu avec points de suspension (badge trop long). */
    private static String ellipsizeMiddle(String s, Paint p, float maxW) {
        if (p.measureText(s) <= maxW || s.length() < 5) return s;
        int half = s.length() / 2;
        int lo = 1, hi = half; // longueur du préfixe conservé (binaire)
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String candidate = s.substring(0, mid) + "…" + s.substring(s.length() - mid);
            if (p.measureText(candidate) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo) + "…" + s.substring(s.length() - lo);
    }
}
