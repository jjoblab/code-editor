package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Grammaire déclarative d'un tokéniseur (ADR-002, chantier C2) — la
 * table de règles consommée par {@link GenericTokenizer}.
 *
 * <p>La famille lexicale ({@code SyntaxFamily}) devient le
 * <b>sélecteur de template</b>, plus le lieu du code : chaque
 * tokéniseur intégré écrit à la main migre sur ce moteur au rythme
 * du cliquet de démantèlement (migration tokéniseur par tokéniseur,
 * la suite existante gardant ses assertions). Migrations livrées :
 * Properties ({@link #PROPERTIES}), TOML ({@link #TOML}), Shell
 * ({@link #SHELL}), YAML ({@link #YAML}), JSON ({@link #JSON}). Les
 * suivantes étendront ce modèle au fur et à mesure — chaque champ
 * documente ce que le moteur sait exploiter aujourd'hui.</p>
 *
 * <p>Champs de l'étape C2-é1 (grammaires clé=valeur — properties,
 * ini, formats de configuration) :</p>
 * <ul>
 *   <li>{@link #lineComments} — préfixes de commentaire de ligne
 *       (uniquement en tête de ligne, AVANT le séparateur) ;</li>
 *   <li>{@link #keyValueSeparators} — caractères séparateurs
 *       clé/valeur ({@code =}, {@code :}) ;</li>
 *   <li>{@link #keyType}/{@link #separatorType}/{@link #valueType}
 *       — les types de jetons des trois zones ;</li>
 *   <li>{@link #escapesInValue} — échappements backslash en valeur
 *       ({@code \\n}, {@code \\t}, {@code \\uXXXX} — 6 caractères).</li>
 * </ul>
 *
 * <p>Champs de l'étape C2-é2 (grammaires à sections et valeurs à
 * jetons — toml, formats de configuration riches) :</p>
 * <ul>
 *   <li>{@link #commentsAnywhere} — vrai : le préfixe de commentaire
 *       s'applique aussi APRÈS le séparateur (un {@code #} en fin de
 *       ligne TOML commente) ;</li>
 *   <li>{@link #sections} — en-têtes {@code [nom]} / {@code [[nom]]}
 *       avant le séparateur, avec leurs deux types de jetons ;</li>
 *   <li>{@link #valueQuotes} — chaînes quotées en zone valeur
 *       (échappements internes en ESCAPE, guillemet orphelin en
 *       ponctuation de repli) ;</li>
 *   <li>{@link #valueNumberExtras} — jeu de caractères étendant les
 *       nombres en valeur (TOML : {@code . - : T Z} pour les dates) ;</li>
 *   <li>{@link #valueKeywords}/{@link #valueKeywordType} — mots-clés
 *       de valeur (booléens TOML) et leur type.</li>
 * </ul>
 *
 * <p>Champs de l'étape C2-é3 (flux libre — shell, scripts, ligne
 * de commande : pas de zones clé=valeur, des jetons sur toute la
 * ligne) :</p>
 * <ul>
 *   <li>{@link #freeForm} — vrai : le moteur tokénise la ligne en
 *       flux de jetons (les règles de zone clé/valeur, sections
 *       comprises, sont ignorées) ;</li>
 *   <li>{@link #commentRequiresBoundary}/{@link #commentAnchors} —
 *       le préfixe de commentaire ne démarre qu'en début de ligne,
 *       après un blanc ou après un caractère qui l'ancre ({@code ;}) ;</li>
 *   <li>{@link #variablePrefixes}/{@link #variableType} — formes
 *       {@code $var}, {@code ${var}}, {@code $?}, {@code $1} ;</li>
 *   <li>{@link #plainStringQuotes} — chaîne à guillemets doubles :
 *       échappements sautés, un seul jeton, non fermée → fin de
 *       ligne ;</li>
 *   <li>{@link #interLineQuote}/{@link #interLineState} — littéral
 *       à état inter-lignes (l'apostrophe non fermée d'un script
 *       shell se propage à la ligne suivante) ;</li>
 *   <li>{@link #commandQuotes}/{@link #commandQuoteType} —
 *       substitution par paires de backticks ;</li>
 *   <li>{@link #wordExtraChars}, {@link #valueKeywords} (table des
 *       mots-clés du flux), {@link #wordFuncFollowChars},
 *       {@link #wordUpperCaseType}, {@link #wordFallbackType} — la
 *       règle de mot : mot-clé, appel de fonction ({@code foo(}),
 *       initiale majuscule, repli ;</li>
 *   <li>{@link #operatorChars} — courses d'opérateurs
 *       ({@code |&}, {@code ;;}).</li>
 * </ul>
 *
 * <p>Champs de l'étape C2-é4 (grammaires à clés par lookahead —
 * yaml, formats d'inventaire) :</p>
 * <ul>
 *   <li>{@link #documentMarkers}/{@link #documentMarkerType} —
 *       séparateurs de document ({@code ---}) reconnus n'importe où ;</li>
 *   <li>{@link #annotationPrefixes}/{@link #annotationType} — ancres,
 *       alias et tags ({@code &a}, {@code *a}, {@code !a}) ;</li>
 *   <li>{@link #keyStartExtras}/{@link #keyStopChars}/
 *       {@link #keyLookahead} — la règle de clé : caractères de
 *       départ au-delà des lettres, fin étendue (un {@code #} coupe),
 *       et clé confirmée par un séparateur en lookahead (sans quoi le
 *       candidat retombe en scalaire et la suite devient zone
 *       valeur) ;</li>
 *   <li>{@link #stringsInKeyZone}/{@link #stringReopensKeyZone} —
 *       chaînes quotées reconnues avant le séparateur, une chaîne
 *       fermée réouvrant la zone clé (comportement YAML historique) ;</li>
 *   <li>{@link #blockIndicators}/{@link #blockIndicatorType} —
 *       indicateurs de scalaire de bloc ({@code |}, {@code >}) en
 *       zone clé ;</li>
 *   <li>{@link #numbersInValue}, {@link #valueWordStartExtras},
 *       {@link #valueWordExtras}, {@link #valueWordConsumesDigits},
 *       {@link #valueWordNumbers} — la règle de mot de valeur : jeu
 *       de départ, jeu de suite, classification numérique des mots
 *       (YAML) ou règle numérique autonome (TOML) ;</li>
 *   <li>{@link #flowPunct} — ponctuation de flux
 *       ({@code []}{@code {}}), les deux zones ;</li>
 *   <li>{@link #scalarFallback}/{@link #scalarStopChars} — le repli
 *       devient un scalaire brut (jusqu'à blanc/séparateur/stop) au
 *       lieu d'une ponctuation mono-caractère.</li>
 * </ul>
 *
 * <p>Champs de l'étape C2-é5 (grammaires balisées en flux libre
 * — json, formats de données structurées) :</p>
 * <ul>
 *   <li>{@link #valueQuotes} — chaînes quotées EN FLUX LIBRE :
 *       échappements apparents (ESCAPE), fermante trouvée en sautant
 *       les échappements, orpheline → fin de ligne et état
 *       inter-lignes propagé ;</li>
 *   <li>{@link #stringKeyLookahead} — une chaîne fermée suivie
 *       (après blancs) d'un séparateur est requalifiée en clé
 *       ({@link #keyType}) : la clé JSON ;</li>
 *   <li>{@link #wordConsumesDigits} — faux : mots de lettres pures
 *       (JSON) ; un chiffre collé à un mot ouvre alors un nombre ;</li>
 *   <li>{@link #numberRequiresBoundary}/{@link #numberLeadingMinus}
 *       — la frontière de mot des nombres est déclarée, et le signe
 *       moins ancré à un chiffre peut ouvrir un nombre (JSON) ;</li>
 *   <li>{@link #operatorSingleChars} — opérateurs mono-caractère,
 *       jamais fusionnés en course ({@code :}, {@code ,}).</li>
 * </ul>
 *
 * <p>Champs de l'étape C2-é6 (famille C — java, kotlin,
 * javascript…) :</p>
 * <ul>
 *   <li>{@link #blockLiterals} — littéraux à délimiteurs :
 *       {@code /*}…{@code *\/} (COMMENT), {@code /**}…{@code *\/}
 *       (DOC_COMMENT), {@code """}…{@code """} kotlin (RAW_STRING),
 *       avec état inter-lignes et portes par langage ;</li>
 *   <li>{@link #charQuotes}/{@link #charType} — littéral de
 *       caractère orphelin sans état ;</li>
 *   <li>{@link #templateLiteralLanguages} — backtick JS/TS avec
 *       interpolations {@code ${…}} en VARIABLE ;</li>
 *   <li>{@link #annotationPrefixes} — annotés aussi en flux libre
 *       ({@code @Override}) ;</li>
 *   <li>{@link #colorPrefix}/{@link #colorType} — littéral de
 *       couleur hexadécimale (3/4/6/8 chiffres) ;</li>
 *   <li>{@link #registryKeywords} — mots-clés du REGISTRE par
 *       langage (repli Java) ;</li>
 *   <li>{@link #wordControlKeywords}/{@link #wordModifierKeywords}/
 *       {@link #wordTypeKeywords}/{@link #wordAllCapsType}/
 *       {@link #wordAfterDotType} — raffinement des mots :
 *       contrôle, modificateur, type intégré, tout-en-majuscules en
 *       CONSTANT, identifiant après point en PROPERTY.</li>
 * </ul>
 *
 * <p>Dès qu'une règle de jeton de valeur est déclarée (chaîne,
 * nombre ou mot-clé), la zone valeur est tokénisée <b>jeton par
 * jeton</b> ({@link #tokenizesValue()}) et {@link #escapesInValue}
 * est ignoré — les échappements ne vivent plus que dans les chaînes
 * quotées.</p>
 *
 * <p>Immuable et thread-safe.</p>
 */
public final class TokenizerTemplate {

    /** Préfixes de commentaire de ligne (jamais vides dans un template actif). */
    public final List<String> lineComments;
    /** Caractères séparateurs clé/valeur (vide : pas de mode clé=valeur). */
    public final Set<Character> keyValueSeparators;
    /** Type de la zone clé (avant le séparateur). */
    public final TokenType keyType;
    /** Type du séparateur lui-même. */
    public final TokenType separatorType;
    /** Type de la zone valeur (après le séparateur). */
    public final TokenType valueType;
    /** Vrai : les séquences d'échappement backslash en valeur reçoivent ESCAPE. */
    public final boolean escapesInValue;

    /** Vrai : le préfixe de commentaire s'applique aussi après le
     * séparateur (TOML) — faux : uniquement en zone clé (Properties). */
    public final boolean commentsAnywhere;
    /** Caractère ouvrant une en-tête de section ({@code [}) — 0 : désactivé. */
    public final char sectionOpen;
    /** Caractère fermant une en-tête de section ({@code ]}). */
    public final char sectionClose;
    /** Type du nom de section (entre crochets). */
    public final TokenType sectionNameType;
    /** Type des crochets fermants (un seul jeton, largeur = nombre d'ouvrants). */
    public final TokenType sectionBracketType;
    /** Guillemets ouvrant une chaîne en zone valeur. */
    public final Set<Character> valueQuotes;
    /** Caractères acceptés DANS un nombre en valeur, au-delà des chiffres
     * (TOML : {@code . - : T Z} pour dates et heures). */
    public final Set<Character> valueNumberExtras;
    /** Mots-clés reconnus en zone valeur (ex. booléens TOML). */
    public final Set<String> valueKeywords;
    /** Type d'un mot-clé de valeur reconnu. */
    public final TokenType valueKeywordType;
    /** Type d'un nombre en valeur. */
    public final TokenType valueNumberType;

    /** Vrai : tokénisation en flux libre (pas de zones clé/valeur). */
    public final boolean freeForm;
    /** Vrai (flux libre) : le commentaire doit être ancré (début de
     * ligne, après un blanc ou après un {@link #commentAnchors}). */
    public final boolean commentRequiresBoundary;
    /** Caractères qui ancrent aussi un commentaire en flux libre
     * (shell : {@code ;}). */
    public final Set<Character> commentAnchors;
    /** Préfixes de variable en flux libre ({@code $}) et leurs formes
     * {@code $var}/{@code ${var}}/{@code $?}/{@code $1}. */
    public final Set<Character> variablePrefixes;
    /** Type d'une variable reconnue. */
    public final TokenType variableType;
    /** Caractères supplémentaires du mot de variable (C2-é11, CSS :
     * {@code -} pour {@code $primary-color}). */
    public final Set<Character> variableWordChars;
    /** Guillemets ouvrant une chaîne à échappements sautés (un seul
     * jeton, non fermée → fin de ligne). */
    public final Set<Character> plainStringQuotes;
    /** Type d'une chaîne simple (C2-é8, SQL) — null : le type de
     * valeur (comportement Shell historique). */
    public final TokenType plainStringType;
    /** Lettres de préfixe de chaîne (C2-é9, Python) : f"…", r'…',
     * b"…" — le préfixe fait partie du jeton. */
    public final Set<Character> stringPrefixes;
    /** Guillemet d'un littéral à état inter-lignes (0 : aucun). */
    public final char interLineQuote;
    /** État de sortie d'un littéral inter-lignes non fermé. */
    public final int interLineState;
    /** Guillemets fermant le littéral inter-lignes, en ordre DÉCLARÉ
     * (C2-é11, CSS : « " » puis « ' » — le premier existant ferme,
     * même si l'autre apparaît plus tôt). Prioritaire sur
     * {@link #interLineQuote} si non vide. */
    public final List<Character> interLineQuotes;
    /** Guillemets ouvrant une substitution par paires (backticks). */
    public final Set<Character> commandQuotes;
    /** Type d'une substitution par paires. */
    public final TokenType commandQuoteType;
    /** Caractères supplémentaires d'un mot, au-delà des lettres
     * (départ) et des alphanumériques (suite) — shell : {@code _ -}. */
    public final Set<Character> wordExtraChars;
    /** Caractères ouvrant un mot SANS le continuer (C2-é11, CSS :
     * {@code &} — a&b est deux mots). */
    public final Set<Character> wordStartExtras;
    /** Caractères qui, suivant un mot, en font un appel de fonction
     * (shell : {@code ( [}). */
    public final Set<Character> wordFuncFollowChars;
    /** Type d'un mot à initiale majuscule (null : désactivé). */
    public final TokenType wordUpperCaseType;
    /** Type de repli d'un mot non reconnu (flux libre). */
    public final TokenType wordFallbackType;
    /** Caractères d'opérateur consommés en course (shell :
     * {@code | & ; < > ( )}). */
    public final Set<Character> operatorChars;

    /** Marqueurs de document ({@code ---}), reconnus n'importe où. */
    public final List<String> documentMarkers;
    /** Type d'un marqueur de document. */
    public final TokenType documentMarkerType;
    /** Préfixes d'ancre/alias/tag ({@code & * !}). */
    public final Set<Character> annotationPrefixes;
    /** Type d'une ancre/alias/tag reconnu. */
    public final TokenType annotationType;
    /** Caractères supplémentaires du mot d'annotation (C2-é9, Python :
     * {@code .} pour {@code @app.route}). */
    public final Set<Character> annotationWordChars;
    /** Vrai (C2-é9, Python) : l'annotation n'est reconnue qu'en début
     * de ligne ou après un blanc (a@b n'est pas un décorateur). */
    public final boolean annotationRequiresBoundary;
    /** Mots-clés d'annotation (C2-é10, Smali/CSS) : le JETON entier
     * (préfixe compris — {@code .class}, {@code @media}) y est
     * raffiné, sinon le type de repli s'applique. */
    public final Set<String> annotationKeywords;
    /** Type d'une annotation raffinée par mot-clé. */
    public final TokenType annotationKeywordType;
    /** Caractères de départ d'une clé au-delà des lettres (YAML :
     * {@code _ " ' - . /}) — vide : clé non filtrée (historique). */
    public final Set<Character> keyStartExtras;
    /** Caractères qui terminent une clé en plus des séparateurs et
     * des blancs (YAML : {@code #}). */
    public final Set<Character> keyStopChars;
    /** Vrai : la clé n'est émise comme telle que si un séparateur la
     * suit (lookahead) — sinon scalaire brut et bascule en zone
     * valeur (YAML). */
    public final boolean keyLookahead;
    /** Vrai : les chaînes quotées sont reconnues aussi AVANT le
     * séparateur (YAML) — faux : uniquement en zone valeur (TOML). */
    public final boolean stringsInKeyZone;
    /** Vrai : une chaîne quotée fermée réouvre la zone clé
     * (réinitialisation afterColon du YAML historique). */
    public final boolean stringReopensKeyZone;
    /** Indicateurs de scalaire de bloc en zone clé ({@code | >}). */
    public final Set<Character> blockIndicators;
    /** Type d'un indicateur de bloc. */
    public final TokenType blockIndicatorType;
    /** Vrai : règle numérique autonome en zone valeur (TOML) — la
     * classification numérique des mots ({@link #valueWordNumbers})
     * la remplace en YAML. */
    public final boolean numbersInValue;
    /** Caractères de départ d'un mot de valeur au-delà des lettres
     * (et des chiffres si {@link #valueWordConsumesDigits}). */
    public final Set<Character> valueWordStartExtras;
    /** Caractères de suite d'un mot de valeur au-delà des lettres
     * (et des chiffres si {@link #valueWordConsumesDigits}). */
    public final Set<Character> valueWordExtras;
    /** Vrai : les chiffres participent au mot de valeur (YAML) —
     * faux : les mots sont lettres pures (TOML). */
    public final boolean valueWordConsumesDigits;
    /** Vrai : un mot de valeur à tête numérique (chiffre ou ±chiffre)
     * est classé nombre (YAML). */
    public final boolean valueWordNumbers;
    /** Ponctuation de flux, les deux zones ({@code [ ] { }}). */
    public final Set<Character> flowPunct;
    /** Vrai : le repli est un scalaire brut (jusqu'à blanc /
     * séparateur / stop) au lieu d'une ponctuation mono-caractère. */
    public final boolean scalarFallback;
    /** Caractères qui terminent un scalaire brut en plus des
     * séparateurs et des blancs (YAML : {@code #}). */
    public final Set<Character> scalarStopChars;

    /** Vrai : une chaîne quotée fermée suivie (après blancs) d'un
     * séparateur est requalifiée en clé (JSON). */
    public final boolean stringKeyLookahead;
    /** Vrai (flux libre) : les chiffres participent au mot — faux :
     * mots de lettres pures (JSON), un chiffre collé ouvre un nombre. */
    public final boolean wordConsumesDigits;
    /** Vrai : un chiffre n'ouvre un nombre qu'en frontière de mot
     * (pas collé à un mot) — faux : partout (JSON historique). */
    public final boolean numberRequiresBoundary;
    /** Vrai : un signe moins ancré à un chiffre ouvre un nombre
     * (JSON : {@code -4.2}). */
    public final boolean numberLeadingMinus;
    /** Vrai : un POINT ancré à un chiffre ouvre un nombre (SQL :
     * {@code .5}) — sans condition de frontière. */
    public final boolean numberLeadingDot;
    /** Vrai : l'unité (lettres, %) est absorbée dans le nombre
     * (C2-é11, CSS : {@code 12px}, {@code 100%}). */
    public final boolean numberUnitSuffix;
    /** Caractères émis en opérateurs mono-caractère, jamais fusionnés
     * en course (JSON : {@code :}, {@code ,}). */
    public final Set<Character> operatorSingleChars;

    /** Littéraux à délimiteurs (famille C) : commentaires de
     * bloc/documentation, chaîne brute Kotlin. */
    public final List<BlockLiteral> blockLiterals;
    /** Règles de classification de ligne (C2-é12, log) — non vide :
     * la ligne entière reçoit le type de la première règle qui
     * matche (troisième saveur du moteur). */
    public final List<LineRule> lineRules;
    /** Guillemets ouvrant un littéral de caractère ({@code 'a'}). */
    public final Set<Character> charQuotes;
    /** Type d'un littéral de caractère. */
    public final TokenType charType;
    /** Langages où le backtick ouvre un template literal JS/TS. */
    public final Set<String> templateLiteralLanguages;
    /** Préfixe d'un littéral de couleur (0 : désactivé). */
    public final char colorPrefix;
    /** Type d'un littéral de couleur. */
    public final TokenType colorType;
    /** Vrai : les mots-clés viennent du registre des langages
     * (famille C, repli mots-clés Java). */
    public final boolean registryKeywords;
    /** Mots-clés de contrôle raffinés (if, return…). */
    public final Set<String> wordControlKeywords;
    /** Type d'un mot-clé de contrôle. */
    public final TokenType wordControlType;
    /** Mots-clés modificateurs raffinés (public, static…). */
    public final Set<String> wordModifierKeywords;
    /** Type d'un mot-clé modificateur. */
    public final TokenType wordModifierType;
    /** Types intégrés reconnus hors mots-clés (String, List…). */
    public final Set<String> wordTypeKeywords;
    /** Type d'un mot du jeu de types. */
    public final TokenType wordTypeKeywordType;
    /** Type d'un identifiant tout-en-majuscules (null : désactivé). */
    public final TokenType wordAllCapsType;
    /** Type d'un identifiant précédé d'un point (null : désactivé). */
    public final TokenType wordAfterDotType;
    /** Vrai (C2-é9, Python) : la casse (ALL_CAPS, majuscule initiale)
     * est évaluée AVANT la parenthèse d'appel — Foo( est TYPE. */
    public final boolean wordFuncAfterCase;
    /** Caractères qui, CONTENUS dans un mot, en font un appel/opcode
     * (C2-é10, Smali : {@code -} — invoke-direct → FUNC). */
    public final Set<Character> wordFuncContainsChars;
    /** Caractères qui, TERMINANT un mot, en font un appel/chemin
     * (C2-é10, Smali : {@code /} — array/ → FUNC). */
    public final Set<Character> wordFuncSuffixChars;
    /** Préfixe d'un descripteur de type (C2-é10, Smali : {@code L}
     * — 0 : désactivé). */
    public final char typeDescriptorPrefix;
    /** Crochet préfixant aussi le descripteur (Smali : {@code [L…;}). */
    public final char typeDescriptorBracket;
    /** Terminateur du descripteur (Smali : {@code ;}). */
    public final char typeDescriptorTerminator;
    /** Longueur maximale du descripteur (Smali : 200). */
    public final int typeDescriptorMaxSpan;
    /** Type d'un descripteur reconnu. */
    public final TokenType typeDescriptorType;
    /** Caractères ouvrant TOUJOURS un nombre, même seuls (C2-é10,
     * Smali : {@code -} et {@code +}). */
    public final Set<Character> numberSignChars;
    /** Vrai (C2-é10, Smali) : une chaîne orpheline n'est PAS consommée
     * jusqu'en fin de ligne — les règles suivantes reprennent à pos. */
    public final boolean stringOrphanFallsThrough;

    private TokenizerTemplate(Builder b) {
        this.lineComments = List.copyOf(b.lineComments);
        this.keyValueSeparators = Collections.unmodifiableSet(
                new HashSet<>(b.keyValueSeparators));
        this.keyType = b.keyType;
        this.separatorType = b.separatorType;
        this.valueType = b.valueType;
        this.escapesInValue = b.escapesInValue;
        this.commentsAnywhere = b.commentsAnywhere;
        this.sectionOpen = b.sectionOpen;
        this.sectionClose = b.sectionClose;
        this.sectionNameType = b.sectionNameType;
        this.sectionBracketType = b.sectionBracketType;
        this.valueQuotes = Collections.unmodifiableSet(new HashSet<>(b.valueQuotes));
        this.valueNumberExtras = Collections.unmodifiableSet(
                new HashSet<>(b.valueNumberExtras));
        this.valueKeywords = Collections.unmodifiableSet(new HashSet<>(b.valueKeywords));
        this.valueKeywordType = b.valueKeywordType;
        this.valueNumberType = b.valueNumberType;
        this.freeForm = b.freeForm;
        this.commentRequiresBoundary = b.commentRequiresBoundary;
        this.commentAnchors = Collections.unmodifiableSet(new HashSet<>(b.commentAnchors));
        this.variablePrefixes = Collections.unmodifiableSet(
                new HashSet<>(b.variablePrefixes));
        this.variableType = b.variableType;
        this.variableWordChars = Collections.unmodifiableSet(
                new HashSet<>(b.variableWordChars));
        this.plainStringQuotes = Collections.unmodifiableSet(
                new HashSet<>(b.plainStringQuotes));
        this.plainStringType = b.plainStringType;
        this.stringPrefixes = Collections.unmodifiableSet(
                new HashSet<>(b.stringPrefixes));
        this.interLineQuote = b.interLineQuote;
        this.interLineState = b.interLineState;
        this.interLineQuotes = List.copyOf(b.interLineQuotes);
        this.commandQuotes = Collections.unmodifiableSet(new HashSet<>(b.commandQuotes));
        this.commandQuoteType = b.commandQuoteType;
        this.wordExtraChars = Collections.unmodifiableSet(new HashSet<>(b.wordExtraChars));
        this.wordStartExtras = Collections.unmodifiableSet(
                new HashSet<>(b.wordStartExtras));
        this.wordFuncFollowChars = Collections.unmodifiableSet(
                new HashSet<>(b.wordFuncFollowChars));
        this.wordUpperCaseType = b.wordUpperCaseType;
        this.wordFallbackType = b.wordFallbackType;
        this.operatorChars = Collections.unmodifiableSet(new HashSet<>(b.operatorChars));
        this.documentMarkers = List.copyOf(b.documentMarkers);
        this.documentMarkerType = b.documentMarkerType;
        this.annotationPrefixes = Collections.unmodifiableSet(
                new HashSet<>(b.annotationPrefixes));
        this.annotationType = b.annotationType;
        this.annotationWordChars = Collections.unmodifiableSet(
                new HashSet<>(b.annotationWordChars));
        this.annotationRequiresBoundary = b.annotationRequiresBoundary;
        this.annotationKeywords = Collections.unmodifiableSet(
                new HashSet<>(b.annotationKeywords));
        this.annotationKeywordType = b.annotationKeywordType;
        this.keyStartExtras = Collections.unmodifiableSet(new HashSet<>(b.keyStartExtras));
        this.keyStopChars = Collections.unmodifiableSet(new HashSet<>(b.keyStopChars));
        this.keyLookahead = b.keyLookahead;
        this.stringsInKeyZone = b.stringsInKeyZone;
        this.stringReopensKeyZone = b.stringReopensKeyZone;
        this.blockIndicators = Collections.unmodifiableSet(new HashSet<>(b.blockIndicators));
        this.blockIndicatorType = b.blockIndicatorType;
        this.numbersInValue = b.numbersInValue;
        this.valueWordStartExtras = Collections.unmodifiableSet(
                new HashSet<>(b.valueWordStartExtras));
        this.valueWordExtras = Collections.unmodifiableSet(
                new HashSet<>(b.valueWordExtras));
        this.valueWordConsumesDigits = b.valueWordConsumesDigits;
        this.valueWordNumbers = b.valueWordNumbers;
        this.flowPunct = Collections.unmodifiableSet(new HashSet<>(b.flowPunct));
        this.scalarFallback = b.scalarFallback;
        this.scalarStopChars = Collections.unmodifiableSet(
                new HashSet<>(b.scalarStopChars));
        this.stringKeyLookahead = b.stringKeyLookahead;
        this.wordConsumesDigits = b.wordConsumesDigits;
        this.numberRequiresBoundary = b.numberRequiresBoundary;
        this.numberLeadingMinus = b.numberLeadingMinus;
        this.numberLeadingDot = b.numberLeadingDot;
        this.numberUnitSuffix = b.numberUnitSuffix;
        this.operatorSingleChars = Collections.unmodifiableSet(
                new HashSet<>(b.operatorSingleChars));
        this.blockLiterals = List.copyOf(b.blockLiterals);
        this.lineRules = List.copyOf(b.lineRules);
        this.charQuotes = Collections.unmodifiableSet(new HashSet<>(b.charQuotes));
        this.charType = b.charType;
        this.templateLiteralLanguages = Collections.unmodifiableSet(
                new HashSet<>(b.templateLiteralLanguages));
        this.colorPrefix = b.colorPrefix;
        this.colorType = b.colorType;
        this.registryKeywords = b.registryKeywords;
        this.wordControlKeywords = Collections.unmodifiableSet(
                new HashSet<>(b.wordControlKeywords));
        this.wordControlType = b.wordControlType;
        this.wordModifierKeywords = Collections.unmodifiableSet(
                new HashSet<>(b.wordModifierKeywords));
        this.wordModifierType = b.wordModifierType;
        this.wordTypeKeywords = Collections.unmodifiableSet(
                new HashSet<>(b.wordTypeKeywords));
        this.wordTypeKeywordType = b.wordTypeKeywordType;
        this.wordAllCapsType = b.wordAllCapsType;
        this.wordAfterDotType = b.wordAfterDotType;
        this.wordFuncAfterCase = b.wordFuncAfterCase;
        this.wordFuncContainsChars = Collections.unmodifiableSet(
                new HashSet<>(b.wordFuncContainsChars));
        this.wordFuncSuffixChars = Collections.unmodifiableSet(
                new HashSet<>(b.wordFuncSuffixChars));
        this.typeDescriptorPrefix = b.typeDescriptorPrefix;
        this.typeDescriptorBracket = b.typeDescriptorBracket;
        this.typeDescriptorTerminator = b.typeDescriptorTerminator;
        this.typeDescriptorMaxSpan = b.typeDescriptorMaxSpan;
        this.typeDescriptorType = b.typeDescriptorType;
        this.numberSignChars = Collections.unmodifiableSet(
                new HashSet<>(b.numberSignChars));
        this.stringOrphanFallsThrough = b.stringOrphanFallsThrough;
    }

    /**
     * Template Properties intégré (migration C2-é1) : commentaires
     * {@code #}/{@code !}, séparateurs {@code =}/{@code :}, clé en
     * PROPERTY, valeur en STRING, échappements — reprend la grammaire
     * historique de {@code PropertiesTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate PROPERTIES = builder()
            .lineComments("#", "!")
            .keyValueSeparators('=', ':')
            .keyType(TokenType.PROPERTY)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.STRING)
            .escapesInValue(true)
            .build();

    /**
     * Template TOML intégré (migration C2-é2) : sections
     * {@code [table]}/{@code [[table]]}, paires {@code clé = valeur},
     * chaînes basiques et littérales, nombres/dates RFC 3339,
     * booléens en CONSTANT, commentaires {@code #} partout — reprend
     * la grammaire historique de {@code TomlTokenizer} octet pour
     * octet. Les mots-clés viennent du registre (source unique D5).
     */
    public static final TokenizerTemplate TOML = builder()
            .lineComments("#")
            .commentsAnywhere(true)
            .sections('[', ']', TokenType.TYPE, TokenType.PUNCT)
            .keyValueSeparators('=')
            .keyType(TokenType.PROPERTY)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.STRING)
            .valueQuotes('"', '\'')
            .valueNumberChars('.', '-', ':', 'T', 'Z')
            .valueKeywords(KeywordTables.TOML_KEYWORDS)
            .valueKeywordType(TokenType.CONSTANT)
            .build();

    /**
     * Template Shell/Bash intégré (migration C2-é3) : commentaires
     * {@code #} ancrés (début/blanc/{@code ;}), variables
     * {@code $var}/{@code ${var}}/{@code $?}/{@code $1}, chaînes
     * doubles à échappements sautés, littéraux entre apostrophes à
     * état inter-lignes, substitution par backticks, mots-clés du
     * registre, appels de fonction, CONSTANT majuscules, courses
     * d'opérateurs — reprend la grammaire historique de
     * {@code ShellTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate SHELL = builder()
            .lineComments("#")
            .commentRequiresBoundary(true)
            .commentAnchors(';')
            .freeForm()
            .variablePrefixes('$')
            .plainStringQuotes('"')
            .interLineString('\'', LexState.SHELL_SINGLE_QUOTE)
            .commandQuotes('`')
            .valueType(TokenType.STRING)
            .valueKeywords(KeywordTables.SHELL_KEYWORDS)
            .valueKeywordType(TokenType.KEYWORD)
            .wordExtraChars('_', '-')
            .wordFuncFollowChars('(', '[')
            .wordUpperCaseType(TokenType.CONSTANT)
            .wordFallbackType(TokenType.PLAIN)
            .operatorChars('|', '&', ';', '<', '>', '(', ')')
            .build();

    /**
     * Constantes scalaires YAML historiques (booléens oui/non et
     * variantes de casse, null, tilde) — table locale au tokéniseur :
     * les mettre dans le profil rendrait ces mots proposés en
     * complétion intégrée (D5), un arbitrage de comportement à prendre
     * avec le propriétaire, pas en passant.
     */
    private static final java.util.Set<String> YAML_SCALARS = java.util.Set.of(
            "true", "false", "True", "False",
            "yes", "no", "Yes", "No",
            "null", "Null", "~");

    /**
     * Template YAML intégré (migration C2-é4) : clés confirmées par
     * lookahead de {@code :}, marqueur de document {@code ---},
     * ancres/alias/tags, indicateurs de bloc, chaînes quotées dans les
     * deux zones (une chaîne fermée réouvre la zone clé), mots de
     * valeur classés (nombre/constant/scalaire), scalaires bruts et
     * ponctuation de flux — reprend la grammaire historique de
     * {@code YamlTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate YAML = builder()
            .lineComments("#")
            .commentsAnywhere(true)
            .documentMarkers("---")
            .annotationPrefixes('&', '*', '!')
            .keyValueSeparators(':')
            .keyType(TokenType.PROPERTY)
            .separatorType(TokenType.OPERATOR)
            .valueType(TokenType.STRING)
            .keyStartExtras('"', '\'', '-', '.', '/', '_')
            .keyStopChars('#')
            .keyLookahead(true)
            .stringsInKeyZone(true)
            .stringReopensKeyZone(true)
            .valueQuotes('"', '\'')
            .blockIndicators('|', '>')
            .numbersInValue(false)
            .valueWordStartExtras('-', '.')
            .valueWordExtras('.', '_', '-', ':', '/')
            .valueWordConsumesDigits(true)
            .valueWordNumbers(true)
            .valueKeywords(YAML_SCALARS)
            .valueKeywordType(TokenType.CONSTANT)
            .flowPunct('[', ']', '{', '}')
            .scalarFallback(true)
            .scalarStopChars('#')
            .build();

    /**
     * Template JSON intégré (migration C2-é5) : chaînes quotées à
     * échappements apparents, clé requalifiée par lookahead de
     * deux-points, nombres signés collés aux mots de lettres pures,
     * booléens/null du registre en KEYWORD, deux-points et virgules
     * en opérateurs mono-caractère, chaîne orpheline propagée par
     * l'état inter-lignes — reprend la grammaire historique de
     * {@code JsonTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate JSON = builder()
            .freeForm()
            .valueQuotes('"')
            .stringKeyLookahead(true)
            .keyValueSeparators(':')
            .keyType(TokenType.PROPERTY)
            .valueType(TokenType.STRING)
            .interLineString('"', LexState.XML_STRING)
            .valueNumberChars('.', 'e', 'E', '+', '-')
            .numberLeadingMinus(true)
            .numberRequiresBoundary(false)
            .wordConsumesDigits(false)
            .valueKeywords(KeywordTables.JSON_KEYWORDS)
            .valueKeywordType(TokenType.KEYWORD)
            .wordFallbackType(TokenType.PLAIN)
            .operatorSingleChars(':', ',')
            .build();

    /**
     * Template de la famille C intégré (migration C2-é6) : le cœur
     * historique du parser maison. Commentaires de ligne et de bloc
     * (documentation distincte), chaînes brutes Kotlin à triples
     * guillemets, littéraux de caractère, template literals JS/TS à
     * interpolations, annotations, nombres hexadécimaux/suffixés,
     * couleurs #RGB/#RRGGBB, mots-clés du REGISTRE par langage avec
     * raffinements (contrôle/modificateur), types intégrés,
     * tout-en-majuscules en CONSTANT, propriétés après point, courses
     * d'opérateurs — reprend la grammaire historique de
     * {@code CLikeTokenizer} octet pour octet, porte du langage
     * comprise (« kotlin » exact pour les chaînes brutes,
     * javascript/typescript/js/ts pour les template literals).
     */
    public static final TokenizerTemplate CLIKE = builder()
            .freeForm()
            .lineComments("//")
            .blockLiteral("/**", "*/", 2, LexState.DOC_COMMENT, TokenType.DOC_COMMENT)
            .blockLiteral("/*", "*/", 2, LexState.BLOCK_COMMENT, TokenType.COMMENT)
            .blockLiteral("\"\"\"", "\"\"\"", 3, LexState.KT_RAW_STRING,
                    TokenType.RAW_STRING, "kotlin")
            .plainStringQuotes('"')
            .charQuotes('\'')
            .interLineString('"', LexState.XML_STRING)
            .templateLiteralLanguages("javascript", "typescript", "js", "ts")
            .annotationPrefixes('@')
            .annotationType(TokenType.ANNOTATION)
            .valueType(TokenType.STRING)
            .valueNumberChars('.', '_', 'x', 'X', 'a', 'b', 'c', 'd', 'e', 'f',
                    'A', 'B', 'C', 'D', 'E', 'F', 'L', 'l')
            .colorLiteral('#', TokenType.NUMBER)
            .registryKeywords(true)
            .valueKeywordType(TokenType.KEYWORD)
            .wordControlKeywords(KeywordTables.CONTROL_KEYWORDS)
            .wordModifierKeywords(KeywordTables.MODIFIER_KEYWORDS)
            .wordTypeKeywords(KeywordTables.JAVA_TYPES)
            .wordExtraChars('_')
            .wordFuncFollowChars('(')
            .wordUpperCaseType(TokenType.TYPE)
            .wordAllCapsType(TokenType.CONSTANT)
            .wordAfterDotType(TokenType.PROPERTY)
            .wordFallbackType(TokenType.PLAIN)
            .operatorChars('+', '-', '*', '/', '%', '=', '!', '<', '>', '&',
                    '|', '^', '~', '?', ':')
            .build();

    /**
     * Template Lua intégré (migration C2-é7) : commentaires de ligne
     * {@code --} et de bloc {@code --[[ ]]}, chaînes longues
     * {@code [[ ]]} partageant l'état inter-lignes du commentaire de
     * bloc (la continuation est COMMENT — garde historique), chaînes
     * quotées à échappements apparents sans état inter-lignes, nombres
     * hexadécimaux, mots-clés et builtins du registre, CONSTANT
     * majuscules, appels de fonction — reprend la grammaire historique
     * de {@code LuaTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate LUA = builder()
            .freeForm()
            .lineComments("--")
            .blockLiteral("--[[", "]]", 4, LexState.BLOCK_COMMENT, TokenType.COMMENT)
            .blockLiteral("[[", "]]", 2, LexState.BLOCK_COMMENT, TokenType.STRING)
            .valueQuotes('"', '\'')
            .valueType(TokenType.STRING)
            .valueNumberChars('.', 'x', 'X', 'a', 'b', 'c', 'd', 'e', 'f',
                    'A', 'B', 'C', 'D', 'E', 'F')
            .valueKeywords(KeywordTables.LUA_KEYWORDS)
            .valueKeywordType(TokenType.KEYWORD)
            .wordTypeKeywords(KeywordTables.LUA_BUILTINS)
            .wordTypeKeywordType(TokenType.TYPE)
            .wordExtraChars('_')
            .wordAllCapsType(TokenType.CONSTANT)
            .wordFuncFollowChars('(')
            .wordFallbackType(TokenType.PLAIN)
            .operatorChars('+', '-', '*', '/', '%', '=', '!', '<', '>', '&',
                    '|', '^', '~', '?', ':')
            .build();

    /**
     * Template SQL intégré (migration C2-é8) : commentaires de ligne
     * {@code --} et de bloc {@code /* *&#47;}, chaînes entre apostrophes
     * à échappements apparents, identifiants entre guillemets doubles
     * (chaîne « simple » au type PROPERTY — échappements avalés),
     * nombres décimaux ouverts par un point, mots-clés du registre
     * (majuscules et minuscules), appels de fonction, majuscule
     * initiale en CONSTANT — reprend la grammaire historique de
     * {@code SqlTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate SQL = builder()
            .freeForm()
            .lineComments("--")
            .blockLiteral("/*", "*/", 2, LexState.BLOCK_COMMENT, TokenType.COMMENT)
            .valueQuotes('\'')
            .valueType(TokenType.STRING)
            .plainStringQuotes('"')
            .plainStringType(TokenType.PROPERTY)
            .valueNumberChars('.')
            .numberLeadingDot(true)
            .valueKeywords(KeywordTables.SQL_KEYWORDS)
            .valueKeywordType(TokenType.KEYWORD)
            .wordFuncFollowChars('(')
            .wordUpperCaseType(TokenType.CONSTANT)
            .wordFallbackType(TokenType.PLAIN)
            .operatorChars('+', '-', '*', '/', '%', '=', '!', '<', '>', '&',
                    '|', '^', '~', '?', ':')
            .build();

    /**
     * Template Python intégré (migration C2-é9) : commentaires
     * {@code #}, chaînes à triple guillemet (état inter-lignes —
     * seuls les GUILLEMETS, la garde historique '''), chaînes à
     * préfixe f/r/b dont le préfixe fait partie du jeton, chaînes
     * quotées à échappements apparents, décorateurs bornés au mot
     * (chemins avec points), nombres hexadécimaux/imaginaires/à
     * soulignés, mots-clés et types du registre, casse évaluée avant
     * l'appel (Foo( est TYPE) — reprend la grammaire historique de
     * {@code PythonTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate PYTHON = builder()
            .freeForm()
            .lineComments("#")
            .blockLiteral("\"\"\"", "\"\"\"", 3, LexState.BLOCK_COMMENT, TokenType.STRING)
            .stringPrefixes('f', 'F', 'r', 'R', 'b', 'B')
            .valueQuotes('"', '\'')
            .valueType(TokenType.STRING)
            .valueNumberChars('.', '_', 'x', 'X', 'a', 'b', 'c', 'd', 'e', 'f',
                    'A', 'B', 'C', 'D', 'E', 'F', 'j', 'J')
            .valueKeywords(KeywordTables.PYTHON_KEYWORDS)
            .valueKeywordType(TokenType.KEYWORD)
            .wordTypeKeywords(KeywordTables.PYTHON_TYPES)
            .wordTypeKeywordType(TokenType.TYPE)
            .annotationPrefixes('@')
            .annotationWordChars('.')
            .annotationRequiresBoundary(true)
            .wordFuncAfterCase(true)
            .wordExtraChars('_')
            .wordAllCapsType(TokenType.CONSTANT)
            .wordFuncFollowChars('(')
            .wordUpperCaseType(TokenType.TYPE)
            .wordFallbackType(TokenType.PLAIN)
            .operatorChars('+', '-', '*', '/', '%', '=', '!', '<', '>', '&',
                    '|', '^', '~', '?', ':')
            .build();

    /**
     * Template Smali intégré (migration C2-é10) : directives
     * {@code .class}/{@code .method} raffinées par table (sinon
     * ANNOTATION), commentaires {@code #}, chaînes à échappements
     * apparents dont l'orpheline TOMBE dans les règles suivantes
     * (garde historique), descripteurs de type {@code L…;}/{@code [L…;}
     * à fermante proche, registres p/v en VARIABLE, mots à tiret ou à
     * slash final en FUNC (opcodes), nombres ouverts par un signe
     * (même seul), hexadécimaux et suffixes t/L — reprend la grammaire
     * historique de {@code SmaliTokenizer} octet pour octet. Aucun état
     * inter-lignes.
     */
    public static final TokenizerTemplate SMALI = builder()
            .freeForm()
            .lineComments("#")
            .annotationPrefixes('.')
            .annotationKeywords(KeywordTables.SMALI_KEYWORDS)
            .annotationKeywordType(TokenType.KEYWORD)
            .annotationType(TokenType.ANNOTATION)
            .annotationWordChars('-')
            .valueQuotes('"')
            .valueType(TokenType.STRING)
            .stringOrphanFallsThrough(true)
            .typeDescriptor('L', '[', ';', 200, TokenType.TYPE)
            .valueNumberChars('x', 'X', 'a', 'b', 'c', 'd', 'e', 'f',
                    'A', 'B', 'C', 'D', 'E', 'F', 't', 'L', 'l')
            .numberSignChars('-', '+')
            .wordTypeKeywords(KeywordTables.SMALI_REGISTERS)
            .wordTypeKeywordType(TokenType.VARIABLE)
            .wordExtraChars('_', '-', '/')
            .wordFuncContainsChars('-')
            .wordFuncSuffixChars('/')
            .wordUpperCaseType(TokenType.TYPE)
            .wordFallbackType(TokenType.PLAIN)
            .operatorSingleChars('+', '-', '*', '/', '%', '=', '!', '<',
                    '>', '&', '|', '^', '~', '?', ':')
            .build();

    /**
     * Template CSS/SCSS/LESS intégré (migration C2-é11) : règles at
     * raffinées par table (sinon VARIABLE — interpolation), variables
     * SCSS à tiret, couleurs hexadécimales, nombres à unités ouverts au
     * point, commentaires de bloc (état CSS_COMMENT) et de ligne SCSS,
     * chaînes à échappements apparents dont l'orphaine porte l'état
     * CSS_STRING (fermable par l'un des deux guillemets, « " » en
     * premier), propriétés en PROPERTY (table), sélecteur majuscule en
     * TYPE, « & » ouvrant un mot sans le continuer — reprend la
     * grammaire historique de {@code CssTokenizer} octet pour octet.
     */
    public static final TokenizerTemplate CSS = builder()
            .freeForm()
            .lineComments("//")
            .blockLiteral("/*", "*/", 2, LexState.CSS_COMMENT, TokenType.COMMENT)
            .annotationPrefixes('@')
            .annotationKeywords(KeywordTables.CSS_AT_RULES)
            .annotationKeywordType(TokenType.KEYWORD)
            .annotationType(TokenType.VARIABLE)
            .annotationWordChars('-')
            .variablePrefixes('$')
            .variableWordChars('-')
            .valueQuotes('"', '\'')
            .valueType(TokenType.STRING)
            .interLineQuotes('"', '\'')
            .interLineStringState(LexState.CSS_STRING)
            .valueNumberChars('.')
            .numberLeadingDot(true)
            .numberUnitSuffix(true)
            .colorLiteral('#', TokenType.NUMBER)
            .wordExtraChars('-', '.', '_')
            .wordStartExtras('&')
            .wordTypeKeywords(KeywordTables.CSS_PROPERTIES)
            .wordTypeKeywordType(TokenType.PROPERTY)
            .wordUpperCaseType(TokenType.TYPE)
            .wordFallbackType(TokenType.PLAIN)
            .operatorSingleChars(':', ';', '{', '}', ',', '!', '>', '+',
                    '~', '*', '=')
            .build();

    /**
     * Template « log » intégré (migration C2-é12) : classifieur de
     * ligne pour les consoles Gradle/JVM embarquées — erreurs
     * (messages, stacktraces, préfixes Kotlin « e: », échec),
     * avertissements (« warning: », préfixe « w: », [warn]),
     * succès (BUILD/Sync SUCCESSFUL, statuts de tâche «  UP-TO-DATE »,
     * « prêt »), en-têtes de section «&nbsp;&gt;&nbsp;…&nbsp;» (SENSIBLES à la
     * casse), lignes bracketées [Tooling]/[Sync]/[JVM] — l'ordre
     * d'évaluation est historique : ERROR avant SUCCESS avant INFO,
     * WARNING avant TYPE. Repli PLAIN SANS span (chemin rapide des
     * consoles). Reprend la grammaire de {@code LogTokenizer} octet
     * pour octet.
     */
    public static final TokenizerTemplate LOG = builder()
            // 1. Erreurs — messages d'échec javac/Gradle/JVM, stacktraces.
            .lineContains("error", TokenType.ERROR)
            .lineContains("failed", TokenType.ERROR)
            .lineContains("failure", TokenType.ERROR)
            .lineContains("severe", TokenType.ERROR)
            .lineContains("exception", TokenType.ERROR)
            .lineStartsWith("e:", TokenType.ERROR)
            .lineStartsWith("erreur", TokenType.ERROR)
            .lineContains("échec", TokenType.ERROR)
            .lineContains("what went wrong", TokenType.ERROR)
            // 2. Avertissements, y compris le préfixe Kotlin « w: ».
            .lineStartsWith("warning:", TokenType.WARNING)
            .lineContains("warning:", TokenType.WARNING)
            .lineStartsWith("> warning", TokenType.WARNING)
            .lineStartsWith("w:", TokenType.WARNING)
            .lineContains("[warn]", TokenType.WARNING)
            // 3. Succès — résumés de build/sync, état prêt, statuts de
            //    tâche « sans travail » (précédés d'un espace).
            .lineContains("build successful", TokenType.SUCCESS)
            .lineContains("sync successful", TokenType.SUCCESS)
            .lineContains("succeeded", TokenType.SUCCESS)
            .lineContains("succès", TokenType.SUCCESS)
            .lineEndsWith("prêt", TokenType.SUCCESS)
            .lineContains("operation completed successfully", TokenType.SUCCESS)
            .lineStartsWith(" up-to-date", TokenType.SUCCESS)
            .lineStartsWith(" from-cache", TokenType.SUCCESS)
            .lineStartsWith(" skipped", TokenType.SUCCESS)
            .lineStartsWith(" no-source", TokenType.SUCCESS)
            // 4. En-têtes de section Gradle « > … » — sensibles à la casse.
            .lineStartsWithCaseSensitive("> ", TokenType.TYPE)
            // 5. Lignes bracketées simples.
            .lineStartsWith("[tooling]", TokenType.INFO)
            .lineStartsWith("[sync]", TokenType.INFO)
            .lineStartsWith("[jvm]", TokenType.INFO)
            .lineStartsWith("[jvm-out]", TokenType.INFO)
            .lineStartsWith("[cancel]", TokenType.INFO)
            .build();

    /**
     * Littéral à délimiteurs (C2-é6, famille C) : ouvrant, fermant,
     * offset de recherche de la fermante (l'ouverture {@code /**}
     * cherche sa fermante dès {@code pos+2} — {@code /**&#47;} se referme),
     * état de sortie inter-lignes, type de jeton et langages
     * activateurs (vide : tous).
     *
     * <p>Immuable et thread-safe.</p>
     */
    public static final class BlockLiteral {
        /** Séquence ouvrante ({@code /*}, {@code /**}, {@code """}). */
        public final String open;
        /** Séquence fermante ({@code *\/}). */
        public final String close;
        /** La fermante est cherchée à partir de {@code pos + offset}. */
        public final int searchOffset;
        /** État de sortie si le littéral n'est pas fermé sur la ligne. */
        public final int state;
        /** Type des jetons du littéral. */
        public final TokenType type;
        /** Langages qui activent le littéral (vide : tous). */
        public final Set<String> languages;

        private BlockLiteral(String open, String close, int searchOffset,
                int state, TokenType type, Set<String> languages) {
            this.open = open;
            this.close = close;
            this.searchOffset = searchOffset;
            this.state = state;
            this.type = type;
            this.languages = Collections.unmodifiableSet(languages);
        }

        /** Vrai si le littéral s'applique au langage donné. */
        public boolean appliesTo(String language) {
            return languages.isEmpty() || languages.contains(language);
        }

        @Override
        public String toString() {
            return open + "…" + close + "→" + state + " (" + type + ")"
                    + (languages.isEmpty() ? "" : " langages=" + languages);
        }
    }

    /** Vrai si des en-têtes de section sont déclarées. */
    public boolean hasSections() {
        return sectionOpen != 0;
    }

    /**
     * Règle de classification de ligne (C2-é12, troisième saveur du
     * moteur) : la ligne entière reçoit le type de la première règle
     * qui matche — l'ordre de déclaration est l'ordre d'évaluation.
     *
     * <p>Insensible à la casse par défaut (comparaison sur la ligne
     * minuscule en {@code Locale.ROOT}) ; une règle sensible matche
     * sur la ligne originale (chevron Gradle «&nbsp;&gt;&nbsp;…&nbsp;»).</p>
     *
     * <p>Immuable et thread-safe.</p>
     */
    public static final class LineRule {
        /** Genre de la règle. */
        public final LineMatch kind;
        /** Texte recherché. */
        public final String text;
        /** Vrai : comparaison sur la ligne originale (casse comprise). */
        public final boolean caseSensitive;
        /** Type de la span couvrant la ligne entière. */
        public final TokenType type;

        private LineRule(LineMatch kind, String text, boolean caseSensitive,
                TokenType type) {
            this.kind = kind;
            this.text = text;
            this.caseSensitive = caseSensitive;
            this.type = type;
        }

        @Override
        public String toString() {
            return kind + "('" + text + "') → " + type
                    + (caseSensitive ? " (sensible)" : "");
        }
    }

    /** Genre d'une {@link LineRule règle de classification de ligne}. */
    public enum LineMatch {
        /** La ligne contient le texte. */
        CONTAINS,
        /** La ligne commence par le texte. */
        STARTS_WITH,
        /** La ligne finit par le texte. */
        ENDS_WITH
    }

    /**
     * Vrai si la zone valeur doit être tokénisée jeton par jeton
     * (chaînes, nombres, mots) plutôt qu'en un run mono-jeton —
     * déclenché par la déclaration d'une règle de jeton de valeur.
     */
    public boolean tokenizesValue() {
        return !valueQuotes.isEmpty() || !valueNumberExtras.isEmpty()
                || !valueKeywords.isEmpty();
    }

    /** Builder fluent. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder pour {@link TokenizerTemplate}. */
    public static final class Builder {
        private final List<String> lineComments = new ArrayList<>();
        private final Set<Character> keyValueSeparators = new HashSet<>();
        private TokenType keyType = TokenType.PROPERTY;
        private TokenType separatorType = TokenType.OPERATOR;
        private TokenType valueType = TokenType.STRING;
        private boolean escapesInValue = false;
        private boolean commentsAnywhere = false;
        private char sectionOpen = 0;
        private char sectionClose = 0;
        private TokenType sectionNameType = TokenType.TYPE;
        private TokenType sectionBracketType = TokenType.PUNCT;
        private final Set<Character> valueQuotes = new HashSet<>();
        private final Set<Character> valueNumberExtras = new HashSet<>();
        private final Set<String> valueKeywords = new HashSet<>();
        private TokenType valueKeywordType = TokenType.CONSTANT;
        private TokenType valueNumberType = TokenType.NUMBER;
        private boolean freeForm = false;
        private boolean commentRequiresBoundary = false;
        private final Set<Character> commentAnchors = new HashSet<>();
        private final Set<Character> variablePrefixes = new HashSet<>();
        private TokenType variableType = TokenType.VARIABLE;
        private final Set<Character> variableWordChars = new HashSet<>();
        private final Set<Character> plainStringQuotes = new HashSet<>();
        private TokenType plainStringType = null;
        private final Set<Character> stringPrefixes = new HashSet<>();
        private char interLineQuote = 0;
        private int interLineState = 0;
        private final List<Character> interLineQuotes = new ArrayList<>();
        private final Set<Character> commandQuotes = new HashSet<>();
        private TokenType commandQuoteType = TokenType.ANNOTATION;
        private final Set<Character> wordExtraChars = new HashSet<>();
        private final Set<Character> wordStartExtras = new HashSet<>();
        private final Set<Character> wordFuncFollowChars = new HashSet<>();
        private TokenType wordUpperCaseType = null;
        private TokenType wordFallbackType = TokenType.PLAIN;
        private final Set<Character> operatorChars = new HashSet<>();
        private final List<String> documentMarkers = new ArrayList<>();
        private TokenType documentMarkerType = TokenType.KEYWORD;
        private final Set<Character> annotationPrefixes = new HashSet<>();
        private TokenType annotationType = TokenType.ANNOTATION;
        private final Set<Character> annotationWordChars = new HashSet<>();
        private boolean annotationRequiresBoundary = false;
        private final Set<String> annotationKeywords = new HashSet<>();
        private TokenType annotationKeywordType = TokenType.KEYWORD;
        private final Set<Character> keyStartExtras = new HashSet<>();
        private final Set<Character> keyStopChars = new HashSet<>();
        private boolean keyLookahead = false;
        private boolean stringsInKeyZone = false;
        private boolean stringReopensKeyZone = false;
        private final Set<Character> blockIndicators = new HashSet<>();
        private TokenType blockIndicatorType = TokenType.KEYWORD;
        private boolean numbersInValue = true;
        private final Set<Character> valueWordStartExtras = new HashSet<>();
        private final Set<Character> valueWordExtras = new HashSet<>();
        private boolean valueWordConsumesDigits = false;
        private boolean valueWordNumbers = false;
        private final Set<Character> flowPunct = new HashSet<>();
        private boolean scalarFallback = false;
        private final Set<Character> scalarStopChars = new HashSet<>();
        private boolean stringKeyLookahead = false;
        private boolean wordConsumesDigits = true;
        private boolean numberRequiresBoundary = true;
        private boolean numberLeadingMinus = false;
        private boolean numberLeadingDot = false;
        private boolean numberUnitSuffix = false;
        private final Set<Character> operatorSingleChars = new HashSet<>();
        private final List<BlockLiteral> blockLiterals = new ArrayList<>();
        private final List<LineRule> lineRules = new ArrayList<>();
        private final Set<Character> charQuotes = new HashSet<>();
        private TokenType charType = TokenType.CHAR;
        private final Set<String> templateLiteralLanguages = new HashSet<>();
        private char colorPrefix = 0;
        private TokenType colorType = TokenType.NUMBER;
        private boolean registryKeywords = false;
        private final Set<String> wordControlKeywords = new HashSet<>();
        private TokenType wordControlType = TokenType.KEYWORD_CONTROL;
        private final Set<String> wordModifierKeywords = new HashSet<>();
        private TokenType wordModifierType = TokenType.KEYWORD_MODIFIER;
        private final Set<String> wordTypeKeywords = new HashSet<>();
        private TokenType wordTypeKeywordType = TokenType.TYPE;
        private TokenType wordAllCapsType = null;
        private TokenType wordAfterDotType = null;
        private boolean wordFuncAfterCase = false;
        private final Set<Character> wordFuncContainsChars = new HashSet<>();
        private final Set<Character> wordFuncSuffixChars = new HashSet<>();
        private char typeDescriptorPrefix = 0;
        private char typeDescriptorBracket = 0;
        private char typeDescriptorTerminator = ';';
        private int typeDescriptorMaxSpan = 200;
        private TokenType typeDescriptorType = TokenType.TYPE;
        private final Set<Character> numberSignChars = new HashSet<>();
        private boolean stringOrphanFallsThrough = false;

        /** Préfixes de commentaire de ligne (entrées vides/nulles ignorées). */
        public Builder lineComments(String... prefixes) {
            for (String p : prefixes) {
                if (p != null && !p.isEmpty()) lineComments.add(p);
            }
            return this;
        }

        /** Caractères séparateurs clé/valeur. */
        public Builder keyValueSeparators(char... separators) {
            for (char c : separators) keyValueSeparators.add(c);
            return this;
        }

        /** Type de la zone clé. */
        public Builder keyType(TokenType type) {
            this.keyType = type != null ? type : TokenType.PROPERTY;
            return this;
        }

        /** Type du séparateur. */
        public Builder separatorType(TokenType type) {
            this.separatorType = type != null ? type : TokenType.OPERATOR;
            return this;
        }

        /** Type de la zone valeur. */
        public Builder valueType(TokenType type) {
            this.valueType = type != null ? type : TokenType.STRING;
            return this;
        }

        /** Échappements backslash en valeur (ESCAPE). */
        public Builder escapesInValue(boolean enabled) {
            this.escapesInValue = enabled;
            return this;
        }

        /** Commentaires applicables aussi après le séparateur. */
        public Builder commentsAnywhere(boolean enabled) {
            this.commentsAnywhere = enabled;
            return this;
        }

        /** En-têtes de section (ouvrant, fermant, type du nom, type des fermants). */
        public Builder sections(char open, char close, TokenType nameType,
                TokenType bracketType) {
            this.sectionOpen = open;
            this.sectionClose = close;
            this.sectionNameType = nameType != null ? nameType : TokenType.TYPE;
            this.sectionBracketType = bracketType != null ? bracketType : TokenType.PUNCT;
            return this;
        }

        /** Guillemets ouvrant une chaîne en zone valeur. */
        public Builder valueQuotes(char... quotes) {
            for (char q : quotes) valueQuotes.add(q);
            return this;
        }

        /** Caractères supplémentaires acceptés dans un nombre en valeur. */
        public Builder valueNumberChars(char... extras) {
            for (char c : extras) valueNumberExtras.add(c);
            return this;
        }

        /** Mots-clés reconnus en zone valeur (copie défensive). */
        public Builder valueKeywords(Set<String> keywords) {
            if (keywords != null) valueKeywords.addAll(keywords);
            return this;
        }

        /** Type d'un mot-clé de valeur reconnu. */
        public Builder valueKeywordType(TokenType type) {
            this.valueKeywordType = type != null ? type : TokenType.CONSTANT;
            return this;
        }

        /** Type d'un nombre en valeur. */
        public Builder valueNumberType(TokenType type) {
            this.valueNumberType = type != null ? type : TokenType.NUMBER;
            return this;
        }

        /** Mode flux libre : jetons sur toute la ligne, zones ignorées. */
        public Builder freeForm() {
            this.freeForm = true;
            return this;
        }

        /** Commentaire (flux libre) ancré : début, blanc ou ancre. */
        public Builder commentRequiresBoundary(boolean required) {
            this.commentRequiresBoundary = required;
            return this;
        }

        /** Caractères qui ancrent aussi un commentaire (ex. ;). */
        public Builder commentAnchors(char... chars) {
            for (char c : chars) commentAnchors.add(c);
            return this;
        }

        /** Préfixes de variable ($var, ${var}, $?, $1). */
        public Builder variablePrefixes(char... prefixes) {
            for (char c : prefixes) variablePrefixes.add(c);
            return this;
        }

        /** Type d'une variable reconnue. */
        public Builder variableType(TokenType type) {
            this.variableType = type != null ? type : TokenType.VARIABLE;
            return this;
        }

        /** Caractères supplémentaires du mot de variable (C2-é11, CSS :
         * « - » pour {@code $primary-color}). */
        public Builder variableWordChars(char... chars) {
            for (char c : chars) variableWordChars.add(c);
            return this;
        }

        /** Guillemets de chaîne à échappements sautés (span unique). */
        public Builder plainStringQuotes(char... quotes) {
            for (char q : quotes) plainStringQuotes.add(q);
            return this;
        }

        /** Type d'une chaîne simple (C2-é8, SQL — null : type de
         * valeur, comportement Shell historique). */
        public Builder plainStringType(TokenType type) {
            this.plainStringType = type;
            return this;
        }

        /** Lettres de préfixe de chaîne (C2-é9, Python : f"…", r'…'). */
        public Builder stringPrefixes(char... prefixes) {
            for (char c : prefixes) stringPrefixes.add(c);
            return this;
        }

        /** Littéral à état inter-lignes (guillemet, état de sortie). */
        public Builder interLineString(char quote, int exitState) {
            this.interLineQuote = quote;
            this.interLineState = exitState;
            return this;
        }

        /** Guillemets fermant le littéral inter-lignes, en ordre
         * DÉCLARÉ (C2-é11, CSS) — prioritaire sur interLineString. */
        public Builder interLineQuotes(char... quotes) {
            for (char q : quotes) interLineQuotes.add(q);
            return this;
        }

        /** État de sortie d'un littéral inter-lignes non fermé. */
        public Builder interLineStringState(int exitState) {
            this.interLineState = exitState;
            return this;
        }

        /** Guillemets de substitution par paires (backticks). */
        public Builder commandQuotes(char... quotes) {
            for (char q : quotes) commandQuotes.add(q);
            return this;
        }

        /** Type d'une substitution par paires. */
        public Builder commandQuoteType(TokenType type) {
            this.commandQuoteType = type != null ? type : TokenType.ANNOTATION;
            return this;
        }

        /** Caractères supplémentaires d'un mot (départ et suite). */
        public Builder wordExtraChars(char... chars) {
            for (char c : chars) wordExtraChars.add(c);
            return this;
        }

        /** Caractères ouvrant un mot SANS le continuer (C2-é11, CSS :
         * « & » — a&b est deux mots). */
        public Builder wordStartExtras(char... chars) {
            for (char c : chars) wordStartExtras.add(c);
            return this;
        }

        /** Caractères qui font du mot précédent un appel de fonction. */
        public Builder wordFuncFollowChars(char... chars) {
            for (char c : chars) wordFuncFollowChars.add(c);
            return this;
        }

        /** Type d'un mot à initiale majuscule (null : désactivé). */
        public Builder wordUpperCaseType(TokenType type) {
            this.wordUpperCaseType = type;
            return this;
        }

        /** Type de repli d'un mot non reconnu (flux libre). */
        public Builder wordFallbackType(TokenType type) {
            this.wordFallbackType = type != null ? type : TokenType.PLAIN;
            return this;
        }

        /** Caractères d'opérateur, consommés en course. */
        public Builder operatorChars(char... chars) {
            for (char c : chars) operatorChars.add(c);
            return this;
        }

        /** Marqueurs de document, reconnus n'importe où (ex. ---). */
        public Builder documentMarkers(String... markers) {
            for (String m : markers) {
                if (m != null && !m.isEmpty()) documentMarkers.add(m);
            }
            return this;
        }

        /** Type d'un marqueur de document. */
        public Builder documentMarkerType(TokenType type) {
            this.documentMarkerType = type != null ? type : TokenType.KEYWORD;
            return this;
        }

        /** Préfixes d'ancre/alias/tag (&, *, !). */
        public Builder annotationPrefixes(char... prefixes) {
            for (char c : prefixes) annotationPrefixes.add(c);
            return this;
        }

        /** Type d'une ancre/alias/tag reconnu. */
        public Builder annotationType(TokenType type) {
            this.annotationType = type != null ? type : TokenType.ANNOTATION;
            return this;
        }

        /** Caractères supplémentaires du mot d'annotation (C2-é9,
         * Python : « . » pour {@code @app.route}). */
        public Builder annotationWordChars(char... chars) {
            for (char c : chars) annotationWordChars.add(c);
            return this;
        }

        /** Annotation reconnue seulement en début de ligne ou après un
         * blanc (C2-é9, Python : a@b n'est pas un décorateur). */
        public Builder annotationRequiresBoundary(boolean required) {
            this.annotationRequiresBoundary = required;
            return this;
        }

        /** Mots-clés d'annotation (C2-é10, Smali/CSS) — le JETON entier
         * (préfixe compris : « .class », « @media ») y est raffiné. */
        public Builder annotationKeywords(Set<String> keywords) {
            if (keywords != null) annotationKeywords.addAll(keywords);
            return this;
        }

        /** Type d'une annotation raffinée par mot-clé. */
        public Builder annotationKeywordType(TokenType type) {
            this.annotationKeywordType = type != null ? type : TokenType.KEYWORD;
            return this;
        }

        /** Caractères de départ d'une clé au-delà des lettres. */
        public Builder keyStartExtras(char... chars) {
            for (char c : chars) keyStartExtras.add(c);
            return this;
        }

        /** Caractères qui terminent une clé (au-delà de séparateur/blancs). */
        public Builder keyStopChars(char... chars) {
            for (char c : chars) keyStopChars.add(c);
            return this;
        }

        /** Clé confirmée par un séparateur en lookahead (sinon scalaire). */
        public Builder keyLookahead(boolean enabled) {
            this.keyLookahead = enabled;
            return this;
        }

        /** Chaînes quotées reconnues aussi avant le séparateur. */
        public Builder stringsInKeyZone(boolean enabled) {
            this.stringsInKeyZone = enabled;
            return this;
        }

        /** Une chaîne fermée réouvre la zone clé. */
        public Builder stringReopensKeyZone(boolean enabled) {
            this.stringReopensKeyZone = enabled;
            return this;
        }

        /** Indicateurs de scalaire de bloc en zone clé (|, >). */
        public Builder blockIndicators(char... chars) {
            for (char c : chars) blockIndicators.add(c);
            return this;
        }

        /** Type d'un indicateur de bloc. */
        public Builder blockIndicatorType(TokenType type) {
            this.blockIndicatorType = type != null ? type : TokenType.KEYWORD;
            return this;
        }

        /** Règle numérique autonome en zone valeur (TOML). */
        public Builder numbersInValue(boolean enabled) {
            this.numbersInValue = enabled;
            return this;
        }

        /** Caractères de départ d'un mot de valeur (au-delà lettres/chiffres). */
        public Builder valueWordStartExtras(char... chars) {
            for (char c : chars) valueWordStartExtras.add(c);
            return this;
        }

        /** Caractères de suite d'un mot de valeur (au-delà lettres/chiffres). */
        public Builder valueWordExtras(char... chars) {
            for (char c : chars) valueWordExtras.add(c);
            return this;
        }

        /** Les chiffres participent au mot de valeur (YAML). */
        public Builder valueWordConsumesDigits(boolean enabled) {
            this.valueWordConsumesDigits = enabled;
            return this;
        }

        /** Mot de valeur à tête numérique classé nombre (YAML). */
        public Builder valueWordNumbers(boolean enabled) {
            this.valueWordNumbers = enabled;
            return this;
        }

        /** Ponctuation de flux, les deux zones ([ ] { }). */
        public Builder flowPunct(char... chars) {
            for (char c : chars) flowPunct.add(c);
            return this;
        }

        /** Le repli devient un scalaire brut (jusqu'à blanc/stop). */
        public Builder scalarFallback(boolean enabled) {
            this.scalarFallback = enabled;
            return this;
        }

        /** Caractères qui terminent un scalaire brut (au-delà de
         * séparateur/blancs). */
        public Builder scalarStopChars(char... chars) {
            for (char c : chars) scalarStopChars.add(c);
            return this;
        }

        /** Chaîne quotée fermée suivie d'un séparateur → clé (JSON). */
        public Builder stringKeyLookahead(boolean enabled) {
            this.stringKeyLookahead = enabled;
            return this;
        }

        /** Les chiffres participent au mot (flux libre) — faux :
         * mots de lettres pures, un chiffre collé ouvre un nombre. */
        public Builder wordConsumesDigits(boolean enabled) {
            this.wordConsumesDigits = enabled;
            return this;
        }

        /** Un chiffre n'ouvre un nombre qu'en frontière de mot. */
        public Builder numberRequiresBoundary(boolean required) {
            this.numberRequiresBoundary = required;
            return this;
        }

        /** Un signe moins ancré à un chiffre ouvre un nombre (JSON). */
        public Builder numberLeadingMinus(boolean enabled) {
            this.numberLeadingMinus = enabled;
            return this;
        }

        /** Un point ancré à un chiffre ouvre un nombre (C2-é8, SQL). */
        public Builder numberLeadingDot(boolean enabled) {
            this.numberLeadingDot = enabled;
            return this;
        }

        /** L'unité (lettres, %) est absorbée dans le nombre (C2-é11,
         * CSS : 12px, 100%). */
        public Builder numberUnitSuffix(boolean enabled) {
            this.numberUnitSuffix = enabled;
            return this;
        }

        /** Opérateurs mono-caractère, jamais fusionnés en course
         * (JSON : ':' et ','). */
        public Builder operatorSingleChars(char... chars) {
            for (char c : chars) operatorSingleChars.add(c);
            return this;
        }

        /** Littéral à délimiteurs (famille C) : ouvrant, fermant,
         * offset de recherche de la fermante, état de sortie,
         * type de jeton, langages activateurs (vide : tous). */
        public Builder blockLiteral(String open, String close, int searchOffset,
                int state, TokenType type, String... languages) {
            Set<String> langs = new HashSet<>();
            for (String l : languages) {
                if (l != null && !l.isEmpty()) langs.add(l);
            }
            blockLiterals.add(new BlockLiteral(open, close, searchOffset,
                    state, type, langs));
            return this;
        }

        /** Guillemets ouvrant un littéral de caractère ('a'). */
        public Builder charQuotes(char... quotes) {
            for (char q : quotes) charQuotes.add(q);
            return this;
        }

        // ── Règles de classification de ligne (C2-é12, log) ─────────

        /** Règle : la ligne CONTENANT le texte reçoit le type (casse
         * ignorée, minuscule Locale.ROOT). */
        public Builder lineContains(String text, TokenType type) {
            lineRules.add(new LineRule(LineMatch.CONTAINS, text, false, type));
            return this;
        }

        /** Règle : la ligne COMMENÇANT par le texte reçoit le type
         * (casse ignorée). */
        public Builder lineStartsWith(String text, TokenType type) {
            lineRules.add(new LineRule(LineMatch.STARTS_WITH, text, false, type));
            return this;
        }

        /** Règle : la ligne FINISSANT par le texte reçoit le type
         * (casse ignorée). */
        public Builder lineEndsWith(String text, TokenType type) {
            lineRules.add(new LineRule(LineMatch.ENDS_WITH, text, false, type));
            return this;
        }

        /** Règle : la ligne COMMENÇANT par le texte reçoit le type,
         * comparaison SENSIBLE à la casse (chevron Gradle « &gt; … »). */
        public Builder lineStartsWithCaseSensitive(String text, TokenType type) {
            lineRules.add(new LineRule(LineMatch.STARTS_WITH, text, true, type));
            return this;
        }

        /** Type d'un littéral de caractère. */
        public Builder charType(TokenType type) {
            this.charType = type != null ? type : TokenType.CHAR;
            return this;
        }

        /** Langages où le backtick ouvre un template literal JS/TS
         * (interpolations ${…} en VARIABLE). */
        public Builder templateLiteralLanguages(String... languages) {
            for (String l : languages) {
                if (l != null && !l.isEmpty()) templateLiteralLanguages.add(l);
            }
            return this;
        }

        /** Littéral de couleur hexadécimale (#RGB, #RRGGBB…), en
         * 3/4/6/8 chiffres — le type est déclaré avec le préfixe. */
        public Builder colorLiteral(char prefix, TokenType type) {
            this.colorPrefix = prefix;
            this.colorType = type != null ? type : TokenType.NUMBER;
            return this;
        }

        /** Mots-clés du REGISTRE des langages (famille C, repli Java). */
        public Builder registryKeywords(boolean enabled) {
            this.registryKeywords = enabled;
            return this;
        }

        /** Mots-clés de contrôle raffinés (copie défensive). */
        public Builder wordControlKeywords(Set<String> keywords) {
            if (keywords != null) wordControlKeywords.addAll(keywords);
            return this;
        }

        /** Type d'un mot-clé de contrôle. */
        public Builder wordControlType(TokenType type) {
            this.wordControlType = type != null ? type : TokenType.KEYWORD_CONTROL;
            return this;
        }

        /** Mots-clés modificateurs raffinés (copie défensive). */
        public Builder wordModifierKeywords(Set<String> keywords) {
            if (keywords != null) wordModifierKeywords.addAll(keywords);
            return this;
        }

        /** Type d'un mot-clé modificateur. */
        public Builder wordModifierType(TokenType type) {
            this.wordModifierType = type != null ? type : TokenType.KEYWORD_MODIFIER;
            return this;
        }

        /** Types intégrés reconnus hors mots-clés (copie défensive). */
        public Builder wordTypeKeywords(Set<String> keywords) {
            if (keywords != null) wordTypeKeywords.addAll(keywords);
            return this;
        }

        /** Type d'un mot du jeu de types. */
        public Builder wordTypeKeywordType(TokenType type) {
            this.wordTypeKeywordType = type != null ? type : TokenType.TYPE;
            return this;
        }

        /** Type d'un identifiant tout-en-majuscules (≥ 2 caractères). */
        public Builder wordAllCapsType(TokenType type) {
            this.wordAllCapsType = type;
            return this;
        }

        /** Type d'un identifiant précédé d'un point. */
        public Builder wordAfterDotType(TokenType type) {
            this.wordAfterDotType = type;
            return this;
        }

        /** Vrai : la casse (ALL_CAPS, majuscule initiale) est évaluée
         * AVANT la parenthèse d'appel (C2-é9, Python : Foo( est TYPE). */
        public Builder wordFuncAfterCase(boolean enabled) {
            this.wordFuncAfterCase = enabled;
            return this;
        }

        /** Caractères qui, CONTENUS dans un mot, en font un appel
         * (C2-é10, Smali : « - »). */
        public Builder wordFuncContainsChars(char... chars) {
            for (char c : chars) wordFuncContainsChars.add(c);
            return this;
        }

        /** Caractères qui, TERMINANT un mot, en font un appel
         * (C2-é10, Smali : « / »). */
        public Builder wordFuncSuffixChars(char... chars) {
            for (char c : chars) wordFuncSuffixChars.add(c);
            return this;
        }

        /** Descripteur de type (C2-é10, Smali) : préfixe, crochet
         * préfixant, terminateur, longueur maximale, type. */
        public Builder typeDescriptor(char prefix, char bracket,
                char terminator, int maxSpan, TokenType type) {
            this.typeDescriptorPrefix = prefix;
            this.typeDescriptorBracket = bracket;
            this.typeDescriptorTerminator = terminator;
            this.typeDescriptorMaxSpan = maxSpan;
            this.typeDescriptorType = type != null ? type : TokenType.TYPE;
            return this;
        }

        /** Caractères ouvrant TOUJOURS un nombre, même seuls (C2-é10,
         * Smali : « - » et « + »). */
        public Builder numberSignChars(char... chars) {
            for (char c : chars) numberSignChars.add(c);
            return this;
        }

        /** Vrai : une chaîne orpheline tombe dans les règles suivantes
         * au lieu d'être consommée (C2-é10, Smali). */
        public Builder stringOrphanFallsThrough(boolean enabled) {
            this.stringOrphanFallsThrough = enabled;
            return this;
        }

        public TokenizerTemplate build() {
            return new TokenizerTemplate(this);
        }
    }

    @Override
    public String toString() {
        return "TokenizerTemplate(comments=" + lineComments
                + ", separators=" + keyValueSeparators
                + ", key=" + keyType + ", sep=" + separatorType
                + ", value=" + valueType + ", escapes=" + escapesInValue
                + ", commentsAnywhere=" + commentsAnywhere
                + ", sections=" + (hasSections()
                        ? sectionOpen + "/" + sectionClose : "aucune")
                + ", valueQuotes=" + valueQuotes
                + ", valueNumberExtras=" + valueNumberExtras
                + ", valueKeywords=" + valueKeywords.size() + " mots"
                + (freeForm ? ", fluxLibre" : "")
                + ", variables=" + variablePrefixes
                + ", littéralInterLignes="
                + (interLineQuote != 0 ? interLineQuote + "→" + interLineState : "aucun")
                + (keyLookahead ? ", clésParLookahead" : "")
                + ", marqueursDocument=" + documentMarkers
                + ", repliScalaire=" + scalarFallback
                + ", opérateursMono=" + operatorSingleChars
                + ", littérauxDélimiteurs=" + blockLiterals
                + ", raffinements=" + (registryKeywords ? "registre" : "table")
                + ")";
    }
}
