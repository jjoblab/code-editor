package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;

import static org.junit.Assert.*;

/**
 * Lot 3 (audit v3.41.0) — tests de
 * {@code EditorCompletionPopup.builtinKeywordCompletions}. Cette
 * méthode est package-private et statique ; on y accède par
 * réflexion.
 *
 * <p>Avant le Lot 3 :</p>
 * <ul>
 *   <li>La méthode ne couvrait que java/kotlin/xml/markdown —
 *       tous les autres langages enregistrés (C, C++, Go, Rust,
 *       Python, JavaScript, TypeScript, SQL, Shell, YAML, JSON,
 *       Lua, Ruby, PHP, Swift, Dart, Groovy, Scala, properties,
 *       toml, smali, log, text) retombaient sur une liste vide,
 *       alors que leurs mots-clés sont enregistrés dans
 *       {@link jo.codeeditor.languages.LanguageRegistry}.</li>
 *   <li>La liste XML contenait {@code "android:layout_padding"}
 *       qui est un <b>attribut inventé</b> — l'attribut réel est
 *       {@code android:padding}. Les autres attributs sont
 *       principalement des attributs Android réels, mais le
 *       mécanisme (hardcoded) ne tient pas compte du fait que
 *       l'utilisateur peut éditer du XML non-Android (SVG, XHTML,
 *       configuration Maven, pom.xml, etc.) — la liste Android
 *       est trompeuse dans ces contextes.</li>
 * </ul>
 *
 * <p>Le Lot 3 remplace cette méthode par une version qui :</p>
 * <ol>
 *   <li>Interroge {@link jo.codeeditor.languages.LanguageRegistry}
 *       pour obtenir les mots-clés enregistrés du langage ;
 *       bénéficie à tous les langages enregistrés.</li>
 *   <li>Supprime la liste XML Android inventée — ne propose que
 *       les 6 mots-clés XML de base enregistrés ({@code xmlns,
 *       android, app, tools, schema, layout}) + les mots-clés
 *       de balise via le profil si l'utilisateur en a
 *       enregistrés.</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class BuiltinKeywordCompletionsTest {

    private static final Class<?> POPUP_CLASS;
    private static final Method METHOD;
    static {
        try {
            POPUP_CLASS = Class.forName("jo.codeeditor.view.popup.EditorCompletionPopup");
            METHOD = POPUP_CLASS.getDeclaredMethod("builtinKeywordCompletions",
                    String.class, String.class);
            METHOD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CompletionSession.Item> call(String lang, String prefix) {
        try {
            return (List<CompletionSession.Item>) METHOD.invoke(null, lang, prefix);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Mots-clés pour tous les langages enregistrés ──────────────

    @Test
    public void keywords_python_returnsPythonKeywords() {
        // Préfixe "de" pour que "def" (longueur 3 > 2) soit proposé.
        List<CompletionSession.Item> items = call("python", "de");
        assertFalse("Python doit proposer des mots-clés via le registre",
                items.isEmpty());
        boolean foundDef = items.stream()
                .anyMatch(i -> "def".equals(i.insertText));
        assertTrue("Le mot-clé 'def' doit être proposé pour Python",
                foundDef);
    }

    @Test
    public void keywords_go_returnsGoKeywords() {
        // Préfixe "fu" pour que "func" (longueur 4 > 2) soit proposé.
        List<CompletionSession.Item> items = call("go", "fu");
        assertFalse("Go doit proposer des mots-clés via le registre",
                items.isEmpty());
        boolean foundFunc = items.stream()
                .anyMatch(i -> "func".equals(i.insertText));
        assertTrue("Le mot-clé 'func' doit être proposé pour Go",
                foundFunc);
    }

    @Test
    public void keywords_rust_returnsRustKeywords() {
        List<CompletionSession.Item> items = call("rust", "fn");
        // Rust : "fn" est un mot-clé. Le filtre accepte les mots-clés
        // commençant par "fn" et plus longs, donc "fn" seul n'est pas
        // dans la liste (sa longueur == prefix), mais d'autres
        // commençant par "fn" le sont si le registre en a. Vérifions
        // avec un préfixe plus court.
        List<CompletionSession.Item> itemsFn = call("rust", "f");
        assertFalse("Rust doit proposer des mots-clés via le registre",
                itemsFn.isEmpty());
    }

    @Test
    public void keywords_c_returnsCKeywords() {
        // Préfixe "in" pour que "int" (longueur 3 > 2) soit proposé.
        List<CompletionSession.Item> items = call("c", "in");
        assertFalse("C doit proposer des mots-clés via le registre",
                items.isEmpty());
        boolean foundInt = items.stream()
                .anyMatch(i -> "int".equals(i.insertText));
        assertTrue("Le mot-clé 'int' doit être proposé pour C",
                foundInt);
    }

    @Test
    public void keywords_javascript_returnsJsKeywords() {
        // Préfixe "func" pour que "function" (longueur 8 > 4) soit proposé.
        List<CompletionSession.Item> items = call("javascript", "func");
        assertFalse("JavaScript doit proposer des mots-clés via le registre",
                items.isEmpty());
        boolean foundFunction = items.stream()
                .anyMatch(i -> "function".equals(i.insertText));
        assertTrue("Le mot-clé 'function' doit être proposé pour JS",
                foundFunction);
    }

    @Test
    public void keywords_typescript_returnsTsKeywords() {
        // Préfixe "inter" pour que "interface" (longueur 9 > 5) soit proposé.
        List<CompletionSession.Item> items = call("typescript", "inter");
        assertFalse("TypeScript doit proposer des mots-clés via le registre",
                items.isEmpty());
        boolean foundInterface = items.stream()
                .anyMatch(i -> "interface".equals(i.insertText));
        assertTrue("Le mot-clé 'interface' doit être proposé pour TS",
                foundInterface);
    }

    // ── XML : la liste inventée est retirée ──────────────────────

    @Test
    public void xml_doesNotContainInventedAttribute() {
        // L'attribut inventé "android:layout_padding" (le vrai est
        // android:padding) ne doit plus être proposé.
        List<CompletionSession.Item> items = call("xml", "android:layout_p");
        // Soit la liste est vide (le profil XML n'a pas
        // android:layout_padding), soit elle contient d'autres
        // suggestions qui ne sont pas android:layout_padding.
        boolean containsInvented = items.stream()
                .anyMatch(i -> "android:layout_padding".equals(i.insertText));
        assertFalse(
            "L'attribut inventé android:layout_padding ne doit plus être proposé",
            containsInvented);
    }

    @Test
    public void xml_keywordsFromRegistry_stillProposed() {
        // Les mots-clés XML enregistrés dans le profil (xmlns,
        // android, app, tools, schema, layout) doivent toujours
        // être proposés.
        List<CompletionSession.Item> items = call("xml", "and");
        boolean foundAndroid = items.stream()
                .anyMatch(i -> "android".equals(i.insertText));
        assertTrue(
            "Le mot-clé 'android' (enregistré dans le profil XML) doit être proposé",
            foundAndroid);
    }

    // ── Régression : Java, Kotlin, Markdown toujours couverts ───

    @Test
    public void java_keywords_stillProposed() {
        // Préfixe "pub" pour que "public" (longueur 6 > 3) soit proposé.
        List<CompletionSession.Item> items = call("java", "pub");
        assertFalse("Java doit toujours proposer ses mots-clés", items.isEmpty());
        boolean foundPublic = items.stream()
                .anyMatch(i -> "public".equals(i.insertText));
        assertTrue("Le mot-clé 'public' doit être proposé pour Java",
                foundPublic);
    }

    @Test
    public void kotlin_keywords_stillProposed() {
        // Préfixe "fu" pour que "fun" (longueur 3 > 2) soit proposé.
        List<CompletionSession.Item> items = call("kotlin", "fu");
        assertFalse("Kotlin doit toujours proposer ses mots-clés", items.isEmpty());
        boolean foundFun = items.stream()
                .anyMatch(i -> "fun".equals(i.insertText));
        assertTrue("Le mot-clé 'fun' doit être proposé pour Kotlin",
                foundFun);
    }

    @Test
    public void markdown_keywords_stillProposed() {
        // Markdown a une liste hardcoded de snippets (# , ## , etc.)
        // qui doivent rester — ils ne sont pas des mots-clés du
        // registre. Le Lot 3 conserve cette liste pour Markdown.
        List<CompletionSession.Item> items = call("markdown", "#");
        assertFalse("Markdown doit toujours proposer ses snippets", items.isEmpty());
        boolean foundHash = items.stream()
                .anyMatch(i -> "# ".equals(i.insertText));
        assertTrue("Le snippet '# ' doit être proposé pour Markdown",
                foundHash);
    }

    // ── Langage inconnu : liste vide ─────────────────────────────

    @Test
    public void unknownLanguage_returnsEmpty() {
        List<CompletionSession.Item> items = call("unknownlang", "x");
        assertTrue("Un langage inconnu ne propose aucune complétion",
                items.isEmpty());
    }
}
