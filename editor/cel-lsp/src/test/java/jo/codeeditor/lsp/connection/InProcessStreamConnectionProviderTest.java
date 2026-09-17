package jo.codeeditor.lsp.connection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.lang.EmptyLanguage;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Tests Robolectric de {@link InProcessStreamConnectionProvider}.
 *
 * <p>Teste les ConnectedInputStream/ConnectedOutputStream personnalisés qui
 * remplacent les PipedInputStream/PipedOutputStream défaillants pour le LSP
 * en processus.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class InProcessStreamConnectionProviderTest {

    @Test
    public void connectedStreams_writeThenRead() throws Exception {
        // Crée une paire de flux connectés.
        InProcessStreamConnectionProvider.ConnectedOutputStream output =
                new InProcessStreamConnectionProvider.ConnectedOutputStream();
        InProcessStreamConnectionProvider.ConnectedInputStream input =
                new InProcessStreamConnectionProvider.ConnectedInputStream(output);

        // Écrit quelques données
        output.write("hello world".getBytes());
        output.flush();

        // Les relit
        byte[] buf = new byte[256];
        int n = input.read(buf, 0, buf.length);
        assertTrue("should have read some data", n > 0);
        assertEquals("hello world", new String(buf, 0, n));
    }

    @Test
    public void connectedStreams_largeWriteDoesNotBlock() throws Exception {
        InProcessStreamConnectionProvider.ConnectedOutputStream output =
                new InProcessStreamConnectionProvider.ConnectedOutputStream();
        InProcessStreamConnectionProvider.ConnectedInputStream input =
                new InProcessStreamConnectionProvider.ConnectedInputStream(output);

        // Écrit un gros volume (100 Ko) — ne doit ni bloquer ni planter.
        byte[] largeData = new byte[100 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        output.write(largeData);
        output.flush();

        // Vérifie qu'on peut tout relire.
        java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int totalRead = 0;
        while (totalRead < largeData.length) {
            int n = input.read(buf, 0, buf.length);
            if (n <= 0) break;
            received.write(buf, 0, n);
            totalRead += n;
        }
        assertEquals(largeData.length, totalRead);
        assertArrayEquals(largeData, received.toByteArray());
    }

    @Test
    public void connectedStreams_closeReturnsEndOfStream() throws Exception {
        InProcessStreamConnectionProvider.ConnectedOutputStream output =
                new InProcessStreamConnectionProvider.ConnectedOutputStream();
        InProcessStreamConnectionProvider.ConnectedInputStream input =
                new InProcessStreamConnectionProvider.ConnectedInputStream(output);

        // Écrit quelques données puis ferme
        output.write("test".getBytes());
        output.close();

        // Lit les données restantes
        byte[] buf = new byte[256];
        int n = input.read(buf, 0, buf.length);
        assertTrue(n > 0);
        assertEquals("test", new String(buf, 0, n));

        // La lecture suivante doit retourner -1 (fin de flux)
        int next = input.read(buf, 0, buf.length);
        assertEquals("should return -1 after close", -1, next);
    }

    @Test
    public void connectedStreams_multipleWritesAndReads() throws Exception {
        InProcessStreamConnectionProvider.ConnectedOutputStream output =
                new InProcessStreamConnectionProvider.ConnectedOutputStream();
        InProcessStreamConnectionProvider.ConnectedInputStream input =
                new InProcessStreamConnectionProvider.ConnectedInputStream(output);

        // Écrit trois messages
        output.write("msg1\n".getBytes());
        output.write("msg2\n".getBytes());
        output.write("msg3\n".getBytes());

        // Lit tout
        byte[] buf = new byte[1024];
        int total = 0;
        StringBuilder sb = new StringBuilder();
        while (true) {
            int n = input.read(buf, 0, buf.length);
            if (n <= 0) break;
            sb.append(new String(buf, 0, n));
            total += n;
            if (total >= 15) break; // "msg1\nmsg2\nmsg3\n" = 15 bytes
        }
        assertEquals("msg1\nmsg2\nmsg3\n", sb.toString());
    }
}
