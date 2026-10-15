package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1/D-01 — câblage de la session de snippet dans {@link EditorSession} :
 * application (texte apuré, aucun marqueur $ résiduel), sélection du
 * premier champ ($1, convention LSP), navigation Tab/Shift+Tab,
 * arrêt final $0, ré-ancrage sur édition, miroirs, notification du
 * listener {@link EditorSession.OnSnippetEditListener}, survie
 * interdite à undo/redo.
 *
 * <p>Rouge avant : {@code SnippetSession} n'était référencée par
 * aucun code de production (M-02) ; l'acceptation d'un snippet insérait
 * les marqueurs $1/${1:ph} littéralement et
 * {@code setOnSnippetEditListener} n'était jamais déclenché (D-01).</p>
 */
class EditorSessionSnippetTest {

    private static EditorSession session(String doc) {
        return new EditorSession(EditorDocument.of(doc));
    }

    @Test
    void applySnippet_insèreTexteApuré_sélectionnePremierChamp() {
        EditorSession s = session("int a = ;");
        // Remplace le « ; » (offset 8) par le snippet.
        boolean active = s.snippets.apply(8, 9, "appel(${1:arg}, $2)$0;");
        assertTrue(active, "une session de snippet est posée");
        assertEquals("int a = appel(arg, );", s.getDocument().getText(),
                "texte apuré — AUCUN marqueur $ résiduel");
        // Premier champ = $1 (convention LSP), placeholder « arg »
        // sélectionné : [15, 18).
        Selection sel = s.getSelection();
        assertEquals(14, sel.start, "début du premier champ $1");
        assertEquals(17, sel.end, "fin du premier champ $1 (placeholder sélectionné)");
        assertTrue(s.snippets.isActive());
    }

    @Test
    void navigation_suivant_précédent_arrêtFinal() {
        EditorSession s = session("");
        s.snippets.apply(0, 0, "$1 + $2 = $0");
        // $1 sélectionné au départ : [0, 0) — placeholder vide.
        assertEquals(0, s.getSelection().start);
        assertEquals(0, s.getSelection().end);

        // Tab → $2 (à l'offset 3 du texte apuré « ·+··=· »).
        assertTrue(s.snippets.next());
        assertEquals(3, s.getSelection().start, "champ $2 (offset du second stop)");
        assertTrue(s.snippets.isActive(), "la session survit à Tab");

        // Shift+Tab → retour à $1.
        assertTrue(s.snippets.prev());
        assertEquals(0, s.getSelection().start);
        assertTrue(s.snippets.isActive(), "la session survit à Shift+Tab");

        // Shift+Tab au premier champ : non consommé, session conservée.
        assertFalse(s.snippets.prev());
        assertTrue(s.snippets.isActive());

        // Tab, Tab → $0 : arrêt final, session close, caret posé (offset 6).
        assertTrue(s.snippets.next());
        assertTrue(s.snippets.next());
        assertFalse(s.snippets.isActive(), "l'arrêt $0 clôt la session");
        assertEquals(6, s.getSelection().start, "caret sur $0 (fin du texte apuré)");
        // Le Tab suivant ne navigue plus rien.
        assertFalse(s.snippets.next());
    }

    @Test
    void éditionDansUnChamp_réancrageEtMiroirs() {
        EditorSession s = session("");
        // $1 apparaît deux fois : la première occurrence est le champ
        // primaire, la seconde un miroir synchronisé.
        s.snippets.apply(0, 0, "f(${1:x}, ${1:x})$0");
        assertEquals("f(x, x)", s.getDocument().getText());
        // Champ primaire $1 sélectionné [2, 3).
        assertEquals(2, s.getSelection().start);
        assertEquals(3, s.getSelection().end);

        // Remplacer le champ $1 par « toto » (simulation de frappe).
        s.replaceRange(2, 3, "toto");
        assertEquals("f(toto, toto)", s.getDocument().getText(),
                "le miroir reçoit le texte du champ primaire");

        // La navigation reste cohérente après l'édition (ré-ancrage) :
        // le prochain arrêt est $0 → session close.
        assertTrue(s.snippets.isActive());
        assertTrue(s.snippets.next());
        assertFalse(s.snippets.isActive());
    }

    @Test
    void listenerDeChamp_déclenchéSurÉdition() {
        EditorSession s = session("");
        s.snippets.apply(0, 0, "bonjour ${1:nom} !");
        final List<int[]> evenements = new ArrayList<>();
        final List<String> inseres = new ArrayList<>();
        s.setOnSnippetEditListener((start, end, inserted) -> {
            evenements.add(new int[]{start, end});
            inseres.add(inserted);
        });
        // Champ $1 : placeholder « nom » en [8, 11).
        s.replaceRange(8, 11, "monde");
        assertEquals(1, evenements.size(), "le listener est déclenché par l'édition de champ");
        assertEquals(8, evenements.get(0)[0]);
        assertEquals("monde", inseres.get(0));
        assertEquals("bonjour monde !", s.getDocument().getText());
    }

    @Test
    void undoTermineLaSession() {
        EditorSession s = session("x");
        s.snippets.apply(1, 1, " ${1:a}");
        assertTrue(s.snippets.isActive());
        assertTrue(s.undo());
        assertFalse(s.snippets.isActive(), "le snippet ne survit pas à l'annulation");
        assertEquals("x", s.getDocument().getText());
    }

    @Test
    void texteSansTabStop_insertionBrute() {
        EditorSession s = session("foo");
        boolean active = s.snippets.apply(0, 3, "bar");
        assertFalse(active, "aucune session sans tab stop");
        assertFalse(s.snippets.isActive());
        assertEquals("bar", s.getDocument().getText(), "insertion brute du texte");
    }

    @Test
    void champVideSimplicité_insertionÀPlageEtSélection() {
        // Plage de remplacement NON triviale : le snippet remplace
        // plusieurs caractères.
        EditorSession s = session("console.con");
        boolean active = s.snippets.apply(0, 11, "console.log($1)$0");
        assertTrue(active);
        assertEquals("console.log()", s.getDocument().getText());
        // $1 : [12, 12).
        assertEquals(12, s.getSelection().start);
        assertEquals(12, s.getSelection().end);
    }}
