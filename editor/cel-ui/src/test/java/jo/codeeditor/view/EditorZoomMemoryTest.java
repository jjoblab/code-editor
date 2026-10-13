package jo.codeeditor.view;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B12 (P1-3) — unitaire de la mémoire de zoom par type de fichier :
 * roundtrip, bornage, défaut, oubli, purge, sûreté nulle.
 *
 * <p>Classe 100 % Java (aucune dépendance Android) : JUnit nu, sans
 * Robolectric. {@code clear()} en {@code @Before} est OBLIGATOIRE —
 * l'état est STATIQUE et fuirait entre les classes de test.</p>
 *
 * @author jo@Dev
 */
public class EditorZoomMemoryTest {

    @Before
    public void resetMemoire() {
        EditorZoomMemory.clear();
    }

    @Test
    public void roundtrip_uneCléUneÉchelle() {
        EditorZoomMemory.remember(".java", 1.8f);
        assertEquals(1.8f, EditorZoomMemory.recall(".java"), 0f);
    }

    @Test
    public void rappelParDéfaut_ÉchelleUn() {
        assertFalse(EditorZoomMemory.isRemembered(".md"));
        assertEquals(1f, EditorZoomMemory.recall(".md"), 0f);
    }

    @Test
    public void rappelSansClé_ÉchelleUnSansMémoriser() {
        assertEquals(1f, EditorZoomMemory.recall(null), 0f);
        EditorZoomMemory.remember(null, 2.5f); // no-op silencieux
        assertFalse(EditorZoomMemory.isRemembered(null));
    }

    @Test
    public void mémorisationBornée_auxPlagesDuZoom() {
        EditorZoomMemory.remember(".x", 9f);
        assertEquals(2.6f, EditorZoomMemory.recall(".x"), 0f);
        EditorZoomMemory.remember(".y", 0.01f);
        assertEquals(0.6f, EditorZoomMemory.recall(".y"), 0f);
    }

    @Test
    public void clésIndépendantes_laDernièreÉcritureGagne() {
        EditorZoomMemory.remember(".java", 1.4f);
        EditorZoomMemory.remember(".md", 0.8f);
        assertEquals(1.4f, EditorZoomMemory.recall(".java"), 0f);
        assertEquals(0.8f, EditorZoomMemory.recall(".md"), 0f);
        EditorZoomMemory.remember(".java", 1.6f);
        assertEquals(1.6f, EditorZoomMemory.recall(".java"), 0f);
    }

    @Test
    public void oubliPUIS_purge() {
        EditorZoomMemory.remember(".java", 1.4f);
        EditorZoomMemory.forget(".java");
        assertFalse(EditorZoomMemory.isRemembered(".java"));
        assertEquals(1f, EditorZoomMemory.recall(".java"), 0f);
        // forget d'une clé inconnue : sans incident.
        EditorZoomMemory.forget(".java");

        EditorZoomMemory.remember(".a", 1.2f);
        EditorZoomMemory.remember(".b", 1.3f);
        EditorZoomMemory.clear();
        assertFalse(EditorZoomMemory.isRemembered(".a"));
        assertFalse(EditorZoomMemory.isRemembered(".b"));
    }
}
