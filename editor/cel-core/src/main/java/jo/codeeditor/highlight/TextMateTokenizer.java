package jo.codeeditor.highlight;

/**
 * Interface optionnelle de tokénisation basée sur TextMate.
 *
 * <p>Isolation modulaire : l'implémentation de référence vit dans le
 * module optionnel {@code :cel-textmate} du DÉPÔT (ADR-002, C3 —
 * {@code jo.codeeditor.textmate.TextMateTokenizerImpl}), qui ne
 * dépend que de ce module. Les consommateurs de code-editor
 * peuvent choisir :
 * <ul>
 *   <li><b>Avec la voie grammaires</b> — dépendre de
 *       {@code :cel-textmate} et crocher une fois au démarrage :
 *       {@code SyntaxHighlighter.setTextMateTokenizer(}
 *       {@code TextMateTokenizerImpl.create())}. Les grammaires
 *       embarquées du dépôt se chargent paresseusement depuis le
 *       classpath ({@code textmate/<lang>.tmg.json} — p. ex. C#) ;
 *       l'hôte enregistre les siennes par
 *       {@code registerGrammar(lang, sourceJson)} — supporter un
 *       nouveau langage reste ajouter un FICHIER, pas du Java.</li>
 *   <li><b>Sans la voie grammaires</b> — omettre entièrement
 *       {@code :cel-textmate}. Le tokéniseur intégré de
 *       {@link SyntaxHighlighter} ({@code :cel-core}) gère tous les
 *       langages courants via le registre par famille. La qualité
 *       est légèrement inférieure à la voie grammaires (pas
 *       d'héritage de portées, pas d'injections) mais reste
 *       suffisante pour l'édition de code — c'est le repli
 *       hors-ligne documenté par l'ADR-002.</li>
 * </ul>
 *
 * <p>Si aucune implémentation n'est enregistrée (ex. tests JVM purs, ou avant
 * l'initialisation de l'application), {@link SyntaxHighlighter#styleLine}
 * bascule sur son tokéniseur intégré par langage (switch-case) — le recours
 * à TextMate est strictement opt-in par clé de langage.
 *
 * <h2>Modèle d'état</h2>
 * <ul>
 *   <li>{@code entryState == 0} → début de fichier (état vierge, pas de ligne précédente)</li>
 *   <li>{@code entryState > 0} → index opaque précédemment renvoyé comme
 *       {@code exitState} par le même tokéniseur. L'implémentation maintient
 *       une {@code Map<Integer, IStateStack>} interne par langage pour
 *       retrouver l'état opaque textmate correspondant.</li>
 *   <li>Le {@code exitState} renvoyé doit être <em>stable</em> : re-tokéniser
 *       la même ligne avec le même {@code entryState} doit produire le même
 *       {@code exitState}, afin que l'éditeur cesse de re-tokéniser l'aval
 *       une fois l'état stabilisé.</li>
 * </ul>
 */
public interface TextMateTokenizer {

    /**
     * Renvoie {@code true} si une grammaire TextMate est enregistrée pour la
     * clé de langage donnée (ex. {@code "java"}, {@code "kotlin"}).
     *
     * <p>Si {@code true}, {@link SyntaxHighlighter#styleLine} délègue à
     * {@link #tokenize(String, int, String)} pour ce langage. Si
     * {@code false}, le tokéniseur intégré (switch-case) est utilisé à la place.
     */
    boolean isAvailable(String language);

    /**
     * Tokénise une ligne unique.
     *
     * <p><b>Contrat de garde des lignes monstres :</b> la délégation de
     * {@link SyntaxHighlighter#styleLine} ne lui transmet JAMAIS une ligne
     * plus longue que {@link SyntaxHighlighter#TEXTMATE_CHAR_LIMIT} — le
     * coût par caractère du moteur de grammaires rend une ligne minifiée
     * (dizaines de milliers de caractères) prohibitif. Les lignes au-delà
     * retombent d'office sur le tokéniseur intégré, machine à états au
     * coût linéaire.</p>
     *
     * @param line       le texte de la ligne (sans le saut de ligne final)
     * @param entryState état opaque du lexer en entrée de ligne (0 = vierge)
     * @param language   clé de langage (ex. {@code "java"}, {@code "kotlin"})
     * @return un {@link StyledLine} avec les portions et un état de sortie stable
     */
    StyledLine tokenize(String line, int entryState, String language);
}
