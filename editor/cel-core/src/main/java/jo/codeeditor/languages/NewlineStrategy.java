package jo.codeeditor.languages;

/**
 * Stratégie de saut de ligne intelligent portée par le profil de
 * langage (ADR-002, chantier C1 — étape 3).
 *
 * <p>Avant C1-é3, {@code SmartNewline.smartEnter} dispatchait sur des
 * égalités de chaînes éparses : famille XML non surchargeable, nom de
 * langage {@code "kotlin"} en dur, {@code switch} par nom canonique
 * pour les indent-triggers. Désormais la stratégie est une donnée du
 * {@link LanguageProfile} : le défaut est calculé depuis la famille,
 * et un hôte peut la surcharger ({@code builder.newlineStrategy(...)}).</p>
 */
public enum NewlineStrategy {
    /**
     * Indentation structurelle XML : pile d'éléments ouverts, alignement
     * d'attributs repliés, expansion de paire {@code <Foo>|</Foo>}.
     * Défaut de la famille {@link SyntaxFamily#XML}.
     */
    XML,
    /**
     * Newline Kotlin : indentation flèche ({@code ->} de
     * {@code when}/corps d'expression), pas de label {@code case}, pas
     * de complétion de point-virgule. Déclarée par le profil
     * {@code kotlin} (famille C_LIKE) — pas de défaut par famille.
     */
    KOTLIN,
    /**
     * Newline C-like (défaut général) : expansion de crochets,
     * opérateur en suspension, continuation de commentaire, puces de
     * liste, labels {@code case}, annotations — plus les
     * {@link NewlineTrigger}s déclarés par le profil (Python
     * {@code :} + mot-clé de bloc, YAML {@code :} sans valeur, Shell
     * et Lua {@code then}/{@code do}).
     */
    C_LIKE
}
