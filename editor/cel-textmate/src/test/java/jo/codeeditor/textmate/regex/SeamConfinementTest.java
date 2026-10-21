package jo.codeeditor.textmate.regex;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 10 (règle d'or 1) — test de CONFINEMENT : les classes du moteur
 * ({@code org.joni}, {@code org.jcodings}) n'apparaissent dans AUCUN
 * fichier du module hors du présent package
 * {@code jo.codeeditor.textmate.regex} — ni imports, ni noms
 * pleinement qualifiés, dans le code livré COMME dans les tests.
 *
 * <p>Le test rend le build rouge au premier import qui fuit : la
 * couture reste une interface interne, pas un point d'extension
 * (règle d'or 2) — aucun type de moteur ne sort, jamais.</p>
 */
class SeamConfinementTest {

    private static final String MOTEUR = "org.joni.";
    private static final String ENCODAGES = "org.jcodings.";

    @Test
    void aucuneRéférenceAuMoteurHorsCouture() throws IOException {
        List<String> fuites = new ArrayList<>();
        // Racines du module : src/main/java (livré) et src/test/java.
        try (Stream<Path> fichiers = Files.walk(Path.of("src"))) {
            fichiers.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !estCouture(p))
                    .forEach(p -> scanner(p, fuites));
        }
        assertTrue(fuites.isEmpty(),
                "références au moteur hors de jo.codeeditor.textmate.regex "
                        + "(règle d'or 1 du plan 10) :\n" + String.join("\n", fuites));
    }

    private static boolean estCouture(Path p) {
        return p.toString().replace('\\', '/')
                .contains("jo/codeeditor/textmate/regex/");
    }

    private static void scanner(Path fichier, List<String> fuites) {
        String contenu;
        try {
            // Lecture manuelle (compatible minSdk 24 — Files.readString
            // exige l'API 33, même en test).
            contenu = new String(Files.readAllBytes(fichier), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fuites.add(fichier + " : illisible (" + e.getMessage() + ")");
            return;
        }
        int idx = contenu.indexOf(MOTEUR);
        while (idx >= 0) {
            fuites.add(fichier + " : « " + MOTEUR + " » à l'offset " + idx);
            idx = contenu.indexOf(MOTEUR, idx + 1);
        }
        idx = contenu.indexOf(ENCODAGES);
        while (idx >= 0) {
            fuites.add(fichier + " : « " + ENCODAGES + " » à l'offset " + idx);
            idx = contenu.indexOf(ENCODAGES, idx + 1);
        }
    }
}
