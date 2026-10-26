package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * COMPENSATION DU VIEWPORT (4.5.0) : une édition groupée qui insère ou
 * retire des lignes STRICTEMENT AU-DESSUS de la première ligne visible
 * ne doit plus faire décaler le texte sous les yeux — le cas typique
 * est l'auto-import ajouté en tête de fichier par les éditions
 * additionnelles d'une acceptation de complétion (appliquées en un lot
 * d'annulation, sélection restaurée après — le modèle de
 * SessionEditGroup). Le recentrage du caret est supprimé PENDANT le
 * lot : le caret intermédiaire d'une édition composite ne dirige pas
 * l'écran ; il reprend à la restauration de la sélection.
 */
@RunWith(RobolectricTestRunner.class)
public class ViewportShiftCompensationTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Un document de 120 lignes, vue de 12 rangées, métriques injectées. */
    private EditorView newScrolledView() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append("ligne ").append(i).append('\n');
        }
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(sb.toString()));
        view.setSession(session);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        view.measure(1080, 480);
        view.layout(0, 0, 1080, 480);
        return view;
    }

    /** Scrolle pour que la ligne doc donnée soit le premier rang visible. */
    private static void scrollToDocLine(EditorView view, int docLine) {
        view.scroll.vOffset = view.docLineToY(docLine);
        assertEquals(docLine, view.docLineForScreenY(0));
    }

    /**
     * Applique une édition de la façon d'un groupe d'éditions (le
     * modèle des éditions additionnelles LSP) : un lot d'annulation,
     * la sélection d'avant restaurée après — le caret intermédiaire
     * ne doit jamais diriger l'écran.
     */
    private static void groupeEdition(EditorView view, int start, int end, String texte,
                                      Selection selAvant) {
        view.session.beginBatch();
        try {
            view.session.replaceRange(start, end, texte);
        } finally {
            view.session.endBatch();
        }
        view.session.setSelection(selAvant);
    }

    @Test
    public void insertionAuDessusDuViewport_viewportCompensé() {
        EditorView view = newScrolledView();
        scrollToDocLine(view, 50);
        // Le caret de l'utilisateur vit dans le viewport.
        int caret = view.session.getDocument().lineStart(55);
        view.session.setSelection(Selection.cursor(caret));
        float vOffsetAvant = view.scroll.vOffset;

        // Auto-import : 3 lignes insérées en tête de fichier, en lot,
        // sélection restaurée.
        groupeEdition(view, 0, 0,
                "import a.A;\nimport b.B;\nimport c.C;\n",
                Selection.cursor(caret + "import a.A;\nimport b.B;\nimport c.C;\n".length()));

        // Le contenu lu (ancienne ligne 50, devenue 53) reste ancré en
        // haut : le scroll a suivi de la hauteur réelle des 3 lignes.
        assertEquals(view.docLineToY(53), view.scroll.vOffset, 0.001f);
        assertEquals(53, view.docLineForScreenY(0));
        assertTrue("le scroll a bien avancé", view.scroll.vOffset > vOffsetAvant);
    }

    @Test
    public void suppressionAuDessusDuViewport_viewportCompensé() {
        EditorView view = newScrolledView();
        scrollToDocLine(view, 50);
        int caret = view.session.getDocument().lineStart(55);
        view.session.setSelection(Selection.cursor(caret));

        // Retrait des 2 premières lignes (undo d'un import) : le
        // contenu remonte, le scroll le suit vers le haut.
        int finLigne2 = view.session.getDocument().lineStart(2);
        groupeEdition(view, 0, finLigne2, "", Selection.cursor(caret - finLigne2));

        assertEquals(view.docLineToY(48), view.scroll.vOffset, 0.001f);
        assertEquals(48, view.docLineForScreenY(0));
    }

    @Test
    public void insertionDansLeViewport_aucuneCompensation() {
        EditorView view = newScrolledView();
        scrollToDocLine(view, 50);
        int caret = view.session.getDocument().lineStart(55);
        view.session.setSelection(Selection.cursor(caret));
        float vOffsetAvant = view.scroll.vOffset;

        // Édition AU premier rang visible : comportement standard — le
        // texte pousse vers le bas, le viewport ne bouge pas.
        int debutLigne50 = view.session.getDocument().lineStart(50);
        groupeEdition(view, debutLigne50, debutLigne50, "nouveau\n",
                Selection.cursor(caret + "nouveau\n".length()));

        assertEquals(vOffsetAvant, view.scroll.vOffset, 0.001f);
    }

    @Test
    public void insertionSousLeViewport_aucuneCompensation() {
        EditorView view = newScrolledView();
        scrollToDocLine(view, 50);
        int caret = view.session.getDocument().lineStart(55);
        view.session.setSelection(Selection.cursor(caret));
        float vOffsetAvant = view.scroll.vOffset;

        int debutLigne80 = view.session.getDocument().lineStart(80);
        groupeEdition(view, debutLigne80, debutLigne80, "loin\n",
                Selection.cursor(caret));

        assertEquals(vOffsetAvant, view.scroll.vOffset, 0.001f);
    }

    @Test
    public void cheminComplétion_importAutoCompensé() {
        // La voie COMPLETE d'une acceptation de complétion avec édition
        // additionnelle : le textEdit principal au caret (dans le
        // viewport), puis l'attachment appliquant l'import EN TÊTE en
        // lot avec restauration — le viewport ne doit pas sauter.
        EditorView view = newScrolledView();
        scrollToDocLine(view, 50);
        int debutLigne60 = view.session.getDocument().lineStart(60);
        view.session.setSelection(Selection.cursor(debutLigne60));
        float vOffsetAvant = view.scroll.vOffset;

        CompletionSession.Item item = new CompletionSession.Item(
                "File", "", "File", "c", 7, 0, false, false,
                (Runnable) () -> {
                    Selection sel = view.session.getSelection();
                    view.session.beginBatch();
                    try {
                        view.session.replaceRange(0, 0, "import java.io.File;\n");
                    } finally {
                        view.session.endBatch();
                    }
                    view.session.setSelection(sel);
                });
        view.setCompletionItems(java.util.Collections.singletonList(item),
                debutLigne60, "");

        assertTrue(view.completionAccept());
        assertTrue(view.session.getText().startsWith("import java.io.File;\n"));
        // L'ancienne ligne 50 est devenue 51 — le scroll l'a suivie ;
        // le caret (ligne 61) reste visible sans recentrage violent.
        assertEquals(view.docLineToY(51), view.scroll.vOffset, 0.001f);
        assertTrue(view.scroll.vOffset > vOffsetAvant);
    }

    @Test
    public void clampedAuHautDuDocument_insertionEnTête() {
        // Viewport déjà tout en haut : l'ancre est la ligne 0,
        // l'édition à la ligne 0 n'est pas STRICTEMENT au-dessus —
        // aucune compensation (comportement standard), jamais de
        // scroll négatif.
        EditorView view = newScrolledView();
        view.scroll.vOffset = 0f;
        int caret = view.session.getDocument().lineStart(5);
        view.session.setSelection(Selection.cursor(caret));
        groupeEdition(view, 0, 0, "prelude\n", Selection.cursor(caret + "prelude\n".length()));
        assertEquals(0f, view.scroll.vOffset, 0.001f);
    }
}
