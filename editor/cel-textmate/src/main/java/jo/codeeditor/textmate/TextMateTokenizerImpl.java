package jo.codeeditor.textmate;

import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TextMateTokenizer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Implémentation de référence du tokéniseur de grammaires (ADR-002,
 * C3 — module optionnel {@code :cel-textmate}, dépendance
 * {@code :cel-core} seule).
 *
 * <p>Le module honore le crochet documenté par
 * {@link TextMateTokenizer} : l'hôte l'enregistre une fois au
 * démarrage et la coloration « grammaires » prend le pas sur les
 * tokéniseurs intégrés pour les langages couverts :</p>
 *
 * <pre>{@code
 * SyntaxHighlighter.setTextMateTokenizer(TextMateTokenizerImpl.create());
 * }</pre>
 *
 * <h2>Grammaires embarquées</h2>
 *
 * <p>DEUX familles vivent dans {@code textmate/} du classpath :</p>
 * <ul>
 *   <li>{@code textmate/<lang>.tmg.json} — grammaires DU DÉPÔT
 *       (Apache-2.0, écrites pour ce moteur, aucune attribution
 *       tierce) ; {@code csharp} est chargée par {@link #create()}
 *       (langage « grammaire-only » : la voie grammaires est la
 *       SEULE pour ce langage) ;</li>
 *   <li>{@code textmate/<lang>.tmLanguage.json} — grammaires
 *       TIERCES vérifiées (MIT, fichiers originaux de l'écosystème
 *       VS Code/TextMate — attribution par fichier dans NOTICE,
 *       décision D3) : {@code css}, {@code html}, {@code java},
 *       {@code javascript}, {@code lua}, {@code markdown},
 *       {@code python}, {@code xml} ; et
 *       {@code textmate/<lang>.tmLanguage} — grammaire plist XML
 *       (format TextMate historique) : {@code kotlin} (Apache-2.0).
 *       Elles sont INACTIVES au démarrage — un langage qui possède
 *       déjà un tokéniseur intégré ne bascule pas silencieusement
 *       sur la voie grammaires (contrat « opt-in par langage ») :
 *       l'hôte l'active explicitement par
 *       {@link #loadEmbeddedGrammar(String)}.</li>
 * </ul>
 *
 * <p>Un hôte ajoute ou remplace les siennes par
 * {@link #registerGrammar(String, String)} — supporter un nouveau
 * langage reste ajouter un FICHIER, pas du Java.</p>
 *
 * <h2>Repli intégré</h2>
 *
 * <p>Sans le module (ou pour un langage non couvert), le
 * tokéniseur intégré de {@code :cel-core} reste actif — la voie
 * grammaires est strictement additive. La porte des 800 lignes
 * ({@code TEXTMATE_LINE_LIMIT}) est décidée par l'appelant
 * ({@code EditorSession}), pas par ce module.</p>
 */
public final class TextMateTokenizerImpl implements TextMateTokenizer {

    /** Grammaires par clé de langage normalisée. */
    private final Map<String, TextMateGrammar> grammars = new HashMap<>();
    /** Moteurs par grammaire (l'interning des piles est par moteur). */
    private final Map<TextMateGrammar, GrammarTokenizer> engines = new HashMap<>();
    /** Références externes déjà signalées sur stderr (une fois). */
    private final java.util.Set<String> warnedExternals =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Constructeur vide — voir {@link #create()} pour le chargement embarqué. */
    public TextMateTokenizerImpl() {
    }

    /**
     * Fabrique l'implémentation avec les grammaires embarquées
     * ({@code textmate/*.tmg.json} du classpath). Un fichier invalide
     * est signalé sur stderr et ignoré — une grammaire cassée ne doit
     * jamais casser le démarrage de l'hôte.
     */
    public static TextMateTokenizerImpl create() {
        TextMateTokenizerImpl impl = new TextMateTokenizerImpl();
        impl.loadEmbeddedGrammars();
        return impl;
    }

    /**
     * Charge paresseusement toutes les grammaires du dépôt (les
     * {@code .tmg.json} — les grammaires tierces demandent
     * {@link #loadEmbeddedGrammar(String)}).
     */
    private void loadEmbeddedGrammars() {
        // Liste explicite plutôt que scanning de répertoire : le
        // classpath d'un AAR n'est pas listable de façon portable —
        // l'inventaire vit ici (et dans NOTICE).
        String[] embedded = {"csharp"};
        for (String lang : embedded) {
            try {
                String source = readResource("textmate/" + lang + ".tmg.json");
                if (source != null) {
                    registerGrammar(lang, source);
                }
            } catch (TextMateGrammar.GrammarException e) {
                System.err.println("cel-textmate: grammaire embarquée invalide ("
                        + lang + ") : " + e.getMessage());
            }
        }
    }

    /**
     * Active une grammaire TIÈRCE embarquée pour un langage ({@code
     * css}, {@code html}, {@code java}, {@code javascript}, {@code
     * kotlin} (plist XML), {@code lua}, {@code markdown}, {@code
     * python}, {@code xml} — l'inventaire complet et les licences
     * vivent dans NOTICE). Les grammaires tierces ne sont PAS
     * actives après {@link #create()} : un langage pourvu d'un
     * tokéniseur intégré ne bascule sur la voie grammaires que par
     * cet appel explicite (ou {@link #registerGrammar} avec la source
     * de votre choix) — le rendu d'un hôte qui n'appelle rien ne
     * change pas.
     *
     * <p>Idempotent : réactiver une grammaire déjà active la
     * remplace (sémantique de {@link #registerGrammar}). Lève une
     * {@link TextMateGrammar.GrammarException} si aucune grammaire
     * embarquée ne correspond à ce langage.</p>
     *
     * @param language clé de langage ({@code "java"}…)
     * @return cette instance (chaînage)
     */
    public TextMateTokenizerImpl loadEmbeddedGrammar(String language) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language is blank");
        }
        String norm = normalize(language);
        // D'abord une grammaire du dépôt (.tmg.json), puis une
        // tierce JSON (.tmLanguage.json), puis une tierce PLIST XML
        // (.tmLanguage — format TextMate historique, kotlin) : le
        // parseur détecte le format automatiquement (C3-bis).
        String source = readResource("textmate/" + norm + ".tmg.json");
        if (source == null) {
            source = readResource("textmate/" + norm + ".tmLanguage.json");
        }
        if (source == null) {
            source = readResource("textmate/" + norm + ".tmLanguage");
        }
        if (source == null) {
            throw new TextMateGrammar.GrammarException(
                    "aucune grammaire embarquée pour « " + norm
                    + " » (inventaire : csharp, css, html, java, "
                    + "javascript, kotlin, lua, markdown, python, xml)");
        }
        return registerGrammar(norm, source);
    }

    private static String readResource(String path) {
        try (java.io.InputStream in =
                TextMateTokenizerImpl.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            // Lecture manuelle UTF-8 (compatible minSdk 24 — ni
            // readAllBytes ni Scanner(InputStream, Charset), API 33+).
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Enregistre (ou remplace) la grammaire d'une clé de langage.
     * Lève une {@link TextMateGrammar.GrammarException} sur source
     * illisible — au moment de l'enregistrement, jamais en coloration.
     * Le parse est INDULGENT (C3-bis) : une règle au dialecte
     * Oniguruma intraduisible est ignorée avec un avertissement sur
     * stderr (le gras du markdown de sora-editor, qui appelle des
     * sous-expressions) — la grammaire charge et le reste colorea.
     * Les sources plist XML (Kotlin de sora-editor) sont acceptées :
     * la détection de format est automatique.
     *
     * @param language clé de langage ({@code "csharp"}, {@code "mylang"}…)
     * @param grammarSource source JSON/JSONC ou plist XML de la grammaire
     * @return cette instance (chaînage)
     */
    public TextMateTokenizerImpl registerGrammar(String language, String grammarSource) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language is blank");
        }
        TextMateGrammar grammar = TextMateGrammar.parse(grammarSource, true);
        for (String warning : grammar.warnings) {
            System.err.println("cel-textmate: grammaire " + language
                    + " — règle ignorée : " + warning);
        }
        synchronized (grammars) {
            grammars.put(normalize(language), grammar);
        }
        return this;
    }

    /** Retire la grammaire d'une clé de langage (retour au tokéniseur intégré). */
    public void unregisterGrammar(String language) {
        synchronized (grammars) {
            grammars.remove(normalize(language));
        }
    }

    @Override
    public boolean isAvailable(String language) {
        if (language == null) return false;
        synchronized (grammars) {
            return grammars.containsKey(normalize(language));
        }
    }

    @Override
    public StyledLine tokenize(String line, int entryState, String language) {
        TextMateGrammar grammar;
        synchronized (grammars) {
            grammar = grammars.get(normalize(language));
        }
        if (grammar == null) {
            // Contrat : isAvailable a dit vrai → ne devrait pas arriver ;
            // repli défensif : ligne plate, état 0 (stable).
            return new StyledLine(java.util.List.of(
                    new jo.codeeditor.highlight.LineSpan(0,
                            line == null ? 0 : line.length(),
                            jo.codeeditor.highlight.TokenType.PLAIN)),
                    entryState, 0);
        }
        GrammarTokenizer engine;
        synchronized (engines) {
            engine = engines.get(grammar);
            if (engine == null) {
                engine = new GrammarTokenizer();
                engines.put(grammar, engine);
            }
        }
        // Registre des grammaires externes (C3-bis) : les includes
        // source.xxx de la grammaire se résolvent vers les AUTRES
        // grammaires enregistrées — l'hôte qui embarque html ET css
        // obtient l'imbrication ; une référence absente dégrade (règle
        // muette) et est signalée UNE fois sur stderr.
        Map<String, TextMateGrammar> externals = new HashMap<>();
        synchronized (grammars) {
            for (TextMateGrammar g : grammars.values()) {
                externals.put(g.scopeName, g);
            }
        }
        warnUnresolved(grammar, externals);
        return engine.tokenize(grammar, line == null ? "" : line, entryState,
                externals);
    }

    /** Signale une seule fois les références externes non résolues —
     * une LIGNE par grammaire (les grammaires à blocs de code,
     * markdown, référencent des dizaines de langages). */
    private void warnUnresolved(TextMateGrammar grammar,
            Map<String, TextMateGrammar> externals) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String ref : grammar.externalRefs) {
            if (!externals.containsKey(ref) && warnedExternals.add(ref)) {
                missing.add(ref);
            }
        }
        if (missing.isEmpty()) return;
        StringBuilder sb = new StringBuilder("cel-textmate: ");
        sb.append(missing.size()).append(" include(s) externe(s) non résolue(s)");
        if (grammar.name != null) {
            sb.append(" — grammaire ").append(grammar.name);
        }
        sb.append(" : ");
        for (int i = 0; i < missing.size() && i < 5; i++) {
            if (i > 0) sb.append(", ");
            sb.append(missing.get(i));
        }
        if (missing.size() > 5) {
            sb.append(", … (").append(missing.size() - 5).append(" autres)");
        }
        sb.append(" — les règles concernées ne colorea rien");
        System.err.println(sb);
    }

    private static String normalize(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }
}
