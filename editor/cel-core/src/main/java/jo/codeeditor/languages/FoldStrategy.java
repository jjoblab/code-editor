package jo.codeeditor.languages;

/**
 * Stratégie de pliage par langage (Lot 2 — audit v3.41.0).
 *
 * <p>Indique au {@link jo.codeeditor.highlight.FoldDetector détecteur de
 * plis} quelle heuristique appliquer pour un langage donné. La
 * stratégie est portée par le {@link LanguageProfile profil de
 * langage} (champ {@code foldStrategy}) et route le détecteur en
 * un seul switch d'enum au lieu d'une chaîne de comparaisons de
 * chaînes éparses.</p>
 *
 * <p><b>Valeurs par défaut par famille</b> (cf. {@code BuiltinLanguages}) :</p>
 * <ul>
 *   <li>{@link #BRACE} — Java, Kotlin, C/C++, Go, Rust, PHP, Swift,
 *       Dart, Groovy, JS/TS, Scala, SQL, Smali, Smali, JSON (tableaux
 *       et objets).</li>
 *   <li>{@link #TAG} — XML, HTML, HTM, SVG.</li>
 *   <li>{@link #INDENT} — Python, YAML.</li>
 *   <li>{@link #KEYWORD} — Lua (function/do/for/while…end), Shell
 *       (if/for/while/case…fi/done), Ruby (def/do/while…end).</li>
 *   <li>{@link #HEADING} — Markdown.</li>
 *   <li>{@link #OFF} — log, text, plaintext, properties, toml (pas
 *       de pliage structurel).</li>
 * </ul>
 *
 * <p>Le détecteur de blocs ({@link #BRACE}) détecte aussi les
 * commentaires de bloc et les javadoc (cf.
 * {@link jo.codeeditor.highlight.FoldDetector#detectBraceFolds}).</p>
 *
 * <p>Les langages contribués par l'hôte peuvent choisir n'importe
 * quelle stratégie via {@link LanguageProfile.Builder#foldStrategy}.</p>
 */
public enum FoldStrategy {
    /** Pliage par accolades {@code { … }} (Java, Kotlin, C, etc.). */
    BRACE,
    /** Pliage par balises {@code <tag> … </tag>} (XML, HTML, SVG). */
    TAG,
    /** Pliage par indentation (Python, YAML). */
    INDENT,
    /** Pliage par paires de mots-clés ouvrant/fermant (Lua, Shell, Ruby). */
    KEYWORD,
    /** Pliage par titres Markdown. */
    HEADING,
    /** Pas de pliage (texte brut, log, properties, toml). */
    OFF;
}
