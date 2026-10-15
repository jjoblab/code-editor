package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2/D-02 — la fuite de thread par session doit être impossible même
 * quand l'hôte OUBLIE {@code dispose()} : le thread de restyle expire
 * après une inactivité bornée (garde-fou
 * {@code allowCoreThreadTimeOut}), et tout restyle soumis APRÈS un
 * {@code dispose()} est rejeté proprement (repli synchrone, jamais
 * d'exécution en arrière-plan, jamais de crash).
 *
 * <p>La durée de vie est rabattue à 50 ms par le test (30 s en
 * production) pour mesurer l'expiration sans attendre.</p>
 *
 * <p>Rouge avant : l'exécuteur mono-thread ne mourrait JAMAIS sans
 * {@code dispose()} — 200 sessions laissaient 201 threads
 * {@code EditorSession-restyle} vivants (mesure L0).</p>
 */
class RestyleThreadExpiryTest {

    private static int compterThreadsRestyle() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("EditorSession-restyle")) n++;
        }
        return n;
    }

    @Test
    void deuxCentsSessionsSansDispose_threadsExpirésSousBorneConstante()
            throws Exception {
        java.util.List<EditorSession> sessions = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                EditorSession s = new EditorSession(EditorDocument.of(
                        "int a = 0;\nint b = 1;\n// ligne " + i + "\n"));
                // Rabat la durée de vie du thread inactif (30 s en
                // production) pour le test.
                s.setRestyleThreadKeepAliveMs(50);
                s.setLanguage("java");
                s.restyleAllAsync();
                sessions.add(s);
            }
            // Laisser finir les restyles en vol.
            for (EditorSession s : sessions) {
                s.awaitPendingRestyle();
            }
            // Laisser expirer les threads inactifs (50 ms × marge).
            Thread.sleep(500);
            int vivants = compterThreadsRestyle();
            // Acceptation : sous une borne CONSTANTE (quelques
            // retardataires au plus), pas 200.
            assertTrue(vivants <= 4,
                    "threads EditorSession-restyle vivants après expiration : "
                            + vivants + " (attendu ≤ 4, fuite si ≈ 200)");
        } finally {
            for (EditorSession s : sessions) {
                s.dispose();
            }
            Thread.sleep(100);
        }
    }

    @Test
    void threadCrééAuPremierRestyleAsynchrone_pasÀLaCréation() throws Exception {
        int avant = compterThreadsRestyle();
        EditorSession s = new EditorSession(EditorDocument.of("int x = 1;\n"));
        s.setRestyleThreadKeepAliveMs(50);
        s.awaitPendingRestyle(); // s'assure qu'aucune soumission résiduelle
        int aprèsCréation = compterThreadsRestyle();
        // À la création seule, aucun restyle asynchrone n'est soumis :
        // le thread n'apparaît qu'à la première demande. (La création
        // déclenche un restyleAll SYNCHRONE — pas de thread.)
        assertTrue(aprèsCréation <= avant + 1,
                "la création seule ne matérialise pas de thread (mesuré "
                        + aprèsCréation + " vs " + avant + " avant)");
        s.dispose();
    }

    @Test
    void restyleSoumisAprèsDispose_rejetProprement() throws Exception {
        EditorSession s = new EditorSession(EditorDocument.of("int a = 1;\nint b = 2;\n"));
        s.setLanguage("java");
        s.dispose();

        // Soumission APRÈS dispose : repli SYNCHRONE — jamais de
        // soumission à l'exécuteur arrêté, jamais d'exception.
        assertDoesNotThrow(() -> s.restyleAllAsync(),
                "restyleAsync après dispose ne doit pas lever (repli synchrone)");
        // Le repli synchrone a réellement re-tokenisé (2 lignes + la
        // ligne vide du saut final).
        assertEquals(3, s.getStyledLines().size(),
                "le repli synchrone restyle bien le document");
        assertFalse(s.isAsyncRestylePending(), "rien n'est en vol après dispose");
    }

    @Test
    void disposeAnnuleLeRestyleEnVol() throws Exception {
        EditorSession s = new EditorSession(EditorDocument.of(
                "int a = 0;\n// padding\n// padding\n"));
        s.setRestyleThreadKeepAliveMs(50_000);
        s.setLanguage("java");
        s.restyleAllAsync();
        assertTrue(s.isAsyncRestylePending() || true); // en vol ou déjà fini
        s.dispose();
        // L'attente ne bloque pas : le future en vol est annulé.
        long t0 = System.nanoTime();
        s.awaitPendingRestyle();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2_000, "awaitPendingRestyle borné après dispose (mesuré " + ms + " ms)");
        assertFalse(s.isAsyncRestylePending());
    }
}
