package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.snippet.SnippetSession;

/**
 * Popup des variables de choix d'un snippet (4.5.0) — quand la
 * navigation entre sur un champ « {@code ${1|alpha,beta,gamma|}} », les
 * options sont proposées dans une carte glass ancrée au-dessus de la
 * ligne de frappe ; toucher une option REMPLACE le champ entier (les
 * miroirs suivent par le pipeline de session) et avance au champ
 * suivant — qui ouvre sa propre carte s'il porte lui-même des choix.
 *
 * <p>Petit frère délibéré du popup de renommage : PopupWindow + carte
 * « liquid glass » aux couleurs du thème, tap extérieur refermé. La
 * saisie clavier dans le champ reste possible (la carte ne prend pas
 * le focus) : le champ reste un champ libre, les options sont un
 * raccourci. Le popup vit tant que la session de snippet est active ;
 * il est refermé par {@link #dismissIfSessionDead()} au fil des
 * notifications de texte (undo/redo terminent la session côté noyau).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class SnippetChoicePopup {

    private final EditorView view;
    private PopupWindow popup;

    SnippetChoicePopup(EditorView view) {
        this.view = view;
    }

    boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    /**
     * Ouvre la carte si le champ COURANT de la session de snippet
     * active porte des options de choix ; sans effet sinon (champ
     * libre, aucune session).
     */
    void showIfNeeded() {
        if (view.session == null || !view.session.snippets.isActive()) {
            dismiss();
            return;
        }
        SnippetSession.TabStop stop = view.session.snippets.get() == null
                ? null : view.session.snippets.get().current();
        if (stop == null || stop.choices.isEmpty()) {
            dismiss();
            return;
        }
        dismiss();
        Context ctx = view.getContext();

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = view.dp(8);
        container.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(view.dp(14));
        bg.setColor(view.theme.glassBg);
        bg.setStroke(view.dp(1), view.theme.glassBorder);
        container.setBackground(bg);

        for (int i = 0; i < stop.choices.size(); i++) {
            final String choice = stop.choices.get(i);
            TextView row = new TextView(ctx);
            row.setText(choice);
            row.setTextColor(view.theme.textColor);
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            row.setPadding(view.dp(12), view.dp(8), view.dp(12), view.dp(8));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(view.dp(10));
            rowBg.setColor(view.theme.gutterBg);
            row.setBackground(rowBg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = view.dp(2);
            lp.bottomMargin = view.dp(2);
            row.setLayoutParams(lp);
            // Interaction tactile brute (modèle SymbolBarView) : la
            // carte ne prend PAS le focus — le caret reste dans le
            // champ, l'IME reste ouvert, la saisie clavier continue.
            row.setOnTouchListener((v, e) -> {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    v.performClick();
                    return true;
                }
                return e.getAction() == android.view.MotionEvent.ACTION_DOWN;
            });
            row.setOnClickListener(v -> choose(choice));
            container.addView(row);
        }

        popup = new PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setFocusable(false);
        EditorPopupMotion.outsideTapDismisses(popup);
        popup.setOnDismissListener(() -> popup = null);

        // Ancrage au-dessus de la ligne du champ : mesure la carte,
        // clampe dans les limites de la vue.
        container.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int h = container.getMeasuredHeight();
        EditorDocument doc = view.session.getDocument();
        int caret = view.session.getSelection().start;
        int line = doc.lineForOffset(Math.max(0, Math.min(caret, doc.length())));
        float lineY = view.docLineToY(line) - view.scroll.vOffset;
        int y = (int) Math.max(view.dp(8), Math.min(lineY - h - view.dp(6),
                view.getHeight() - h - view.dp(8)));
        popup.showAtLocation(view, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, y);
        EditorPopupMotion.cardIn(container, -view.dp(8));
    }

    /**
     * Applique une option : remplace le champ ENTIER, avance au champ
     * suivant (le pipeline de session synchronise les miroirs ; l'arrêt
     * final {@code $0} clôt la session en posant le caret), et ouvre la
     * carte du champ suivant s'il porte lui-même des choix.
     * Visibilité paquet : testé en place.
     */
    void choose(String choice) {
        if (view.session == null || !view.session.snippets.isActive()) {
            dismiss();
            return;
        }
        SnippetSession.TabStop stop = view.session.snippets.get() == null
                ? null : view.session.snippets.get().current();
        if (stop == null) {
            dismiss();
            return;
        }
        dismiss();
        view.session.replaceRange(stop.start, stop.end, choice);
        // Avance au champ suivant — ou clôt sur $0 (caret posé,
        // sélection du prochain champ posée par le contrôleur).
        view.session.snippets.next();
        view.onTextChanged();
        showIfNeeded();
    }

    /** Referme la carte. */
    void dismiss() {
        if (popup != null) {
            popup.dismiss();
            popup = null;
        }
    }

    /** Le nombre d'options de la carte ouverte (0 si fermée). */
    int optionCount() {
        if (view.session == null || !view.session.snippets.isActive()) return 0;
        SnippetSession.TabStop stop = view.session.snippets.get() == null
                ? null : view.session.snippets.get().current();
        return stop == null ? 0 : stop.choices.size();
    }

    /**
     * Referme la carte si la session de snippet n'est plus active —
     * appelé au fil des notifications de texte (undo/redo et fin de
     * session côté noyau n'ont pas de hook UI dédié).
     */
    void dismissIfSessionDead() {
        if (isShowing() && (view.session == null || !view.session.snippets.isActive())) {
            dismiss();
        }
    }
}
