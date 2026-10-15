package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur générique « à syntaxe C » — <b>migré sur le moteur
 * générique</b> (ADR-002, chantier C2, étape 6) : la grammaire
 * (commentaires de ligne/bloc/documentation à états inter-lignes,
 * chaînes brutes Kotlin à triples guillemets, littéraux de caractère,
 * template literals JS/TS à interpolations, annotations, nombres
 * hexadécimaux/suffixés, couleurs #RRGGBB, mots-clés du registre avec
 * raffinements contrôle/modificateur, types intégrés, tout-en-
 * majuscules, propriétés après point, courses d'opérateurs) vit
 * désormais dans le template déclaratif {@link TokenizerTemplate#CLIKE},
 * exécuté par {@link GenericTokenizer} en mode flux libre. Le
 * dispatcher ({@code SyntaxHighlighter}) continue d'appeler cette
 * entrée — signature inchangée, comportement repris octet pour octet
 * de l'implémentation historique écrite à la main (verrouillé par
 * {@code CLikeTokenizerTest} et les suites
 * {@code SyntaxHighlighterTest} / {@code FineTokenDistinctionsTest},
 * y compris les portes par langage : « kotlin » exact pour les chaînes
 * brutes, javascript/typescript/js/ts pour les template literals,
 * repli mots-clés Java pour les identifiants inconnus).
 *
 * <p>Historique : cœur du « parser maison » extrait de
 * SyntaxHighlighter (v3.4x), partagé par toute la famille C (java,
 * kotlin, javascript, typescript, c, cpp, go, rust, ruby, php, swift,
 * dart, groovy) ; le cliquet de démantèlement (C2) remplace le corps
 * par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class CLikeTokenizer {

    /**
     * Tokénise une ligne à syntaxe C — délégation au moteur générique
     * avec le template {@link TokenizerTemplate#CLIKE} et le langage
     * demandé (mots-clés du registre, portes par langage).
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>commentaires {@code //}, {@code /* … *&#47;} et
     *       {@code /** … *&#47;} (DOC_COMMENT distinct, états
     *       inter-lignes) ;</li>
     *   <li>chaînes {@code "…"} à échappements sautés (jeton unique),
     *       littéraux de caractère {@code 'a'} → CHAR, chaînes brutes
     *       Kotlin {@code """…"""} → RAW_STRING (état inter-lignes) ;</li>
     *   <li>template literals JS/TS avec interpolations {@code ${…}} en
     *       VARIABLE et échappements en ESCAPE ;</li>
     *   <li>annotations {@code @Nom} → ANNOTATION ;</li>
     *   <li>nombres décimaux/hexadécimaux/octaux/binaires à suffixes et
     *       littéraux de couleur {@code #RRGGBB} → NUMBER ;</li>
     *   <li>mots-clés du langage (registre) raffinés
     *       KEYWORD_CONTROL/KEYWORD_MODIFIER, types intégrés → TYPE,
     *       ALL_CAPS → CONSTANT, identifiant suivi de {@code (} → FUNC,
     *       après un point → PROPERTY, initiale majuscule → TYPE ;</li>
     *   <li>courses d'opérateurs, ponctuation en repli.</li>
     * </ul></p>
     */
    public static StyledLine styleCLike(String line, int entryState, String language) {
        return GenericTokenizer.styleLine(TokenizerTemplate.CLIKE, line, entryState, language);
    }

    private CLikeTokenizer() {}
}
