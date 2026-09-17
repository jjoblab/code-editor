package jo.codeeditor.view;

import android.content.Context;
import android.text.StaticLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.session.EditorSession;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Tests du LRU de shaped-layouts adressé par contenu
 * ({@code EditorView.shapedLayoutFor}) :
 * <ul>
 *   <li>les lignes identiques (même texte + même signature de spans + même
 *       couleur de base du paint + même génération de police + même thème)
 *       partagent UN SEUL StaticLayout — pas de re-shaping par frame ;</li>
 *   <li>une signature de spans différente sur le MÊME texte reconstruit
 *       (des spans périmées ne doivent jamais être servies) ;</li>
 *   <li>une couleur de base du paint différente reconstruit (le chemin du
 *       magnifier mute la couleur du paint au draw) ;</li>
 *   <li>le swap de thème et le changement de taille/typeface (révision de
 *       police d'EditorMetrics) invalident TOUT ;</li>
 *   <li>le cache est BORNE à 64 entrées (éviction LRU) et l'adressage par
 *       contenu fonctionne à travers les sessions (même texte = même
 *       entrée).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class ShapedLayoutCacheTest {

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("int x = 1;\n}")));
        return view;
    }

    private static StyledLine plainLine() {
        return new StyledLine(Collections.emptyList(), 0, 0);
    }

    private static StyledLine spans(LineSpan... spans) {
        return new StyledLine(Arrays.asList(spans), 0, 0);
    }

    // ── Adressage par contenu ─────────────────────────────────────

    @Test
    public void identicalLinesShareOneLayout() {
        EditorView view = newView();
        StaticLayout a = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        StaticLayout b = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        assertSame("deux lignes identiques doivent partager LE MÊME layout", a, b);
        assertNotNull(a);
    }

    @Test
    public void differentTextBuildsDifferentLayouts() {
        EditorView view = newView();
        StaticLayout a = view.shapedLayoutFor("int x = 1;", plainLine(), view.textPaint);
        StaticLayout b = view.shapedLayoutFor("int y = 2;", plainLine(), view.textPaint);
        assertNotSame(a, b);
    }

    @Test
    public void sameTextDifferentSpansRebuilds() {
        EditorView view = newView();
        StyledLine keyword = spans(new LineSpan(0, 3,
                jo.codeeditor.highlight.TokenType.KEYWORD));
        StyledLine comment = spans(new LineSpan(0, 3,
                jo.codeeditor.highlight.TokenType.COMMENT));
        StaticLayout a = view.shapedLayoutFor("int x = 1;", keyword, view.textPaint);
        StaticLayout b = view.shapedLayoutFor("int x = 1;", comment, view.textPaint);
        // Signature différente (type de span) → pas de partage.
        assertNotSame("des spans différents ne doivent JAMAIS être servis depuis la même entrée",
                a, b);
        // Le cache ne garde qu'UNE entrée par texte : re-demander le comment
        // re-sert b (hit), re-demander le keyword reconstruit (miss).
        StaticLayout b2 = view.shapedLayoutFor("int x = 1;", comment, view.textPaint);
        assertSame(b, b2);
        StaticLayout a2 = view.shapedLayoutFor("int x = 1;", keyword, view.textPaint);
        assertNotSame(a, a2);
    }

    @Test
    public void sameTextSameSpansDifferentSpanBoundsRebuilds() {
        EditorView view = newView();
        StyledLine s1 = spans(new LineSpan(0, 3, jo.codeeditor.highlight.TokenType.KEYWORD));
        StyledLine s2 = spans(new LineSpan(0, 4, jo.codeeditor.highlight.TokenType.KEYWORD));
        StaticLayout a = view.shapedLayoutFor("int x = 1;", s1, view.textPaint);
        StaticLayout b = view.shapedLayoutFor("int x = 1;", s2, view.textPaint);
        assertNotSame(a, b);
    }

    @Test
    public void differentBasePaintColorRebuilds() {
        EditorView view = newView();
        StaticLayout a = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        // Le draw path mute la couleur de base du paint (magnifier, fallback…)
        int saved = view.textPaint.getColor();
        view.textPaint.setColor(0xFF123456);
        StaticLayout b = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        assertNotSame("une couleur de base différente doit reconstruire "
                + "(couleur cuite dans le TextPaint capturé)", a, b);
        view.textPaint.setColor(saved);
    }

    // ── Invalidation globale ──────────────────────────────────────

    @Test
    public void themeSwapInvalidatesEverything() {
        EditorView view = newView();
        StaticLayout before = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        view.setTheme(EditorTheme.light());
        assertEquals("setTheme doit vider le cache shaped", 0, view.shapedLayoutCacheSize());
        StaticLayout after = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        assertNotSame(before, after);
    }

    @Test
    public void fontSizeChangeInvalidatesEverything() {
        EditorView view = newView();
        StaticLayout before = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        int revBefore = view.metrics.getFontRevision();
        view.metrics.setTextSize(view.metrics.getTextSize() + 4f);
        assertTrue(view.metrics.getFontRevision() > revBefore);
        StaticLayout after = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        assertNotSame(before, after);
    }

    @Test
    public void typefaceChangeInvalidatesEverything() {
        EditorView view = newView();
        StaticLayout before = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        view.metrics.setTypeface(android.graphics.Typeface.SERIF);
        StaticLayout after = view.shapedLayoutFor("}", plainLine(), view.textPaint);
        assertNotSame(before, after);
    }

    // ── Bornage / LRU ─────────────────────────────────────────────

    @Test
    public void cacheIsBoundedAt64Entries() {
        EditorView view = newView();
        // Remplis bien au-delà de la capacité.
        for (int i = 0; i < 100; i++) {
            view.shapedLayoutFor("line-" + i, plainLine(), view.textPaint);
        }
        assertEquals(EditorShapedLayoutCache.SHAPED_CACHE_CAPACITY, view.shapedLayoutCacheSize());
    }

    @Test
    public void lruEvictionDropsLeastRecentlyUsed() {
        EditorView view = newView();
        view.shapedLayoutFor("victim", plainLine(), view.textPaint);
        // Accède à 64 autres lignes → "victim" est la plus ancienne.
        for (int i = 0; i < EditorShapedLayoutCache.SHAPED_CACHE_CAPACITY; i++) {
            view.shapedLayoutFor("line-" + i, plainLine(), view.textPaint);
        }
        assertEquals(EditorShapedLayoutCache.SHAPED_CACHE_CAPACITY, view.shapedLayoutCacheSize());
        StaticLayout rebuilt = view.shapedLayoutFor("victim", plainLine(), view.textPaint);
        // La victime a été évincée puis reconstruite : le cache est plein
        // et une autre entrée a dû céder sa place (la nouvelle LRU).
        assertEquals(EditorShapedLayoutCache.SHAPED_CACHE_CAPACITY, view.shapedLayoutCacheSize());
        assertNotNull(rebuilt);
    }

    // ── Adressage par contenu inter-sessions ──────────────────────

    @Test
    public void contentAddressingWorksAcrossSessions() {
        EditorView view = newView();
        StaticLayout a = view.shapedLayoutFor("    }", plainLine(), view.textPaint);
        // Nouvelle session, document différent — même texte, mêmes spans,
        // même thème, même police : le layout DOIT être partagé (c'est le
        // principe du contenu-adressé, indépendant de la session).
        view.setSession(new EditorSession(EditorDocument.of("x")));
        StaticLayout b = view.shapedLayoutFor("    }", plainLine(), view.textPaint);
        assertSame(a, b);
    }
}
