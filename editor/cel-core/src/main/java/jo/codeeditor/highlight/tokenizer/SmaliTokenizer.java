package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur Smali — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 10) : la grammaire (directives {@code .class}
 * raffinées par table, descripteurs {@code L…;}/{@code [L…;},
 * registres en VARIABLE, opcodes à tiret/slash en FUNC, nombres ouverts
 * par un signe, chaîne orpheline tombante — garde historique) vit
 * désormais dans le template déclaratif {@link TokenizerTemplate#SMALI},
 * exécuté par {@link GenericTokenizer} en mode flux libre. Le
 * dispatcher ({@code SyntaxHighlighter}) continue d'appeler cette
 * entrée — signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code SmaliTokenizerTest}).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class SmaliTokenizer {

    /**
     * Tokénise une ligne Smali — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#SMALI}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>directives ({@code .class}, {@code .method}…) → KEYWORD,
     *       inconnues → ANNOTATION</li>
     *   <li>registres ({@code p0}-{@code p9}, {@code v0}-{@code v15}) →
     *       VARIABLE</li>
     *   <li>commentaires {@code #}</li>
     *   <li>chaînes à échappements apparents — l'orpheline tombe dans
     *       les règles suivantes (garde historique)</li>
     *   <li>descripteurs ({@code Ljava/lang/String;}, {@code [L…;}) →
     *       TYPE, fermante à moins de 200 caractères</li>
     *   <li>littéraux hexadécimaux, nombres ouverts par un signe</li>
     *   <li>opcodes ({@code invoke-direct}, {@code array/}) → FUNC</li>
     * </ul></p>
     */
    public static StyledLine styleSmali(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.SMALI, line, entryState);
    }

    private SmaliTokenizer() {}
}
