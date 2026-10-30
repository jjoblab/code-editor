package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.kit.PopupCard;
import jo.codeeditor.view.popup.kit.PopupTokens;
import jo.codeeditor.view.state.CompletionState;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.List;

/**
 * Rendu de la documentation de complétion (phase 4) : les MÊMES trois
 * blocs servent le PANNEAU intégré (vue large) et le BOTTOM SHEET
 * (vue étroite) — bande SIGNATURE teintée (le fence du serveur,
 * enfin affiché), RÉSUMÉ, lignes PARAM/RETOUR indentées. Les rangées
 * viennent de l'ancrage (source unique) : ce painter ne mesure rien.
 * Méthodes STATIQUES (vue et carte passées en paramètre).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
final class EditorCompletionDocPainter {
    private EditorCompletionDocPainter() {}

    /**
     * Dessine les rangées de documentation dans un rectangle donné
     * (panneau du popup ou corps du sheet).
     *
     * @param view la vue hôte (paints, thème)
     * @param card la carte de popup partagée (bandes teintées)
     * @param canvas le canvas de la frame
     * @param scratchRect RectF de travail réutilisé (aucune allocation par frame)
     * @param rows les rangées typées (ancrage : source unique)
     * @param x la gauche du bloc (px)
     * @param y le haut du bloc (px)
     * @param w la largeur du bloc (px)
     * @param padX la marge horizontale intérieure (px)
     * @param density la densité écran
     * @param docRowH la hauteur d'une rangée (px)
     */
    static void drawRows(EditorView view, PopupCard card, Canvas canvas,
            RectF scratchRect, List<CompletionState.DocRow> rows, float x,
            float y, float w, float padX, float density, float docRowH) {
        // Bande teintée couvrant les rangées SIG de tête.
        int sigRows = 0;
        for (CompletionState.DocRow r : rows) {
            if (r.type == CompletionState.DocRow.SIG) sigRows++;
            else break;
        }
        if (sigRows > 0) {
            RectF bande = scratchRect;
            bande.set(x, y, x + w, y + sigRows * docRowH + 4 * density);
            card.drawBand(canvas, bande, PopupCard.BAND_TINTED);
        }
        view.textPaint.setTypeface(view.metrics.getTypeface());
        for (int i = 0; i < rows.size(); i++) {
            CompletionState.DocRow r = rows.get(i);
            float baseline = y + 4 * density + i * docRowH + docRowH * 0.75f;
            switch (r.type) {
                case CompletionState.DocRow.SIG:
                    view.textPaint.setColor(view.theme.func);
                    canvas.drawText(r.text, x + padX, baseline, view.textPaint);
                    break;
                case CompletionState.DocRow.PARAM: {
                    boolean isTag = !r.text.isEmpty() && r.text.charAt(0) == '@';
                    view.textPaint.setColor(isTag
                            ? view.theme.keyword : view.theme.textColor);
                    canvas.drawText(r.text, x + padX + 8 * density, baseline,
                            view.textPaint);
                    break;
                }
                case CompletionState.DocRow.RETURNS:
                    view.textPaint.setColor(view.theme.gutterText);
                    view.textPaint.setFakeBoldText(true);
                    canvas.drawText(r.text, x + padX + 8 * density, baseline,
                            view.textPaint);
                    view.textPaint.setFakeBoldText(false);
                    break;
                default:
                    view.textPaint.setColor(view.theme.textColor);
                    canvas.drawText(r.text, x + padX, baseline, view.textPaint);
                    break;
            }
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    /**
     * Dessine le BOTTOM SHEET de documentation (vue étroite) : la
     * documentation vit en BAS de vue quand le panneau intégré n'a pas
     * la place — la liste de complétion garde toutes ses rangées.
     * Géométrie : {@code EditorView.completionDocSheetMetrics()}.
     */
    static void drawSheet(EditorView view, PopupCard card, Canvas canvas,
            Path scratchPath, RectF scratchRect) {
        if (!view.completion.visible) return;
        float[] m = view.completionDocSheetMetrics();
        if (m == null) return;
        float density = view.getResources().getDisplayMetrics().density;
        // Sheet : coins du HAUT arrondis (jeton sheet), fond verre.
        float radius = PopupTokens.RADIUS_SHEET_DP * density;
        Path p = scratchPath;
        p.reset();
        p.moveTo(m[0], m[3]);
        p.lineTo(m[0], m[1] + radius);
        p.quadTo(m[0], m[1], m[0] + radius, m[1]);
        p.lineTo(m[2] - radius, m[1]);
        p.quadTo(m[2], m[1], m[2], m[1] + radius);
        p.lineTo(m[2], m[3]);
        p.close();
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawPath(p, view.bgPaint);
        Paint.Style styleSauvé = view.caretPaint.getStyle();
        float strokeSauvé = view.caretPaint.getStrokeWidth();
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawPath(p, view.caretPaint);
        view.caretPaint.setStrokeWidth(strokeSauvé);
        view.caretPaint.setStyle(styleSauvé);
        // Contenu : mêmes trois blocs que le panneau.
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_META);
        float docRowH = view.metrics.getTextSize() * PopupTokens.TEXT_META * 1.3f;
        drawRows(view, card, canvas, scratchRect,
                view.completion.docPanelRows,
                m[0], m[1], m[2] - m[0], 8 * density, density, docRowH);
    }
}
