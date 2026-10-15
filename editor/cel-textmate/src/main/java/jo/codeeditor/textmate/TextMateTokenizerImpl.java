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
 * <p>Les grammaires du dépôt vivent dans {@code textmate/<lang>.tmg.json}
 * (classpath du module) et sont chargées paresseusement au premier
 * {@link #isAvailable(String)}. Ce sont des fichiers DU DÉPÔT
 * (Apache-2.0, écrits pour ce moteur — aucune attribution tierce,
 * décision D3 : vérification fichier par fichier, attribution dans
 * NOTICE le jour où une grammaire tierce embarquée). Un hôte ajoute
 * ou remplace les siennes par {@link #registerGrammar(String, String)}
 * — supporter un nouveau langage reste ajouter un FICHIER, pas du
 * Java.</p>
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

    /** Charge paresseusement toutes les grammaires du classpath. */
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
     * invalide — au moment de l'enregistrement, jamais en coloration.
     *
     * @param language clé de langage ({@code "csharp"}, {@code "mylang"}…)
     * @param grammarSource source JSON/JSONC de la grammaire
     * @return cette instance (chaînage)
     */
    public TextMateTokenizerImpl registerGrammar(String language, String grammarSource) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language is blank");
        }
        TextMateGrammar grammar = TextMateGrammar.parse(grammarSource);
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
        return engine.tokenize(grammar, line == null ? "" : line, entryState);
    }

    private static String normalize(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }
}
