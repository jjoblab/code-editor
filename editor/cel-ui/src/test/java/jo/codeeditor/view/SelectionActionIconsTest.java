package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Path;
import android.graphics.RectF;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.icons.EditorIcons;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * B16 (retour terrain) — verrouille <b>l'icône propre de chaque action
 * de sélection</b> dans le menu de débordement « ⋯ » du toolbar de
 * sélection : toutes les rangées de la section MORE affichaient la
 * MÊME ampoule (Partager, Déplacer, Dupliquer… indistinguables).
 *
 * <ul>
 *   <li><b>Actions intégrées</b> — chacune des 7 actions de
 *       {@code BuiltinSelectionActions} porte une icône NON NULLE et
 *       DISTINCTE ;</li>
 *   <li><b>Nouvelles icônes du moteur</b> — DUPLICATE, COMMENT, CASE,
 *       SHARE, MOVE_UP, MOVE_DOWN, MOVE_BLOCK : le path SVG parse sans
 *       exception, non dégénéré, borné dans le viewport 24×24 ;</li>
 *   <li><b>Plomberie du débordement</b> — les actions non affichées de
 *       la pill (section MORE du NavMenu) transportent leur icône
 *       jusqu'à la rangée (le painter la dessine, fallback ampoule si
 *       absente) ;</li>
 *   <li><b>Rétro-compatibilité</b> — une action d'hôte construite sans
 *       icône (anciens constructeurs) reste valide : icône null →
 *       ampoule, jamais de crash.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SelectionActionIconsTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(480, 1920);
        view.layout(0, 0, 480, 1920);
        TestMetrics.injecter(view, 40f, 10f);
        return view;
    }

    // ── 1. Les 7 actions intégrées portent des icônes distinctes ───

    @Test
    public void actionsIntégrées_icônesDistinctesEtNonNulles() {
        EditorView view = newView();
        SelectionAction[] builtins = {
                BuiltinSelectionActions.duplicate(view),
                BuiltinSelectionActions.toggleComment(view),
                BuiltinSelectionActions.convertCase(
                        view, BuiltinSelectionActions.CaseMode.TOGGLE),
                BuiltinSelectionActions.share(view),
                BuiltinSelectionActions.moveLinesUp(view),
                BuiltinSelectionActions.moveLinesDown(view),
                BuiltinSelectionActions.blockDrag(view),
        };
        Set<EditorIcons.Icon> icons = new HashSet<>();
        for (SelectionAction action : builtins) {
            assertNotNull("l'action « " + action.label
                            + " » doit porter une icône de débordement",
                    action.icon);
            icons.add(action.icon);
        }
        assertEquals("7 actions intégrées = 7 icônes DISTINCTES"
                        + " (le retour terrain : tout se ressemblait)",
                7, icons.size());
    }

    // ── 2. Les nouveaux paths du moteur parsent et sont bornés ─────

    @Test
    public void nouvellesIcônesMoteur_parsentBornéesNonDégénérées() {
        EditorIcons.Icon[] nouvelles = {
                EditorIcons.Icon.DUPLICATE,
                EditorIcons.Icon.COMMENT,
                EditorIcons.Icon.CASE,
                EditorIcons.Icon.SHARE,
                EditorIcons.Icon.MOVE_UP,
                EditorIcons.Icon.MOVE_DOWN,
                EditorIcons.Icon.MOVE_BLOCK,
        };
        for (EditorIcons.Icon icon : nouvelles) {
            Path path = EditorIcons.path(icon);
            assertNotNull(icon + " : le path parse", path);
            RectF bounds = EditorIcons.bounds(icon);
            // Non dégénéré : de l'encre sur les deux axes.
            assertTrue(icon + " : largeur d'encre > 2/24",
                    bounds.width() > 2f);
            assertTrue(icon + " : hauteur d'encre > 2/24",
                    bounds.height() > 2f);
            // Borné dans le viewport Material 24×24 (marge 0.5).
            assertTrue(icon + " : bornes dans le viewport (gauche)",
                    bounds.left >= -0.5f);
            assertTrue(icon + " : bornes dans le viewport (haut)",
                    bounds.top >= -0.5f);
            assertTrue(icon + " : bornes dans le viewport (droite)",
                    bounds.right <= 24.5f);
            assertTrue(icon + " : bornes dans le viewport (bas)",
                    bounds.bottom <= 24.5f);
        }
    }

    // ── 3. Le débordement transporte l'icône jusqu'à la rangée ─────

    @Test
    public void débordementDeLaPill_lesActionsGardentLeurIcône() {
        EditorView view = newView();
        // 10 actions d'hôte à libellés LONGS (débordement garanti sur
        // le viewport étroit de 480 px, parité
        // SelectionToolbarOverflowTest), chacune portant une icône du
        // moteur — le plomb testé est bien le TRANSPORT de l'icône,
        // pas la longueur des libellés intégrés.
        EditorIcons.Icon[] roulement = {
                EditorIcons.Icon.DUPLICATE,
                EditorIcons.Icon.COMMENT,
                EditorIcons.Icon.CASE,
                EditorIcons.Icon.SHARE,
                EditorIcons.Icon.MOVE_UP,
                EditorIcons.Icon.MOVE_DOWN,
                EditorIcons.Icon.MOVE_BLOCK,
        };
        for (int i = 1; i <= 10; i++) {
            String id = "Action longue numéro " + i;
            view.addSelectionAction(new SelectionAction(id, id,
                    () -> { }, null, roulement[i % roulement.length]));
        }

        // Sélection armée + pill visible : la métrique est la source
        // unique du débordement.
        int start = DOC.indexOf("name");
        view.getSession().setSelection(Selection.range(start, start + 4));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertNotNull(m);
        assertFalse("viewport étroit : les 10 actions débordent",
                m.overflow.isEmpty());

        // Chaque action débordante garde son icône : la rangée MORE du
        // NavMenu référence la même SelectionAction (row.ref), le
        // painter dessine row.ref.icon (ampoule si null).
        for (SelectionAction action : view.popupManager.selActions.overflow) {
            assertNotNull("l'action débordante « " + action.label
                    + " » garde son icône jusqu'à la rangée", action.icon);
        }
    }

    // ── 4. Rétro-compatibilité : sans icône → null (ampoule) ───────

    @Test
    public void actionHôteSansIcône_icôneNull_ampouleDocumentée() {
        EditorView view = newView();
        // Anciens constructeurs (3 et 4 args) : toujours valides.
        SelectionAction trois =
                new SelectionAction("host.a", "Sans icône", () -> { });
        SelectionAction quatre =
                new SelectionAction("host.b", "Sans icône", () -> { },
                        () -> true);
        assertNull("constructeur 3 args → icône null (fallback ampoule)",
                trois.icon);
        assertNull("constructeur 4 args → icône null (fallback ampoule)",
                quatre.icon);
        // L'action d'hôte PEUT choisir une icône du moteur.
        SelectionAction avec = new SelectionAction("host.c", "Point d'arrêt",
                () -> { }, null, EditorIcons.Icon.BREAKPOINT);
        assertEquals(EditorIcons.Icon.BREAKPOINT, avec.icon);
    }
}
