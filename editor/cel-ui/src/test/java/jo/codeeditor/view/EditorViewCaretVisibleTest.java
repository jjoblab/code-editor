package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * ★ v2.59 — Vérifie l'API {@link EditorView#setCaretVisible(boolean)}
 * introduite pour découpler « cacher le caret » de
 * {@link EditorSession#setReadOnly(boolean)}.
 *
 * <p><b>Contexte du fix</b> : avant v2.59, le {@code ConsoleLogView} utilisait
 * {@code setReadOnly(true)} pour empêcher l'utilisateur de taper ET cacher le
 * caret — mais ce flag bloquait AUSSI les mutations programmatiques
 * (appendLine / setContent / clearAll via {@code EditorSession.replaceRange}),
 * donc la console restait VIDE à vie (l'éditeur ne montrait que le bandeau
 * ligne-1). En découplant les deux rôles ({@code setReadOnly} pour bloquer
 * les éditions, {@code setCaretVisible(false)} pour cacher le caret), la
 * console peut enfin muter son document programmatiquement sans montrer de
 * caret à l'utilisateur.</p>
 *
 * <p><b>Stratégie</b> : Robolectric est utilisé pour fournir un
 * {@link Context} fonctionnel à EditorView. On vérifie :</p>
 * <ol>
 *   <li>{@code isCaretVisible()} retourne {@code true} par défaut après
 *       construction d'un {@link EditorView} neuf.</li>
 *   <li>{@code setCaretVisible(false)} positionne le flag à {@code false}.
 *       Vérifie que le champ interne {@code caretVisible} a changé (par
 *       lecture de {@link EditorView#isCaretVisible()}).</li>
 *   <li>{@code setCaretVisible(true)} après {@code setCaretVisible(false)}
 *       restaure la visibilité — pas d'état figé.</li>
 *   <li><b>Non-régression</b> : un {@link EditorSession} attaché à l'EditorView
 *       sans {@code setReadOnly(true)} accepte une mutation programmatique
 *       via {@code replaceRange} (le document grossit). C'est précisément
 *       le fix qui rend la console de build fonctionnelle.</li>
 * </ol>
 *
 * @author jo@Dev
 * @since v2.59
 */
@RunWith(RobolectricTestRunner.class)
public class EditorViewCaretVisibleTest {

    @Test
    public void caretVisible_defaultIsTrue() {
        EditorView view = newEditorView();
        assertTrue("Le caret doit être visible par défaut après construction",
                view.isCaretVisible());
    }

    @Test
    public void setCaretVisible_false_hidesCaret() {
        EditorView view = newEditorView();
        view.setCaretVisible(false);
        assertFalse("setCaretVisible(false) doit positionner isCaretVisible()=false",
                view.isCaretVisible());
    }

    @Test
    public void setCaretVisible_true_afterFalse_reEnables() {
        EditorView view = newEditorView();
        view.setCaretVisible(false);
        view.setCaretVisible(true);
        assertTrue("setCaretVisible(true) après false doit restaurer isCaretVisible()=true",
                view.isCaretVisible());
    }

    /**
     * Vérifie que drawCaret ne garde pas une référence à EditorSession.readOnly
     * pour décider de la visibilité — c'est-à-dire qu'après le fix, un
     * EditorView avec un EditorSession NON read-only MAIS avec
     * caretVisible=false se comporte comme avant (caret caché). Le champ
     * {@code caretVisible} est la nouvelle source de vérité.
     */
    @Test
    public void setCaretVisible_false_worksWithoutReadOnlySession() {
        EditorView view = newEditorView();
        // Session explicitement NON read-only (comme le fait désormais le
        // ConsoleLogView après le fix v2.59).
        EditorSession session = new EditorSession(EditorDocument.of(""));
        assertFalse("Session neuve ne doit PAS être read-only par défaut",
                session.isReadOnly());
        view.setSession(session);
        view.setCaretVisible(false);
        // Le caret doit être marqué invisible même si la session est mutable.
        assertFalse(view.isCaretVisible());
        // La session reste mutable (la console peut appeler replaceRange).
        assertFalse(session.isReadOnly());
    }

    /**
     * Test de non-régression du fix principal : un EditorView dont la session
     * n'est PAS read-only peut quand même muter son document via
     * {@link EditorSession#replaceRange(int, int, String)}. C'est précisément
     * ce que ConsoleLogView.appendLine() fait — et ce qui était cassé avant
     * v2.59 (setReadOnly(true) bloquait la mutation programmatique).
     */
    @Test
    public void sessionNotReadOnly_acceptsProgrammaticReplaceRange() {
        EditorView view = newEditorView();
        EditorSession session = new EditorSession(EditorDocument.of(""));
        session.setReadOnly(false);  // explicite — comportement nouveau ConsoleLogView
        view.setSession(session);

        int lenBefore = session.getDocument().length();
        session.replaceRange(0, 0, "hello");
        int lenAfter = session.getDocument().length();

        assertTrue("Après replaceRange sur session non read-only, le document doit grossir "
                        + "(avant=" + lenBefore + ", après=" + lenAfter + ")",
                lenAfter > lenBefore);
        assertEquals("Le texte inséré doit être présent dans le document",
                "hello", session.getText());
    }

    /**
     * Vérifie qu'une session read-only bloque ENCORE les mutations (comportement
     * historique inchangé — on n'a pas touché EditorSession.setReadOnly).
     * ConsoleLogView ne doit PLUS l'utiliser, mais d'autres appelants
     * éventuels conservent ce comportement.
     */
    @Test
    public void sessionReadOnly_stillBlocksMutations() {
        EditorView view = newEditorView();
        EditorSession session = new EditorSession(EditorDocument.of(""));
        session.setReadOnly(true);
        view.setSession(session);

        int lenBefore = session.getDocument().length();
        session.replaceRange(0, 0, "hello");
        int lenAfter = session.getDocument().length();

        assertEquals("Sous read-only=true, replaceRange doit être no-op "
                        + "(avant=" + lenBefore + ", après=" + lenAfter + ")",
                lenBefore, lenAfter);
        assertEquals("Le document doit rester vide sous read-only=true",
                "", session.getText());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static EditorView newEditorView() {
        return new EditorView(RuntimeEnvironment.getApplication());
    }
}
