package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.kit.PopupCard;
import jo.codeeditor.view.popup.kit.PopupTokens;
import jo.codeeditor.view.popup.kit.SignatureDocSections;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.RectF;

/**
 * Rendu des SECTIONS de la documentation de l'aide de signature
 * (phase 4, maquette) : section PARAMÈTRE ACTIF sur bande teintée
 * (libellé « Parameter i/n », nom en accent + type en méta,
 * description dessous), FILET, section RÉSUMÉ (libellé + ≤ 2 lignes),
 * ligne RETOURNE dédiée (préfixe atténué + texte gras), sections
 * secondaires en peek. Méthodes STATIQUES (vue et carte passées en
 * paramètre) : le painter principal ne porte aucun champ de plus.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
final class EditorSignatureDocPainter {
    private EditorSignatureDocPainter() {}

    /**
     * Dessine les sections sous la ligne de signature et renvoie le Y
     * de fin (après la dernière section).
     *
     * @param view la vue hôte (paints, thème, ressources)
     * @param card la carte de popup partagée (bandes, filets)
     * @param canvas le canvas de la frame
     * @param scratchRect RectF de travail réutilisé (aucune allocation par frame)
     * @param s les sections calculées (source unique : layout)
     * @param x la gauche de la carte (px)
     * @param width la largeur de la carte (px)
     * @param y le haut de la première section (px)
     * @param padX la marge horizontale intérieure (px)
     * @param density la densité écran
     * @param docRowH la hauteur d'une ligne de documentation (px)
     * @param nomRowH la hauteur de la ligne nom du paramètre (px)
     * @param filetZone la hauteur d'une zone de filet (px)
     * @param numParamètre le numéro 1-based du paramètre actif
     * @param nbParamètres le nombre de paramètres de la signature
     */
    static float drawSections(EditorView view, PopupCard card, Canvas canvas,
            RectF scratchRect, SignatureDocSections s, float x, float width,
            float y, float padX, float density, float docRowH, float nomRowH,
            float filetZone, int numParamètre, int nbParamètres) {
        // ── Section PARAMÈTRE ACTIF — bande teintée pleine largeur.
        if (s.aParamètre()) {
            float paramH = docRowH + nomRowH + s.lignesParamètre.size() * docRowH;
            RectF bande = scratchRect;
            bande.set(x, y, x + width, y + paramH);
            card.drawBand(canvas, bande, PopupCard.BAND_TINTED);
            // Libellé de section « Parameter i/n » (micro, atténué).
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_MICRO);
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(view.getResources().getString(
                            jo.codeeditor.ui.R.string.cel_signature_param_of,
                            numParamètre, nbParamètres),
                    x + padX, y + docRowH * 0.85f, view.textPaint);
            // Nom (accent, taille titre) + type (méta, atténué) sur la
            // même ligne de base.
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
            view.textPaint.setColor(view.theme.func);
            float nomX = x + padX;
            float nomBase = y + docRowH + nomRowH * 0.75f;
            canvas.drawText(s.nomParamètre, nomX, nomBase, view.textPaint);
            float largeurNom = view.textPaint.measureText(s.nomParamètre);
            if (!s.typeParamètre.isEmpty()) {
                view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_META);
                view.textPaint.setColor(view.theme.gutterText);
                canvas.drawText(s.typeParamètre,
                        nomX + largeurNom + 4 * density, nomBase, view.textPaint);
            }
            // Description du paramètre (méta, couleur de texte).
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_META);
            view.textPaint.setColor(view.theme.textColor);
            for (int i = 0; i < s.lignesParamètre.size(); i++) {
                canvas.drawText(s.lignesParamètre.get(i), x + padX,
                        y + docRowH + nomRowH + (i + 1) * docRowH * 0.95f,
                        view.textPaint);
            }
            y += paramH;
        }

        // ── Filet avant le résumé (la section paramètre le précède).
        if (!s.lignesRésumé.isEmpty() && s.aParamètre()) {
            card.drawDivider(canvas, x + 4 * density,
                    x + width - 4 * density, y + filetZone * 0.5f);
            y += filetZone;
        }

        // ── Section RÉSUMÉ — libellé micro + corps.
        if (!s.lignesRésumé.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_MICRO);
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(view.getResources().getString(
                            jo.codeeditor.ui.R.string.cel_signature_summary),
                    x + padX, y + docRowH * 0.85f, view.textPaint);
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_META);
            view.textPaint.setColor(view.theme.textColor);
            for (int i = 0; i < s.lignesRésumé.size(); i++) {
                canvas.drawText(s.lignesRésumé.get(i), x + padX,
                        y + docRowH + (i + 1) * docRowH * 0.95f, view.textPaint);
            }
            y += docRowH + s.lignesRésumé.size() * docRowH;
        }

        // ── Ligne RETOURNE dédiée : préfixe atténué + texte gras.
        if (!s.ligneRetour.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_META);
            view.textPaint.setColor(view.theme.gutterText);
            String préfixe = view.getResources().getString(
                    jo.codeeditor.ui.R.string.cel_signature_returns) + " ";
            canvas.drawText(préfixe, x + padX, y + docRowH * 0.85f, view.textPaint);
            view.textPaint.setColor(view.theme.keyword);
            view.textPaint.setFakeBoldText(true);
            canvas.drawText(s.ligneRetour,
                    x + padX + view.textPaint.measureText(préfixe),
                    y + docRowH * 0.85f, view.textPaint);
            view.textPaint.setFakeBoldText(false);
            y += docRowH;
        }

        // ── Sections secondaires — vue PEEK uniquement (motif de la
        // bande doc historique : sections @ en couleur de mot-clé).
        if (!s.lignesExtra.isEmpty()) {
            if (!s.lignesRésumé.isEmpty() || !s.ligneRetour.isEmpty()) {
                card.drawDivider(canvas, x + 4 * density,
                        x + width - 4 * density, y + filetZone * 0.5f);
                y += filetZone;
            }
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_META);
            for (int i = 0; i < s.lignesExtra.size(); i++) {
                String line = s.lignesExtra.get(i);
                boolean isTag = !line.isEmpty() && line.charAt(0) == '@';
                view.textPaint.setColor(isTag
                        ? view.theme.keyword : view.theme.textColor);
                canvas.drawText(line, x + padX,
                        y + (i + 1) * docRowH * 0.95f, view.textPaint);
            }
        }
        return y;
    }
}
