package jo.codeeditor.session;

import jo.codeeditor.highlight.FoldDetector;
import jo.codeeditor.shift.DiagnosticShift;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B14 (P1-5 « plis par niveau + régions ») — verrouille le pliage
 * SÉLECTIF par profondeur d'imbrication (« Replier au niveau N »,
 * sémantique VS Code Fold Level N : profondeur EXACTE — 1 = classes,
 * 2 = méthodes) et les commandes « tout replier / tout déplier ».
 *
 * <ul>
 *   <li><b>Niveau 1</b> — les régions RACINES se replient (la classe),
 *       pas leurs enfants ;</li>
 *   <li><b>Niveau 2</b> — les MÉTHODES se replient, la classe reste
 *       OUVERTE ;</li>
 *   <li><b>Niveau 3</b> — les blocs internes, indépendamment ;</li>
 *   <li><b>Tout replier / tout déplier</b> — tous niveaux, retour
 *       compté ;</li>
 *   <li><b>Niveau invalide</b> (0) — no-op ;</li>
 *   <li><b>Régions nommées</b> — l'espace après le marqueur devient
 *       facultatif (C# « #region », IntelliJ « //region ») et le NOM
 *       suit la région (placeholder de la chip).</li>
 * </ul>
 *
 * @author jo@Dev
 */
public class CollapseFoldLevelsTest {

    // class A {            ligne 0  ┐ pli classe (niveau 1)
    //     int x = 0;       ligne 1  │
    //     void m() {       ligne 2  ┐ pli méthode m (niveau 2)
    //         if (x > 0) { ligne 3  ┐ pli if (niveau 3)
    //             x--;     ligne 4  │
    //         }            ligne 5  ┘
    //     }                ligne 6  ┘
    //     void n() {       ligne 7  (trop courte : pas de pli)
    //     }                ligne 8
    // }                    ligne 9  ┘
    private static final String DOC =
        "class A {\n"
        + "    int x = 0;\n"
        + "    void m() {\n"
        + "        if (x > 0) {\n"
        + "            x--;\n"
        + "        }\n"
        + "    }\n"
        + "    void n() {\n"
        + "    }\n"
        + "}\n";

    private static EditorSession sessionAvecPlis() {
        EditorSession s = new SessionBuilder(DOC).build();
        s.setFoldRegions(FoldDetector.detect(DOC, "java"));
        return s;
    }

    // ── Niveaux ─────────────────────────────────────────────────

    @Test
    public void niveau1_replieLesRacines_pasLesEnfants() {
        EditorSession s = sessionAvecPlis();
        assertEquals(3, s.getFoldRegions().size(),
                "trois plis détectés (classe, m, if)");
        assertEquals(1, s.collapseFoldsAtLevel(1), "une seule racine (la classe)");
        assertTrue(s.hasCollapsedFolds());
        // La classe repliée masque tout son corps (lignes 1-9).
        assertTrue(s.isLineFolded(1));
        assertTrue(s.isLineFolded(4));
    }

    @Test
    public void niveau2_replieLesMéthodes_laClasseResteOuverte() {
        EditorSession s = sessionAvecPlis();
        assertEquals(1, s.collapseFoldsAtLevel(2), "une méthode pliable (m)");
        // m replié masque ses lignes internes (3-6)…
        assertTrue(s.isLineFolded(4));
        assertTrue(s.isLineFolded(6));
        // …mais la classe est OUVERTE : la ligne 1 (hors m) est visible.
        assertFalse(s.isLineFolded(1));
    }

    @Test
    public void niveau3_replieLesBlocsInternes() {
        EditorSession s = sessionAvecPlis();
        assertEquals(1, s.collapseFoldsAtLevel(3), "un bloc interne (le if)");
        assertTrue(s.isLineFolded(4));
        // Classe et méthode restent ouvertes : lignes 1 et 6 visibles.
        assertFalse(s.isLineFolded(1));
        assertFalse(s.isLineFolded(6));
    }

    @Test
    public void niveau2_neTouchePasLaClasse_repliéeAuNiveau1() {
        // Les deux niveaux cohabitent : replier 1 PUIS 2 — la classe
        // repliée cache déjà tout ; développer la classe révèle les
        // méthodes ENCORE repliées (chaque région porte son état).
        EditorSession s = sessionAvecPlis();
        s.collapseFoldsAtLevel(1);
        s.collapseFoldsAtLevel(2);
        assertEquals(2, s.getCollapsedFolds().size());
        // Déplie TOUT : les deux reviennent (le if, jamais replié, ne
        // compte pas).
        assertEquals(2, s.expandAllFolds());
        assertFalse(s.hasCollapsedFolds());
        assertEquals(0, s.getCollapsedFolds().size());
    }

    // ── Tout replier / tout déplier ────────────────────────────

    @Test
    public void toutReplier_puisToutDéplier() {
        EditorSession s = sessionAvecPlis();
        assertEquals(3, s.collapseAllFolds(), "classe + m + if");
        assertTrue(s.hasCollapsedFolds());
        assertEquals(3, s.getCollapsedFolds().size());
        assertEquals(3, s.expandAllFolds());
        assertFalse(s.hasCollapsedFolds());
        // Idempotence : re-tout déplier ne change rien.
        assertEquals(0, s.expandAllFolds());
    }

    @Test
    public void niveauInvalide_noOp() {
        EditorSession s = sessionAvecPlis();
        assertEquals(0, s.collapseFoldsAtLevel(0));
        assertEquals(0, s.collapseFoldsAtLevel(-1));
        assertFalse(s.hasCollapsedFolds());
    }

    // ── Régions nommées : espace facultatif (B14) ───────────────

    @Test
    public void régionsMarqueurSansEspace_CSharp_etIntelliJ() {
        String doc =
            "#region Champs\n"
            + "int a;\n"
            + "int b;\n"
            + "#endregion\n"
            + "//region Utilitaires\n"
            + "void u1() {\n"
            + "}\n"
            + "void u2() {\n"
            + "}\n"
            + "//endregion\n";
        List<DiagnosticShift.FoldRegion> folds =
                FoldDetector.detectNamedRegions(doc);
        assertEquals(2, folds.size());
        // Les NOMS suivent la région (chips « Champs » / « Utilitaires »).
        assertEquals("Champs", folds.get(0).placeholder);
        assertEquals("Utilitaires", folds.get(1).placeholder);
        assertEquals("region", folds.get(0).kind);
        // La région C# couvre bien des lignes (>= 3 exigées).
        assertTrue(folds.get(0).end > folds.get(0).start);
    }

    @Test
    public void régionsEspacées_toujoursDétectées_régression() {
        // Les formes HISTORIQUES (avec espace) restent détectées.
        String doc =
            "// region Services\n"
            + "void s1() {\n"
            + "}\n"
            + "void s2() {\n"
            + "}\n"
            + "// endregion\n";
        List<DiagnosticShift.FoldRegion> folds =
                FoldDetector.detectNamedRegions(doc);
        assertEquals(1, folds.size());
        assertEquals("Services", folds.get(0).placeholder);
    }

    @Test
    public void régionsNested_laProfondeurCompte() {
        // Une région DANS une région : la profondeur d'imbrication
        // sépare les niveaux (la hiérarchie du pli par niveau s'y
        // applique aussi).
        String doc =
            "// region Externe\n"
            + "int a;\n"
            + "int b;\n"
            + "#region Interne\n"
            + "int c;\n"
            + "int d;\n"
            + "#endregion\n"
            + "// endregion\n";
        EditorSession s = new SessionBuilder(doc).build();
        s.setFoldRegions(FoldDetector.detectNamedRegions(doc));
        assertEquals(2, s.getFoldRegions().size());
        assertEquals(1, s.collapseFoldsAtLevel(1), "seule la région externe est racine");
        assertTrue(s.isLineFolded(1));
        // L'interne est de niveau 2 : NON touchée (état indépendant).
        assertEquals(1, s.getCollapsedFolds().size());
    }
}
