package jo.codeeditor.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.theme.ColorAttributes;
import jo.codeeditor.theme.EditorColorScheme;

import static org.junit.Assert.assertEquals;

/**
 * Rendu des modificateurs sémantiques (4.4.0) : le masque canonique des
 * jetons devient une surcouche de STYLE dans les SemSpan mis en cache —
 * déprécié barré, statique gras, abstrait italique, combinés OR'és — et
 * une surcouche de TEINTE optionnelle du registre (sans opinion de
 * scheme, la couleur du jeton est conservée telle quelle).
 */
@RunWith(RobolectricTestRunner.class)
public class SemanticModifierStylesTest {

    /** Ligne 1 : «     int total = old; » — « total » @25 (5 car.), « old » @33 (3 car.). */
    private static final String DOC = "public class A {\n    int total = old;\n}\n";

    private EditorView view;
    private EditorSession session;

    @Before
    public void setUp() {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
    }

    private LineRenderCache.SemSpan spanAprèsDessin(int type, int modifiers, int start, int len) {
        session.setSemanticTokens(List.of(
                new DiagnosticShift.SemanticToken(start, len, type, modifiers)));
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        LineRenderCache.LineCacheEntry e =
                view.layoutForLine(1, "    int total = old;");
        assertEquals("précondition : le jeton a produit un span", 1, e.semSpans.size());
        return e.semSpans.get(0);
    }

    @Test
    public void statique_grasEtCouleurDeBaseConservée() {
        LineRenderCache.SemSpan s = spanAprèsDessin(7,
                DiagnosticShift.SemanticToken.MOD_STATIC, 25, 5);
        assertEquals(LineRenderCache.SemSpan.STYLE_BOLD, s.styleFlags);
        // Sans opinion de scheme : la couleur du TYPE de jeton est
        // conservée (ici VARIABLE, type canonique 7).
        assertEquals(view.theme.colorForToken(TokenType.VARIABLE), s.color);
    }

    @Test
    public void déprécié_barré() {
        LineRenderCache.SemSpan s = spanAprèsDessin(7,
                DiagnosticShift.SemanticToken.MOD_DEPRECATED, 25, 5);
        assertEquals(LineRenderCache.SemSpan.STYLE_STRIKE, s.styleFlags);
        assertEquals(view.theme.colorForToken(TokenType.VARIABLE), s.color);
    }

    @Test
    public void abstrait_italique() {
        LineRenderCache.SemSpan s = spanAprèsDessin(2,
                DiagnosticShift.SemanticToken.MOD_ABSTRACT, 25, 5);
        assertEquals(LineRenderCache.SemSpan.STYLE_ITALIC, s.styleFlags);
        assertEquals(view.theme.colorForToken(TokenType.TYPE), s.color);
    }

    @Test
    public void combinés_statiqueEtAbstrait_grasItalique() {
        LineRenderCache.SemSpan s = spanAprèsDessin(7,
                DiagnosticShift.SemanticToken.MOD_STATIC
                        | DiagnosticShift.SemanticToken.MOD_ABSTRACT, 25, 5);
        assertEquals(LineRenderCache.SemSpan.STYLE_BOLD | LineRenderCache.SemSpan.STYLE_ITALIC,
                s.styleFlags);
    }

    @Test
    public void sansModificateur_champHistoriqueAucunStyle() {
        LineRenderCache.SemSpan s = spanAprèsDessin(7, 0, 25, 5);
        assertEquals("le jeton sans modificateur reste sur le chemin historique",
                0, s.styleFlags);
    }

    @Test
    public void teinteDuRegistre_sansOpinionCouleurConservée_avecOpinionAppliquée() {
        // Sans opinion : la couleur de base passe (testée plus haut).
        // Avec opinion : le scheme teinte le jeton déprécié.
        EditorColorScheme scheme = EditorColorScheme.createDefault();
        scheme.setColor(ColorAttributes.KEY_SEMANTIC_DEPRECATED, 0xFF9A3B3B,
                view.isDarkTheme());
        view.setColorScheme(scheme);
        LineRenderCache.SemSpan s = spanAprèsDessin(7,
                DiagnosticShift.SemanticToken.MOD_DEPRECATED, 25, 5);
        assertEquals(LineRenderCache.SemSpan.STYLE_STRIKE, s.styleFlags);
        assertEquals("l'opinion du scheme teinte le jeton déprécié",
                0xFF9A3B3B, s.color);
    }
}
