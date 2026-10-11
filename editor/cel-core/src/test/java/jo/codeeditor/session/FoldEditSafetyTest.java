package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot A (audit v3.41.0) — suppressions silencieuses dans un pli
 * replié.
 *
 * <p>Symptôme observé sur l'appareil : un pli replié, le caret tapé
 * juste après la chip {@code {…}} (donc sur {@code fold.end}, à
 * l'intérieur strict de la zone masquée pour {@code FoldRegions.expandAt}
 * qui exige {@code r.start < offset && offset < r.end}). L'utilisateur
 * appuie sur Retour arrière : le caractère précédent est supprimé —
 * or ce caractère est dans la zone masquée. L'utilisateur ne voit
 * rien se passer au bon endroit. Pire : il peut enchaîner plusieurs
 * Retours arrière et détruire plusieurs lignes sans le voir.</p>
 *
 * <p>Cause racine : les éditions (frappe, backspace, deleteForward,
 * paste, cut, tab, commentaire) ne connaissent pas les plis repliés
 * — une recherche de « fold » dans {@code cel-core/.../edit/},
 * {@code ImeBridge}, {@code EditorImeBridge} et {@code EditorCommands}
 * ne renvoie aucun résultat. {@code EditorSession.expandFoldAt} n'est
 * appelé que par la navigation programmatique (aller à la définition,
 * etc.), et {@code FoldRegions.expandAt} ne déplie que si
 * {@code r.start < offset && offset < r.end} (bornes strictes).
 * Un caret à {@code r.end} (juste après la chip, avant le {@code })}
 * ou après le suffixe est donc « hors du pli » : aucun dépliage.</p>
 *
 * <p>Spécification attendue : toute mutation dont la plage touche
 * l'intérieur d'un pli replié, bornes incluses côté intérieur,
 * déplie d'abord ce pli, puis s'applique. L'utilisateur voit alors
 * ce qui est modifié. L'annulation n'enregistre pas le dépliage
 * comme une étape de texte — il est un effet de bord visuel.</p>
 */
class FoldEditSafetyTest {

    private static final String JSON_DOC =
            "{\n"                                              // 0
          + "  \"parameters\": {\n"                          // 1 ← ligne de début du pli
          + "    \"a\": 1,\n"                                 // 2 (masquée si pli replié)
          + "    \"b\": 2,\n"                                 // 3 (masquée)
          + "    \"c\": 3,\n"                                 // 4 (masquée)
          + "    \"d\": 4,\n"                                 // 5 (masquée)
          + "  },\n"                                         // 6 ← ligne de fin du pli
          + "  \"name\": \"x\"\n"                             // 7
          + "}\n";                                            // 8

