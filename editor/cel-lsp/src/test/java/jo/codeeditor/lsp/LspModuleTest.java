package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.ProcessBuilderConnectionProvider;
import jo.codeeditor.lsp.connection.SocketConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des classes centrales du module {@code cel-lsp} qui ne nécessitent
 * pas de connexion réelle à un serveur LSP.
 */
class LspModuleTest {

    @Test
    void streamConnectionProvider_processBuilderCreation() {
        ProcessBuilderConnectionProvider provider = new ProcessBuilderConnectionProvider(
            "/usr/bin/java", Arrays.asList("-jar", "server.jar"), "/tmp");
        assertNotNull(provider);
    }

    @Test
    void streamConnectionProvider_socketCreation() {
        SocketConnectionProvider provider = new SocketConnectionProvider("localhost", 5007);
        assertNotNull(provider);
    }

    @Test
    void languageServerDefinition_creation() {
        LanguageServerDefinition def = new LanguageServerDefinition("java", "jdtls") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return new SocketConnectionProvider("localhost", 5007);
            }
        };
        assertEquals("java", def.getExt());
        assertEquals("jdtls", def.getName());
        assertNotNull(def.createConnectionProvider("/tmp"));
        assertNull(def.expectedCapabilities());
        assertNull(def.getInitializationOptions());
        assertTrue(def.callExitForLanguageServer());
    }

    @Test
    void lspProject_creation() {
        LspProject project = new LspProject("/tmp/workspace");
        assertEquals("/tmp/workspace", project.getWorkspacePath());
    }

    @Test
    void lspProject_addServerDefinition() {
        LspProject project = new LspProject("/tmp/workspace");
        LanguageServerDefinition def = new LanguageServerDefinition("java", "jdtls") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return new SocketConnectionProvider("localhost", 5007);
            }
        };
        project.addServerDefinition(def);
        assertSame(def, project.getDefinition("java"));
        assertNull(project.getDefinition("kotlin"));
    }

    @Test
    void lspProject_createEditor() {
        LspProject project = new LspProject("/tmp/workspace");
        LanguageServerDefinition def = new LanguageServerDefinition("java", "jdtls") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return new SocketConnectionProvider("localhost", 5007);
            }
        };
        project.addServerDefinition(def);
        LspEditor editor = project.createEditor("file:///tmp/Foo.java");
        assertNotNull(editor);
        assertEquals("file:///tmp/Foo.java", editor.getFileUri());
        assertFalse(editor.isConnected());
    }

    @Test
    void lspFeature_enum() {
        // Vérifie simplement que les valeurs de l'enum existent
        assertNotNull(LspFeature.COMPLETION);
        assertNotNull(LspFeature.HOVER);
        assertNotNull(LspFeature.SIGNATURE_HELP);
        assertNotNull(LspFeature.DEFINITION);
        assertNotNull(LspFeature.DIAGNOSTICS);
        assertNotNull(LspFeature.CODE_ACTION);
        assertNotNull(LspFeature.DOCUMENT_HIGHLIGHT);
        assertNotNull(LspFeature.INLAY_HINT);
        assertNotNull(LspFeature.DOCUMENT_SYMBOL);
        assertNotNull(LspFeature.RENAME);
        assertNotNull(LspFeature.FORMATTING);
        assertNotNull(LspFeature.REFERENCES);
        assertNotNull(LspFeature.WORKSPACE_SYMBOL);
    }

    @Test
    void defaultLanguageClient_noOpImplementations() {
        DefaultLanguageClient client = new DefaultLanguageClient();
        // Vérifie que les appels no-op ne plantent pas
        client.telemetryEvent("test");
        client.logMessage(new org.eclipse.lsp4j.MessageParams());
        client.showMessage(new org.eclipse.lsp4j.MessageParams());
        // showMessageRequest retourne un futur — vérifie juste qu'il complète
        org.eclipse.lsp4j.ShowMessageRequestParams params = new org.eclipse.lsp4j.ShowMessageRequestParams();
        params.setActions(Arrays.asList(new org.eclipse.lsp4j.MessageActionItem("OK")));
        client.showMessageRequest(params).thenAccept(action -> {
            assertNotNull(action);
            assertEquals("OK", action.getTitle());
        });
    }

    @Test
    void streamPair_creation() {
        StreamConnectionProvider.StreamPair pair = new StreamConnectionProvider.StreamPair(
            new java.io.ByteArrayInputStream(new byte[0]),
            new java.io.ByteArrayOutputStream());
        assertNotNull(pair.input);
        assertNotNull(pair.output);
    }

    // Tests multi-extension (.kt + .kts)

    @Test
    void languageServerDefinition_defaultSingleExtension() {
        LanguageServerDefinition def = new LanguageServerDefinition("java", "jdtls") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return null;
            }
        };
        assertEquals(1, def.getAllExtensions().size(), "Mono-ext par défaut");
        assertTrue(def.getAllExtensions().contains("java"));
    }

    @Test
    void languageServerDefinition_multiExtension() {
        LanguageServerDefinition def = new LanguageServerDefinition("kt", "kotlin-lsp") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return null;
            }
            @Override
            public java.util.Set<String> getAdditionalExtensions() {
                return java.util.Collections.singleton("kts");
            }
        };
        assertEquals(2, def.getAllExtensions().size(), "Multi-ext doit avoir 2 extensions");
        assertTrue(def.getAllExtensions().contains("kt"));
        assertTrue(def.getAllExtensions().contains("kts"));
    }

    @Test
    void lspProject_addServerDefinition_multiExtension() {
        LspProject project = new LspProject("/tmp/workspace");
        LanguageServerDefinition def = new LanguageServerDefinition("kt", "kotlin-lsp") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return null;
            }
            @Override
            public java.util.Set<String> getAdditionalExtensions() {
                return java.util.Collections.singleton("kts");
            }
        };
        project.addServerDefinition(def);
        // La def doit être enregistrée sous "kt" ET "kts"
        assertSame(def, project.getDefinition("kt"), "Def doit être enregistrée sous 'kt'");
        assertSame(def, project.getDefinition("kts"), "Def doit être enregistrée sous 'kts'");
        assertNull(project.getDefinition("java"));
    }
}
