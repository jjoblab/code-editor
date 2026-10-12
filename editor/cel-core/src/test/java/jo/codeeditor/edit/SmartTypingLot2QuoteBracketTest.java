package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 2 (audit v3.41.0) — auto-close des guillemets et des crochets
 * piloté par les {@code autoCloseQuotes} / {@code autoCloseBrackets}
 * du {@link jo.codeeditor.languages.LanguageProfile profil de
 * langage}.
 *
 * <p>Avant le Lot 2, l'auto-close des guillemets n'était déclenchée
 * que pour {@code java} et {@code kotlin} (via
 * {@link LanguageIds#isJavaOrKotlin} — qui compare en exact). Les
 * autres langages C_LIKE (C, C++, Go, Rust, JS, TS, PHP, Swift,
 * Dart, Groovy, Scala), Python, Lua, Shell, SQL et JSON ne
 * bénéficiaient pas de l'auto-close des guillemets.</p>
 *
 * <p>Avant le Lot 2, l'auto-close des crochets était appliquée à
 * tous les langages sauf {@code text} (via
 * {@link LanguageIds#isPlainText}). XML, HTML, CSS, Markdown,
 * YAML, properties, toml, log recevaient des paires {@code ()}
 * {@code {}} {@code []} qui n'ont pas de sens dans ces langages.</p>
 *
 * <p>Le Lot 2 route les deux comportements via le registre :</p>
 * <ul>
 *   <li>{@code autoCloseQuotes} : le caractère tapé doit être dans
 *       l'ensemble {@code profile.autoCloseQuotes} pour déclencher
 *       l'auto-close. L'ancien garde {@code isJavaOrKotlin} est
 *       supprimé.</li>
 *   <li>{@code autoCloseBrackets} : le caractère tapé doit être
 *       dans l'ensemble {@code profile.autoCloseBrackets} pour
 *       déclencher l'auto-close. L'ancien garde
 *       {@code !isPlainText} est supprimé.</li>
 * </ul>
 */
class SmartTypingLot2QuoteBracketTest {

    // ── Auto-close des guillemets pour C_LIKE (au-delà de Java/Kotlin) ──

    @Test
    void smartInsert_quote_autoClosesForC() {
        // C est un langage C_LIKE ; avant Lot 2, pas d'auto-close.
        RangeEdit e = EditOps.smartInsert("int x = ", 8, 8, '"', "c");
        assertEquals("\"\"", e.text,
                "C (famille C_LIKE) doit auto-fermer les guillemets");
        assertEquals(9, e.caret);
    }

    @Test
    void smartInsert_quote_autoClosesForGo() {
        RangeEdit e = EditOps.smartInsert("var s = ", 8, 8, '"', "go");
        assertEquals("\"\"", e.text,
                "Go (famille C_LIKE) doit auto-fermer les guillemets");
    }

    @Test
    void smartInsert_quote_autoClosesForRust() {
        RangeEdit e = EditOps.smartInsert("let s = ", 8, 8, '"', "rust");
        assertEquals("\"\"", e.text,
                "Rust (famille C_LIKE) doit auto-fermer les guillemets");
    }

    @Test
    void smartInsert_quote_autoClosesForJavascript() {
        RangeEdit e = EditOps.smartInsert("var s = ", 8, 8, '"', "javascript");
        assertEquals("\"\"", e.text,
                "JavaScript (famille C_LIKE) doit auto-fermer les guillemets");
    }

    @Test
    void smartInsert_quote_autoClosesForTypescript() {
        RangeEdit e = EditOps.smartInsert("let s = ", 8, 8, '"', "typescript");
        assertEquals("\"\"", e.text,
                "TypeScript (famille C_LIKE) doit auto-fermer les guillemets");
    }

    // ── Python, Lua, SQL, JSON : auto-close des guillemets ────────

    @Test
    void smartInsert_quote_autoClosesForPython() {
        RangeEdit e = EditOps.smartInsert("s = ", 4, 4, '"', "python");
        assertEquals("\"\"", e.text,
                "Python doit auto-fermer les guillemets");
    }

    @Test
    void smartInsert_quote_autoClosesForLua() {
        RangeEdit e = EditOps.smartInsert("s = ", 4, 4, '"', "lua");
        assertEquals("\"\"", e.text,
                "Lua doit auto-fermer les guillemets");
    }

    @Test
    void smartInsert_quote_autoClosesForSql() {
        RangeEdit e = EditOps.smartInsert("name = ", 7, 7, '\'', "sql");
        assertEquals("''", e.text,
                "SQL doit auto-fermer les guillemets (apostrophes)");
    }

    @Test
    void smartInsert_quote_autoClosesForJson() {
        RangeEdit e = EditOps.smartInsert("\"key", 4, 4, '"', "json");
        // Position après la fin de la chaîne entamée — c'est la
        // fermeture de la première chaîne. Mais en position 4, le
        // caractère précédent est " (lettre pas identifiant), donc
        // afterIdent=false → auto-close. En fait dans le cas présent
        // le texte est `"key"` et l'utilisateur tape `"` à la fin.
        // Vérifions plutôt avec une position claire.
        RangeEdit e2 = EditOps.smartInsert("\"key\": ", 7, 7, '"', "json");
        assertEquals("\"\"", e2.text,
                "JSON doit auto-fermer les guillemets pour les valeurs");
    }

    @Test
    void smartInsert_quote_autoClosesForShell() {
        // Shell a un ensemble de guillemets (apostrophes + doubles).
        RangeEdit e = EditOps.smartInsert("echo ", 5, 5, '\'', "shell");
        assertEquals("''", e.text,
                "Shell doit auto-fermer les guillemets");
    }

    // ── Text/Log/Properties/Toml : PAS d'auto-close des guillemets ──

    @Test
    void smartInsert_quote_doesNotAutoCloseForText() {
        RangeEdit e = EditOps.smartInsert("text ", 5, 5, '"', "text");
        assertEquals("\"", e.text,
                "text (pas d'auto-close des guillemets) — Lot 2");
    }

    @Test
    void smartInsert_quote_doesNotAutoCloseForLog() {
        RangeEdit e = EditOps.smartInsert("log ", 4, 4, '"', "log");
        assertEquals("\"", e.text,
                "log (pas d'auto-close des guillemets) — Lot 2");
    }

    @Test
    void smartInsert_quote_doesNotAutoCloseForProperties() {
        RangeEdit e = EditOps.smartInsert("key = ", 6, 6, '"', "properties");
        assertEquals("\"", e.text,
                "properties (pas d'auto-close des guillemets) — Lot 2");
    }

    @Test
    void smartInsert_quote_doesNotAutoCloseForToml() {
        RangeEdit e = EditOps.smartInsert("key = ", 6, 6, '"', "toml");
        assertEquals("\"", e.text,
                "toml (pas d'auto-close des guillemets) — Lot 2");
    }

    @Test
    void smartInsert_quote_doesNotAutoCloseForYaml() {
        RangeEdit e = EditOps.smartInsert("key: ", 5, 5, '"', "yaml");
        assertEquals("\"", e.text,
                "yaml (pas d'auto-close des guillemets par défaut) — Lot 2");
    }

    // ── Régression : Java/Kotlin toujours auto-close ─────────────

    @Test
    void smartInsert_quote_stillAutoClosesForJava() {
        RangeEdit e = EditOps.smartInsert("String s = ", 11, 11, '"', "java");
        assertEquals("\"\"", e.text);
    }

    @Test
    void smartInsert_quote_stillAutoClosesForKotlin() {
        RangeEdit e = EditOps.smartInsert("val s = ", 8, 8, '"', "kotlin");
        assertEquals("\"\"", e.text);
    }

    @Test
    void smartInsert_quote_stillAutoClosesForKtAlias() {
        // Alias kt résolu par le registre.
        RangeEdit e = EditOps.smartInsert("val s = ", 8, 8, '"', "kt");
        assertEquals("\"\"", e.text,
                "kt (alias de kotlin) doit auto-fermer les guillemets");
    }

    // ── Auto-close des crochets : seulement pour les langages de code ──

    @Test
    void smartInsert_paren_doesNotAutoCloseForXml() {
        // XML n'a pas de structure () {} [] — pas d'auto-close.
        RangeEdit e = EditOps.smartInsert("<foo", 4, 4, '(', "xml");
        assertEquals("(", e.text,
                "xml (pas d'auto-close des crochets) — Lot 2");
    }

    @Test
    void smartInsert_paren_doesNotAutoCloseForHtml() {
        RangeEdit e = EditOps.smartInsert("<foo", 4, 4, '(', "html");
        assertEquals("(", e.text,
                "html (pas d'auto-close des crochets) — Lot 2");
    }

    @Test
    void smartInsert_paren_doesNotAutoCloseForCss() {
        RangeEdit e = EditOps.smartInsert("body ", 5, 5, '(', "css");
        assertEquals("(", e.text,
                "css (pas d'auto-close des crochets) — Lot 2");
    }

    @Test
    void smartInsert_paren_doesNotAutoCloseForMarkdown() {
        RangeEdit e = EditOps.smartInsert("text ", 5, 5, '(', "markdown");
        assertEquals("(", e.text,
                "markdown (pas d'auto-close des crochets) — Lot 2");
    }

    @Test
    void smartInsert_paren_doesNotAutoCloseForYaml() {
        RangeEdit e = EditOps.smartInsert("key: ", 5, 5, '(', "yaml");
        assertEquals("(", e.text,
                "yaml (pas d'auto-close des crochets) — Lot 2");
    }

    @Test
    void smartInsert_paren_doesNotAutoCloseForShell() {
        RangeEdit e = EditOps.smartInsert("echo ", 5, 5, '(', "shell");
        assertEquals("(", e.text,
                "shell (pas d'auto-close des crochets) — Lot 2");
    }

    // ── Régression : Java/Python toujours auto-close des crochets ──

    @Test
    void smartInsert_paren_stillAutoClosesForJava() {
        RangeEdit e = EditOps.smartInsert("foo", 3, 3, '(', "java");
        // Attention : next char à pos=3 est EOF, donc nextIsIdent=false.
        // L'auto-close se déclenche.
        assertEquals("()", e.text);
    }

    @Test
    void smartInsert_paren_stillAutoClosesForPython() {
        RangeEdit e = EditOps.smartInsert("foo", 3, 3, '(', "python");
        assertEquals("()", e.text);
    }

    @Test
    void smartInsert_paren_stillAutoClosesForJson() {
        RangeEdit e = EditOps.smartInsert("foo", 3, 3, '(', "json");
        assertEquals("()", e.text);
    }

    // ── Hors contexte (chaîne/commentaire) : pas d'auto-close ─────

    @Test
    void smartInsert_quote_inComment_doesNotAutoClose() {
        // Régression : la garde contexte du Lot 1 reste active.
        RangeEdit e = EditOps.smartInsert("// foo ", 7, 7, '"', "java");
        assertEquals("\"", e.text,
                "Pas d'auto-close de quote dans un commentaire de ligne");
    }
}
