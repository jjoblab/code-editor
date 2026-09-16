package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ v2.30 — Smoke tests du RENDU du popup de complétion avec badges de
 * type (portage KindBadge de CodeAssist) : la chaîne complète — badge
 * (glyphe + teinte), label avec runs de match du préfixe, detail aligné
 * à droite — ne doit pas planter sur un Canvas réel, quel que soit le
 * kind (LSP moderne, tag data, ou provider v1.x sans kind).
 *
 * @author jo@Dev
 * @since v2.30
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionPopupBadgeRenderTest {

    private EditorView viewWithCompletion(CompletionSession.Item... items) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of("public class Main {\n    obj\n}\n"));
        view.setSession(session);
        List<CompletionSession.Item> list = new ArrayList<>();
        for (CompletionSession.Item it : items) list.add(it);
        view.completionItems.clear();
        view.completionItems.addAll(list);
        view.completionVisible = true;
        view.completionSelected = 0;
        view.completionScrollOffset = 0;
        view.completionPrefix = "ob";
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
    public void drawsPopupWithModernLspKinds() {
        EditorView view = viewWithCompletion(
                new CompletionSession.Item("obj", "java.lang.Object", "obj",
                        "v", 6, 10, false, false),
                new CompletionSession.Item("Object", "java.lang", "Object",
                        "c", 7, 9, false, false),
                new CompletionSession.Item("Observable", "java.util", "Observable",
                        "c", 7, 8, false, false));
        drawPopup(view); // ne doit pas jeter
    }

    @Test
    public void drawsPopupWithKindTags() {
        // Annotation (Class + data="annotation") et record (data="record").
        EditorView view = viewWithCompletion(
                new CompletionSession.Item("Override", "java.lang", "Override",
                        "c", 7, 10, false, false, "annotation", null),
                new CompletionSession.Item("Point", "x.y", "Point",
                        "c", 7, 9, false, false, "record", null));
        drawPopup(view);
    }

    @Test
    public void drawsPopupWithLegacyProvidersAndKeywords() {
        // Provider v1.x : kind inconnu + icône string historique ;
        // mot-clé builtin (kind 14) ; snippet « {} ».
        EditorView view = viewWithCompletion(
                new CompletionSession.Item("obj", "", "obj", "v", 0, 5, false, false),
                new CompletionSession.Item("public", "keyword", "public",
                        "k", 14, 3, true, false),
                new CompletionSession.Item("objStream", "", "objStream",
                        "{}", 15, 2, false, true));
        drawPopup(view);
    }

    @Test
    public void drawsPopupWithEmptyPrefixAndNoDetail() {
        EditorView view = viewWithCompletion(
                new CompletionSession.Item("foo", null, "foo", "m", 2, 4, false, false));
        view.completionPrefix = "";
        drawPopup(view);
    }

    @Test
    public void drawingWithInvisiblePopupIsANoOp() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("x")));
        view.completionVisible = false;
        drawPopup(view); // pas de NPE sur popup invisible
        assertTrue(view.completionItems.isEmpty());
    }
}
