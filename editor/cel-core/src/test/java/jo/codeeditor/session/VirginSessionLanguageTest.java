package jo.codeeditor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 4.8.0 — une session VIERGE est neutre : texte brut.
 *
 * <p>Historiquement, {@code EditorSession} naissait avec
 * {@code language = "java"} : un hôte qui n'appelait jamais
 * {@code setLanguage} obtenait des mots-clés Java, une coloration
 * C-like aux mots-clés Java et un postfix éligible sur n'importe
 * quel fichier — un {@code .txt} compris. Le défaut devient
 * {@code "text"} (profil intégré sans mot-clé, sans snippet) ;
 * les hôtes qui veulent du Java le déclarent explicitement.</p>
 */
class VirginSessionLanguageTest {

    @Test
    void sessionViergeEstEnTexteBrut() {
        assertEquals("text", new EditorSession().getLanguage(),
            "une session sans langage déclaré ne doit pas prétendre être Java");
    }
}