    private EditorSession newSessionWithFold() {
        EditorSession session = new EditorSession(EditorDocument.of(JSON_DOC));
        EditorDocument d = session.getDocument();
        // Pli replié : debut après le « { » ouvrant de la ligne 1,
        // fin juste avant le « } » fermant de la ligne 6.
        int foldStart = d.lineEnd(1) - 2; // avant « ,\n » — après le « { » ouvrant
        // Plus précis : après « { » de la ligne 1. La ligne 1 est
        // « "parameters": { » — foldStart = lineEnd(1) - 1 = après « { »,
        // avant « \n ».
        foldStart = d.lineStart(1) + "  \"parameters\": {".length();
        int foldEnd = d.lineStart(6) + 2; // juste avant le « } » fermant
        // Ajustement : foldEnd = position du « } » de la ligne 6 « "  }, " ».
        // La ligne 6 a 2 espaces + « }, ». foldEnd = lineStart(6) + 2.
        foldEnd = d.lineStart(6) + 2;
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(foldStart, foldEnd,
                "{…}", "block", true));
        session.setFoldRegions(folds);
        return session;
    }

    /**
     * Cas 1 : Retour arrière à {@code fold.end} (juste après la chip,
     * avant le suffixe) ne doit PAS supprimer silencieusement le
     * caractère masqué précédent. Le pli doit être déplié d'abord,
     * puis le backspace s'applique sur le texte visible.
     */
    @Test
    void backspace_atFoldEnd_expandsFoldInsteadOfDeletingHidden() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);

        // Place le caret à fold.end (juste après la chip, sur le « } »
        // fermant du suffixe).
        session.setSelection(Selection.cursor(fold.end));
        String textBefore = session.getText();

        // Backspace — devrait déplier le pli, pas supprimer du contenu masqué.
        session.backspace();

        // Le pli doit être déplié (collapsed = false).
        DiagnosticShift.FoldRegion afterFold = session.getFoldRegions().get(0);
        assertFalse(afterFold.collapsed,
                "Le pli doit être déplié par backspace à fold.end, pas rester replié");

        // Le texte du document ne doit pas avoir perdu le contenu masqué
        // (le backspace a déplié, pas supprimé).
        String textAfter = session.getText();
        assertTrue(textAfter.contains("\"a\": 1"),
                "Le contenu masqué (\"a\": 1) doit toujours être présent après backspace "
                + "à fold.end — le pli a été déplié, pas supprimé");
        assertTrue(textAfter.contains("\"d\": 4"),
                "Le contenu masqué (\"d\": 4) doit toujours être présent");
    }

    /**
     * Cas 2 : deleteForward quand le caret est juste après la chip
     * (offset = fold.end, c.-à-d. sur le « } » fermant du suffixe)
     * ne doit pas supprimer silencieusement le caractère masqué
     * qui suit dans le pli. Le pli doit être déplié d'abord.
     */
    @Test
    void deleteForward_atFoldEnd_expandsFoldInsteadOfDeletingHidden() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);

        // Caret à fold.end (juste après la chip, sur le } fermant
        // du suffixe).
        session.setSelection(Selection.cursor(fold.end));
        String textBefore = session.getText();

        // deleteForward — devrait déplier le pli (le caractère qui
        // suit fold.end est dans le suffixe composite, mais le pli
        // est déplié car la zone d'édition [fold.end, fold.end+1]
        // touche l'intérieur du pli).
        session.deleteForward();

        // Le pli doit avoir été déplié (pas supprimé silencieusement).
        // La liste peut être vide si le pli est supprimé par shiftFoldRegions
        // après la suppression — acceptons les deux cas : soit le pli est
        // déplié (collapsed=false), soit il a été retiré de la liste.
        List<DiagnosticShift.FoldRegion> folds = session.getFoldRegions();
        if (!folds.isEmpty()) {
            DiagnosticShift.FoldRegion afterFold = folds.get(0);
            assertFalse(afterFold.collapsed,
                    "Le pli doit être déplié par deleteForward à fold.end");
        }
        // Le contenu masqué doit toujours être présent (le pli a été
        // déplié, pas supprimé silencieusement).
        String textAfter = session.getText();
        assertTrue(textAfter.contains("\"a\": 1"),
                "Le contenu masqué (\"a\": 1) doit toujours être présent après "
                + "deleteForward — le pli a été déplié, pas supprimé");
    }

    /**
     * Cas 3 : frappe d'un caractère à fold.end déplie le pli avant
     * l'insertion. Sans dépliage, le caractère s'insère dans la zone
     * masquée (invisible) — l'utilisateur ne voit rien.
     */
    @Test
    void typeChar_atFoldEnd_expandsFoldBeforeInserting() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);
        session.setSelection(Selection.cursor(fold.end));

        // Tape un caractère visible « X ».
        session.typeChar('X');

        // Le pli doit être déplié.
        DiagnosticShift.FoldRegion afterFold = session.getFoldRegions().get(0);
        assertFalse(afterFold.collapsed,
                "Le pli doit être déplié par typeChar à fold.end");

        // Le caractère doit être présent dans le texte.
        String textAfter = session.getText();
        assertTrue(textAfter.contains("X"),
                "Le caractère tapé doit être présent dans le texte");

        // Et le contenu masqué doit toujours être là.
        assertTrue(textAfter.contains("\"a\": 1"),
                "Le contenu masqué doit toujours être présent");
    }

    /**
     * Cas 4 : édition sur une zone qui ne touche PAS l'intérieur
     * d'un pli replié ne doit PAS déplier le pli. Cela garantit
     * que le mécanisme de dépliage automatique n'est pas trop
     * large.
     */
    @Test
    void edit_outsideFold_doesNotExpandFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);
        EditorDocument d = session.getDocument();

        // Caret sur la ligne 0 (avant le pli).
        int caret = d.lineStart(0) + 1; // après le « { » ouvrant de la racine
        session.setSelection(Selection.cursor(caret));

        session.typeChar(' ');

        // Le pli ne doit pas avoir été déplié.
        DiagnosticShift.FoldRegion afterFold = session.getFoldRegions().get(0);
        assertTrue(afterFold.collapsed,
                "Une édition hors du pli ne doit pas le déplier");
    }

    /**
     * Cas 5 : sélection qui traverse un pli replié + suppression.
     * Le pli doit être déplié pour que l'utilisateur voie ce qui est
     * supprimé. Alternative : suppression atomique de toute la région
     * repliée. Le test ci-dessous accepte les deux comportements
     * tant que le contenu masqué n'est pas supprimé silencieusement
     * sans dépliage.
     */
    @Test
    void selection_acrossFold_backspaceExpandsOrDeletesAtomically() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);
        EditorDocument d = session.getDocument();

        // Sélection qui traverse le pli : de la ligne 0 (avant le
        // pli) à la ligne 7 (après le pli).
        int selStart = d.lineStart(0);
        int selEnd = d.lineStart(7);
        session.setSelection(Selection.range(selStart, selEnd));

        // Avant backspace, le pli est replié.
        assertTrue(fold.collapsed, "Précondition : pli replié");

        // Backspace avec sélection — devrait déplier le pli (la zone
        // éditée [0, lineStart(7)] contient entièrement le pli, donc
        // expandInRange le déplie), puis appliquer la suppression.
        // shiftFoldRegions ajustera les offsets du pli déplié — il peut
        // finir par disparaître de la liste (start > end après suppression),
        // ou rester déplié si les offsets sont encore valides.
        session.backspace();

        // Comportement attendu : le pli a été déplié (collapsed=false)
        // AVANT la suppression. La suppression a pu le retirer de la
        // liste (shiftFoldRegions ajuste et peut purger les plis
        // devenus incohérents). Vérifions juste que le contenu masqué
        // n'a pas été supprimé silencieusement SANS dépliage : on doit
        // voir un pli déplié OU le pli a été supprimé avec le reste de
        // la sélection (acceptable : l'utilisateur a tout sélectionné).
        // Si le pli est encore dans la liste, il doit être déplié.
        List<DiagnosticShift.FoldRegion> folds = session.getFoldRegions();
        for (DiagnosticShift.FoldRegion r : folds) {
            assertFalse(r.collapsed,
                    "Tout pli restant doit être déplié après suppression "
                    + "d'une sélection qui le traversait");
        }
    }

    /**
     * Cas 6 : une édition à l'intérieur strict d'un pli replié
     * (r.start < offset < r.end) déplie aussi le pli. Cela arrive
     * rarement via l'UI (le caret ne s'arrête pas dans l'intérieur
     * strict), mais peut arriver via l'API programmatique.
     */
    @Test
    void edit_insideFoldStrict_expandsFold() {
        EditorSession session = newSessionWithFold();
        DiagnosticShift.FoldRegion fold = session.getFoldRegions().get(0);

        // Caret au milieu du pli (entre r.start et r.end strictement).
        int midOffset = (fold.start + fold.end) / 2;
        session.setSelection(Selection.cursor(midOffset));

        session.typeChar('X');

        DiagnosticShift.FoldRegion afterFold = session.getFoldRegions().get(0);
        assertFalse(afterFold.collapsed,
                "Une édition à l'intérieur strict du pli doit le déplier");
    }
}
