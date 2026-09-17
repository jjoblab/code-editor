package jo.codeeditor.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * Feuille popup qui rend par-dessus l'éditeur le contenu d'aperçu de
 * l'hôte (généralement une WebView pour Markdown/HTML, ou une View canvas
 * pour les aperçus de layout XML).
 *
 * <p>Le chrome de la feuille (en-tête carte de verre avec nom de fichier
 * + bouton fermer, bascule scindé/plein optionnelle, geste
 * glisser-pour-fermer) appartient entièrement à la bibliothèque
 * d'éditeur — l'hôte fournit uniquement la View de corps via
 * {@link EditorPreviewHost#onCreatePreviewView(Context, EditorView,
 * EditorView.PreviewMode)}. Quand l'hôte renvoie {@code null}, la
 * feuille retombe sur une {@link CanvasBodyView} qui délègue le
 * {@code onDraw} de chaque frame à {@link EditorPreviewHost#drawPreview}.
 *
 * <h3>Layout</h3>
 * <pre>
 *  ┌─────────────────────────────────────────────┐
 *  │  filename.md        [split] [full]    [X]   │ ← en-tête (24dp de haut)
 *  ├─────────────────────────────────────────────┤
 *  │                                             │
 *  │           corps hôte (WebView/Canvas)        │
 *  │                                             │
 *  └─────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Largeur/hauteur sont calculées depuis la géométrie de l'éditeur :
 * <ul>
 *   <li>{@code SHEET_SPLIT} → moitié droite de l'éditeur (ancrée en haut à droite)</li>
 *   <li>{@code SHEET_FULL}  → zone complète de l'éditeur</li>
 * </ul>
 *
 * <h3>Fermeture</h3>
 * <ul>
 *   <li>Taper le bouton X</li>
 *   <li>Taper hors de la feuille (quand {@code setOutsideTouchable} est honoré)</li>
 *   <li>Appuyer sur Retour (géré par le OnKeyListener par défaut de {@link PopupWindow})</li>
 * </ul>
 */
class EditorPreviewSheet {

    private final EditorView editor;
    private EditorView.PreviewMode mode;
    private PopupWindow popup;
    private View bodyView;
    private boolean canvasFallback = false;

    EditorPreviewSheet(EditorView editor, EditorView.PreviewMode mode) {
        this.editor = editor;
        this.mode = mode;
    }

    EditorView.PreviewMode getMode() { return mode; }

    /** Affiche la feuille ancrée à l'éditeur. No-op si l'hôte est null. */
    void show() {
        EditorPreviewHost host = editor.getPreviewHost();
        if (host == null) return;
        Context ctx = editor.getContext();

        // ── Construction de la vue de corps (fournie par l'hôte, ou repli canvas). ──
        bodyView = host.onCreatePreviewView(ctx, editor, mode);
        if (bodyView == null) {
            canvasFallback = true;
            bodyView = new CanvasBodyView(ctx, editor, host);
        }
        // Détachement défensif : l'hôte peut légitimement mettre en cache
        // et renvoyer la même View à travers plusieurs instances de feuille
        // (ex. une WebView réutilisée entre ouvertures). Quand le
        // PopupWindow précédent a été démonté, la référence parent du
        // bodyView n'était pas nettoyée — l'ajouter à un nouveau bodyFrame
        // lancerait IllegalStateException « child already has a parent ».
        // On détache d'abord ici pour garder un contrat amical pour l'hôte
        // (l'hôte n'a pas à se soucier de la gestion du parent).
        if (bodyView.getParent() instanceof ViewGroup) {
            ((ViewGroup) bodyView.getParent()).removeView(bodyView);
        }
        bodyView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Construction du conteneur de la feuille : en-tête en haut, corps en dessous. ──
        LinearLayout sheet = buildSheetContainer(ctx);
        sheet.addView(buildHeader(ctx));
        View divider = new View(ctx);
        divider.setBackgroundColor(editor.theme.glassBorder);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, editor.dp(1))));
        sheet.addView(divider);
        FrameLayout bodyFrame = new FrameLayout(ctx);
        bodyFrame.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bodyFrame.addView(bodyView);
        sheet.addView(bodyFrame);

        // ── Calcul largeur/hauteur et position d'ancrage. ──
        int width, height, x, y;
        int[] location = new int[2];
        editor.getLocationInWindow(location);
        if (mode == EditorView.PreviewMode.SHEET_FULL) {
            width = editor.getWidth();
            height = editor.getHeight();
            x = location[0];
            y = location[1];
        } else { // SHEET_SPLIT
            width = editor.getWidth() / 2;
            height = editor.getHeight();
            x = location[0] + (editor.getWidth() - width);
            y = location[1];
        }

        popup = new PopupWindow(sheet, width, height, true);
        popup.setFocusable(true);
        popup.setOutsideTouchable(false); // fermeture via X uniquement — évite une fermeture accidentelle pendant la saisie dans l'éditeur
        popup.setClippingEnabled(true);
        popup.setOnDismissListener(this::onDismissed);
        // Le bouton Retour ferme la feuille (le OnKeyListener par défaut du
        // PopupWindow honore KEYCODE_BACK quand focusable=true).
        popup.showAtLocation(editor, Gravity.NO_GRAVITY, x, y);

        // Pousse le contenu initial dans le corps.
        refreshBody();
    }

    void dismiss() {
        if (popup != null) {
            popup.dismiss();
            // onDismissed sera invoqué par le listener.
        }
    }

    boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    /**
     * Appelée après un changement de texte de l'éditeur (débounce ~200 ms).
     * Pousse le nouveau contenu vers l'hôte (qui met alors à jour son corps
     * WebView) et demande à la View de corps de s'invalider.
     */
    void refreshBody() {
        if (bodyView == null) return;
        if (canvasFallback) {
            // CanvasBodyView lit depuis l'hôte à chaque frame — simple invalidate.
            bodyView.invalidate();
        } else {
            // La View de corps de l'hôte doit déjà avoir reçu
            // onPreviewContentChanged via EditorView.updatePreviewContent.
            // On déclenche juste un redraw pour que la WebView se mette à jour.
            bodyView.invalidate();
        }
    }

    /** Bascule entre SHEET_SPLIT et SHEET_FULL sans reconstruire le popup. */
    void switchMode(EditorView.PreviewMode newMode) {
        if (newMode == this.mode || popup == null) return;
        this.mode = newMode;
        // Recalcule la taille et réaffiche aux nouvelles dimensions.
        int[] location = new int[2];
        editor.getLocationInWindow(location);
        int width, height, x, y;
        if (mode == EditorView.PreviewMode.SHEET_FULL) {
            width = editor.getWidth();
            height = editor.getHeight();
            x = location[0];
            y = location[1];
        } else {
            width = editor.getWidth() / 2;
            height = editor.getHeight();
            x = location[0] + (editor.getWidth() - width);
            y = location[1];
        }
        popup.update(x, y, width, height);
        // Notifie l'hôte des nouvelles bornes.
        EditorPreviewHost host = editor.getPreviewHost();
        if (host != null) {
            host.onPreviewModeChanged(mode, editor.getPreviewLeft(), editor.getPreviewWidth());
        }
        refreshBody();
    }

    // ════════════════════════════════════════════════════════════════
    // Chrome de la feuille
    // ════════════════════════════════════════════════════════════════

    private LinearLayout buildSheetContainer(Context ctx) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(editor.theme.glassBg);
        bg.setStroke(editor.dp(1), editor.theme.glassBorder);
        // Léger rayon de coin pour SHEET_SPLIT (le côté droit flotte) ; 0 pour FULL.
        if (mode == EditorView.PreviewMode.SHEET_SPLIT) {
            float r = editor.dp(14);
            // 8 rayons : top-left-x, top-left-y, top-right-x, top-right-y,
            // bottom-right-x, bottom-right-y, bottom-left-x, bottom-left-y.
            bg.setCornerRadii(new float[]{
                r, r, 0, 0, 0, 0, r, r
            });
        }
        container.setBackground(bg);
        return container;
    }

    private View buildHeader(Context ctx) {
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = editor.dp(12);
        int padV = editor.dp(8);
        header.setPadding(padH, padV, padH, padV);
        header.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Nom de fichier (gauche)
        TextView title = new TextView(ctx);
        String name = editor.getFileName();
        if (name == null || name.isEmpty()) name = "Preview";
        title.setText(name);
        title.setTextColor(editor.theme.textColor);
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setMaxEms(20);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        header.addView(title);

        // Bouton de bascule scindé (icône chevron droit)
        View splitBtn = buildIconButton(ctx, this::onSplitClicked,
            "Split preview", EditorPreviewSheet::drawSplitGlyph);
        header.addView(splitBtn);
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(
            editor.dp(28), editor.dp(28));
        splitLp.setMargins(editor.dp(8), 0, 0, 0);
        splitBtn.setLayoutParams(splitLp);

        // Bouton de bascule plein écran (icône œil)
        View fullBtn = buildIconButton(ctx, this::onFullClicked,
            "Full preview", EditorPreviewSheet::drawFullGlyph);
        header.addView(fullBtn);
        LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(
            editor.dp(28), editor.dp(28));
        fullLp.setMargins(editor.dp(4), 0, 0, 0);
        fullBtn.setLayoutParams(fullLp);

        // Bouton de fermeture X
        View closeBtn = buildIconButton(ctx, this::onCloseClicked,
            "Close preview", EditorPreviewSheet::drawCloseGlyph);
        header.addView(closeBtn);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
            editor.dp(28), editor.dp(28));
        closeLp.setMargins(editor.dp(4), 0, 0, 0);
        closeBtn.setLayoutParams(closeLp);

        return header;
    }

    @FunctionalInterface interface GlyphDrawer {
        void draw(Canvas c, float cx, float cy, float r, Paint p);
    }

    private View buildIconButton(Context ctx, Runnable onClick,
                                  String contentDesc, GlyphDrawer glyph) {
        ImageView btn = new ImageView(ctx, null, 0) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
                if (w == 0 || h == 0) return;
                float cx = w * 0.5f, cy = h * 0.5f;
                float r = Math.min(w, h) * 0.32f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, editor.dp(2)));
                p.setColor(editor.applyAlphaToColor(editor.theme.gutterText, 0.85f));
                glyph.draw(canvas, cx, cy, r, p);
            }
        };
        btn.setContentDescription(contentDesc);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnClickListener(v -> onClick.run());
        btn.setBackground(rippleBackground(ctx));
        btn.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    /** Ripple simple sélectionnable — construit en code pour éviter les dépendances au thème. */
    private android.graphics.drawable.RippleDrawable rippleBackground(Context ctx) {
        int statePressed = android.R.attr.state_pressed;
        android.content.res.ColorStateList rippleCs =
            android.content.res.ColorStateList.valueOf(
                editor.applyAlphaToColor(editor.theme.keyword, 0.32f));
        android.graphics.drawable.ColorDrawable mask =
            new android.graphics.drawable.ColorDrawable(Color.WHITE);
        return new android.graphics.drawable.RippleDrawable(
            rippleCs, null, mask);
    }

    // ── Gestionnaires des boutons d'en-tête ──

    private void onSplitClicked() {
        switchMode(EditorView.PreviewMode.SHEET_SPLIT);
    }

    private void onFullClicked() {
        switchMode(EditorView.PreviewMode.SHEET_FULL);
    }

    private void onCloseClicked() {
        dismiss();
    }

    private void onDismissed() {
        // Réinitialise l'état de l'éditeur pour que previewMode revienne à NONE.
        // Garde-fou anti-récursion : setPreviewMode(NONE) appelle
        // closePreviewSheet() qui appelle dismiss() — mais dismiss() est
        // déjà en cours, donc on met le champ à null d'abord.
        if (editor.getPreviewSheet() != null) {
            // Fermeture via l'API publique pour garder l'état de l'éditeur cohérent.
            editor.closePreviewSheet();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Dessinateurs de glyphes (évite d'embarquer des ressources drawable dans la lib éditeur)
    // ════════════════════════════════════════════════════════════════

    private static void drawSplitGlyph(Canvas c, float cx, float cy, float r, Paint p) {
        // Deux rects arrondis en contour côte à côte (écho de EditorRenderer.drawPreviewIcons).
        RectF left = new RectF(cx - r, cy - r * 0.7f, cx - r * 0.2f, cy + r * 0.7f);
        RectF right = new RectF(cx + r * 0.2f, cy - r * 0.7f, cx + r, cy + r * 0.7f);
        p.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(left, r * 0.18f, r * 0.18f, p);
        c.drawRoundRect(right, r * 0.18f, r * 0.18f, p);
        // Ligne de séparation verticale entre les panneaux.
        c.drawLine(cx, cy - r * 0.6f, cx, cy + r * 0.6f, p);
    }

    private static void drawFullGlyph(Canvas c, float cx, float cy, float r, Paint p) {
        // Œil rempli avec pupille.
        Path eye = new Path();
        RectF topArc = new RectF(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.6f);
        eye.addArc(topArc, 200, 140);
        RectF botArc = new RectF(cx - r, cy - r * 0.18f, cx + r, cy + r * 1.02f);
        eye.arcTo(botArc, 20, 140);
        eye.close();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(eye, p);
        p.setStyle(Paint.Style.STROKE);
        c.drawPath(eye, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r * 0.2f, p);
    }

    private static void drawCloseGlyph(Canvas c, float cx, float cy, float r, Paint p) {
        // « X » stylisé.
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(cx - r, cy - r, cx + r, cy + r, p);
        c.drawLine(cx - r, cy + r, cx + r, cy - r, p);
    }

    // ════════════════════════════════════════════════════════════════
    // CanvasBodyView — repli quand l'hôte renvoie null depuis
    // onCreatePreviewView (c.-à-d. aperçu canvas uniquement, comme les layouts XML).
    // ════════════════════════════════════════════════════════════════

    @SuppressLint("ViewConstructor")
    private static class CanvasBodyView extends View {
        private final EditorView editor;
        private final EditorPreviewHost host;
        private final Paint bgPaint;

        CanvasBodyView(Context ctx, EditorView editor, EditorPreviewHost host) {
            super(ctx);
            this.editor = editor;
            this.host = host;
            this.bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.bgPaint.setColor(editor.theme.editorBg);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Remplit le fond pour éviter de voir le texte de l'éditeur au travers.
            canvas.drawPaint(bgPaint);
            // Demande à l'hôte de rendre dans tout le canvas du corps.
            host.drawPreview(canvas, 0f, 0f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return host.hitTestPreview(event.getX(), event.getY())
                    || super.onTouchEvent(event);
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                // Accessibilité (ClickableViewAccessibility) — annonce le tap
                // avant que l'hôte ne le consomme.
                performClick();
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
