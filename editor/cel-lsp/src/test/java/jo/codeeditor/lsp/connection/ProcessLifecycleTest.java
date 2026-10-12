package jo.codeeditor.lsp.connection;

import org.junit.Test;

import java.io.File;
import jo.codeeditor.lsp.connection.StreamConnectionProvider.StreamPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Robustesse du cycle de vie du sous-processus serveur
 * ({@link ProcessBuilderConnectionProvider}) :
 *
 * <ol>
 *   <li><b>stderr drainé</b> — un serveur bavard sur stderr remplit le
 *       tampon du pipe (~64 Kio) et SE BLOQUE : une pompe dédiée le vide
 *       vers le journal (borné) au lieu de l'ignorer ;</li>
 *   <li><b>arrêt échelonné</b> — destroy() puis attente bornée puis arrêt
 *       forcé : stop() ne rend la main qu'une fois le processus mort ;</li>
 *   <li><b>échec de démarrage typé</b> — {@link LspStartException} au
 *       lieu d'une RuntimeException générique.</li>
 * </ol>
 *
 * <p>Tests JVM : commandes {@code sh} standard du runner.</p>
 */
public class ProcessLifecycleTest {

    private static boolean shDisponible() {
        return new File("/bin/sh").exists() || new File("/usr/bin/sh").exists();
    }

    @org.junit.Before
    public void skipSiPasDeShell() {
        org.junit.Assume.assumeTrue(shDisponible());
    }

    @Test
    public void serveurBavardSurStderr_neBloquePasLeDemarrage() throws Exception {
        // Écrit 2 Mio sur stderr puis dort : sans pompe, le pipe (64 Kio)
        // se remplit et le processus bloque sur write() — avec la pompe,
        // start() rend la main et le flux continue d'être vidé.
        ProcessBuilderConnectionProvider p = new ProcessBuilderConnectionProvider(
                "/bin/sh",
                List.of("-c", "for i in $(seq 1 2048); do head -c 1024 /dev/zero | tr '\\0' 'x' >&2; done; sleep 30"),
                null);
        StreamConnectionProvider.StreamPair pair = p.start();
        assertNotNull(pair);
        // Le serveur tourne encore (il dort) — la pompe a tout absorbé.
        assertNotNull(p.getProcess());
        assertTrue(p.getProcess().isAlive());
        p.stop();
        assertFalse(p.getProcess() == null ? false : p.getProcess().isAlive());
    }

    @Test
    public void stop_arreteVraimentLeProcessus() throws Exception {
        ProcessBuilderConnectionProvider p = new ProcessBuilderConnectionProvider(
                "/bin/sh", List.of("-c", "sleep 60"), null);
        p.start();
        assertNotNull(p.getProcess());
        assertTrue(p.getProcess().isAlive());
        long t0 = System.nanoTime();
        p.stop();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        // stop() est borné (attente limitée + arrêt forcé) et le process
        // est mort au retour.
        assertTrue("stop() doit être rapide (rapport : " + ms + " ms)", ms < 15000);
        Process proc = p.getProcess();
        // Après stop(), le process référencé n'est plus vivant (ou est
        // détaché) — le contrat est : plus aucun processus actif.
        if (proc != null) {
            long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < fin && proc.isAlive()) {
                Thread.sleep(20);
            }
            assertFalse("le processus doit être terminé après stop()", proc.isAlive());
        }
    }

    @Test
    public void demarrageEchoue_exceptionTypee() {
        ProcessBuilderConnectionProvider p = new ProcessBuilderConnectionProvider(
                "/binaire/qui/nexiste/pas", new ArrayList<>(), null);
        try {
            p.start();
            fail("LspStartException attendue");
        } catch (LspStartException e) {
            assertTrue(e.getMessage().contains("/binaire/qui/nexiste/pas"));
        }
    }

    @Test
    public void apresStop_aucunThreadDePompeVivant() throws Exception {
        ProcessBuilderConnectionProvider p = new ProcessBuilderConnectionProvider(
                "/bin/sh", List.of("-c", "sleep 5"), null);
        p.start();
        p.stop();
        // La pompe stderr est daemon ET interrompue à l'arrêt : après
        // stop(), aucun thread "cel-lsp-stderr" ne doit rester.
        long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean found = true;
        while (System.nanoTime() < fin && found) {
            found = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().contains("cel-lsp-stderr")) {
                    found = true;
                    Thread.sleep(50);
                    break;
                }
            }
        }
        assertFalse("la pompe stderr doit être arrêtée avec le processus", found);
    }
}
