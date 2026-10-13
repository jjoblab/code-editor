package jo.codeeditor.view.preview;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.icons.EditorIcons;

import androidx.annotation.RestrictTo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
 * <p>Le chrome de la feuille appartient entièrement à la bibliothèque
 * d'éditeur — l'hôte fournit uniquement la View de corps via
 * {@link EditorPreviewHost#onCreatePreviewView(Context, EditorView,
 * EditorView.PreviewMode)}. Quand l'hôte renvoie {@code null}, la feuille
 * retombe sur une {@link CanvasBodyView} qui délègue le {@code onDraw}
 * de chaque frame à {@link EditorPreviewHost#drawPreview}.</p>
 *
 * <h3>B6 — chrome modernisé</h3>
 * <ul>
 *   <li>Carte flottante : élévation réelle ({@code 12 dp}) au-dessus de
 *       l'éditeur, coins arrondis côté flottant (gauche en scindé) ;</li>
 *   <li>En-tête : petite icône ŒIL ({@link EditorIcons}) + nom de
 *       fichier, <b>segmented control</b> scindé/plein (pilule avec
 *       compartiment actif allumé) et bouton fermer — icônes vectorielles
 *       du moteur, teintées du thème ;</li>
 *   <li>La bascule de mode re-dessine la carte (rayons de coins adaptés)
 *       sans reconstruire le popup.</li>
 * </ul>
 *
 * <h3>Layout</h3>
 * <pre>
 *  ╭──────────────────────────────────────────────╮
 *  │ ◉ exemple.md      [◧][⤢]  [✕]                │ ← en-tête (44 dp)
 *  ├──────────────────────────────────────────────┤
 *  │                                              │
 *  │         corps hôte (WebView / canvas)        │
 *  │                                              │
 *  ╰──────────────────────────────────────────────╯
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
 *   <li>Taper le bouton ✕</li>
 *   <li>Taper le bouton d'action d'aperçu de la barre de fil d'Ariane
 *       (bascule B6)</li>
 *   <li>Appuyer sur Retour (géré par le OnKeyListener par défaut de {@link PopupWindow})</li>
 * </ul>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorPreviewSheet {

    private final EditorView editor;
    private EditorView.PreviewMode mode;
    private PopupWindow popup;
    private View bodyView;
    private boolean canvasFallback = false;
    /** Conteneur carte — fond dérivé du mode (rayons de coins). */
    private LinearLayout sheetContainer;
    /** Compartiments du segmented control (scindé / plein). */
    private View splitSegment;
    private View fullSegment;

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
        sheetContainer = buildSheetContainer(ctx);
        sheetContainer.addView(buildHeader(ctx));
        View divider = new View(ctx);
        divider.setBackgroundColor(editor.theme.glassBorder);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, editor.dp(1))));
        sheetContainer.addView(divider);
        FrameLayout bodyFrame = new FrameLayout(ctx);
        bodyFrame.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bodyFrame.addView(bodyView);
        sheetContainer.addView(bodyFrame);

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

        popup = new PopupWindow(sheetContainer, width, height, true);
        popup.setFocusable(true);
        popup.setOutsideTouchable(false); // fermeture via ✕ uniquement — évite une fermeture accidentelle pendant la saisie dans l'éditeur
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
        // La carte se re-identifie : rayons de coins + élévation du mode.
        applyCardAppearance();
        // Notifie l'hôte des nouvelles bornes.
        EditorPreviewHost host = editor.getPreviewHost();
        if (host != null) {
            host.onPreviewModeChanged(mode, editor.getPreviewLeft(), editor.getPreviewWidth());
        }
        refreshBody();
    }

    // ════════════════════════════════════════════════════════════════
    // Chrome de la feuille (B6 — carte flottante modernisée)
    // ════════════════════════════════════════════════════════════════

    private LinearLayout buildSheetContainer(Context ctx) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        applyCardAppearance(container);
        return container;
    }

    /** Fond + rayons + élévation de la carte selon le mode courant. */
    private void applyCardAppearance() {
        if (sheetContainer != null) applyCardAppearance(sheetContainer);
    }

    private void applyCardAppearance(View container) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(editor.theme.glassBg);
        bg.setStroke(editor.dp(1), editor.theme.glassBorder);
        // Scindé : la carte flotte à droite de l'éditeur — coins GAUCHES
        // arrondis (16 dp), côté ancré droit net. Plein : couvre l'éditeur,
        // coins droits seuls pour rester raccord avec le bord d'écran.
        float r = editor.dp(16);
        if (mode == EditorView.PreviewMode.SHEET_SPLIT) {
            bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        } else {
            bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        }
        container.setBackground(bg);
        // Carte flottante : ombre réelle au-dessus de l'éditeur (le contour
        // du GradientDrawable fournit l'outline de l'élévation).
        container.setElevation(editor.dp(12));
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

        // Petite icône ŒIL en accent — signature « aperçu » de la carte.
        View eye = new View(ctx) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                p.setColor(editor.theme.keyword);
                EditorIcons.draw(canvas, EditorIcons.Icon.EYE,
                        getWidth() / 2f, getHeight() / 2f,
                        Math.min(getWidth(), getHeight()) * 0.8f, p);
            }
        };
        LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(
            editor.dp(18), editor.dp(18));
        eyeLp.rightMargin = editor.dp(8);
        header.addView(eye, eyeLp);

        // Nom de fichier (gauche)
        TextView title = new TextView(ctx);
        String name = editor.getFileName();
        if (name == null || name.isEmpty()) name = "Preview";
        title.setText(name);
        title.setTextColor(editor.theme.textColor);
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setMaxEms(16);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        header.addView(title);

        // Segmented control scindé/plein (pilule à deux compartiments).
        View segmented = buildSegmentedControl(ctx);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, editor.dp(32));
        segLp.leftMargin = editor.dp(8);
        segmented.setLayoutParams(segLp);
        header.addView(segmented);

        // Bouton de fermeture ✕
        View closeBtn = buildIconButton(ctx, this::onCloseClicked,
            "Close preview", EditorIcons.Icon.CLOSE);
        header.addView(closeBtn);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
            editor.dp(32), editor.dp(32));
        closeLp.leftMargin = editor.dp(4);
        closeBtn.setLayoutParams(closeLp);

        return header;
    }

    /** Pilule scindé/plein — compartiment actif voilé d'accent. */
    private View buildSegmentedControl(Context ctx) {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(editor.applyAlphaToColor(editor.theme.gutterText, 0.10f));
        bg.setStroke(editor.dp(1), editor.theme.glassBorder);
        bg.setCornerRadius(editor.dp(16));
        pill.setBackground(bg);
        int pad = editor.dp(2);
        pill.setPadding(pad, pad, pad, pad);

        splitSegment = buildSegment(ctx, EditorIcons.Icon.SPLIT,
                "Split preview", this::onSplitClicked);
        fullSegment = buildSegment(ctx, EditorIcons.Icon.EXPAND,
                "Full preview", this::onFullClicked);
        pill.addView(splitSegment, new LinearLayout.LayoutParams(
                editor.dp(36), editor.dp(28)));
        pill.addView(fullSegment, new LinearLayout.LayoutParams(
                editor.dp(36), editor.dp(28)));

        highlightActiveSegment();
        return pill;
    }

    private View buildSegment(Context ctx, EditorIcons.Icon icon,
            String contentDesc, Runnable onClick) {
        ImageView segment = new ImageView(ctx, null, 0) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
                if (w == 0 || h == 0) return;
                boolean active = isSegmentActive(this);
                p.setColor(active
                        ? editor.theme.keyword
                        : editor.applyAlphaToColor(editor.theme.gutterText, 0.85f));
                EditorIcons.draw(canvas, icon, w * 0.5f, h * 0.5f,
                        Math.min(w, h) * 0.55f, p);
            }
        };
        segment.setContentDescription(contentDesc);
        segment.setFocusable(true);
        segment.setClickable(true);
        segment.setOnClickListener(v -> {
            onClick.run();
            // Le compartiment actif change — redessine la pilule entière.
            highlightActiveSegment();
            if (splitSegment != null) splitSegment.invalidate();
            if (fullSegment != null) fullSegment.invalidate();
        });
        segment.setBackground(rippleBackground(ctx));
        return segment;
    }

    private boolean isSegmentActive(View segment) {
        boolean splitActive = mode == EditorView.PreviewMode.SHEET_SPLIT;
        return (segment == splitSegment) == splitActive;
    }

    /** Voile le compartiment du mode courant (le second reste neutre). */
    private void highlightActiveSegment() {
        if (splitSegment == null || fullSegment == null) return;
        applySegmentBackground(splitSegment,
                mode == EditorView.PreviewMode.SHEET_SPLIT);
        applySegmentBackground(fullSegment,
                mode == EditorView.PreviewMode.SHEET_FULL);
    }

    private void applySegmentBackground(View segment, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active
                ? editor.applyAlphaToColor(editor.theme.keyword, 0.22f)
                : 0x00000000);
        bg.setCornerRadius(editor.dp(14));
        segment.setBackground(bg);
    }

    private View buildIconButton(Context ctx, Runnable onClick,
                                  String contentDesc, EditorIcons.Icon icon) {
        ImageView btn = new ImageView(ctx, null, 0) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
                if (w == 0 || h == 0) return;
                p.setColor(editor.applyAlphaToColor(editor.theme.gutterText, 0.85f));
                EditorIcons.draw(canvas, icon, w * 0.5f, h * 0.5f,
                        Math.min(w, h) * 0.55f, p);
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
