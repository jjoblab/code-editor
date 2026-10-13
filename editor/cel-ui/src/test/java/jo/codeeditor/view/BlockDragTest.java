package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B13 (P1-2 « drag-déposer de bloc ») — verrouille le pipeline du
 * mode armé par la pill ✥ Move :
 *
 * <ul>
 *   <li><b>Armement</b> — sélection non-caret → armBlockDrag() vrai,
 *       le mode vit jusqu'à la fin du geste ;</li>
 *   <li><b>Dépôt</b> — drag au-delà du slop puis UP : le texte
 *       déménage AU POINT DE DÉPÔT (caret fantôme sous le doigt,
 *       sémantique caractère de moveRange), sélection ré-épinglée
 *       sur le bloc déplacé, loupe refermée, UNE étape d'undo ;</li>
 *   <li><b>Tap = annulation</b> — UP sans mouvement : document
 *       intact, sélection d'origine conservée ;</li>
 *   <li><b>CANCEL</b> — le système interrompt : document intact ;</li>
 *   <li><b>Dépôt dans la source</b> — no-op propre (rien sur la pile
 *       d'annulation) ;</li>
 *   <li><b>Pill</b> — BuiltinSelectionActions.blockDrag : le perform
 *       ARME le mode (contrat Copy/Cut/Paste respecté).</li>
 * </ul>
 *
 * <p>Métriques injectées par réflexion (pattern FastScrollGutterDragTest)
 * : lineH 40, charW 10, padTop 20, padLeft 0, gouttière 70 →
 * textAreaLeft = 70 ; offsetAt(x, y) = lineStart(ligne((y−20)/40)) +
 * (int)((x−70)/10+0.5) borné à la longueur de ligne.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class BlockDragTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    private static final float PAD_TOP = 20f;
    private static final float PAD_BOTTOM = 0f;

    // "aaaa\nbbbb\ncccc\ndddd\neeee\n" — lignes aux offsets 0/5/10/15/20.
    private static final String DOC = "aaaa\nbbbb\ncccc\ndddd\neeee\n";

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view);
        return view;
    }

    private static void injectMetrics(EditorView view) {
        try {
            setFloat(view.metrics, "lineHeight", LINE_H);
            setFloat(view.metrics, "charWidth", CHAR_W);
            setFloat(view.metrics, "padTop", PAD_TOP);
            setFloat(view.metrics, "padBottom", PAD_BOTTOM);
            setFloat(view.metrics, "gutterWidth", CHAR_W * 7f);
            setFloat(view.metrics, "foldStripWidth", CHAR_W * 2f);
            setFloat(view.metrics, "padLeft", 0f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // y=100 → ligne 2 (« cccc », lineStart 10) ; x=140 → col 7 → bornée 4 → offset 14.
    // y=60  → ligne 1 (« bbbb », lineStart 5)  ; x=100 → col 3 → offset 8 (dans « bbbb »).

    // ── 1. Armement ────────────────────────────────────────────────

    @Test
    public void armement_NécessiteUneVraieSélection() {
        EditorView view = newView();
        view.getSession().setSelection(6); // caret seul
        assertFalse("caret seul : pas d'armement", view.armBlockDrag());
        assertFalse(view.isBlockDragArmed());

        view.getSession().setSelection(Selection.range(5, 9)); // « bbbb »
        assertTrue(view.armBlockDrag());
        assertTrue(view.isBlockDragArmed());
        assertFalse("armé mais pas encore engagé", view.isBlockDragActive());
        assertEquals(DOC, view.getSession().getText());
        idle();
    }

    // ── 2. Dépôt réel ──────────────────────────────────────────────

    @Test
    public void dragPuisRelâchement_déplaceLeBlocAuPointDeDépôt() {
        EditorView view = newView();
        EditorSession session = view.getSession();
        session.setSelection(Selection.range(5, 9)); // « bbbb »
        assertTrue(view.armBlockDrag());

        long t = 1000L;
        send(view, MotionEvent.ACTION_DOWN, t, t, 100f, 100f);
        // MOVE au-delà du slop (24 px) : le drag s'engage.
        send(view, MotionEvent.ACTION_MOVE, t, t + 50, 140f, 100f);
        assertTrue("le drag est engagé (loupe + caret fantôme)",
                view.isBlockDragActive());
        assertTrue("la loupe vit pendant le drag", view.magnifierActive);
        // Le point de dépôt suit le doigt : offsetAt(140,100) = 14.
        assertEquals(14, view.blockDragCtl.getDropOffset());

        send(view, MotionEvent.ACTION_UP, t, t + 100, 140f, 100f);
        idle();

        // moveRange(5, 9, 14) : le trou de « bbbb » garde son \n,
        // l'insertion atterrit à 14−4=10 → « aaaa\n\n » + « cccc » +
        // « bbbb » + « \ndddd\neeee\n » (sémantique caractère).
        assertEquals("aaaa\n\nccccbbbb\ndddd\neeee\n", session.getText());
        // Sélection ré-épinglée sur le texte déplacé.
        assertEquals(Selection.range(10, 14), session.getSelection());
        assertEquals("bbbb",
                session.getText().substring(
                        session.getSelection().start, session.getSelection().end));
        // Le mode retombe, la loupe se referme.
        assertFalse(view.isBlockDragArmed());
        assertFalse(view.isBlockDragActive());
        assertFalse(view.magnifierActive);

        // UNE étape d'undo ramène le document d'origine.
        session.undo();
        assertEquals(DOC, session.getText());
    }

    // ── 3. Tap = annulation du mode ────────────────────────────────

    @Test
    public void tapSansMouvement_annuleLeModeSansToucherAuDocument() {
        EditorView view = newView();
        EditorSession session = view.getSession();
        session.setSelection(Selection.range(5, 9));
        assertTrue(view.armBlockDrag());

        long t = 1000L;
        send(view, MotionEvent.ACTION_DOWN, t, t, 100f, 100f);
        // UP au même point (jamais au-delà du slop) : annulation.
        send(view, MotionEvent.ACTION_UP, t, t + 50, 100f, 100f);
        idle();

        assertEquals("le tap annule, le document est intact", DOC, session.getText());
        assertEquals("la sélection d'origine est conservée",
                Selection.range(5, 9), session.getSelection());
        assertFalse(view.isBlockDragArmed());
    }

    // ── 4. CANCEL ──────────────────────────────────────────────────

    @Test
    public void cancelSystème_interrromptLeDragProprement() {
        EditorView view = newView();
        EditorSession session = view.getSession();
        session.setSelection(Selection.range(5, 9));
        assertTrue(view.armBlockDrag());

        long t = 1000L;
        send(view, MotionEvent.ACTION_DOWN, t, t, 100f, 100f);
        send(view, MotionEvent.ACTION_MOVE, t, t + 50, 140f, 100f);
        assertTrue(view.isBlockDragActive());
        send(view, MotionEvent.ACTION_CANCEL, t, t + 80, 140f, 100f);
        idle();

        assertEquals("le CANCEL laisse le document intact", DOC, session.getText());
        assertFalse(view.isBlockDragArmed());
        assertFalse(view.isBlockDragActive());
        assertFalse(view.magnifierActive);
    }

    // ── 5. Dépôt dans la source = no-op ────────────────────────────

    @Test
    public void dépôtDansLaSource_noOpSansPollutionDUndo() {
        EditorView view = newView();
        EditorSession session = view.getSession();
        session.setSelection(Selection.range(5, 9)); // « bbbb »
        assertTrue(view.armBlockDrag());

        long t = 1000L;
        send(view, MotionEvent.ACTION_DOWN, t, t, 100f, 100f);
        // Drag vers l'INTÉRIEUR de la source : y=60 → ligne 1, x=100 →
        // offset 8 (dans « bbbb » 5-9).
        send(view, MotionEvent.ACTION_MOVE, t, t + 50, 100f, 60f);
        send(view, MotionEvent.ACTION_UP, t, t + 100, 100f, 60f);
        idle();

        assertEquals("dépôt dans la source : document identique",
                DOC, session.getText());
        assertFalse(view.isBlockDragArmed());
        // La pile d'undo n'a PAS été polluée : un undo ne doit rien changer.
        session.undo();
        assertEquals(DOC, session.getText());
    }

    // ── 6. Annulation publique (pincement / hôte / changement de session) ──

    @Test
    public void annulationPublique_etPurgeAuChangementDeSession() {
        EditorView view = newView();
        EditorSession session = view.getSession();
        session.setSelection(Selection.range(5, 9));
        assertTrue(view.armBlockDrag());
        // Annulation publique (même chemin que la prise de possession du
        // pincement et du multi-doigts).
        view.cancelBlockDrag();
        assertFalse(view.isBlockDragArmed());
        assertEquals(DOC, session.getText());

        // Armement puis changement de session : le mode retombe (les
        // offsets photographiés décriraient l'ANCIEN document).
        session.setSelection(Selection.range(5, 9));
        assertTrue(view.armBlockDrag());
        EditorSession autre = new EditorSession(EditorDocument.of("xyz\n"));
        view.setSession(autre);
        assertFalse("changement de session : le mode retombe",
                view.isBlockDragArmed());
        idle();
    }

    // ── 7. L'action de la pill ARME le mode ────────────────────────

    @Test
    public void actionDePill_armaLeMode() {
        EditorView view = newView();
        EditorSession session = view.getSession();
        session.setSelection(Selection.range(5, 9));
        SelectionAction action = BuiltinSelectionActions.blockDrag(view);
        assertEquals("builtin.blockdrag", action.id);
        assertTrue(action.label, action.label.contains("Move"));
        assertTrue("sélection vivante → activée", action.isEnabled());
        // Le perform arme le mode (contrat pill : la pill est déjà
        // refermée par le dispatcher AVANT le perform).
        action.perform.run();
        assertTrue(view.isBlockDragArmed());
        idle();

        // Sans sélection : l'item est estompé (isEnabled faux).
        view.cancelBlockDrag();
        session.setSelection(6);
        assertFalse(BuiltinSelectionActions.blockDrag(view).isEnabled());
    }

    // ── 8. Le mode engloutit SON geste ────────────────────────────

    @Test
    public void leGesteDuBlocNeDéplacePasLeCaret() {
        EditorView view = newView();
        EditorSession session = view.getSession();
        session.setSelection(Selection.range(5, 9));
        assertTrue(view.armBlockDrag());

        long t = 1000L;
        // DOWN + UP sur une zone de TEXTE (pas la gouttière) : le tap
        // résout l'annulation du mode — le caret NE BOUGE PAS (un tap
        // normal aurait posé le caret sous le doigt).
        send(view, MotionEvent.ACTION_DOWN, t, t, 100f, 100f);
        send(view, MotionEvent.ACTION_UP, t, t + 50, 100f, 100f);
        idle();
        assertEquals("le caret n'a pas suivi le tap d'annulation",
                Selection.range(5, 9), session.getSelection());
    }
}
