package jo.codeeditor.view.chrome;

import jo.codeeditor.view.BuiltinSelectionActions;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.SelectionAction;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Barre de touches de code au-dessus du clavier (4.5.0) — une rangée
 * de touches configurables branchée sur l'éditeur : indentation,
 * commentaire, déplacements de lignes, duplication… ou les actions
 * propres de l'application hôte.
 *
 * <h2>Modèle</h2>
 *
 * <p>La barre consomme des {@link SelectionAction} — le MÊME modèle
 * que la pill de sélection et son menu « ⋯ » : libellé, fournisseur
 * d'état activé (touche estompée et rejetée à faux), action au tap.
 * {@link #bind(EditorView)} pose les cinq touches usuelles (Tab,
 * commentaire, déplacer la ligne vers le haut/bas, dupliquer) ;
 * {@link #setActions(List)} remplace la rangée — le SPI d'extension
 * hôte est l'apport d'actions maison ({@code new SelectionAction(…)}
 * avec un {@code perform} libre).</p>
 *
 * <h2>Chrome</h2>
 *
 * <p>Petite sœur assumée de la barre de symboles : touches à cible
 * tactile de 44 dp, interaction tactile BRUTE (la barre ne prend pas
 * le focus — le caret reste dans l'éditeur et l'IME reste ouvert),
 * {@code performClick()} facteur commun du chemin tactile et
 * TalkBack. Défilement horizontal quand la rangée déborde. Les
 * couleurs suivent le thème de l'{@link EditorView} lié —
 * contrairement à la barre de symboles, la barre de touches ne
 * code RIEN en dur.</p>
 *
 * <h2>Attachement</h2>
 *
 * <p>La barre n'est JAMAIS posée par la bibliothèque — l'hôte
 * l'ajoute à son layout (typiquement juste au-dessus du clavier, le
 * {@code adjustResize} de l'activité la remonte avec l'IME). Elle
 * cohabite avec la barre de symboles : l'une agit sur le CODE
 * (actions), l'autre INSÈRE des caractères — l'hôte peut empiler les
 * deux ou n'en montrer qu'une.</p>
 */
public class EditorTouchBar extends LinearLayout {

    /** Hauteur de la rangée (dp). */
    private static final int BAR_HEIGHT_DP = 44;
    /** Cible tactile minimale d'une touche (dp). */
    private static final int KEY_MIN_SIZE_DP = 44;

    private EditorView editorView;
    private final LinearLayout row;
    private final List<SelectionAction> actions = new ArrayList<>();
    private final List<TextView> keys = new ArrayList<>();

    public EditorTouchBar(Context context) {
        this(context, null);
    }

    public EditorTouchBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(scroller, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Lie la barre à l'éditeur et pose les touches usuelles : Tab
     * (indentation de la sélection), commentaire, déplacer la ligne
     * vers le haut et vers le bas, dupliquer. Le déplacement du caret
     * rafraîchit les états activés (première/dernière ligne, langage
     * sans commentaire).
     */
    public void bind(EditorView view) {
        this.editorView = view;
        // Rafraîchit les états au déplacement du caret — le même
        // canal que le fil d'Ariane, SANS remplacer le listener
        // principal de l'hôte.
        view.addOnSelectionChangedListener((line, col, isCursor) -> post(this::refreshEnabled));
        setActions(defaultActions(view));
    }

    /** Les cinq touches usuelles d'édition de code. */
    public static List<SelectionAction> defaultActions(EditorView view) {
        return Arrays.asList(
                new SelectionAction("touchbar.tab", "Tab", () -> {
                    if (view.session != null) {
                        view.session.indent();
                        view.onTextChanged();
                    }
                }),
                BuiltinSelectionActions.toggleComment(view),
                BuiltinSelectionActions.moveLinesUp(view),
                BuiltinSelectionActions.moveLinesDown(view),
                BuiltinSelectionActions.duplicate(view));
    }

    /**
     * Remplace la rangée de touches — le point d'extension hôte :
     * chaque {@link SelectionAction} apporte son libellé, son état
     * activé et son action au tap. Une liste vide vide la barre.
     */
    public void setActions(List<SelectionAction> newActions) {
        actions.clear();
        keys.clear();
        row.removeAllViews();
        if (newActions != null) {
            for (SelectionAction action : newActions) {
                if (action != null) actions.add(action);
            }
        }
        for (SelectionAction action : actions) {
            row.addView(buildKey(action));
        }
        refreshEnabled();
    }

    /** La rangée courante (copie défensive, pour lecture/diagnostic). */
    public List<SelectionAction> getActions() {
        return new ArrayList<>(actions);
    }

    /** Ré-évalue les états activés des touches (caret, langage). */
    public void refreshEnabled() {
        for (int i = 0; i < actions.size() && i < keys.size(); i++) {
            SelectionAction action = actions.get(i);
            boolean enabled = action.enabled == null || action.enabled.getAsBoolean();
            keys.get(i).setEnabled(enabled);
            keys.get(i).setAlpha(enabled ? 1f : 0.4f);
        }
    }

    /** Construit une touche : texte, thème de l'éditeur, tap sans focus. */
    private TextView buildKey(final SelectionAction action) {
        TouchKey key = new TouchKey(getContext(), () -> {
            if (action.enabled != null && !action.enabled.getAsBoolean()) return;
            if (action.perform != null) action.perform.run();
        });
        key.setText(action.label);
        key.setGravity(Gravity.CENTER);
        key.setPadding(dp(14), 0, dp(14), 0);
        key.setMinWidth(dp(KEY_MIN_SIZE_DP));
        key.setMinimumWidth(dp(KEY_MIN_SIZE_DP));
        int h = dp(BAR_HEIGHT_DP) - dp(8);
        key.setMinimumHeight(h);
        key.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        // Thème de l'éditeur lié — repli neutre tant que bind() n'est
        // pas passé (barre posée dans un layout XML avant liaison).
        int bgColor = editorView != null ? editorView.theme.gutterBg : 0xFF2A2A2A;
        int fgColor = editorView != null ? editorView.theme.textColor : 0xFFD4D4D4;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(bgColor);
        key.setBackground(bg);
        key.setTextColor(fgColor);
        if (action.icon != null) {
            key.setContentDescription(action.label);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = dp(6);
        key.setLayoutParams(lp);
        keys.add(key);
        return key;
    }

    /**
     * Une touche : TextView qui exécute son action DANS performClick
     * (A-05 — chemin tactile et TalkBack unifiés), avec l'interaction
     * tactile brute du modèle barre de symboles : la touche ne prend
     * PAS le focus — le caret reste dans l'éditeur et l'IME reste
     * ouvert.
     */
    private static final class TouchKey extends TextView {

        private final Runnable click;

        TouchKey(Context context, Runnable click) {
            super(context);
            this.click = click;
            setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    v.performClick();
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_DOWN;
            });
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (click != null) click.run();
            return true;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,
                android.view.View.MeasureSpec.makeMeasureSpec(dp(BAR_HEIGHT_DP),
                        android.view.View.MeasureSpec.EXACTLY));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
