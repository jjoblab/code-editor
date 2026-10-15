package jo.codeeditor.lsp.connection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * D-06 — contrat de démarrage des providers de connexion : échec de
 * connexion → {@link LspStartException} typée, message français, cause
 * conservée (l'ancienne RuntimeException générique à message anglais ne
 * se distinguait pas d'un bug interne du client).
 *
 * <p>Rouge avant : SocketConnectionProvider levait
 * {@code RuntimeException("Failed to connect to LSP server at …")} —
 * asymétrique avec ProcessBuilderConnectionProvider (LspStartException).</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ConnectionProvidersStartExceptionTest {

    @Test
    public void socketHôteInjoignable_LspStartExceptionCauseConservée() {
        // Port garanti fermé : un ServerSocket éphémère est ouvert puis
        // refermé — personne n'écoute.
        int port;
        try (ServerSocket éphémère = new ServerSocket(0)) {
            port = éphémère.getLocalPort();
        } catch (IOException e) {
            fail("impossible de réserver un port éphémère : " + e);
            return;
        }
        SocketConnectionProvider provider = new SocketConnectionProvider("127.0.0.1", port);
        try {
            provider.start();
            fail("la connexion doit échouer sur un port fermé");
        } catch (LspStartException e) {
            assertTrue("message français lisible : " + e.getMessage(),
                    e.getMessage().contains("Impossible de se connecter"));
            assertTrue("l'adresse est dans le message : " + e.getMessage(),
                    e.getMessage().contains("127.0.0.1"));
            assertNotNull("la cause est conservée (diagnostic hôte)", e.getCause());
            assertTrue("la cause est l'échec d'E/S du socket",
                    e.getCause() instanceof IOException);
        }
    }

    @Test
    public void localSocketSansServeur_LspStartExceptionCauseConservée() {
        // Nom de socket abstrait sans serveur : sous l'environnement de
        // test, la connexion échoue en IOException → contrat typé.
        LocalSocketConnectionProvider provider =
                new LocalSocketConnectionProvider("lsp-inexistant-test");
        try {
            provider.start();
            fail("la connexion doit échouer sans serveur au bout du socket");
        } catch (LspStartException e) {
            assertTrue("message français lisible : " + e.getMessage(),
                    e.getMessage().contains("Impossible de se connecter"));
            assertTrue("le nom du socket est dans le message : " + e.getMessage(),
                    e.getMessage().contains("lsp-inexistant-test"));
            assertNotNull("la cause est conservée", e.getCause());
        }
    }
}
