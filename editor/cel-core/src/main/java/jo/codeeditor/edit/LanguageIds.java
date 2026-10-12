package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

/**
 * Classification des identifiants de langage pour l'édition intelligente.
 * Détermine quelle famille d'aide à la saisie activer (aucune pour le texte
 * brut, paires/indentation style C pour Java-Kotlin, indentation structurelle
 * pour XML-HTML) afin que smartInsert et les gestionnaires de saut de ligne
 * dispatchent sur une famille plutôt que sur des égalités de chaînes éparses.
 *
 * <p><b>Lot 1 (audit v3.41.0)</b> : la résolution passe désormais par le
 * {@link LanguageRegistry registre} des langages. Les ids sont normalisés
 * (minuscules, trim) et résolus via leurs alias déclarés ({@code kt} →
 * Kotlin, {@code htm} / {@code svg} → XML, etc.). La casse est insensible.
 * Les langages contribués par l'hôte via
 * {@link LanguageRegistry#register(LanguageProfile)} sont ainsi reconnus
 * au même titre que les langages intégrés.</p>
 *
 * <p><b>Sémantique</b> : {@code isJavaOrKotlin} et {@code isXml} reflètent
 * la <b>famille lexicale</b> (C_LIKE, XML), pas seulement les ids Java et
 * Kotlin. Un langage de famille C_LIKE (C, Go, JS, TS, Scala, etc.)
 * déclenche donc l'auto-close des quotes ; un langage de famille XML
 * (xml, html, htm, svg) déclenche l'indentation structurelle. La
 * classification « texte brut » reste sur les marqueurs explicites
 * {@code text}/{@code plaintext} (le registre ne définit pas de famille
 * « prose »).</p>
 */
final class LanguageIds {

    private LanguageIds() {}

    /**
     * Retourne vrai si le langage est du texte brut (pas d'auto-close,
     * pas d'indentation intelligente). Le texte brut est identifié par
     * les marqueurs explicites {@code text}/{@code plaintext} (le
     * registre n'a pas de famille « prose »).
     */
    static boolean isPlainText(String lang) {
        if (lang == null || lang.isEmpty()) return true;
        String normalized = lang.trim().toLowerCase(java.util.Locale.ROOT);
        return "text".equals(normalized) || "plaintext".equals(normalized);
    }

    /**
     * Retourne vrai si le langage est Java ou Kotlin (résolu par
     * alias via le registre : {@code kt}, {@code Kotlin}, etc.).
     * Active l'auto-close des quotes par {@link SmartTyping} et la
     * complétion d'instruction par {@link SmartNewline}.
     *
     * <p><b>Lot 1 (audit v3.41.0)</b> : la résolution passe par le
     * {@link LanguageRegistry registre} — l'alias {@code kt} et la
     * casse mixte ({@code Kotlin}, {@code JAVA}) sont désormais
     * reconnus. L'élargissement à tous les langages de famille
     * C_LIKE (C, Go, JS, TS, etc.) pour l'auto-close des quotes sera
     * traité dans le Lot 2 via les « traits d'édition » de
     * {@link LanguageProfile} ; ce Lot 1 conserve la sémantique
     * stricte pour ne pas introduire de comportement inattendu
     * pour les langages C_LIKE contribués.</p>
     */
    static boolean isJavaOrKotlin(String lang) {
        if (lang == null || lang.isEmpty()) return false;
        LanguageProfile profile = LanguageRegistry.forName(lang);
        if (profile == null) return false;
        String name = profile.name;
        return "java".equals(name) || "kotlin".equals(name);
    }

    /**
     * Retourne vrai si le langage appartient à la famille lexicale XML
     * (résolu par alias via le registre : {@code xml}, {@code html},
     * {@code htm}, {@code svg}, {@code XML}, etc.). Active
     * l'indentation structurelle des balises par {@link SmartNewline}
     * → {@link XmlNewline}.
     */
    static boolean isXml(String lang) {
        if (lang == null || lang.isEmpty()) return false;
        LanguageProfile profile = LanguageRegistry.forName(lang);
        return profile != null && profile.family == SyntaxFamily.XML;
    }
}
