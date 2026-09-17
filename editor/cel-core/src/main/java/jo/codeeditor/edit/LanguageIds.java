package jo.codeeditor.edit;

/**
 * Classification des identifiants de langage pour l'édition intelligente.
 * Détermine quelle famille d'aide à la saisie activer (aucune pour le texte
 * brut, paires/indentation style C pour Java-Kotlin, indentation structurelle
 * pour XML-HTML) afin que smartInsert et les gestionnaires de saut de ligne
 * dispatchent sur une famille plutôt que sur des égalités de chaînes éparses.
 */
final class LanguageIds {

    private LanguageIds() {}

    /**
     * Retourne vrai si le langage est du texte brut (pas d'auto-close, pas d'indentation intelligente).
     */
    static boolean isPlainText(String lang) {
        return lang == null || lang.isEmpty() || "text".equals(lang) || "plaintext".equals(lang);
    }

    /** Retourne vrai si le langage est Java ou Kotlin (paires, désindentation des fermeurs). */
    static boolean isJavaOrKotlin(String lang) {
        return "java".equals(lang) || "kotlin".equals(lang);
    }

    /** Retourne vrai si le langage est XML ou HTML (indentation structurelle des balises). */
    static boolean isXml(String lang) {
        return "xml".equals(lang) || "html".equals(lang);
    }
}
