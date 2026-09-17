package jo.codeeditor.highlight;

/**
 * Interface optionnelle de tokénisation basée sur TextMate.
 *
 * <p>Isolation modulaire : l'implémentation de référence se trouve dans le
 * module {@code :tm4e} ({@code jo.codeeditor.tm4e.TextMateTokenizerImpl}).
 * Le module {@code :tm4e} est un module de bibliothèque <em>optionnel</em>
 * qui ne dépend que de {@code :core}. Les consommateurs de code-editor
 * peuvent choisir :
 * <ul>
 *   <li><b>Avec TextMate</b> — dépendre de {@code :tm4e} (récupère par
 *       transition les sources tm4e embarquées + les grammaires/thèmes).
 *       Les 17 fichiers de grammaire (Java, Kotlin, XML, HTML, CSS, JS, TS,
 *       Python, etc.) sont chargés paresseusement depuis
 *       {@code assets/textmate/} au premier appel de
 *       {@link #isAvailable(String)}.</li>
 *   <li><b>Sans TextMate</b> — omettre entièrement {@code :tm4e}. Le
 *       tokéniseur intégré de {@link SyntaxHighlighter} ({@code :core})
 *       gère tous les langages courants (Java, Kotlin, XML, HTML, CSS, JS,
 *       TS, Python, Go, Rust, C, C++, Shell, YAML, etc.) via une machine à
 *       états par langage (switch-case). La qualité est légèrement inférieure
 *       à TextMate (pas d'héritage de portées, pas d'injections) mais reste
 *       suffisante pour l'édition de code.</li>
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
 *       retrouver l'état opaque tm4e correspondant.</li>
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
     * @param line       le texte de la ligne (sans le saut de ligne final)
     * @param entryState état opaque du lexer en entrée de ligne (0 = vierge)
     * @param language   clé de langage (ex. {@code "java"}, {@code "kotlin"})
     * @return un {@link StyledLine} avec les portions et un état de sortie stable
     */
    StyledLine tokenize(String line, int entryState, String language);
}
