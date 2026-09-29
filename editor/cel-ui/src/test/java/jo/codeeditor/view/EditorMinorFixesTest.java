package jo.codeeditor.view;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B21 (b, g, i, j) — mineurs cel-ui : garde de complétion, schedule de
 * diagnostics différé (plus d'exécution synchrone sans Handler), rename
 * fallback à sémantique inchangée (comparaison en place), résultats de
 * références jetés quand la vue est détachée.
 */
@RunWith(RobolectricTestRunner.class)
public class EditorMinorFixesTest {

    // ── B21b : complétion — caret reculé avant le token ────────────

    @Test
    public void completionAccept_withCaretBeforeTokenStart_isRejected() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("int foo;\nint bar;\n"));
        view.setSession(session);
        view.layout(0, 0, 1080, 1920);

        // Pose un état de complétion : token à [4..7), caret au token end.
        session.setSelection(7);
        view.setCompletionItems(java.util.Collections.singletonList(
                new jo.codeeditor.completion.CompletionSession.Item(
                        "foo", "int variable", "foo", "v", 0, 0, false, false)), 4, "fo");
        assertTrue("pré-condition : le popup doit être visible",
                view.isCompletionVisible());

        // Le caret recule AVANT le début du token (flèches/clic).
        session.setSelection(1);
        boolean accepted = view.completionAccept();
        assertFalse("l'acceptation doit être rejetée (caret < tokenStart)", accepted);
        assertEquals("le texte ne doit pas être muté",
                "int foo;\nint bar;\n", session.getDocument().getText());
    }

    // ── B21g : diagnostics — plus d'exécution synchrone sans Handler ──

    @Test
    public void diagnosticsSchedule_withoutHandler_doesNotRunSynchronously() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("abc\n"));
        view.setSession(session);
        // Vue jamais attachée : getHandler() == null sous Robolectric.
        view.layout(0, 0, 1080, 1920);
        Assume.assumeFalse("l'environnement a attaché la vue — prémisses invalides",
                view.getHandler() != null);

        AtomicInteger runs = new AtomicInteger();
        view.diagnosticsPusher.attach(text -> {
            runs.incrementAndGet();
            return java.util.Collections.emptyList();
        });

        // attach() planifie un run initial — SANS Handler il ne doit PAS
        // s'exécuter en synchrone (compteur à 0 après attach + schedule).
        view.diagnosticsPusher.schedule();
        assertEquals("aucune exécution synchrone sans Handler (B21g)",
                0, runs.get());
    }

    // ── B21i : rename fallback — sémantique inchangée, zéro substring ──

    @Test
    public void renameFallback_replacesWholeWords_only() throws Exception {
        Method m = Class.forName("jo.codeeditor.view.popup.EditorRenamePopup")
                .getDeclaredMethod("substringRename", String.class, String.class, String.class);
        m.setAccessible(true);

        // Remplacements entiers de mots.
        Object r1 = m.invoke(null, "int foo; fooBar foo( foo );", "foo", "baz");
        assertEquals("int baz; fooBar baz( baz );", r1);

        // Aucun remplacement → null.
        Object r2 = m.invoke(null, "int bar;", "foo", "baz");
        assertEquals(null, r2);

        // Préfixe/suffixe de mot protégés → aucun remplacement → null.
        Object r3 = m.invoke(null, "_foo foo_ xfoox", "foo", "baz");
        assertEquals(null, r3);
    }

    // ── B21j : références — résultat jeté sans Handler (vue détachée) ──

    @Test
    public void referencesResult_withoutHandler_isDropped() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("foo();\n"));
        view.setSession(session);
        view.layout(0, 0, 1080, 1920);
        Assume.assumeFalse("l'environnement a attaché la vue — prémisses invalides",
                view.getHandler() != null);
        // Détache la vue : le handler devient null → le résultat doit être jeté.
        view.onDetachedFromWindow();

        CountDownLatch resolved = new CountDownLatch(1);
        view.setReferencesResolver((text, offset) -> {
            resolved.countDown();
            return List.of(new DefinitionLocation("f.java", 0, "foo"));
        });

        session.setSelection(0);
        view.showReferences(); // planifie la résolution async
        assertTrue("le resolver doit être appelé", resolved.await(5, TimeUnit.SECONDS));

        // Attend la fin de la tâche async (résolution puis branche apply).
        Thread.sleep(150);
        assertFalse("le popup ne doit PAS s'ouvrir contre une vue détachée (B21j)",
                view.isReferencesVisible());
    }
}
