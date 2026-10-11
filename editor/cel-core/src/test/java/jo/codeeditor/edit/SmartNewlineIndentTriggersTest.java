package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 2/3 (audit v3.41.0) — indentation après {@code :},
 * {@code then}, {@code do} pour les langages à indentation
 * par mots-clés (Python, YAML, Shell, Lua, Ruby).
 *
 * <p>Avant ce correctif, {@link SmartNewline#smartEnter}
 * n'indente pas après une ligne se terminant par {@code :}
 * (Python {@code def}, {@code if}, etc. ; YAML
 * {@code key:}) ou par {@code then}/{@code do} (Shell
 * {@code if … then}, Lua {@code for … do}). L'utilisateur
 * doit indenter manuellement — contrairement à les éditeurs de code de bureau et
 * les IDE professionnels.</p>
 *
 * <p>Périmètre minimal (décision 4.4 — évaluer le risque de
 * faux positifs) :</p>
 * <ul>
 *   <li><b>Python</b> ({@code python}, alias {@code py}) :
 *       indent après une ligne se terminant par {@code :}
 *       qui commence par un mot-clé de bloc
 *       ({@code def}, {@code class}, {@code if}, {@code elif},
 *       {@code for}, {@code while}, {@code try}, {@code with},
 *       {@code except}, {@code else}, {@code finally}).</li>
 *   <li><b>YAML</b> ({@code yaml}, alias {@code yml}) :
 *       indent après une ligne se terminant par {@code :}
 *       (sans valeur après le deux-points, ou avec une
 *       valeur qui s'étend sur plusieurs lignes). Pas de
 *       garde par mot-clé — YAML n'a pas de mots-clés de
 *       bloc.</li>
 *   <li><b>Shell</b> ({@code shell}, alias {@code bash},
 *       {@code sh}) : indent après une ligne se terminant
 *       par {@code then} ou {@code do}.</li>
 *   <li><b>Lua</b> ({@code lua}) : indent après une ligne
 *       se terminant par {@code then} ou {@code do}.</li>
 * </ul>
 *
 * <p><b>Risque de faux positifs évalué</b> :</p>
 * <ul>
 *   <li>Java/Kotlin/JS/TS : pas affectés — ces langages sont
 *       C_LIKE et utilisent les accolades, pas les
 *       deux-points.</li>
 *   <li>JSON : pas affecté — JSON n'a pas de
 *       {@code language} non-C_LIKE avec {@code :} ; et
 *       JSON ne déclenche pas l'indent trigger car le
 *       profil n'est ni PYTHON/YAML/SHELL/LUA.</li>
 *   <li>Ternaire Java {@code cond ? a : b} : pas affecté —
 *       Java est C_LIKE, pas de trigger {@code :}.</li>
 * </ul>
 */
class SmartNewlineIndentTriggersTest {

    // ── Python : indent après `:` ─────────────────────────────────

    @Test
    void python_defColon_indentsNextLine() {
        String text = "def foo():";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "python");
        assertTrue(e.text.startsWith("\n    "),
                "Python : Entrée après « def foo(): » doit indenter +4 — "
                        + "text='" + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void python_ifColon_indentsNextLine() {
        String text = "if x > 0:";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "python");
        assertTrue(e.text.startsWith("\n    "),
                "Python : Entrée après « if x > 0: » doit indenter +4");
    }

    @Test
    void python_classColon_indentsNextLine() {
        String text = "class Foo:";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "python");
        assertTrue(e.text.startsWith("\n    "),
                "Python : Entrée après « class Foo: » doit indenter +4");
    }

    @Test
    void python_nonBlockColon_doesNotIndent() {
        // Un dictionnaire « d = {1: 2} » — le `:` n'est pas un
        // mot-clé de bloc. Pas d'indentation.
        String text = "d = {1: 2}";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "python");
        // Pas d'indentation — le texte commence par "\n" sans
        // espaces supplémentaires.
        assertFalse(e.text.startsWith("\n    "),
                "Python : Entrée après « d = {1: 2} » ne doit PAS indenter — "
                        + "ce n'est pas un mot-clé de bloc");
    }

    @Test
    void python_pyAlias_indentsNextLine() {
        String text = "def foo():";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "py");
        assertTrue(e.text.startsWith("\n    "),
                "Python (alias py) : Entrée après « def foo(): » doit indenter +4");
    }

    // ── YAML : indent après `:` ───────────────────────────────────

    @Test
    void yaml_keyColon_indentsNextLine() {
        String text = "services:";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "yaml");
        assertTrue(e.text.startsWith("\n    "),
                "YAML : Entrée après « services: » doit indenter +4 — "
                        + "text='" + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void yaml_keyWithScalar_doesNotIndent() {
        // « key: value » — le `:` est suivi d'une valeur, pas
        // d'un bloc imbriqué. YAML n'indente pas dans ce cas
        // (la valeur est sur la même ligne).
        String text = "name: project";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "yaml");
        // Pas d'indentation — le texte commence par "\n" sans
        // espaces supplémentaires (ou avec l'indentation courante
        // qui est 0).
        assertFalse(e.text.startsWith("\n    "),
                "YAML : Entrée après « name: project » ne doit PAS indenter — "
                        + "la valeur est sur la même ligne");
    }

    @Test
    void yaml_ymlAlias_indentsNextLine() {
        String text = "services:";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "yml");
        assertTrue(e.text.startsWith("\n    "),
                "YAML (alias yml) : Entrée après « services: » doit indenter +4");
    }

    // ── Shell : indent après `then` et `do` ───────────────────────

    @Test
    void shell_ifThen_indentsNextLine() {
        String text = "if [ -f file ]; then";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "shell");
        assertTrue(e.text.startsWith("\n    "),
                "Shell : Entrée après « if … then » doit indenter +4 — "
                        + "text='" + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void shell_forDo_indentsNextLine() {
        String text = "for i in 1 2 3; do";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "shell");
        assertTrue(e.text.startsWith("\n    "),
                "Shell : Entrée après « for … do » doit indenter +4");
    }

    @Test
    void shell_bashAlias_indentsNextLine() {
        String text = "while true; do";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "bash");
        assertTrue(e.text.startsWith("\n    "),
                "Shell (alias bash) : Entrée après « while … do » doit indenter +4");
    }

    // ── Lua : indent après `then` et `do` ─────────────────────────

    @Test
    void lua_ifThen_indentsNextLine() {
        String text = "if x > 0 then";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "lua");
        assertTrue(e.text.startsWith("\n    "),
                "Lua : Entrée après « if … then » doit indenter +4 — "
                        + "text='" + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void lua_forDo_indentsNextLine() {
        String text = "for i = 1, 10 do";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "lua");
        assertTrue(e.text.startsWith("\n    "),
                "Lua : Entrée après « for … do » doit indenter +4");
    }

    // ── Java (C_LIKE) : pas de trigger `:` ────────────────────────

    @Test
    void java_ternaryColon_doesNotIndent() {
        // Java ternaire « cond ? a : b » — le `:` ne doit pas
        // déclencher d'indentation (Java est C_LIKE).
        String text = "int x = cond ? a : b";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "java");
        assertFalse(e.text.startsWith("\n    "),
                "Java : Entrée après un ternaire « … : b » ne doit PAS indenter — "
                        + "Java est C_LIKE, pas de trigger « : »");
    }

    @Test
    void java_switchCase_indentsByExistingBehavior() {
        // Java switch « case 1: » — le `:` déclenche l'indentation
        // via le label case existant de javaNewline (isCaseLabel).
        // C'est le comportement historique, pas un trigger « : »
        // générique — le test valide que ce comportement est
        // préservé.
        String text = "case 1:";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "java");
        assertTrue(e.text.startsWith("\n    "),
                "Java : Entrée après « case 1: » DOIT indenter via "
                        + "le label case existant (comportement préservé) — "
                        + "text='" + e.text.replace("\n", "\\n") + "'");
    }

    // ─<arg_value> Régression : Java/Kotlin indentent toujours après `{` ───────

    @Test
    void java_openBrace_stillIndents_regression() {
        String text = "if (cond) {";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "java");
        assertTrue(e.text.startsWith("\n    "),
                "Régression : Java doit toujours indenter après « { »");
    }

    @Test
    void kotlin_openBrace_stillIndents_regression() {
        String text = "fun foo() {";
        RangeEdit e = EditOps.smartEnter(text, text.length(), "kotlin");
        assertTrue(e.text.startsWith("\n    "),
                "Régression : Kotlin doit toujours indenter après « { »");
    }
}
