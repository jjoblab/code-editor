package jo.codeeditor.session;

import org.junit.jupiter.api.Test;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7 (plan 09) — l'écart minimal de {@code setComposingText} : chaque
 * mise à jour de composition n'épisse que la DIFFÉRENCE médiane entre
 * l'ancien et le nouveau texte (préfixe/suffixe communs préservés),
 * jamais la région entière. Le document, la région et le caret restent
 * IDENTIQUES à l'épissure intégrale — seule la mutation devient
 * proportionnelle à la variation.
 *
 * <ul>
 *   <li>composition CJK croissante : chaque étape ajoute le suffixe seul ;</li>
 *   <li>backspace de composition : seul le suffixe retiré disparaît ;</li>
 *   <li>remplacement du MILIEU : préfixe et suffixe communs survivent ;</li>
 *   <li>mise à jour REDONDANTE : aucune épissure, aucune étape d'undo ;</li>
 *   <li>les paires de substitution ne sont JAMAIS coupées à la frontière ;</li>
 *   <li>l'annulation retire un mot composé en UNE passe (coalescence des
 *       écarts adjacents, comme les frappes tapées).</li>
 * </ul>
 */
public class ComposingMinimalDiffTest {

    private EditorSession newSession(String content) {
        return new EditorSession(EditorDocument.of(content));
    }

    // ── Croissance CJK : le document suit exactement le contrat IME ──

    @Test
    public void compositionCroissante_documentEtRegionCommeLEpissureIntegrale() {
        EditorSession s = newSession("");
        s.imeSetComposingText("こ", 1);
        s.imeSetComposingText("こん", 1);
        s.imeSetComposingText("こんに", 1);
        s.imeSetComposingText("こんにちは", 1);

        assertEquals("こんにちは", s.getDocument().getText(),
                "le document porte le mot de composition complet");
        int[] region = s.getComposingRegion();
        assertEquals(0, region[0]);
        assertEquals("こんにちは".length(), region[1],
                "la région couvre le mot entier");
        assertEquals("こんにちは".length(), s.getSelection().start,
                "le caret est à la fin (newCursorPosition == 1)");
    }

    @Test
    public void retraitDeSuffixe_backspaceDeComposition() {
        EditorSession s = newSession("");
        s.imeSetComposingText("hello", 1);
        s.imeSetComposingText("hell", 1);
        assertEquals("hell", s.getDocument().getText(),
                "seul le suffixe « o » a été retiré");
        s.imeSetComposingText("hel", 1);
        assertEquals("hel", s.getDocument().getText());
    }

    @Test
    public void remplacementDuMilieu_prefixeEtSuffixePreserves() {
        EditorSession s = newSession("");
        s.imeSetComposingText("abc", 1);
        s.imeSetComposingText("axc", 1);
        assertEquals("axc", s.getDocument().getText(),
                "seul le « b » a été remplacé par « x »");
        int[] region = s.getComposingRegion();
        assertEquals(0, region[0]);
        assertEquals(3, region[1]);
    }

    @Test
    public void suffixeRetireETRemplaceEnMemeTemps() {
        // L'IME raccroche la fin du mot : « kana » → « kan » → «kanaa ».
        EditorSession s = newSession("");
        s.imeSetComposingText("kana", 1);
        s.imeSetComposingText("kan", 1);
        s.imeSetComposingText("kanaa", 1);
        assertEquals("kanaa", s.getDocument().getText());
        int[] region = s.getComposingRegion();
        assertEquals(0, region[0]);
        assertEquals(5, region[1]);
    }

    // ── Mise à jour redondante : zéro mutation, zéro étape d'undo ────

