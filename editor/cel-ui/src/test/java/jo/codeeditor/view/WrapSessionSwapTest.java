package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B8 (retour terrain n°5) — « quand le mode retour à la ligne est activé
 * qu'un fichier était ouvert, puis j'ouvre un autre, ça donne un mauvais
 * rendu où les lignes en bas semblent empilées ».
 *
 * <p>Cause racine : {@link EditorView#setSession(EditorSession)} ne
 * reconstruisait JAMAIS le modèle de wrap — le modèle de l'ANCIEN document
 * survit au changement de session, et {@code WrapModel.topRow()} clampe
 * toute ligne au-delà de l'ancien compte de lignes sur le même total :
 * toutes les lignes de queue se dessinent au même Y (« empilées »),
 * d'autant plus visibles que le nouveau fichier est long.</p>
 *
 * <p>Les épreuves conduisent la vue avec le wrap ACTIF (la démo démarre
 * « retour à la ligne » coché), les métriques INJECTÉES (parité avec
 * SingleTapNoSelectionTest — Robolectric ne mesure pas les polices) et
 * changent de document comme le sélecteur de langage de la démo le fait
 * (nouvelle session + setSession).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class WrapSessionSwapTest {

    private static final String SHORT_DOC =
            "public class A {\n"          // 0
            + "    int x = 0;\n"          // 1
            + "}";                         // 2 — 3 lignes, pas de \n final

    /** 60 lignes SANS \n final — remplit l'écran, le scénario du terrain. */
    private static String longDoc() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            if (i > 0) sb.append('\n');
            sb.append("ligne ").append(i).append(" du long document");
        }
        return sb.toString();
    }

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView(String text) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(text));
        view.setSession(session);
        view.setWordWrap(true);
        // Dimensionne la vue (le wrap dépend de la largeur) — sans layout,
        // rebuildWrapModel travaillerait sur une largeur nulle.
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                        1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                        1920, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1920);
        // Métriques injectées (parité SingleTapNoSelectionTest) — le layout
        // EST passé : la reconstruction a déjà eu lieu sur la largeur réelle,
        // il ne reste qu'à fiabiliser lineHeight/charWidth pour les Y.
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private EditorSession sessionOf(String text) {
        return new EditorSession(EditorDocument.of(text));
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    @Test
    public void changementDeSession_reconstruitLeModeleDuNouveauDocument() {
        EditorView view = newView(SHORT_DOC);
        assertEquals("pré-condition : modèle de l'ancien document (3 lignes)",
                3, view.wrapModel.getLineCount());

        // Ouverture d'un fichier BEAUCOUP plus long, wrap toujours actif.
        view.setSession(sessionOf(longDoc()));

        // LE cœur de la régression : le modèle décrit le NOUVEAU document.
        assertEquals("le modèle de wrap suit le nouveau document (60 lignes)",
                60, view.wrapModel.getLineCount());
    }

    @Test
    public void lignesAuDelaDeLAncienCompte_neSontPlusEmpilees() {
        EditorView view = newView(SHORT_DOC);

        view.setSession(sessionOf(longDoc()));

        // Avant B8 : topRow(10) == topRow(30) == topRow(59) == clamp(total
        // de l'ANCIEN modèle) → docLineToY identique → lignes empilées.
        float y10 = view.docLineToY(10);
        float y30 = view.docLineToY(30);
        float y59 = view.docLineToY(59);
        assertTrue("la ligne 30 descend sous la ligne 10 (Y croissant)",
                y30 > y10);
        assertTrue("la ligne 59 descend sous la ligne 30 (Y croissant)",
                y59 > y30);
        assertTrue("l'écart 10→30 vaut au moins 20 hauteurs de ligne",
                y30 - y10 >= 20 * LINE_H - 0.5f);
    }

    @Test
    public void memeCompteDeLignes_rangeesRecompteesSurLeNouveauContenu() {
        // Même compte de lignes (3), contenus différents : l'ancien code
        // gardait l'objet modèle ET ses rangées comptées sur les longueurs
        // de l'ANCIEN document.
        EditorView view = newView("a\nb\nc");
        // Étroite pour que « bbb... » se replie sur plusieurs rangées.
        view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                        200, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                        800, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 200, 800);
        injectMetrics(view, LINE_H, CHAR_W);
        assertEquals(1, view.wrapModel.rowsOf(1));

        String longMiddleLine = "a\n" + "b".repeat(200) + "\nc";
        view.setSession(sessionOf(longMiddleLine));

        assertTrue("la longue ligne du NOUVEAU document est repliée (>1 rangée)",
                view.wrapModel.rowsOf(1) > 1);
    }

    @Test
    public void sessionNull_avecWrap_leModeleEstPurge() {
        EditorView view = newView(SHORT_DOC);
        assertTrue(view.wrapModel != null);

        view.setSession(null);

        assertNull("sans session, le modèle meurt avec son document",
                view.wrapModel);
    }
}
