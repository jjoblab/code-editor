package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.CompletionPublisher;
import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4.7.0 — la résolution PARESSEUSE de la documentation des candidats
 * ({@code completionItem/resolve}) : la documentation servie
 * DIRECTEMENT dans la liste voyage telle quelle (zéro requête de
 * résolution) ; un candidat sans documentation ne la reçoit qu'après
 * un appel explicite de {@code resolveDocumentation}, uniquement si le
 * serveur annonce {@code resolveProvider} ; la documentation résolue
 * est mise en cache (re-service et pré-remplissage des lots suivants
 * sans nouvelle requête).
 */
class CompletionResolveProviderTest {

    private static final String DOC = "public class A {\n    var s = li.add\n}\n";

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;
    private FakeCompletionResolveServer server;
    private LanguageServerWrapper wrapper;
    private File racine;

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        this.racine = racine;
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeCompletionResolveServer();
        wrapper = startWrapper();
        editor = new LspEditor(project, "file:///proj/src/A.java");
        wrapper.getClient().setLspEditor(editor);
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    private LanguageServerWrapper startWrapper() throws Exception {
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        LanguageServerWrapper w = new LanguageServerWrapper(
                definition, racine.getAbsolutePath(), executor);
        w.start().join();
        return w;
    }

    /** Rebranche un wrapper sur le MÊME serveur avec resolveProvider annoncé. */
    private void rebrancherAvecAnnonce() throws Exception {
        wrapper.shutdown();
        server.announceResolve = true;
        wrapper = startWrapper();
        editor = new LspEditor(project, "file:///proj/src/A.java");
        wrapper.getClient().setLspEditor(editor);
    }

    /** L'appel asynchrone attendu — le callback peut venir d'un autre thread. */
    private static String résoudre(LspCompletionProvider provider, String label)
            throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        provider.resolveDocumentation(label, future::complete);
        return future.get(10, TimeUnit.SECONDS);
    }

    private List<jo.codeeditor.lang.model.CompletionItem> demander(
            LspCompletionProvider provider) {
        java.util.List<jo.codeeditor.lang.model.CompletionItem> items =
                new java.util.ArrayList<>();
        CompletionPublisher pub = new CompletionPublisher() {
            @Override
            public void addItem(jo.codeeditor.lang.model.CompletionItem item) {
                items.add(item);
            }

            @Override
            public void addItems(java.util.List<jo.codeeditor.lang.model.CompletionItem> list) {
                items.addAll(list);
            }

            @Override
            public void flush() {
            }

            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
        provider.complete(DOC, DOC.indexOf("add") + 3, "d", pub);
        return items;
    }

    @Test
    void documentationDirecte_transportéeSansRésolution() {
        List<jo.codeeditor.lang.model.CompletionItem> items =
                demander(new LspCompletionProvider(wrapper, editor, null));
        assertEquals(2, items.size());
        assertEquals(1, server.completionRequests);
        assertEquals(0, server.resolveRequests,
                "une documentation directe ne doit déclencher AUCUNE résolution");
        jo.codeeditor.lang.model.CompletionItem direct = items.get(0);
        assertEquals("toString", direct.label);
        assertEquals("La représentation textuelle de l'objet", direct.documentation);
        assertNull(items.get(1).documentation, "add est servi SANS documentation");
    }

    @Test
    void sansAnnonce_aucuneRequête_callbackNull() throws Exception {
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        demander(provider); // le lot doit être servi d'abord
        assertNull(résoudre(provider, "add"));
        assertEquals(0, server.resolveRequests,
                "sans resolveProvider annoncé, aucune requête n'est émise");
    }

    @Test
    void avecAnnonce_résolutionAsynchrone_donnéeIntacte() throws Exception {
        rebrancherAvecAnnonce();
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        demander(provider);
        String doc = résoudre(provider, "add");
        assertEquals(server.resolveDoc, doc);
        assertEquals(1, server.resolveRequests,
                "exactement UNE requête de résolution");
        assertNotNull(server.lastResolvedItem);
        Object data = server.lastResolvedItem.getData();
        // Après le round-trip JSON-RPC, data revient en JsonPrimitive
        // (Gson) ou en String selon le chemin — les deux formes prouvent
        // que la donnée de l'item ORIGINAL a fait l'aller-retour intact.
        String dataStr = data instanceof com.google.gson.JsonElement
                ? ((com.google.gson.JsonElement) data).getAsString()
                : String.valueOf(data);
        assertEquals("add-magic-data", dataStr,
                "le champ data de l'item ORIGINAL fait l'aller-retour tel quel");
    }

    @Test
    void cache_laDeuxièmeRésolutionNeReRequêtePas() throws Exception {
        rebrancherAvecAnnonce();
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        demander(provider);
        assertEquals(server.resolveDoc, résoudre(provider, "add"));
        assertEquals(server.resolveDoc, résoudre(provider, "add"));
        assertEquals(1, server.resolveRequests,
                "la documentation résolue est resservie du cache");
    }

    @Test
    void labelInconnu_ignoréSansRequête() throws Exception {
        rebrancherAvecAnnonce();
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        demander(provider);
        assertNull(résoudre(provider, "inconnu"));
        assertNull(résoudre(provider, null));
        assertEquals(0, server.resolveRequests,
                "un libellé absent du dernier lot ne part jamais au serveur");
    }

    @Test
    void sansLotServi_rienARésoudre() throws Exception {
        rebrancherAvecAnnonce();
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        assertNull(résoudre(provider, "add"),
                "avant tout lot servi, le cache est vide et rien ne part");
        assertEquals(0, server.resolveRequests);
    }

    @Test
    void documentationRésolue_préremplieAuLotSuivant() throws Exception {
        rebrancherAvecAnnonce();
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        demander(provider);
        assertEquals(server.resolveDoc, résoudre(provider, "add"));

        // Le lot re-servi transporte la documentation résolue : le popup
        // l'affiche immédiatement, sans attendre un nouveau séjour.
        List<jo.codeeditor.lang.model.CompletionItem> second = demander(provider);
        assertEquals(2, server.completionRequests);
        assertEquals(server.resolveDoc, second.get(1).documentation,
                "add arrive avec sa documentation résolue en cache");
        assertEquals(1, server.resolveRequests,
                "aucune nouvelle requête de résolution pour le lot re-servi");
    }

    @Test
    void serveurSilencieux_callbackNull_sansCrash() throws Exception {
        rebrancherAvecAnnonce();
        server.resolveDoc = null; // le serveur répond SANS documentation
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        demander(provider);
        assertNull(résoudre(provider, "add"));
        assertEquals(1, server.resolveRequests);
    }

    @Test
    void callbackDéfaillant_neTuePasLeThreadDuPont() throws Exception {
        rebrancherAvecAnnonce();
        LspCompletionProvider provider = new LspCompletionProvider(wrapper, editor, null);
        demander(provider);
        AtomicReference<Throwable> crash = new AtomicReference<>();
        CountDownLatch fait = new CountDownLatch(1);
        provider.resolveDocumentation("add", doc -> {
            try {
                throw new IllegalStateException("consommateur mort");
            } catch (Throwable t) {
                crash.set(t);
            } finally {
                fait.countDown();
            }
        });
        assertTrue(fait.await(10, TimeUnit.SECONDS),
                "le callback (même défaillant) doit être invoqué");
        assertNotNull(crash.get());
        // Le wrapper survit : la résolution suivante (cache) fonctionne.
        assertEquals(server.resolveDoc, résoudre(provider, "add"));
    }
}
