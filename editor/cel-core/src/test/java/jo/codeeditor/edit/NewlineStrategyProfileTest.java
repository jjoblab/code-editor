package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.NewlineStrategy;
import jo.codeeditor.languages.NewlineTrigger;
import jo.codeeditor.languages.SyntaxFamily;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (chantier C1, étape 3) — le dispatch du saut de ligne
 * intelligent devient une <b>donnée du profil</b>.
 *
 * <p>Avant C1-é3, {@link SmartNewline#smartEnter} dispatchait sur des
 * égalités de chaînes éparses : {@code LanguageIds.isXml} (famille XML,
 * non surchargeable), {@code "kotlin".equals(language)} (nom de langage
 * en dur, insensible au registre) et un {@code switch (canonical)} pour
 * les indent-triggers Python/YAML/Shell/Lua. Un langage maison d'une
 * famille existante ne pouvait donc PAS recevoir la newline Kotlin ni
 * de triggers d'indentation, et un langage XML ne pouvait PAS y
 * échapper — comportement par langage hors table, contraire au critère
 * C1 (« une entrée de table, zéro Java »).</p>
 *
 * <p>Désormais le profil porte une {@link NewlineStrategy} (XML /
 * KOTLIN / C_LIKE, défaut calculé par famille, surchargeable) et des
 * {@link NewlineTrigger}s (déclencheurs d'indentation +1 : fin de ligne
 * {@code :}, {@code then}, {@code do}… avec optionnellement des
 * préfixes de mots-clés de bloc, comme Python).</p>
 */
class NewlineStrategyProfileTest {

    /** Nettoyage : les profils de test ne fuient pas vers les autres tests. */
    @AfterEach
    void tearDown() {
        LanguageRegistry.unregister("t-ktl");
        LanguageRegistry.unregister("t-xmlx");
        LanguageRegistry.unregister("t-xml2");
        LanguageRegistry.unregister("t-pyl");
        LanguageRegistry.unregister("t-yml");
    }

    // ── NewlineTrigger : la règle de données elle-même ────────────

    @Test
    void trigger_suffixSeul_matcheFinDeLigne() {
        NewlineTrigger t = NewlineTrigger.onSuffix(":");
        assertTrue(t.matches("services:"));
        assertFalse(t.matches("name: project"));
        assertFalse(t.matches(""));
    }

    @Test
    void trigger_avecPrefixes_exigeMotCléDeBloc() {
        NewlineTrigger t = NewlineTrigger.onSuffix(":",
                "def ", "class ", "if ", "try:");
        assertTrue(t.matches("def foo():"));
        assertTrue(t.matches("try:"));
        assertFalse(t.matches("d = {1: 2}"));
    }

    // ── Langage maison Kotlin-like : la newline Kotlin par la table ──

    @Test
    void ktLikeMaison_newlineKotlinSansJava() {
        LanguageRegistry.register(LanguageProfile.builder("t-ktl")
                .family(SyntaxFamily.C_LIKE)
                .newlineStrategy(NewlineStrategy.KOTLIN)
                .build());
        // kotlinNewline n'a pas de label case (c'est javaNewline qui
        // indente après « case 1: ») — le discriminant Kotlin/Java.
        RangeEdit e = EditOps.smartEnter("case 1:", 7, "t-ktl");
        assertEquals("\n", e.text,
                "Un langage maison KOTLIN-like ne doit PAS indenter après un"
                        + " label case (comportement kotlinNewline) — text='"
                        + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void ktLikeMaison_indenteApresFleche() {
        LanguageRegistry.register(LanguageProfile.builder("t-ktl")
                .family(SyntaxFamily.C_LIKE)
                .newlineStrategy(NewlineStrategy.KOTLIN)
                .build());
        RangeEdit e = EditOps.smartEnter("x ->", 4, "t-ktl");
        assertTrue(e.text.startsWith("\n    "),
                "La newline KOTLIN indente +1 après une flèche when/match —"
                        + " text='" + e.text.replace("\n", "\\n") + "'");
    }

    // ── XML surchargeable : la famille ne verrouille plus la stratégie ──

    @Test
    void xmlSurchargeCLike_pasDAlignementAttribut() {
        LanguageRegistry.register(LanguageProfile.builder("t-xmlx")
                .family(SyntaxFamily.XML)
                .newlineStrategy(NewlineStrategy.C_LIKE)
                .build());
        // XmlNewline alignerait le premier attribut en colonne 14 ;
        // la stratégie C_LIKE surchargée doit produire la newline
        // simple (indentation courante, vide ici).
        RangeEdit e = EditOps.smartEnter("<LinearLayout foo", 17, "t-xmlx");
        assertEquals("\n", e.text,
                "Un langage de famille XML peut désormais échapper à la"
                        + " newline structurelle (surcharge C_LIKE) — text='"
                        + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void xmlMaison_structurelParDéfaut() {
        LanguageRegistry.register(LanguageProfile.builder("t-xml2")
                .family(SyntaxFamily.XML)
                .build());
        // Sans surcharge, la famille XML garde la newline structurelle :
        // alignement du premier attribut sous « <LinearLayout ».
        RangeEdit e = EditOps.smartEnter("<LinearLayout foo", 17, "t-xml2");
        assertEquals("\n" + " ".repeat(14), e.text,
                "La newline XML structurelle reste le défaut de la famille"
                        + " XML (alignement d'attribut) — text='"
                        + e.text.replace("\n", "\\n") + "'");
    }

    // ── Triggers d'indentation par la table ───────────────────────

    @Test
    void pyLikeMaison_indenteApresDef() {
        LanguageRegistry.register(LanguageProfile.builder("t-pyl")
                .family(SyntaxFamily.PYTHON)
                .newlineTrigger(NewlineTrigger.onSuffix(":",
                        "def ", "class ", "if ", "elif ", "for ", "while ",
                        "try:", "try :", "with ", "except ", "else:",
                        "finally:"))
                .build());
        RangeEdit e = EditOps.smartEnter("def foo():", 10, "t-pyl");
        assertTrue(e.text.startsWith("\n    "),
                "Un langage maison à trigger « : » indente +1 après « def"
                        + " foo(): » — text='" + e.text.replace("\n", "\\n")
                        + "'");
    }

    @Test
    void pyLikeMaison_pasDIndentSurDictionnaire() {
        LanguageRegistry.register(LanguageProfile.builder("t-pyl")
                .family(SyntaxFamily.PYTHON)
                .newlineTrigger(NewlineTrigger.onSuffix(":",
                        "def ", "class ", "if ", "try:"))
                .build());
        RangeEdit e = EditOps.smartEnter("d = {1: 2}", 10, "t-pyl");
        assertFalse(e.text.startsWith("\n    "),
                "Le trigger « : » avec préfixes de bloc ne doit PAS"
                        + " s'activer sur un dictionnaire — text='"
                        + e.text.replace("\n", "\\n") + "'");
    }

    @Test
    void ymlLikeMaison_indenteApresCleSansValeur() {
        LanguageRegistry.register(LanguageProfile.builder("t-yml")
                .family(SyntaxFamily.YAML)
                .newlineTrigger(NewlineTrigger.onSuffix(":"))
                .build());
        RangeEdit e = EditOps.smartEnter("services:", 9, "t-yml");
        assertTrue(e.text.startsWith("\n    "),
                "Un langage maison à trigger « : » (sans préfixe) indente"
                        + " +1 après « services: » — text='"
                        + e.text.replace("\n", "\\n") + "'");
    }

    // ── Gardes : les langages intégrés ne changent pas ────────────

    @Test
    void kotlinIntegré_newlineKotlin() {
        RangeEdit e = EditOps.smartEnter("case 1:", 7, "kotlin");
        assertEquals("\n", e.text,
                "Kotlin intégré : pas d'indentation après label case"
                        + " (comportement préservé)");
    }

    @Test
    void javaIntegré_labelCase_indente() {
        RangeEdit e = EditOps.smartEnter("case 1:", 7, "java");
        assertTrue(e.text.startsWith("\n    "),
                "Java intégré : le label case indente toujours (comportement"
                        + " préservé)");
    }

    @Test
    void xmlIntegré_aligneAttribut() {
        RangeEdit e = EditOps.smartEnter("<LinearLayout foo", 17, "xml");
        assertEquals("\n" + " ".repeat(14), e.text,
                "XML intégré : l'alignement d'attribut est préservé");
    }

    @Test
    void langageInconnu_newlineSimple() {
        RangeEdit e = EditOps.smartEnter("case 1:", 7, "langage-inconnu");
        assertTrue(e.text.startsWith("\n    "),
                "Langage inconnu : la newline C-like (label case) reste le"
                        + " comportement par défaut");
    }
}
