package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.ui.R;

import static org.junit.Assert.assertEquals;

/**
 * T3 (P2) — verrouille la <b>présence des ressources</b> de libellés
 * canoniques et leurs <b>défauts anglais inchangés</b> (les littéraux
 * historiques) :
 *
 * <p>La migration vers les ressources ne doit déplacer AUCUN pixel ni
 * casser les tests visuels existants — les valeurs par défaut sont
 * exactement les littéraux d'avant T3. L'hôte traduit en redéclarant
 * les mêmes noms (préfixe {@code cel_}) dans ses propres ressources
 * (la résolution Android priorise la ressource de l'application).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class CanonicalLabelsResourcesTest {

    private final Context ctx = RuntimeEnvironment.getApplication();

    @Test
    public void pill_deSelection_defautsHistoriques() {
        assertEquals("Copy", ctx.getString(R.string.cel_sel_copy));
        assertEquals("Cut", ctx.getString(R.string.cel_sel_cut));
        assertEquals("Paste", ctx.getString(R.string.cel_sel_paste));
        assertEquals("Select all", ctx.getString(R.string.cel_sel_select_all));
    }

    @Test
    public void actionsSocle_defautsHistoriques() {
        assertEquals("Duplicate", ctx.getString(R.string.cel_builtin_duplicate));
        assertEquals("Comment", ctx.getString(R.string.cel_builtin_comment));
        assertEquals("Share", ctx.getString(R.string.cel_builtin_share));
        // La casse fait partie du libellé historique.
        assertEquals("UPPER", ctx.getString(R.string.cel_case_upper));
        assertEquals("lower", ctx.getString(R.string.cel_case_lower));
        assertEquals("Toggle case", ctx.getString(R.string.cel_case_toggle));
    }

    @Test
    public void menuContextuel_defautsHistoriques() {
        assertEquals("Undo", ctx.getString(R.string.cel_menu_undo));
        assertEquals("Redo", ctx.getString(R.string.cel_menu_redo));
        assertEquals("Organize imports",
                ctx.getString(R.string.cel_menu_organize_imports));
    }

    @Test
    public void diagnostics_defautsHistoriques() {
        assertEquals("Error", ctx.getString(R.string.cel_diag_error));
        assertEquals("Warning", ctx.getString(R.string.cel_diag_warning));
        assertEquals("Info", ctx.getString(R.string.cel_diag_info));
        assertEquals("QUICK FIXES", ctx.getString(R.string.cel_diag_quick_fixes));
        assertEquals("See all", ctx.getString(R.string.cel_diag_see_all));
    }

    @Test
    public void navMenu_defautsHistoriques() {
        assertEquals("GO TO", ctx.getString(R.string.cel_nav_goto));
        assertEquals("INTENTIONS", ctx.getString(R.string.cel_nav_intentions));
        assertEquals("MORE", ctx.getString(R.string.cel_nav_more));
        assertEquals("Nothing found in source.",
                ctx.getString(R.string.cel_nav_nothing_found));
    }
}
