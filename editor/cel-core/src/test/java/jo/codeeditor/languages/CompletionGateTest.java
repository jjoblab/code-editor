package jo.codeeditor.languages;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-002 (chantier C1) — la porte de contexte de complétion devient une
 * donnée du profil.
 *
 * <p>Avant C1, l'exemption vivait dans l'UI : {@code EditorCompletionPopup}
 * commutait sur la famille lexicale (LOG → jamais, XML → toujours). Ces
 * politiques sont des données par langage : elles descendent dans
 * {@link LanguageProfile} via {@link CompletionGate}, avec défaut par
 * famille et surcharge par langage.</p>
 *
 * <p>Ce test JVM fixe le contrat des défauts et de la surcharge ; la
 * traduction comportementale (le popup honore la donnée) vit dans
 * {@code CompletionGateProfileTest} (cel-ui, Robolectric).</p>
 */
public class CompletionGateTest {

    /** Défauts par famille : LOG → OFF, XML → ALWAYS, autres → STANDARD. */
    @Test
    public void defaultsParFamille() {
        assertEquals(CompletionGate.OFF,
                LanguageProfile.builder("t1").family(SyntaxFamily.LOG).build().completionGate);
        assertEquals(CompletionGate.ALWAYS,
                LanguageProfile.builder("t2").family(SyntaxFamily.XML).build().completionGate);
        assertEquals(CompletionGate.STANDARD,
                LanguageProfile.builder("t3").family(SyntaxFamily.C_LIKE).build().completionGate);
        assertEquals(CompletionGate.STANDARD,
                LanguageProfile.builder("t4").family(SyntaxFamily.PYTHON).build().completionGate);
        assertEquals(CompletionGate.STANDARD,
                LanguageProfile.builder("t5").family(SyntaxFamily.MARKDOWN).build().completionGate);
    }

    /** La surcharge explicite gagne sur le défaut de famille. */
    @Test
    public void surchargeExpliciteGagne() {
        assertEquals(CompletionGate.STANDARD,
                LanguageProfile.builder("t6").family(SyntaxFamily.LOG)
                        .completionGate(CompletionGate.STANDARD).build().completionGate);
        assertEquals(CompletionGate.OFF,
                LanguageProfile.builder("t7").family(SyntaxFamily.XML)
                        .completionGate(CompletionGate.OFF).build().completionGate);
    }

    /** Null → retombe sur le défaut de famille (nullabilité défensive du builder). */
    @Test
    public void nullRetombeSurLeDéfaut() {
        assertEquals(CompletionGate.ALWAYS,
                LanguageProfile.builder("t8").family(SyntaxFamily.XML)
                        .completionGate(null).build().completionGate);
    }

    /** Les langages intégrés portent la politique attendue (xml, html, log). */
    @Test
    public void langagesIntégrés() {
        assertEquals(CompletionGate.ALWAYS, LanguageRegistry.forName("xml").completionGate);
        assertEquals(CompletionGate.ALWAYS, LanguageRegistry.forName("html").completionGate);
        assertEquals(CompletionGate.OFF, LanguageRegistry.forName("log").completionGate);
        assertEquals(CompletionGate.STANDARD, LanguageRegistry.forName("java").completionGate);
        assertEquals(CompletionGate.STANDARD, LanguageRegistry.forName("python").completionGate);
    }
}
