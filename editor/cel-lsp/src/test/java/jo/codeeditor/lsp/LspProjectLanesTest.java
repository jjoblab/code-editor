package jo.codeeditor.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Voies d'exécution d'un {@link LspProject} (Lot 7) : propriété par
 * projet, fermeture effective, soumission protégée pendant l'extinction.
 *
 * <p>L'ancienne conception tenait les exécuteurs dans des champs
 * statiques (processus entier, jamais fermés) : aucun {@code cel-lsp-*}
 * ne pouvait être libéré, et les éditeurs successifs partageaient des
 * threads zombies. Les voies vivent désormais dans le projet et meurent
 * avec lui.</p>
 */
class LspProjectLanesTest {

    /** Un thread LSP vivant existe-t-il (nom cel-lsp-* ou lsp-*) ? */
    private static boolean threadLspVivant() {
        Thread[] threads = new Thread[Thread.activeCount() + 16];
        int n = Thread.enumerate(threads);
        for (int i = 0; i < n; i++) {
            if (!threads[i].isAlive()) continue;
            String name = threads[i].getName();
            if (name.startsWith("cel-lsp") || name.startsWith("lsp-")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void shutdown_fermeLaVoieDeFond_aucunThreadLspRestant(@TempDir Path racine) throws Exception {
        LspProject project = new LspProject(racine.toFile().getAbsolutePath());
        // Popule la voie paresseuse : la tâche doit s'exécuter.
        CountDownLatch executed = new CountDownLatch(1);
        project.taskExecutor().execute(executed::countDown);
        assertTrue(executed.await(2, TimeUnit.SECONDS),
                "la tâche soumise doit s'exécuter sur la voie");
        assertTrue(threadLspVivant(), "la voie doit être peuplée avant l'extinction");

        project.shutdown();

        // Les threads daemon s'éteignent de façon asynchrone — borne 5 s (A-06).
        assertTrue(jo.codeeditor.AttenteBornee.jusquA(5000, () -> !threadLspVivant()),
                "aucun thread cel-lsp-* ne doit survivre à shutdown()");
    }

    @Test
    void submitBackground_apresExtinction_abandonneSansException(@TempDir Path racine) {
        LspProject project = new LspProject(racine.toFile().getAbsolutePath());
        LspEditor editor = new LspEditor(project,
                racine.resolve("Essai.java").toUri().toString());
        project.shutdown();
        // Ne doit NI lever RejectedExecutionException, NI exécuter la
        // tâche (elle échouerait le test si elle tournait).
        editor.submitBackground(() -> fail("aucune tâche ne doit s'exécuter après extinction"));
    }
}
