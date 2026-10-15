package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static jo.codeeditor.highlight.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Caractérisation du styleur de logs (ADR-002, chantier C2 — cliquet de
 * démantèlement) : verrouille le comportement EXACT (type de l'unique
 * span par ligne, ou ABSENCE de span pour le PLAIN — chemin rapide)
 * de {@code LogTokenizer.styleLog} sur une batterie de lignes
 * représentatives des consoles Gradle/JVM, AVANT puis APRÈS la
 * migration sur le moteur générique {@link GenericTokenizer} (troisième
 * saveur : classifieur de ligne). La migration doit garder cette suite
 * verte sans toucher à ses assertions — c'est le critère « octet pour
 * octet » du chantier C2.
 */
class LogTokenizerTest {

    private static List<LineSpan> spans(String line) {
        return LogTokenizer.styleLog(line).spans;
    }

    // ── Erreurs (évaluées EN PREMIER) ────────────────────────────────

    @Test
    void erreur_messageDErreur() {
        assertEquals(List.of(
                new LineSpan(0, 16, ERROR)),
                spans("error: something"));
    }

    @Test
    void erreur_buildFailed() {
        // « BUILD FAILED » gagne sur tout (règle ERROR avant SUCCESS —
        // garde documentée de l'ordre d'évaluation).
        assertEquals(List.of(
                new LineSpan(0, 18, ERROR)),
                spans("BUILD FAILED in 3s"));
    }

    @Test
    void erreur_préfixeKotlinE() {
        assertEquals(List.of(
                new LineSpan(0, 12, ERROR)),
                spans("e: file.kt:3"));
    }

    @Test
    void erreur_échecEnFrançais() {
        assertEquals(List.of(
                new LineSpan(0, 14, ERROR)),
                spans("échec du build"));
    }

    @Test
    void erreur_whatWentWrong() {
        assertEquals(List.of(
                new LineSpan(0, 16, ERROR)),
                spans("What went wrong:"));
    }

    @Test
    void erreur_exception() {
        assertEquals(List.of(
                new LineSpan(0, 22, ERROR)),
                spans("java.lang.Exception: x"));
    }

    // ── Avertissements (avant SUCCESS) ───────────────────────────────

    @Test
    void avertissement_warningDeuxPoints() {
        assertEquals(List.of(
                new LineSpan(0, 23, WARNING)),
                spans("warning: deprecated API"));
    }

    @Test
    void avertissement_préfixeKotlinW() {
        assertEquals(List.of(
                new LineSpan(0, 12, WARNING)),
                spans("w: file.kt:3"));
    }

    @Test
    void avertissement_chevronWarning() {
        // « > warning … » : la règle WARNING précède la règle TYPE des
        // chevrons — garde historique.
        assertEquals(List.of(
                new LineSpan(0, 13, WARNING)),
                spans("> warning foo"));
    }

    // ── Succès (avant INFO) ──────────────────────────────────────────

    @Test
    void succès_buildSuccessful() {
        assertEquals(List.of(
                new LineSpan(0, 22, SUCCESS)),
                spans("BUILD SUCCESSFUL in 2s"));
    }

    @Test
    void succès_statutDeTâcheAvecEspace() {
        // Les statuts Gradle sont précédés d'un espace (sortie chunked).
        assertEquals(List.of(
                new LineSpan(0, 11, SUCCESS)),
                spans(" UP-TO-DATE"));
    }

    @Test
    void succès_sansEspaceInitialPasUnStatut() {
        // Sans l'espace initial, la règle ne matche pas → PLAIN.
        assertEquals(List.of(), spans("no-source"));
    }

    @Test
    void succès_prêtEnFinDeLigne() {
        assertEquals(List.of(
                new LineSpan(0, 9, SUCCESS)),
                spans("Sync prêt"));
    }

    @Test
    void succès_avantInfo() {
        // « [Tooling] … succès » : SUCCESS gagne sur INFO — garde
        // documentée de l'ordre.
        assertEquals(List.of(
                new LineSpan(0, 29, SUCCESS)),
                spans("[Tooling] terminé avec succès"));
    }

    // ── En-têtes de section ──────────────────────────────────────────

    @Test
    void entête_chevronTask() {
        // « > Task :… » — règle SENSIBLE À LA CASSE sur la ligne
        // ORIGINALE (pas de minuscule).
        assertEquals(List.of(
                new LineSpan(0, 19, TYPE)),
                spans("> Task :app:compile"));
    }

    @Test
    void entête_chevronConfigure() {
        assertEquals(List.of(
                new LineSpan(0, 13, TYPE)),
                spans("> Configure x"));
    }

    // ── Lignes bracketées ────────────────────────────────────────────

    @Test
    void info_tooling() {
        assertEquals(List.of(
                new LineSpan(0, 14, INFO)),
                spans("[Tooling] sync"));
    }

    @Test
    void info_jvmOut() {
        assertEquals(List.of(
                new LineSpan(0, 14, INFO)),
                spans("[JVM-out] heap"));
    }

    // ── Sortie standard : AUCUNE span ────────────────────────────────

    @Test
    void plain_sortieGradleOrdinaire() {
        // Le chemin rapide : pas de span du tout (rendu texte brut).
        assertEquals(List.of(), spans("Task :app:compileJava"));
    }

    @Test
    void plain_ligneVide() {
        assertEquals(List.of(), spans(""));
    }

    @Test
    void état_sortieToujoursNormal() {
        StyledLine line = LogTokenizer.styleLog("> Task :x");
        assertEquals(LexState.NORMAL, line.exitState);
        assertEquals(LexState.NORMAL, line.entryState);
    }
}
