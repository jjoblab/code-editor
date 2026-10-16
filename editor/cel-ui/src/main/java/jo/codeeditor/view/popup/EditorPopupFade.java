package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;

import java.util.function.BooleanSupplier;

/**
 * B7 (retour terrain n°4) — fondus d'apparition/disparition des popups
 * Canvas de l'éditeur : « l'animation quand ils s'affichent et disparaissent
 * pour être plus fluides — je crois que le mécanisme existe déjà ».
 *
 * <p>Le mécanisme existe en effet : le {@code ValueAnimator} +
 * {@code postInvalidateOnAnimation} du glissement du caret. Ce contrôleur le
 * généralise aux popups :</p>
 *
 * <ul>
 *   <li><b>Fondu d'entrée (120 ms, décélération)</b> — chaque popup Canvas
 *       apparaît sous une couche alpha montante
 *       ({@code Canvas.saveLayerAlpha}) au lieu de claquement sec. Détection
 *       par front montant dans {@link #onDraw} : aucun crochet n'est
 *       nécessaire à l'OUVERTURE ;</li>
 *   <li><b>Fondu de sortie (110 ms)</b> — au dismiss, le popup est
 *       photographié dans un bitmap ({@link #snuff} appelé AVANT que l'état
 *       ne soit nettoyé), puis le fantôme s'efface : l'état logique est déjà
 *       mort (hit-tests inertes), seule l'image s'en va doucement. Réservé
 *       aux popups dont les bornes sont mesurables — les autres
 *       disparaissent instantanément (comme avant) ;</li>
 *   <li><b>Réouverture en vol</b> — un popup qui réapparaît pendant son
 *       fondu de sortie annule le fantôme et remonte depuis l'alpha courant
 *       (enchaînement doux, jamais de flash plein).</li>
 * </ul>
 *
 * <p><b>Robolectric</b> : le raster de {@code drawPath} est un no-op sous
 * l'ombre — le bitmap fantôme est donc vide en test (seuls les états et
 * transitions sont vérifiés, pas les pixels).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorPopupFade {

    /** Les popups Canvas pilotables. L'ordre est stable (ordinal indexé). */
    public enum Kind {
        COMPLETION, SIGNATURE, QUICK_DOC, CODE_ACTIONS, NAV_MENU,
        GOTO_SYMBOL, SELECTION_TOOLBAR, SQUIGGLE_POPUP, COLOR_PICKER,
        DIAGNOSTIC_POPUP, DIAGNOSTIC_LIST_SHEET, DIAGNOSTIC_SHEET,
        /** B27 — carte d'aperçu « goutte d'eau » (gouttière). */
        PREVIEW_DROP
    }

    /**
     * Peintre d'un popup pour le fondu : {@link #draw(Canvas)} dessine le
     * popup en coordonnées écran (même code que le pipeline de rendu),
     * {@link #bounds()} retourne ses bornes écran (null = pas de fondu de
     * sortie possible, le popup disparaît instantanément).
     */
    public interface PopupPainter {
        void draw(Canvas canvas);
        RectF bounds();
    }

    /** Durée du fondu d'entrée (ms). */
    private static final long FADE_IN_MS = 120L;
    /** Durée du fondu de sortie (ms). */
    private static final long FADE_OUT_MS = 110L;

    private final EditorView view;
    private final State[] states = new State[Kind.values().length];

    public EditorPopupFade(EditorView view) {
        this.view = view;
        for (int i = 0; i < states.length; i++) states[i] = new State();
    }

    /**
     * Enregistre un popup auprès du contrôleur — appelé UNE fois par le
     * renderer (qui possède les peintres) à sa construction.
     *
     * @param kind    identité du popup
     * @param visible fournisseur de visibilité (le drapeau d'EditorView)
     * @param painter peintre (dessin + bornes) ; {@code bounds() == null}
     *                désactive le fondu de sortie pour ce popup
     */
    public void register(Kind kind, BooleanSupplier visible, PopupPainter painter) {
        State st = states[kind.ordinal()];
        st.visible = visible;
        st.painter = painter;
    }

    /**
     * Point de passage du renderer pour chaque popup Canvas : gère le fondu
     * d'entrée (front montant de visibilité), l'alpha de couche pendant
     * l'animation, et le fantôme de sortie après un {@link #snuff}.
     *
     * @param kind identité du popup
     * @param canvas canevas du frame courant
     * @param draw  le dessin du popup (délégué au peintre du pipeline)
     */
    public void onDraw(Kind kind, Canvas canvas, Runnable draw) {
        State st = states[kind.ordinal()];
        if (st.visible == null) {
            draw.run(); // popup non enregistré — comportement inchangé
            return;
        }
        boolean visibleNow = st.visible.getAsBoolean();
        if (visibleNow) {
            if (st.phase == Phase.HIDDEN) {
                startIn(st, 0f);
            } else if (st.phase == Phase.OUT) {
                // Réouverture en vol : le fantôme meurt, le vrai popup
                // remonte depuis l'alpha courant.
                dropSnapshot(st);
                startIn(st, st.alpha);
            }
            if (st.phase == Phase.IN && st.alpha < 1f) {
                int sc = canvas.saveLayerAlpha(0, 0, canvas.getWidth(),
                        canvas.getHeight(), (int) (255 * st.alpha),
                        Canvas.ALL_SAVE_FLAG);
                draw.run();
                canvas.restoreToCount(sc);
            } else {
                draw.run();
            }
            return;
        }
        // Invisible ce frame : un fondu d'entrée interrompu s'arrête net
        // (les données peuvent déjà être incohérentes — pas de fantôme).
        if (st.phase == Phase.IN) {
            stopAnimator(st);
            st.phase = Phase.HIDDEN;
            st.alpha = 1f;
        }
        if (st.phase == Phase.OUT && st.snapshot != null) {
            // Le fantôme s'efface par-dessus le contenu rafraîchi.
            st.snapshotPaint.setAlpha((int) (255 * Math.max(0f, st.alpha)));
            canvas.drawBitmap(st.snapshot, st.snapLeft, st.snapTop,
                    st.snapshotPaint);
        }
    }

    /**
     * À appeler JUSTE AVANT qu'un dismiss ne nettoie l'état d'un popup :
     * photographie le popup (peintre encore vivant) et démarre son fondu de
     * sortie. No-op si le popup est déjà invisible, non enregistré ou sans
     * bornes mesurables (disparition instantanée, comme avant B7).
     */
    public void snuff(Kind kind) {
        State st = states[kind.ordinal()];
        if (st.visible == null || st.painter == null) return;
        if (!st.visible.getAsBoolean()) return;
        RectF b = st.painter.bounds();
        if (b == null || b.width() < 1f || b.height() < 1f
                || b.width() > 4096f || b.height() > 4096f) {
            // Bornes absentes ou déraisonnables — off instantané.
            stopAnimator(st);
            dropSnapshot(st);
            st.phase = Phase.HIDDEN;
            st.alpha = 1f;
            return;
        }
        // Photographie : le peintre dessine en coordonnées écran — on
        // translate le canevas bitmap pour le recevoir tel quel.
        int w = (int) Math.ceil(b.width());
        int h = (int) Math.ceil(b.height());
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.translate(-b.left, -b.top);
            st.painter.draw(c);
            dropSnapshot(st);
            st.snapshot = bmp;
            st.snapLeft = b.left;
            st.snapTop = b.top;
        } catch (Throwable t) {
            // Mémoire ou canevas indisponible — off instantané, jamais de
            // crash pour une animation.
            dropSnapshot(st);
            st.phase = Phase.HIDDEN;
            st.alpha = 1f;
            return;
        }
        // Fondu de sortie : 1 → 0.
        stopAnimator(st);
        st.phase = Phase.OUT;
        st.alpha = 1f;
        ValueAnimator out = ValueAnimator.ofFloat(1f, 0f);
        out.setDuration(FADE_OUT_MS);
        out.setInterpolator(new DecelerateInterpolator());
        out.addUpdateListener(a -> {
            st.alpha = (Float) a.getAnimatedValue();
            view.postInvalidateOnAnimation();
            if (st.snapshot == null) {
                // Réouverture en vol déjà traitée ailleurs — stop propre.
                a.cancel();
            }
        });
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (st.phase == Phase.OUT) {
                    dropSnapshot(st);
                    st.phase = Phase.HIDDEN;
                    st.alpha = 1f;
                    view.postInvalidateOnAnimation();
                }
            }
        });
        st.animator = out;
        out.start();
        view.postInvalidateOnAnimation();
    }

    /** Démarre le fondu d'entrée depuis {@code from}. */
    private void startIn(State st, float from) {
        stopAnimator(st);
        st.phase = Phase.IN;
        st.alpha = from;
        ValueAnimator in = ValueAnimator.ofFloat(from, 1f);
        in.setDuration(FADE_IN_MS);
        in.setInterpolator(new DecelerateInterpolator());
        in.addUpdateListener(a -> {
            st.alpha = (Float) a.getAnimatedValue();
            view.postInvalidateOnAnimation();
        });
        in.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (st.phase == Phase.IN) {
                    st.phase = Phase.SHOWN;
                    st.alpha = 1f;
                }
            }
        });
        st.animator = in;
        in.start();
        view.postInvalidateOnAnimation();
    }

    private void stopAnimator(State st) {
        if (st.animator != null) {
            st.animator.cancel();
            st.animator = null;
        }
    }

    private void dropSnapshot(State st) {
        if (st.snapshot != null) {
            st.snapshot.recycle();
            st.snapshot = null;
        }
    }

    // ── États observables (harnais de test) ─────────────────────────

    /** Phase courante du popup (HIDDEN/IN/SHOWN/OUT). */
    public Phase phase(Kind kind) {
        return states[kind.ordinal()].phase;
    }

    /** Alpha courant du popup (0..1). */
    public float alpha(Kind kind) {
        return states[kind.ordinal()].alpha;
    }

    /** Vrai si un fantôme de sortie est en vol pour ce popup. */
    public boolean isGhostFlying(Kind kind) {
        return states[kind.ordinal()].snapshot != null;
    }

    /** Phase d'animation d'un popup (exposée pour le harnais de test). */
    public enum Phase { HIDDEN, IN, SHOWN, OUT }

    private final class State {
        BooleanSupplier visible;
        PopupPainter painter;
        Phase phase = Phase.HIDDEN;
        float alpha = 1f;
        ValueAnimator animator;
        Bitmap snapshot;
        float snapLeft, snapTop;
        final Paint snapshotPaint = new Paint();
    }
}
