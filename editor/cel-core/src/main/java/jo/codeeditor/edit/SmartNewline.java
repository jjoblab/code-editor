package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.NewlineStrategy;
import jo.codeeditor.languages.NewlineTrigger;

/**
 * Gestionnaires de saut de ligne intelligents : dispatch par la
 * STRATÉGIE DU PROFIL (ADR-002, C1-é3 — XML structurel / Kotlin /
 * C-like), indentation Java (puces, labels case, annotations,
 * commentaires) et Kotlin (flèches), plus la complétion d'instruction
 * sur Entrée. Extrait d'EditOps pour isoler la responsabilité
 * « nouvelle ligne » de la saisie caractère par caractère.
 *
 * <p><b>ADR-002 (C1-é3)</b> : le dispatch ne commute plus sur des
 * égalités de chaînes ({@code LanguageIds.isXml},
 * {@code "kotlin".equals(language)}, {@code switch (canonical)}) mais
 * sur {@link LanguageProfile#newlineStrategy} — donnée du profil,
 * défaut par famille, surchargeable par l'hôte. Les déclencheurs
 * d'indentation (+1 après {@code :}, {@code then}, {@code do}) sont
 * également des {@link NewlineTrigger}s du profil, la table intégrée
 * déclarant ceux de Python/YAML/Shell/Lua (comportement repris octet
 * pour octet).</p>
 */
final class SmartNewline {

    private SmartNewline() {}

