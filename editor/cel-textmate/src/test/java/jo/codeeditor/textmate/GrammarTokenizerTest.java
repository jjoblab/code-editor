package jo.codeeditor.textmate;

import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-002 (C3) — le moteur d'exécution des grammaires : leftmost-first,
 * pile de contextes inter-lignes (exitState opaque STABLE), portée de
 * contenu, backreferences du end, fusion des spans.
 *
 * <p>Les grammaires de travail vivent en RESSOURCES de test
 * ({@code textmate/*.tmg.json}) — mêmes deux niveaux d'échappement
 * (JSON → regex) que les grammaires embarquées réelles, sans l'échot
 * d'un troisième niveau de text block Java.</p>
 */
class GrammarTokenizerTest {

    /** Grammaire de travail : commentaires, doc blocks, chaînes (échappements), mots-clés. */
    private static final TextMateGrammar GRAMMAR = load("textmate/grammar-test.tmg.json");

    private final GrammarTokenizer engine = new GrammarTokenizer();

    private static TextMateGrammar load(String path) {
        try (InputStream in = GrammarTokenizerTest.class.getClassLoader()
                .getResourceAsStream(path);
                Scanner sc = new Scanner(in, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            return TextMateGrammar.parse(sc.next());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ressource de test manquante : " + path, e);
        }
    }

    private StyledLine tok(String line, int state) {
        return engine.tokenize(GRAMMAR, line, state);
    }

    /** Type du jeton couvrant la première occurrence de needle dans la ligne. */
    private static TokenType type(StyledLine line, String lineText, String needle) {
        int col = lineText.indexOf(needle);
        assertTrue(col >= 0, "aiguille absente : " + needle);
        for (LineSpan s : line.spans) {
            if (col >= s.startCol && col < s.endCol) return s.type;
        }
        throw new AssertionError("aucun span ne couvre « " + needle + " » (col " + col
                + ") : " + line.spans);
    }

    // ── Matches simples : leftmost-first, portées mappées ────────

    @Test
    void motsClésEtNombres() {
        String line = "if x == 42 return";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.KEYWORD_CONTROL, type(l, line, "if"));
        assertEquals(TokenType.NUMBER, type(l, line, "42"));
        assertEquals(TokenType.KEYWORD_CONTROL, type(l, line, "return"));
        assertEquals(TokenType.PLAIN, type(l, line, "x"));
    }

    @Test
    void commentaireDeLigne_puisRien() {
        StyledLine l = tok("// note entière", 0);
        assertEquals(1, l.spans.size());
        assertEquals(TokenType.COMMENT, l.spans.get(0).type);
        assertEquals(0, l.exitState);
    }

    @Test
    void appelDeFonction_entityNameFunction() {
        String line = "foo(1)";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.FUNC, type(l, line, "foo"));
        assertEquals(TokenType.NUMBER, type(l, line, "1"));
    }

    // ── Chaînes : même ligne, échappements ───────────────────────

    @Test
    void chaîneMonoLigne_avecÉchappement() {
        String line = "s = \"a\\nb\"";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.STRING, type(l, line, "\"a"));
        assertEquals(TokenType.ESCAPE, type(l, line, "\\n"));
        assertEquals(0, l.exitState, "la chaîne fermée ne laisse rien en pile");
    }

    @Test
    void guillemetÉchappé_neFermePasLaChaîne() {
        // "a\"b" — l'échappement \" est consommé, la chaîne ferme à la
        // fin, l'état de sortie est vide.
        String line = "\"a\\\"b\"";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.ESCAPE, type(l, line, "\\\""));
        assertEquals(0, l.exitState);
    }

    // ── Chaînes multilignes : pile d'états, stabilité ────────────

    @Test
    void chaîneMultiligne_étatEtPortéeDeContenu() {
        String line1 = "s = \"abc";
        StyledLine l1 = tok(line1, 0);
        assertTrue(l1.exitState != 0, "une chaîne non fermée empile un contexte");
        assertEquals(TokenType.STRING, type(l1, line1, "abc"));

        String line2 = "suite de chaîne";
        StyledLine l2 = tok(line2, l1.exitState);
        assertEquals(TokenType.STRING, l2.spans.get(0).type,
                "toute la ligne dans une chaîne non fermée est STRING");
        assertTrue(l2.exitState != 0);

        String line3 = "fin\" + x";
        StyledLine l3 = tok(line3, l2.exitState);
        assertEquals(TokenType.STRING, type(l3, line3, "fin\""));
        assertEquals(TokenType.PLAIN, type(l3, line3, "x"));
        assertEquals(0, l3.exitState, "la fermeture dépile tout");
    }

    @Test
    void exitState_stable_etInterné() {
        StyledLine l1 = tok("s = \"abc", 0);
        int state1 = l1.exitState;
        assertTrue(state1 != 0, "une pile non vide a un identifiant non nul");
        // Contrat : re-tokéniser la même ligne → même exitState.
        StyledLine again = tok("s = \"abc", 0);
        assertEquals(state1, again.exitState);

        // Interning : une pile de même contenu (même règle encore
        // ouverte) réutilise le même identifiant — c'est ce qui borne
        // la table d'états et garantit la stabilité.
        StyledLine l2 = tok("suite", state1);
        assertEquals(state1, l2.exitState, "pile inchangée → même identifiant");

        // La fermeture ramène à 0 (pile vide).
        StyledLine l3 = tok("fin\"", state1);
        assertEquals(0, l3.exitState);
    }

    @Test
    void sousRèglesActivesDansLeContexte() {
        // L'échappement n'est reconnu QUE dans la chaîne (sous-règle).
        String dans = "x = \"a\\tb";
        StyledLine lDans = tok(dans, 0);
        assertEquals(TokenType.ESCAPE, type(lDans, dans, "\\t"));
        String hors = "a\\tb";
        StyledLine lHors = tok(hors, 0);
        assertEquals(TokenType.PLAIN, type(lHors, hors, "\\t"),
                "hors chaîne, l'échappement n'est pas une règle active");
    }

    // ── Doc block : begin/end multi-lignes ───────────────────────

    @Test
    void docComment_multiligne() {
        StyledLine l1 = tok("/** doc", 0);
        assertEquals(TokenType.DOC_COMMENT, l1.spans.get(0).type);
        assertTrue(l1.exitState != 0);
        StyledLine l2 = tok("corps", l1.exitState);
        assertEquals(TokenType.DOC_COMMENT, l2.spans.get(0).type,
                "le contenu d'un doc block reste DOC_COMMENT");
        String line3 = "fin */ code";
        StyledLine l3 = tok(line3, l1.exitState);
        assertEquals(TokenType.DOC_COMMENT, type(l3, line3, "fin "));
        assertEquals(TokenType.PLAIN, type(l3, line3, "code"));
        assertEquals(0, l3.exitState);
    }

    // ── Backreferences du end ────────────────────────────────────

    @Test
    void backréférenceDuEnd_résolueAvecBegin() {
        // Bloc délimité par un triple guillemet identique — le end \1
        // reprend le délimiteur capturé par le begin.
        TextMateGrammar g = load("textmate/backref-test.tmg.json");
        GrammarTokenizer engine = new GrammarTokenizer();
        StyledLine l1 = engine.tokenize(g, "x = \"\"\"abc", 0);
        assertTrue(l1.exitState != 0);
        // La clôture doit reconnaître """ (pas ''').
        StyledLine l2 = engine.tokenize(g, "suite\"\"\"", l1.exitState);
        assertEquals(0, l2.exitState, "le \\1 du end doit matcher le délimiteur du begin");
        // Un délimiteur différent ne ferme PAS.
        StyledLine l2b = engine.tokenize(g, "suite'''", l1.exitState);
        assertTrue(l2b.exitState != 0);
    }

    // ── Fusion des spans adjacents ───────────────────────────────

    @Test
    void spansAdjacents_deMêmeTypeFusionnés() {
        // "abc" : quotes + contenu = un seul span STRING.
        StyledLine l = tok("\"abc\"", 0);
        long stringSpans = l.spans.stream().filter(s -> s.type == TokenType.STRING).count();
        assertEquals(1, stringSpans);
    }

    // ── Leftmost-first : l'ordre déclare les priorités ───────────

    @Test
    void leftmost_gagne_surLordreDeDéclaration() {
        // Chaque règle s'applique à SA position : le moteur prend le
        // match le plus à GAUCHE d'abord, puis continue.
        String line = "return foo(1)";
        StyledLine l = tok(line, 0);
        assertEquals(TokenType.KEYWORD_CONTROL, type(l, line, "return"));
        assertEquals(TokenType.FUNC, type(l, line, "foo"));
        assertEquals(TokenType.NUMBER, type(l, line, "1"));
    }

    // ── C3-bis : includes externes (compatibilité sora-editor) ───

    /**
     * Grammaire hôte : une règle locale (mot-clé) + un include
     * EXTERNE {@code source.embed} — comme html incluant source.css.
     */
    private static final TextMateGrammar HOST = TextMateGrammar.parse(
            "{\"scopeName\": \"source.host\", \"patterns\": ["
                    + "{\"match\": \"\\\\bfor\\\\b\", \"name\": \"keyword.control.host\"},"
                    + "{\"include\": \"source.embed\"}]}");

    /**
     * Grammaire invitée : les identifiants en entity.name.function
     * (FUNC).
     */
    private static final TextMateGrammar EMBED = TextMateGrammar.parse(
            "{\"scopeName\": \"source.embed\", \"patterns\": ["
                    + "{\"match\": \"[a-z]+\", \"name\": \"entity.name.function.embed\"}]}" );

    @Test
    void includeExterne_résolueParLeRegistre() {
        // Registre {scopeName → grammaire} : la règle de l'invitée
        // colorea la zone déléguée.
        java.util.Map<String, TextMateGrammar> externals =
                java.util.Map.of("source.embed", EMBED);
        StyledLine l = engine.tokenize(HOST, "for count", 0, externals);
        assertEquals(TokenType.KEYWORD_CONTROL, l.spans.get(0).type, "for → local");
        assertEquals(TokenType.FUNC, type(l, "for count", "count"),
                "count → règle de la grammaire invitée");
    }

    @Test
    void includeExterne_absente_dégradationSansÉchec() {
        // Registre vide : la règle incluse ne matche RIEN — le texte
        // délégué retombe dans le PLAIN de repli du moteur (html sans
        // css : les balises restent colorées, pas le contenu).
        StyledLine l = engine.tokenize(HOST, "for count", 0, java.util.Map.of());
        assertEquals(TokenType.KEYWORD_CONTROL, l.spans.get(0).type);
        assertEquals(2, l.spans.size());
        assertEquals(TokenType.PLAIN, l.spans.get(1).type, "count reste PLAIN");
    }

    // ── C3-ter : matchs de largeur nulle, idiome (?!\\G), « \\n » virtuel ──

    /**
     * Grammaire de travail pour les idiomes VS Code : wrapper de
     * commentaire « blancs de tête + lookahead » à fermante
     * {@code (?!\G)} (java/html/lua de sora-editor), appel de fonction
     * à begin lookahead + fermante lookahead {@code (?=\\))}.
     */
    private static final TextMateGrammar LOOKAHEAD = load(
            "textmate/lookahead-test.tmg.json");

    private StyledLine tokLook(String line, int state) {
        return engine.tokenize(LOOKAHEAD, line, state);
    }

    /** La ligne est intégralement couverte : aucun trou entre spans. */
    private static void couvertureIntégrale(StyledLine l, int longueur) {
        int fin = 0;
        for (LineSpan s : l.spans) {
            assertEquals(fin, s.startCol, "trou avant le span " + s);
            assertTrue(s.endCol > s.startCol, "span vide : " + s);
            fin = s.endCol;
        }
        assertEquals(longueur, fin, "la fin de ligne n'est pas couverte");
    }

    @Test
    void commentaireLigne_wrapperNotG_seRefermeEnFinDeLigne() {
        // Régression C3-ter : la fermante (?!\G) du wrapper (idiome
        // java/html/lua) ne compile pas en java.util.regex où \G vaut
        // TOUJOURS la position courante — sans la garde portée par le
        // cadre, le wrapper ne se refermait JAMAIS et le contexte
        // avalait les lignes suivantes.
        StyledLine l = tokLook("  // commentaire", 0);
        assertEquals(TokenType.COMMENT, type(l, "  // commentaire", "//"));
        couvertureIntégrale(l, 16);
        assertEquals(0, l.exitState, "le wrapper ET le commentaire se referment (\\n virtuel)");
    }

    @Test
    void ligneAprèsCommentaire_pasAvalée() {
        // La ligne suivant un commentaire de ligne doit être typée
        // NORMALEMENT — le wrapper (?!\G) refermé en fin de ligne ne
        // doit pas fuiter sur l'aval.
        StyledLine l1 = tokLook("  // note", 0);
        StyledLine l2 = tokLook("if x = 42", l1.exitState);
        assertEquals(TokenType.KEYWORD_CONTROL, type(l2, "if x = 42", "if"));
        assertEquals(TokenType.NUMBER, type(l2, "if x = 42", "42"));
        assertEquals(0, l2.exitState);
    }

    @Test
    void fermanteLookaheadVide_dépileEtRetente() {
        // Régression C3-ter : une fermante DE LARGEUR NULLE ((?=\\)))
        // dépile et RETENTE À LA MÊME POSITION — l'ancienne garde
        // anti-boucle sautait un caractère (le « ) » échappait alors à
        // la fermante du parent, le cadre ne se refermait jamais).
        StyledLine l = tokLook("foo(1)", 0);
        assertEquals(TokenType.FUNC, type(l, "foo(1)", "foo"));
        couvertureIntégrale(l, 6);
        assertEquals(0, l.exitState, "le cadre function-call se referme sur (?=\\))");
    }

    @Test
    void beginLookaheadVide_pousseEtRetente() {
        // Un begin DE LARGEUR NULLE (wrapper lookahead) pousse le
        // cadre puis retente À LA MÊME POSITION : les sous-règles du
    // wrapper colorent depuis le début du mot (pas de trou).
        StyledLine l = tokLook("ab foo(1)", 0);
        assertEquals(TokenType.PLAIN, type(l, "ab foo(1)", "ab"));
        assertEquals(TokenType.FUNC, type(l, "ab foo(1)", "foo"));
        couvertureIntégrale(l, 9);
        assertEquals(0, l.exitState);
    }

    @Test
    void frontièreDeMotTransparente_beginNeMatchePasAuMilieu() {
        // Régression C3-ter : bornes TRANSPARENTES — un « \\b » à la
        // position courante doit voir le VRAI caractère précédent. En
        // bornes d'ancrage (l'ancien comportement), le begin
        // lookahead matchait AU MILIEU d'un mot (frontière fantôme au
        // bord de région).
        StyledLine l = tokLook("ab foo(1)", 0);
        // Si \\b était ancré au bord de région, un cadre function-call
        // serait poussé au « b » de « ab » : « ab » ne serait pas un
        // seul span PLAIN couvrant [0,2).
        couvertureIntégrale(l, 9);
        assertEquals(TokenType.FUNC, type(l, "ab foo(1)", "foo"),
                "le begin ne matche qu'à la frontière du mot");
    }

    @Test
    void cadreLookahead_propagationInterLignes() {
        // L'appel non fermé propage son cadre : la ligne suivante est
        // du contenu de l'appel jusqu'à la fermante lookahead.
        StyledLine l1 = tokLook("foo(", 0);
        assertTrue(l1.exitState != 0, "le cadre function-call est ouvert");
        StyledLine l2 = tokLook("x)", l1.exitState);
        couvertureIntégrale(l2, 2);
        assertEquals(0, l2.exitState, "fermé par le lookahead vide sur « ) »");
    }

    @Test
    void oscillationZéroLargeur_disjoncteurBorné() {
        // Grammaire dégénérée : begin ET end lookahead vide qui
        // s'annulent — le disjoncteur (50 étapes vides consécutives)
        // doit faire progresser le scan sans boucle infinie.
        TextMateGrammar dégénérée = TextMateGrammar.parse(
                "{\"scopeName\": \"source.d\", \"patterns\": ["
                        + "{\"begin\": \"(?=x)\", \"end\": \"(?=x)\", \"name\": \"meta.d\"}]}");
        StyledLine l = engine.tokenize(dégénérée, "xax", 0);
        couvertureIntégrale(l, 3);
    }

    // ── C3-quinquies : clause while (garde de continuation de ligne) ──

    /** Grammaire de travail : citation à clause while (style markdown). */
    private static final TextMateGrammar WHILE = load(
            "textmate/while-test.tmg.json");

    @Test
    void clauseWhile_continuationLePréfixeEstConsommé() {
        // La ligne de continuation matche le while : le préfixe « > »
        // est CONSOMMÉ (portée de la règle) et le contenu est tokénisé
        // par les sous-règles du cadre (le nombre reste NUMBER).
        StyledLine l1 = engine.tokenize(WHILE, "> citation 42", 0);
        assertTrue(l1.exitState != 0, "le cadre citation est ouvert");
        StyledLine l2 = engine.tokenize(WHILE, "> suite 7", l1.exitState);
        assertEquals(TokenType.NUMBER, type(l2, "> suite 7", "7"),
                "le contenu de la continuation est tokénisé par les sous-règles");
        assertTrue(l2.exitState != 0,
                "le cadre citation RESTE ouvert : le while a matché (continuation)");
    }

    @Test
    void clauseWhile_ligneSansPréfixeDépileLeCadre() {
        // Une ligne qui ne matche PAS le while : le cadre ne s'applique
        // PAS à cette ligne (dépilement en tête de ligne, SANS fermante
        // en ligne) — la ligne est typée par les règles RACINE.
        StyledLine l1 = engine.tokenize(WHILE, "> citation 42", 0);
        StyledLine l2 = engine.tokenize(WHILE, "note", l1.exitState);
        assertEquals(TokenType.KEYWORD_CONTROL, type(l2, "note", "note"),
                "la ligne hors citation est typée par les règles racine");
    }

    @Test
    void clauseWhile_lookaheadNégatifSansOscillation() {
        // Régression C3-quinquies : le while des blocs de code markdown
        // est un lookahead NÉGATIF — en fermante en ligne (l'ancien
        // pliage C3-bis) il matcherait vide PARTOUT et oscillait
        // pousser/dépiler (le contenu des blocs ``` restait PLAIN).
        // En garde de tête de ligne : le contenu est tokénisé.
        TextMateGrammar fenced = TextMateGrammar.parse(
                "{\"scopeName\": \"source.f\", \"patterns\": ["
                        + "{\"begin\": \"```\", \"end\": \"```\","
                        + " \"patterns\": ["
                        + "  {\"begin\": \"(^|\\\\G)(\\\\s*)(.*)\","
                        + "   \"while\": \"(^|\\\\G)(?!\\\\s*(`{3,})\\\\s*$)\","
                        + "   \"patterns\": ["
                        + "    {\"match\": \"\\\\bint\\\\b\", \"name\": \"storage.type.f\"}]}]}]}");
        StyledLine l1 = engine.tokenize(fenced, "```", 0);
        assertTrue(l1.exitState != 0, "bloc ouvert");
        StyledLine l2 = engine.tokenize(fenced, "int x = 1;", l1.exitState);
        assertEquals(TokenType.TYPE, type(l2, "int x = 1;", "int"),
                "le contenu du bloc est tokénisé (pas d'oscillation)");
        // La ligne de clôture (``` seule, comme le markdown réel) dépile
        // le bloc embarqué (while échoue) PUIS le bloc lui-même (sa
        // fermante matche).
        StyledLine l3 = engine.tokenize(fenced, "```", l2.exitState);
        assertEquals(0, l3.exitState, "le bloc se referme sur la clôture");
    }

    @Test
    void includeExterne_lesRefsDuBesoinDeLinvitéeSeRésolventChezElle() {
        // Régression C3-quinquies : les #ref des règles INVITÉES se
        // résolvent dans le repository de L'INVITÉE — l'hôte qui
        // inclut source.embed ne connaît pas le repository de
        // l'invitée : sans suivi du propriétaire, la règle incluse ne
        // matchait RIEN (le walk mourait au premier #ref non trouvé).
        TextMateGrammar hôte = TextMateGrammar.parse(
                "{\"scopeName\": \"source.h\", \"patterns\": ["
                        + "{\"begin\": \"§\", \"end\": \"§\","
                        + " \"patterns\": [{\"include\": \"source.embed\"}]}]}");
        TextMateGrammar invitée = TextMateGrammar.parse(
                "{\"scopeName\": \"source.embed\", \"patterns\": ["
                        + "{\"include\": \"#mots\"}],"
                        + "\"repository\": {"
                        + "  \"mots\": {\"patterns\": ["
                        + "    {\"match\": \"\\\\bchiffre\\\\b\", \"name\": \"constant.numeric.e\"}]}}}");
        java.util.Map<String, TextMateGrammar> externals =
                java.util.Map.of("source.embed", invitée);
        StyledLine l1 = engine.tokenize(hôte, "§", 0, externals);
        StyledLine l2 = engine.tokenize(hôte, "chiffre", l1.exitState, externals);
        assertEquals(TokenType.NUMBER, type(l2, "chiffre", "chiffre"),
                "le #ref de l'invitée se résout dans SON repository");
    }
}
