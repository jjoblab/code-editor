package jo.codeeditor.lsp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-08 — acceptance permanente : AUCUNE journalisation directe
 * (android.util.Log) dans le code de production de cel-lsp — tout
 * passe par la façade installable EditorLog (extinction possible par
 * l'hôte, uniformité des niveaux, pas de bruit logcat par frappe).
 *
 * <p>Rouge avant : 70 sites android.util.Log (dont un Log.i À CHAQUE
 * didChange synchronisé — un par frappe de l'utilisateur).</p>
 */
class NoDirectAndroidLoggingTest {

    @Test
    void celLspProductionSansAndroidUtilLog() throws IOException {
        Path racine = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(racine),
                "le test s'exécute depuis la racine du module cel-lsp ("
                        + System.getProperty("user.dir") + ")");
        List<String> fautifs = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(racine)) {
            fichiers.filter(f -> f.toString().endsWith(".java"))
                    .forEach(f -> {
                        try {
                            List<String> lignes = Files.readAllLines(f);
                            for (int i = 0; i < lignes.size(); i++) {
                                if (lignes.get(i).contains("android.util.Log.")) {
                                    fautifs.add(f + ":" + (i + 1));
                                }
                            }
                        } catch (IOException ignored) {
                            // Fichier illisible : signalé comme fautif.
                            fautifs.add(f + ":<illisible>");
                        }
                    });
        }
        assertTrue(fautifs.isEmpty(),
                "journalisation directe android.util.Log interdite dans cel-lsp "
                        + "(passer par jo.codeeditor.util.EditorLog) — sites : " + fautifs);
    }
}