    /**
     * Smart Enter : poursuite de l'indentation, plus profonde après les ouvreurs, expansion de crochets, etc.
     *
     * <p>Lot 2/3 (audit v3.41.0) : indent triggers pour les langages
     * à indentation par mots-clés (Python, YAML, Shell, Lua). Quand
     * l'utilisateur appuie sur Entrée après une ligne se terminant
     * par {@code :} (Python, YAML) ou {@code then}/{@code do}
     * (Shell, Lua), la ligne suivante est indentée +1 niveau.
     * Depuis C1-é3, ces triggers vivent dans le profil
     * ({@link LanguageProfile#newlineTriggers}).</p>
     */
    static RangeEdit smartEnter(CharSequence text, int pos, String language) {
        // ADR-002 (C1-é3) : dispatch par la stratégie du profil.
        // Langage inconnu ou null → C_LIKE sans triggers (comportement
        // historique du repli javaNewline, inchangé).
        LanguageProfile profile = language != null
                ? LanguageRegistry.forName(language) : null;
        NewlineStrategy strategy = profile != null
                ? profile.newlineStrategy : NewlineStrategy.C_LIKE;
        if (strategy == NewlineStrategy.XML) {
            return XmlNewline.xmlNewline(text, pos, language);
        }
        if (strategy == NewlineStrategy.KOTLIN) {
            return kotlinNewline(text, pos, language, profile);
        }
        // Stratégie C_LIKE : indent triggers du profil (Python, YAML,
        // Shell, Lua — et tout langage maison qui en déclare),
        // vérifiés avant le comportement C de javaNewline.
        if (profile != null && !profile.newlineTriggers.isEmpty()
                && indentTriggerMatch(text, pos, profile)) {
            String indentUnit = IndentDetection.detectIndentUnit(text);
            int lineStart = EditTextUtils.lineStartForOffset(text, pos);
            String currentIndent = EditTextUtils.extractIndent(text, lineStart);
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent,
                    pos + 1 + deeperIndent.length());
        }
        // Java et défaut
        return javaNewline(text, pos, language, profile);
    }

    /**
     * Lot 2/3 (audit v3.41.0) — détermine si la ligne avant le
     * caret matche un {@link NewlineTrigger} <b>du profil</b> (ADR-002,
     * C1-é3). Chaque trigger porte son suffixe de fin de ligne
     * ({@code :}, {@code then}, {@code do}) et, optionnellement, ses
     * préfixes de mots-clés de bloc (Python : {@code def},
     * {@code class}, {@code if}… — évite les dictionnaires
     * {@code {1: 2}}, annotations, slices).
     *
     * <p><b>Risque de faux positifs évalué</b> : Java/Kotlin/
     * JS/TS sont C_LIKE et ne déclarent pas de trigger {@code :} —
     * le ternaire Java {@code cond ? a : b} ne déclenche pas.
     * JSON n'en déclare pas non plus. Les langages maison ne
     * déclenchent que s'ils déclarent leurs triggers.</p>
     */
    private static boolean indentTriggerMatch(CharSequence text, int pos,
            LanguageProfile profile) {
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String lineText = EditTextUtils.extractLineText(text, lineStart).trim();
        if (lineText.isEmpty()) return false;
        for (NewlineTrigger trigger : profile.newlineTriggers) {
            if (trigger.matches(lineText)) return true;
        }
        return false;
    }

    /**
     * Gestionnaire de saut de ligne Java/Kotlin. ADR-002 (C1-é4) :
     * les délimiteurs (chaîne, doc, continuation de bloc) viennent du
     * profil — repli C historique quand le langage est inconnu.
     */
    private static RangeEdit javaNewline(CharSequence text, int pos, String language,
            LanguageProfile profile) {
        boolean isKotlin = "kotlin".equals(language);
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);
        // Lot 1 (audit v3.41.0) : unité d'indentation détectée
        // (tabulation, 2 ou 4 ou 8 espaces) au lieu du littéral codé
        // dur "    ". Un fichier 2-espaces recevait 4 espaces de
        // continuation avant ce correctif.
        String indentUnit = IndentDetection.detectIndentUnit(text);

        // Vérifier si on est dans un littéral de chaîne — pas de saut de ligne intelligent
        if (!isKotlin && CodeContext.isInString(text, pos, profile)) {
            return plainNewline(text, pos, currentIndent);
        }

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Expansion de paire vide : {|} -> {<indent>\n<indent>curseur\n<indent>}
        if (BracketPairs.isOpener(charBefore) && BracketPairs.isCloser(charAfter) && BracketPairs.isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + indentUnit;
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Indentation plus profonde après les ouvreurs : { ou ( ou [
        if (BracketPairs.isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // ADR-002 (C1-é4) : continuation de commentaire doc/bloc par les
        // DONNÉES du profil, AVANT le test d'opérateur en suspension —
        // historiquement le motif (charBefore == '*' && …) était placé
        // APRÈS isDanglingOperator, qui matche aussi '*' : il était donc
        // court-circuité et « /** + Entrée » produisait une indentation
        // d'opérateur au lieu de la continuation documentée. Le profil
        // porte désormais l'ouvreur de doc ("/**", ou "///" pour un
        // langage maison) et le préfixe de continuation (" * " pour les
        // blocs C, null = newline simple). Le caret suit le texte
        // inséré comme historiquement (un caractère avant la fin).
        String blockStart = profile != null ? profile.commentSyntax.blockStart : "/*";
        String docStart = profile != null ? profile.docCommentStart : "/**";
        String cont = profile != null ? profile.blockCommentContinuation : " * ";
        if (cont != null
                && (endsWithAt(text, pos, blockStart) || endsWithAt(text, pos, docStart))) {
            return new RangeEdit(pos, pos, "\n" + currentIndent + cont,
                    pos + 1 + currentIndent.length() + cont.length() - 1);
        }

        // Poursuite de l'indentation : saut de ligne avec la même indentation
        // Vérifier aussi l'indentation de continuation (opérateurs en suspension)
        if (CodeContext.isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + continuationIndent, pos + 1 + continuationIndent.length());
        }

        // Vérifier si dans un commentaire de bloc quand l'utilisateur appuie sur Entrée
        if (CodeContext.isInBlockComment(text, pos, profile)) {
            // Chercher le motif * en début — la continuation du profil
            // (" * " pour les blocs C ; null → newline simple).
            if (cont != null) {
                return new RangeEdit(pos, pos, "\n" + currentIndent + cont,
                        pos + 1 + currentIndent.length() + cont.length() - 1);
            }
            return plainNewline(text, pos, currentIndent);
        }

        // Continuation de puce de liste (- ou * en début de ligne)
        String trimmedLine = EditTextUtils.extractLineText(text, lineStart).trim();
        if (trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ")) {
            String bullet = trimmedLine.substring(0, 2);
            if (trimmedLine.length() > 2) {
                return new RangeEdit(pos, pos, "\n" + currentIndent + bullet, pos + 1 + currentIndent.length() + 2);
            } else {
                // Item de liste vide : le supprimer
                return new RangeEdit(lineStart, pos, "\n", lineStart + 1);
            }
        }

        // Indentation des labels case : case X: ou default:
        if (CodeContext.isCaseLabel(text, pos - 1)) {
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Ligne d'annotation seule : même indentation
        if (CodeContext.isAnnotationOnlyLine(text, lineStart)) {
            return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
        }

        // Défaut : continuer avec la même indentation
        return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
    }

    /**
     * Gestionnaire de saut de ligne Kotlin (pas de découpe de chaîne, pas de labels case, indentation flèche).
     * ADR-002 (C1-é4) : la continuation de bloc lit le profil.
     */
    private static RangeEdit kotlinNewline(CharSequence text, int pos, String language,
            LanguageProfile profile) {
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);
        String indentUnit = IndentDetection.detectIndentUnit(text);

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Expansion de paire vide
        if (BracketPairs.isOpener(charBefore) && BracketPairs.isCloser(charAfter) && BracketPairs.isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + indentUnit;
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Plus profond après les ouvreurs
        if (BracketPairs.isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Indentation flèche (->) dans when/match
        if (CodeContext.endsWithArrow(text, pos - 1)) {
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Continuation d'opérateur en suspension
        if (CodeContext.isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + continuationIndent, pos + 1 + continuationIndent.length());
        }

        // Continuation de commentaire de bloc — profilée (C1-é4).
        if (CodeContext.isInBlockComment(text, pos, profile)) {
            String cont = profile != null ? profile.blockCommentContinuation : " * ";
            if (cont != null) {
                return new RangeEdit(pos, pos, "\n" + currentIndent + cont,
                        pos + 1 + currentIndent.length() + cont.length() - 1);
            }
            return plainNewline(text, pos, currentIndent);
        }

        // Expansion de paire vide avec ouvreurs
        if (BracketPairs.isOpener(charBefore)) {
            char closer = BracketPairs.closerFor(charBefore);
            if (closer != '\0') {
                String deeperIndent = currentIndent + indentUnit;
                String result = "\n" + deeperIndent + "\n" + currentIndent;
                return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
            }
        }

        // Défaut : continuer avec la même indentation
        return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
    }

    /**
     * Saut de ligne simple avec poursuite de l'indentation.
     */
    private static RangeEdit plainNewline(CharSequence text, int pos, String indent) {
        return new RangeEdit(pos, pos, "\n" + indent, pos + 1 + indent.length());
    }

    /** Vrai quand le texte avant {@code pos} se termine par {@code needle} (null → faux). */
    private static boolean endsWithAt(CharSequence text, int pos, String needle) {
        return needle != null && pos >= needle.length()
                && EditTextUtils.startsWith(text, needle, pos - needle.length());
    }

    // ── smartEnter (complétion d'instruction) ──────────────────

    /**
     * Complétion d'instruction : terminer la ligne par ; si besoin, ajouter un bloc { } pour le contrôle de flux.
     */
    static RangeEdit smartEnterComplete(CharSequence text, int pos, String language) {
        if (!LanguageIds.isJavaOrKotlin(language)) {
            return new RangeEdit(pos, pos, "", pos);
        }
        // Lot 1 (audit v3.41.0) : Kotlin n'utilise pas de
        // point-virgule de façon idiomatique. L'ancien code
        // insérait un ; final pour Java ET Kotlin (via le garde
        // isJavaOrKotlin). Désormais l'insertion du ; est réservée
        // à Java ; le bloc { } pour if/else/for/... reste inséré
        // pour les deux langages (utile dans les deux).
        LanguageProfile profile = LanguageRegistry.forName(language);
        boolean isKotlin = profile != null && "kotlin".equals(profile.name);

        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String lineText = EditTextUtils.extractLineText(text, lineStart);
        String trimmed = lineText.trim();
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);

        // En-têtes de contrôle de flux nécessitant un bloc { }
        String[] controlFlowKeywords = {"if", "else", "for", "while", "do", "try", "catch", "finally"};
        for (String kw : controlFlowKeywords) {
            if (trimmed.startsWith(kw) && (trimmed.endsWith("{") || trimmed.endsWith(")"))) {
                // A déjà un bloc ou des parenthèses - ajouter { }
                if (!trimmed.endsWith("{")) {
                    return new RangeEdit(pos, pos, " {\n" + currentIndent + "    \n" + currentIndent + "}",
                        pos + currentIndent.length() + 5);
                }
            }
        }

        // Si la ligne ne finit pas par ; } ou {, ajouter ; — mais
        // seulement pour Java (pas Kotlin).
        if (isKotlin) {
            return new RangeEdit(pos, pos, "", pos);
        }
        char lastChar = trimmed.isEmpty() ? '\0' : trimmed.charAt(trimmed.length() - 1);
        if (lastChar != ';' && lastChar != '{' && lastChar != '}' && lastChar != ',') {
            return new RangeEdit(pos, pos, ";", pos + 1);
        }

        return new RangeEdit(pos, pos, "", pos);
    }
}
