package jo.codeeditor.highlight;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.FoldStrategy;
import jo.codeeditor.shift.DiagnosticShift;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 2 (audit v3.41.0) — tests de la nouvelle stratégie de pliage
 * pilotée par le {@link LanguageProfile profil de langage} via le
 * {@link FoldStrategy} enum. Ces tests échouent avant le Lot 2 car
 * l'ancien {@link FoldDetector#detect} dispatchait par une chaîne
 * de comparaisons de strings (« xml », « python », etc.) qui :
 * <ul>
 *   <li>ne reconnaissait pas les alias {@code html}, {@code htm},
 *       {@code svg} (enregistrés dans le registre comme alias de
 *       xml) ;</li>
 *   <li>ne reconnaissait pas YAML (pas de branche dans le switch) ;</li>
 *   <li>ne détectait pas les tableaux JSON {@code [ … ]}, seulement
 *       les objets {@code { … }} ;</li>
 *   <li>ne détectait pas les commentaires de bloc et les javadoc en
 *       C-like ;</li>
 *   <li>n'utilisait pas le registre pour les langages contribués.</li>
 * </ul>
 */
class FoldStrategyTest {

    @AfterEach
    void cleanup() {
        // reset via réflexion (package-private).
        try {
            java.lang.reflect.Method m = Class.forName(
                    "jo.codeeditor.languages.LanguageRegistry")
                    .getDeclaredMethod("resetToBuiltins");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── HTML/SVG/htm : alias XML doivent plier par balises ────────

    @Test
    void html_foldsByTag_viaXmlAlias() {
        // html est un alias de xml enregistré dans BuiltinLanguages.
        // L'ancien switch de FoldDetector ne reconnaissait que "xml"
        // exact, donc html tombait sur detectBraceFolds et ne pliait
        // pas par balises.
        String text = "<html>\n  <body>\n    <p>Hello</p>\n  </body>\n</html>\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "html");
        // Doit détecter au moins un pli par balise <html> ou <body>.
        assertTrue(folds.size() >= 1,
                "html doit plier par balises (alias xml via registre),"
                        + " got " + folds.size() + " folds");
        // Vérifier qu'on a des plis de type "element" (pas "block").
        boolean foundElementFold = false;
        for (DiagnosticShift.FoldRegion f : folds) {
            if ("element".equals(f.kind)) {
                foundElementFold = true;
                break;
            }
        }
        assertTrue(foundElementFold,
                "html doit produire au moins un pli de type element");
    }

    @Test
    void svg_foldsByTag_viaXmlAlias() {
        String text = "<svg xmlns=\"http://www.w3.org/2000/svg\">\n"
                + "  <g>\n    <rect/>\n  </g>\n</svg>\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "svg");
        assertTrue(folds.size() >= 1,
                "svg doit plier par balises (alias xml via registre),"
                        + " got " + folds.size() + " folds");
    }

    @Test
    void htm_foldsByTag_viaXmlAlias() {
        String text = "<div>\n  <p>Hello</p>\n</div>\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "htm");
        assertTrue(folds.size() >= 1,
                "htm doit plier par balises (alias xml via registre)");
    }

    // ── YAML : pliage par indentation (INDENT strategy) ──────────

    @Test
    void yaml_foldsByIndentation() {
        // YAML n'a pas de pliage par accolades — c'est l'indentation
        // qui définit les blocs. L'ancien switch ne reconnaissait pas
        // YAML → tombait sur detectBraceFolds → aucun pli.
        String text = "name: foo\n"
                + "services:\n"
                + "  - name: web\n"
                + "    port: 8080\n"
                + "    image: nginx\n"
                + "  - name: db\n"
                + "    port: 5432\n"
                + "    image: postgres\n"
                + "config:\n"
                + "  timeout: 30\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "yaml");
        // Doit détecter au moins un pli par indentation (sous
        // "services:" par exemple).
        assertTrue(folds.size() >= 1,
                "yaml doit plier par indentation (strategy INDENT),"
                        + " got " + folds.size() + " folds");
    }

    // ── JSON : tableaux [ … ] doivent aussi plier ────────────────

    @Test
    void json_foldsArrays_alongWithObjects() {
        // L'ancien detectBraceFolds ne détectait que { }, pas [ ].
        // Le Lot 2 étend la détection aux [ ] en C_LIKE/JSON.
        String text = "{\n"
                + "  \"items\": [\n"
                + "    { \"name\": \"a\" },\n"
                + "    { \"name\": \"b\" },\n"
                + "    { \"name\": \"c\" }\n"
                + "  ]\n"
                + "}\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "json");
        // Doit détecter au moins : 1 pli pour { racine, 1 pli pour
        // [ items, et 3 plis pour chaque objet interne. On asserte
        // au moins 2 (objet racine + tableau items) pour ne pas
        // casser si la détection des objets internes est plus fine.
        assertTrue(folds.size() >= 2,
                "json doit plier les tableaux [ ] ET les objets { },"
                        + " got " + folds.size() + " folds");
        // Vérifier qu'on a au moins un pli qui démarre à [.
        boolean foundArrayFold = false;
        for (DiagnosticShift.FoldRegion f : folds) {
            if (f.start < text.length() && text.charAt(f.start) == '[') {
                foundArrayFold = true;
                break;
            }
        }
        assertTrue(foundArrayFold,
                "json doit détecter un pli de tableau [ … ]");
    }

    // ── Javadoc et commentaires de bloc doivent plier en Java ─────

    @Test
    void java_foldsJavadocAndBlockComments() {
        // Les commentaires de bloc /** … */ et /* … */ doivent être
        // pliables en Java (et tout langage C-like). L'ancien
        // détecteur ne regardait que les accolades.
        String text = "/**\n"
                + " * Javadoc de la classe.\n"
                + " * Plus de détails.\n"
                + " * Encore.\n"
                + " */\n"
                + "public class Foo {\n"
                + "    /*\n"
                + "     * Commentaire de bloc.\n"
                + "     * Multi-ligne.\n"
                + "     */\n"
                + "    int x = 1;\n"
                + "    int y = 2;\n"
                + "}\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "java");
        // Doit détecter : 1 pli javadoc, 1 pli commentaire de bloc,
        // 1 pli classe = 3 plis minimum.
        assertTrue(folds.size() >= 3,
                "java doit plier javadoc + commentaire de bloc + accolades,"
                        + " got " + folds.size() + " folds");
        // Au moins un pli qui démarre à /*.
        boolean foundCommentFold = false;
        for (DiagnosticShift.FoldRegion f : folds) {
            if (f.start + 1 < text.length()
                    && text.charAt(f.start) == '/'
                    && text.charAt(f.start + 1) == '*') {
                foundCommentFold = true;
                break;
            }
        }
        assertTrue(foundCommentFold,
                "java doit détecter un pli de commentaire de bloc /* … */");
    }

    // ── Langage contribué : FoldDetector respecte la stratégie du profil ──

    @Test
    void contributedLanguage_xmlFamily_foldsByTag() {
        // Un hôte enregistre un langage « myxml » de famille XML.
        // FoldDetector doit utiliser la stratégie TAG (par défaut
        // pour la famille XML), pas detectBraceFolds.
        LanguageRegistry.register(LanguageProfile.builder("myxml")
                .family(jo.codeeditor.languages.SyntaxFamily.XML)
                .build());
        String text = "<root>\n  <item>a</item>\n  <item>b</item>\n</root>\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "myxml");
        assertTrue(folds.size() >= 1,
                "myxml (famille XML contribué) doit plier par balises");
        // Au moins un pli de type "element".
        boolean foundElement = false;
        for (DiagnosticShift.FoldRegion f : folds) {
            if ("element".equals(f.kind)) {
                foundElement = true;
                break;
            }
        }
        assertTrue(foundElement,
                "myxml doit produire au moins un pli de type element");
    }

    @Test
    void contributedLanguage_offStrategy_noFolds() {
        // Un hôte enregistre « mylang » avec foldStrategy OFF.
        LanguageRegistry.register(LanguageProfile.builder("mylang")
                .family(jo.codeeditor.languages.SyntaxFamily.C_LIKE)
                .foldStrategy(FoldStrategy.OFF)
                .build());
        String text = "{\n  int x;\n  int y;\n}\n";
        List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "mylang");
        assertTrue(folds.isEmpty(),
                "mylang (foldStrategy OFF) ne doit produire aucun pli");
    }

    // ── text/log : pas de pliage ─────────────────────────────────

    @Test
    void textLog_noFolds() {
        // text et log ont foldStrategy OFF par défaut (cf.
        // defaultFoldStrategy).
        String text = "Ligne 1\nLigne 2\nLigne 3\n{not a block}\n";
        assertTrue(FoldDetector.detect(text, "text").isEmpty(),
                "text (foldStrategy OFF) ne doit produire aucun pli");
        assertTrue(FoldDetector.detect(text, "log").isEmpty(),
                "log (foldStrategy OFF) ne doit produire aucun pli");
    }
}
