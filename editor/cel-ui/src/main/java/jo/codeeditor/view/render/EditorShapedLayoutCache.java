package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.chrome.EditorTheme;

import androidx.annotation.RestrictTo;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Cache de layouts façonnés adressé par contenu : mémoïse les
 * {@link android.text.StaticLayout} du mode ligatures par TEXTE de ligne
 * pour que les lignes identiques ("}", "    }", ""…) partagent un seul
 * façonnage natif au lieu d'en payer un par ligne à chaque frame.
 *
 * <p>Extrait d'EditorView (le chemin de dessin en mode ligatures y
 * reconstruisait un SpannableStringBuilder + StaticLayout pour chaque
 * ligne à chaque frame — scroll, clignotement, glissement). Clé : le
 * texte de la ligne ; validité d'entrée : signature des spans (start,
 * end, type), PLUS invalidation globale quand la police (révision
 * EditorMetrics) ou le thème (couleurs cuites dans les spans et couleur
 * de base des layouts) change. L'identité de session ne participe
 * délibérément PAS : même texte + mêmes spans + même police + même thème
 * = mêmes pixels, quel que soit le document — c'est tout l'intérêt de
 * l'adressage par contenu.</p>
 *
 * <p>La couleur de base du layout est TOUJOURS la couleur de texte du
 * thème — jamais la couleur courante du paint passé en argument : ce
 * paint est partagé avec tout le pipeline de rendu et muté par de
 * nombreuses couches (spans sémantiques, inlays, chrome, loupe). En
 * signer les entrées avec sa couleur courante cuisait une couleur
 * résiduelle arbitraire dans les layouts et reconstruisait le cache à
 * chaque changement de couleur (voir le bug de la loupe qui « perdait
 * la couleur »).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorShapedLayoutCache {

    private final EditorView view;

    static final int SHAPED_CACHE_CAPACITY = 64;

    private static final class ShapedEntry {
        final android.text.StaticLayout layout;
        final int spansSig;
        ShapedEntry(android.text.StaticLayout layout, int spansSig) {
            this.layout = layout;
            this.spansSig = spansSig;
        }
    }

    private final java.util.LinkedHashMap<String, ShapedEntry> shapedLayoutCache =
            new java.util.LinkedHashMap<String, ShapedEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, ShapedEntry> eldest) {
                    return size() > SHAPED_CACHE_CAPACITY;
                }
            };
    private int shapedCacheFontRev = -1;
    private EditorTheme shapedCacheTheme;

    public EditorShapedLayoutCache(EditorView view) {
        this.view = view;
    }

    /**
     * Retourne un {@link android.text.StaticLayout} façonné pour le dessin
     * en mode ligatures de {@code lineText}, mémoïsé par adressage de
     * contenu. Les lignes identiques (même texte, même signature de spans,
     * même génération de police, même thème) partagent UN seul layout au
     * lieu de payer le coût SpannableStringBuilder + façonnage à chaque
     * frame.
     *
     * <p>Le {@code paint} fourni ne contribue que ses attributs stables
     * (typeface, taille, drapeaux — couverts par la révision de police).
     * Sa couleur est IGNORÉE : la couleur de base du layout est la couleur
     * de texte du thème, ce qui détache la clé du cache de tout état
     * mutable du paint partagé.</p>
     *
     * <p>Sûreté de threads : appelé depuis le thread UI uniquement
     * (chemin de dessin).</p>
     */
    public android.text.StaticLayout layoutFor(String lineText, StyledLine styled,
                                         android.graphics.Paint paint) {
        int fontRev = view.metrics.getFontRevision();
        if (fontRev != shapedCacheFontRev || view.theme != shapedCacheTheme) {
            // Typeface / taille de texte / couleurs de thème changés —
            // tous les layouts en cache sont périmés (couleurs et police
            // sont cuites dans les spans et le TextPaint capturé à la
            // construction).
            shapedLayoutCache.clear();
            shapedCacheFontRev = fontRev;
            shapedCacheTheme = view.theme;
        }
        int sig = shapedSignature(styled);
        ShapedEntry e = shapedLayoutCache.get(lineText);
        if (e != null && e.spansSig == sig) return e.layout;

        android.text.SpannableStringBuilder ssb =
                new android.text.SpannableStringBuilder(lineText);
        if (styled != null && styled.spans != null) {
            for (LineSpan span : styled.spans) {
                int start = EditorView.clamp(span.startCol, 0, lineText.length());
                int end = EditorView.clamp(span.endCol, 0, lineText.length());
                if (start >= end) continue;
                int color = view.theme.colorForToken(span.type);
                ssb.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // StyleSpan pour COMMENT (italique) et KEYWORD (gras).
                if (span.type == jo.codeeditor.highlight.TokenType.COMMENT) {
                    ssb.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.ITALIC),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (span.type == jo.codeeditor.highlight.TokenType.KEYWORD) {
                    ssb.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (span.type == jo.codeeditor.highlight.TokenType.ANNOTATION) {
                    // Annotations en gras aussi pour les distinguer
                    // rapidement des types normaux.
                    ssb.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        // Couleur de base : la couleur de texte du THÈME — jamais l'état
        // mutable du paint partagé au moment de l'appel. Les couches de
        // rendu (spans sémantiques, inlays) et de chrome (loupe, minimap,
        // popups) mutent ce paint en cours de frame ; cuire leur couleur
        // résiduelle ici figeait une couleur erronée pour les portions de
        // ligne sans span (bug « la loupe fait perdre la couleur »).
        android.text.TextPaint basePaint = new android.text.TextPaint(paint);
        basePaint.setColor(view.theme.textColor);
        android.text.StaticLayout sl = new android.text.StaticLayout(
            ssb, basePaint, Integer.MAX_VALUE,
            android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        shapedLayoutCache.put(lineText, new ShapedEntry(sl, sig));
        return sl;
    }

    /** Signature de validité rapide : spans (start, end, type). La couleur
     *  de base ne participe PAS : elle est toujours la couleur de texte du
     *  thème (déjà couverte par l'invalidation globale sur changement de
     *  thème) — la clé ne dépend ainsi d'aucun état mutable du paint
     *  partagé. */
    private static int shapedSignature(StyledLine styled) {
        int h = 0;
        if (styled == null || styled.spans == null) return h;
        for (LineSpan span : styled.spans) {
            h = h * 31 + span.startCol;
            h = h * 31 + span.endCol;
            h = h * 31 + span.type.ordinal();
        }
        return h;
    }

    /** Invalide tous les layouts façonnés (changement de thème). */
    public void clear() {
        shapedLayoutCache.clear();
    }

    /** Nombre de layouts façonnés mémoïsés (tests/diagnostics). */
    public int size() {
        return shapedLayoutCache.size();
    }
}