    @Test
    public void miseAJourRedondante_neCreerPlusDEtapeUndoFantome() {
        EditorSession s = newSession("");
        s.imeSetComposingText("hello", 1);
        // L'IME renvoie le MÊME texte (certaines IME le font pour
        // re-synchroniser leur miroir) : l'épissure intégrale enregistrait
        // une étape d'annulation hello→hello (undo fantôme).
        s.imeSetComposingText("hello", 1);
        // UNE seule annulation doit donc suffire à revenir au document vide.
        s.undo();
        assertEquals("", s.getDocument().getText(),
                "une annulation remonte la composition entière (pas d'étape fantôme)");
    }

    // ── Paires de substitution jamais coupées à la frontière ────────

    @Test
    public void emojiAdjacent_aucunePaireCoupee() {
        EditorSession s = newSession("");
        // Deux emoji distincts qui ne diffèrent que par le BAS substitut :
        // la frontière du préfixe commun tomberait ENTRE le haut et le bas
        // d'une paire sans la garde de recul.
        String first = "\uD83D\uDE00";  // 🙂 U+1F600
        String second = "\uD83D\uDE01"; // 😁 U+1F601
        s.imeSetComposingText(first, 1);
        s.imeSetComposingText(second, 1);
        String doc = s.getDocument().getText();
        assertEquals(second, doc,
                "le document porte exactement le nouvel emoji");
        assertEquals(2, doc.length(),
                "une paire de substitution bien formée, pas deux moitiés");
        assertTrue(Character.isHighSurrogate(doc.charAt(0))
                && Character.isLowSurrogate(doc.charAt(1)));
    }

    @Test
    public void suffixeCommunEmoji_aucunePaireCoupee() {
        EditorSession s = newSession("");
        // Le suffixe commun commence au milieu d'une paire : recul d'un
        // caractère pour ne pas couper « abc🙂 » → « ab🙂 ».
        String withEmoji = "abc\uD83D\uDE00";
        String shorter = "ab\uD83D\uDE00";
        s.imeSetComposingText(withEmoji, 1);
        s.imeSetComposingText(shorter, 1);
        assertEquals(shorter, s.getDocument().getText());
    }

    // ── Composition vide et sélection initiale (contrats inchangés) ──

    @Test
    public void compositionVideEffaceLaRegion() {
        EditorSession s = newSession("");
        s.imeSetComposingText("hello", 1);
        s.imeSetComposingText("", 1);
        assertEquals("", s.getDocument().getText(),
                "une composition vide efface la région (l'IME annule)");
        // Miroir inchangé de l'épissure intégrale : une composition vide
        // laisse une région VIDE (pas de retour à « pas en composition »).
        int[] region = s.getComposingRegion();
        assertTrue(region != null && region[0] == 0 && region[1] == 0,
                "région vide [0,0]");
    }

    @Test
    public void premiereCompositionRemplaceLaSelection() {
        EditorSession s = newSession("word");
        s.setSelection(Selection.range(0, 4));
        s.imeSetComposingText("phrase", 1);
        assertEquals("phrase", s.getDocument().getText(),
                "la première composition remplace la sélection (contrat IME)");
    }

    // ── L'annulation d'un mot composé = UNE passe (comme une frappe) ──

    @Test
    public void annulation_retireLeMotComposeEnUnePasse() {
        EditorSession s = newSession("fin");
        s.setSelection(3);
        s.imeSetComposingText("a", 1);
        s.imeSetComposingText("ab", 1);
        s.imeSetComposingText("abc", 1);
        assertEquals("finabc", s.getDocument().getText());

        // Les écarts d'une même composition (insertions adjacentes d'un
        // caractère) coalescent exactement comme les frappes tapées :
        // UNE annulation retire le mot composé ENTIER — l'utilisateur ne
        // remonte pas caractère par caractère un mot qu'il vient de taper.
        s.undo();
        assertEquals("fin", s.getDocument().getText(),
                "une annulation retire le mot composé entier (coalescence des écarts)");
        // Rien d'autre à annuler : la pile ne contient plus d'étape de
        // composition (pas d'étapes fantômes).
        s.undo();
        assertEquals("fin", s.getDocument().getText(),
                "la pile d'annulation est épuisée après le mot composé");
    }
}
