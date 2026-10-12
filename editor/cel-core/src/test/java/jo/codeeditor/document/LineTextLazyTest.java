package jo.codeeditor.document;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code lineText} ne doit PAS matérialiser le document complet.
 *
 * <p>★ B19 : le cache de {@link EditorDocument#getText()} est invalidé à
 * CHAQUE édition — l'ancien {@code lineText} appelait
 * {@code getText().substring(start, end)} : chaque frappe, le rendu et le
 * hit-test (Renderer, TextPainter, HitMapper, WrapGeometry…) recopiaient
 * TOUT le fichier pour lire une tranche de quelques caractères — plusieurs
 * Mo transitoires par frappe sur un gros fichier. {@link #subText} fait
 * le même travail en O(log n + tranche) sans matérialisation.</p>
 *
 * <p>Instrumentation : le champ privé {@code cachedRevision} n'est aligné
 * sur {@code revision} QUE par un appel à {@code getText()} — le lire par
 * réflexion après {@code replace() + lineText()} prouve si la
 * matérialisation a eu lieu.</p>
 */
class LineTextLazyTest {

    /** Construit un document de {@code n} lignes courtes. */
    private static EditorDocument doc(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("ligne ").append(i).append('\n');
        return EditorDocument.of(sb.toString());
    }

    private static int cachedRevision(EditorDocument d) throws Exception {
        Field f = EditorDocument.class.getDeclaredField("cachedRevision");
        f.setAccessible(true);
        return f.getInt(d);
    }

    @Test
    void apresEdition_lineTextNeMaterialisePasLeDocument() throws Exception {
        EditorDocument d = doc(2000);
        // Pré-remplit le cache (comme le ferait un getText initial).
        d.getText();
        assertEquals(d.getRevision(), cachedRevision(d), "le cache doit être chaud");

        // Édition (nouvelle révision → cache invalidé).
        d = d.replace(10, 10, "X");
        assertNotEquals(d.getRevision(), cachedRevision(d),
                "l'édition doit invalider le cache");

        // ★ LE TEST : lineText ne doit PAS reconstruire le texte complet.
        for (int i = 0; i < 50; i++) {
            d.lineText(i);
        }
        assertNotEquals(d.getRevision(), cachedRevision(d),
                "lineText ne doit pas appeler getText() — utiliser subText");
    }

    @Test
    void semantiqueIdentiqueALaReference() {
        // Équivalence stricte avec l'ancien comportement :
        // getText().substring(start, end) pour CHAQUE ligne.
        String texte = "première ligne\n\navec accents éàü\n"
                + "emoji 😀 ligne\n"
                + "windows\r\nfin\n"
                + "dernière sans retour";
        EditorDocument d = EditorDocument.of(texte);
        for (int l = 0; l < d.lineCount(); l++) {
            int start = d.lineStart(l);
            int end = d.lineEnd(l);
            String attendu = start >= end ? "" : d.getText().substring(start, end);
            assertEquals(attendu, d.lineText(l), "ligne " + l);
        }
        // Ligne vide.
        assertEquals("", d.lineText(1));
        // Dernière ligne (pas de \n final).
        assertEquals("dernière sans retour", d.lineText(d.lineCount() - 1));
    }

    @Test
    void bornesClampeesIdentiques() {
        EditorDocument d = EditorDocument.of("ab\ncd\n");
        // Ligne négative / au-delà : clampées comme avant.
        assertEquals(d.lineText(-5), d.lineText(0));
        assertEquals(d.lineText(999), d.lineText(d.lineCount() - 1));
    }

    @Test
    void renduSimule_50LignesVisiblesSansMaterialisation() throws Exception {
        // Reproduit le chemin de rendu : une salve de lineText sur les
        // lignes visibles d'un gros document fraîchement édité.
        EditorDocument d = doc(50000);
        d.getText(); // cache chaud initial
        d = d.replace(100, 100, "é"); // une frappe
        assertNotEquals(d.getRevision(), cachedRevision(d));

        for (int i = 100; i < 150; i++) {
            String ligne = d.lineText(i);
            assertNotNull(ligne);
        }
        assertNotEquals(d.getRevision(), cachedRevision(d),
                "50 lineText ne doivent jamais matérialiser 50 000 lignes");
    }
}
