package jo.codeeditor.view.render;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.util.Collections;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import static org.junit.Assert.*;

/**
 * Rendu du popup avec les champs riches (4.3.0) : origine du symbole
 * ({@code container} en chip à droite de la ligne du libellé, tronquée
 * proprement à l'ellipse au-delà de 40 % de la largeur) et documentation
 * transportée — sans empiéter sur le libellé ni rétrécir la seconde
 * ligne. Smoke tests sur un Canvas réel : la chaîne complète ne doit
 * jamais planter, quel que soit le remplissage.
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionContainerRenderTest {

    private EditorView viewWithItem(CompletionSession.Item item) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of("public class Main {\n    obj\n}\n"));
        view.setSession(session);
        view.completion.items.clear();
        view.completion.items.addAll(Collections.singletonList(item));
        view.completion.visible = true;
        view.completion.selected = 0;
        view.completion.scrollOffset = 0;
        view.completion.prefix = "obj";
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private void drawPopup(EditorView view) {
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        EditorRenderer renderer = new EditorRenderer(view);
        renderer.drawCompletionPopup(canvas);
    }

    @Test
    public void dessine_avecContainerEtDocumentation() {
        CompletionSession.Item item = new CompletionSession.Item(
                "obj", "java.lang.Object", "obj", "v", 6, 10, false, false,
                null, null, null, -1, -1, null,
                "java.util", "Retourne un objet.");
        drawPopup(viewWithItem(item)); // ne doit pas jeter
    }

    @Test
    public void dessine_containerTresLong_tronqueSansEmpieter() {
        // Un paquet profond dépasse la zone droite (40 % du popup) :
        // la troncature à l'ellipse doit tenir sans planter et sans
        // repousser le label hors du popup.
        CompletionSession.Item item = new CompletionSession.Item(
                "of", "java.util.List", "of", "m", 2, 10, false, false,
                null, null, null, -1, -1, null,
                "com.example.tres.long.chemin.de.paquet.interne.Type",
                null);
        drawPopup(viewWithItem(item)); // ne doit pas jeter
    }

    @Test
    public void dessine_containerSeulSansDetail() {
        CompletionSession.Item item = new CompletionSession.Item(
                "of", "", "of", "m", 2, 10, false, false,
                null, null, null, -1, -1, null,
                "java.util", null);
        drawPopup(viewWithItem(item)); // ne doit pas jeter
    }

    @Test
    public void dessine_sansContainer_dispositionDeuxLignes() {
        // container absent (tous les serveurs d'aujourd'hui) : badge,
        // label, et l'identité qualifiée sous le libellé.
        CompletionSession.Item item = new CompletionSession.Item(
                "obj", "java.lang.Object", "obj", "v", 6, 10, false, false);
        drawPopup(viewWithItem(item)); // ne doit pas jeter
    }
}
