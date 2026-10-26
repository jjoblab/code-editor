package jo.codeeditor.demo.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test du tiroir « Fichiers » (refonte UX 3.73.0) : filtrage insensible
 * à la casse et aux accents, reconstruction des sections par famille,
 * badges de capacité moteur et remontée des sélections.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class SampleDrawerTest {

    // ── Filtrage (insensible casse + accents) ──────────────────────

    @Test
    public void leFiltreCorrespondSansCasseNiAccents() {
        assertTrue(SampleDrawer.matches(null, "Java"));
        assertTrue(SampleDrawer.matches("", "Java"));
        assertTrue(SampleDrawer.matches("   ", "Java"));
        assertTrue(SampleDrawer.matches("jav", "Java"));
        assertTrue(SampleDrawer.matches("JAVA", "Java"));
        // Le « é » de Minifié se plie en « e » : « minifie » trouve.
        assertTrue(SampleDrawer.matches("minifie", "Minifié (JS)"));
        assertTrue(SampleDrawer.matches("C++", "C++"));
        assertFalse(SampleDrawer.matches("rust", "Java"));
        assertFalse(SampleDrawer.matches("kotlin", "Java"));
    }

    // ── Construction + reconstruction ──────────────────────────────

    @Test
    public void leTiroirListeLes34ÉchantillonsEtFiltreEnDirect() {
        Application app = RuntimeEnvironment.getApplication();
        AtomicInteger selected = new AtomicInteger(0);
        final String[] picked = {null, null};
        SampleDrawer drawer = new SampleDrawer(app, new SampleDrawer.Listener() {
            @Override
            public void onSampleSelected(String label, String sampleId) {
                selected.incrementAndGet();
                picked[0] = label;
                picked[1] = sampleId;
            }

            @Override
            public void onSettings() { }

            @Override
            public void onAbout() { }
        }, id -> "java".equals(id) || "csharp".equals(id), id -> false);

        LinearLayout sections = drawer.sectionsForTest();
        // Sans filtre : 5 en-têtes de famille + 34 échantillons
        // (32 langages + stress « Minifié (JS) », plan 11 : dockerfile
        // et les deux charges Java — 4.5.1 : le banc « Postfix »).
        int familyHeaders = 0;
        int rows = 0;
        for (int i = 0; i < sections.getChildCount(); i++) {
            Object tag = sections.getChildAt(i).getTag();
            if ("family".equals(tag)) familyHeaders++;
            else if ("sample".equals(tag)) rows++;
        }
        assertEquals(5, familyHeaders);
        assertEquals(34, rows);
        assertEquals(0, selected.get());

        // Le champ de filtrage reconstruit en direct : « rust » ne
        // laisse qu'une famille et une ligne.
        EditText filter = drawer.filterForTest();
        filter.setText("rust");
        familyHeaders = 0;
        rows = 0;
        for (int i = 0; i < sections.getChildCount(); i++) {
            Object tag = sections.getChildAt(i).getTag();
            if ("family".equals(tag)) familyHeaders++;
            else if ("sample".equals(tag)) rows++;
        }
        assertEquals(1, familyHeaders);
        assertEquals(1, rows);
        assertEquals("rust", drawer.filterText());

        // Filtre sans résultat : message dédié, zéro rangée.
        filter.setText("zzzz");
        rows = 0;
        for (int i = 0; i < sections.getChildCount(); i++) {
            if ("sample".equals(sections.getChildAt(i).getTag())) rows++;
        }
        assertEquals(0, rows);

        // Effacement : tout revient.
        filter.setText("");
        rows = 0;
        for (int i = 0; i < sections.getChildCount(); i++) {
            if ("sample".equals(sections.getChildAt(i).getTag())) rows++;
        }
        assertEquals(34, rows);
    }

    @Test
    public void leTiroirAnnonceLesSélections() {
        Application app = RuntimeEnvironment.getApplication();
        final String[] picked = {null, null};
        SampleDrawer drawer = new SampleDrawer(app, new SampleDrawer.Listener() {
            @Override
            public void onSampleSelected(String label, String sampleId) {
                picked[0] = label;
                picked[1] = sampleId;
            }

            @Override
            public void onSettings() { }

            @Override
            public void onAbout() { }
        }, id -> "csharp".equals(id), id -> true);

        // Simule le tap sur la rangée « C# » : la sélection remonte
        // avec le couple libellé/identifiant exact.
        LinearLayout sections = drawer.sectionsForTest();
        int csharpRow = -1;
        for (int i = 0; i < sections.getChildCount(); i++) {
            android.view.View child = sections.getChildAt(i);
            if ("sample".equals(child.getTag())
                    && child instanceof android.view.ViewGroup) {
                android.view.View name = ((android.view.ViewGroup) child).getChildAt(1);
                if (name instanceof android.widget.TextView
                        && "C#".equals(((android.widget.TextView) name).getText())) {
                    csharpRow = i;
                    break;
                }
            }
        }
        assertTrue(csharpRow >= 0);
        sections.getChildAt(csharpRow).performClick();
        assertEquals("C#", picked[0]);
        assertEquals("csharp", picked[1]);
    }
}
