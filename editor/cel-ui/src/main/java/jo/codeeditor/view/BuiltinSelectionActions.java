package jo.codeeditor.view;

import android.content.Intent;

import java.util.Locale;
import java.util.function.BooleanSupplier;

import jo.codeeditor.document.Selection;
import jo.codeeditor.edit.CommentSyntax;
import jo.codeeditor.util.EditorLog;

/**
 * Actions intégrées <b>socle</b> de la pill de sélection (T2, lot 2),
 * livrées via le MÊME SPI que les actions d'hôte (T1) :
 * {@link EditorView#addSelectionAction(SelectionAction)} — l'hôte
 * décide lesquelles installer (et peut les retirer par identifiant).
 *
 * <ul>
 *   <li><b>Duplicate</b> — duplique la sélection juste après elle-même
 *       ({@code EditorSession.duplicateSelection()}, une étape
 *       d'undo) ;</li>
 *   <li><b>Comment</b> — bascule les commentaires de LIGNE des lignes
 *       de la sélection, avec la syntaxe du langage courant
 *       ({@code EditorSession.toggleLineComment()} — {@code //},
 *       {@code #}, {@code --}, encadrement XML… selon
 *       {@link CommentSyntax#forLanguage}) ; désactivée (estompée)
 *       pour un langage sans aucune syntaxe de commentaire (JSON) ;</li>
 *   <li><b>Convert case</b> — UPPER / lower / Toggle (inversion
 *       caractère par caractère), moteur pur
 *       {@link #convertCase(String, CaseMode)} ; la sélection est
 *       ré-épinglée sur le texte transformé (aller-retour possible) ;</li>
 *   <li><b>Share</b> — Intent système {@code ACTION_SEND} sur le texte
 *       sélectionné. <b>Opt-in</b> : une bibliothèque ne doit pas
 *       forcer une dépendance d'interaction système — l'action
 *       n'existe que si l'hôte l'injecte.</li>
 * </ul>
 *
 * <p>Chaque {@code perform} respecte le contrat de la pill : exécuté
 * sur le thread UI, referme la pill et masque les poignées (c'est le
 * dispatcher du tap qui le fait, voir
 * {@code EditorSelectionGestures.handleSelectionToolbarTap}), puis
 * notifie la vue du changement de texte ({@code onTextChanged} pour
 * la re-surlignage et l'annulation des états différés).</p>
 *
 * <pre>{@code
 * view.addSelectionAction(BuiltinSelectionActions.duplicate(view));
 * view.addSelectionAction(BuiltinSelectionActions.toggleComment(view));
 * view.addSelectionAction(BuiltinSelectionActions.convertCase(
 *         view, BuiltinSelectionActions.CaseMode.TOGGLE));
 * view.addSelectionAction(BuiltinSelectionActions.share(view));
 * }</pre>
 *
 * @author jo@Dev
 * @since 3.55.0
 */
public final class BuiltinSelectionActions {

    /** Mode de conversion de casse (moteur pur). */
    public enum CaseMode {
        /** Tout en MAJUSCULES ({@code Locale.ROOT}). */
        UPPER,
        /** Tout en minuscules ({@code Locale.ROOT}). */
        LOWER,
        /** Inversion caractère par caractère (parité « Toggle Case » IntelliJ). */
        TOGGLE
    }

    private BuiltinSelectionActions() {}

    /**
     * Action « Duplicate » — duplique la sélection (une étape d'undo),
     * moteur {@code EditorSession.duplicateSelection()}.
     */
    public static SelectionAction duplicate(EditorView view) {
        return new SelectionAction("builtin.duplicate",
                view.getContext().getString(jo.codeeditor.ui.R.string.cel_builtin_duplicate),
                () -> {
                    if (view.session == null) return;
                    view.session.duplicateSelection();
                    view.onTextChanged();
                },
                sessionAlive(view));
    }

    /**
     * Action « Move up » — déplace le BLOC de lignes de la sélection
     * d'une ligne vers le haut (P2 « mouvements de lignes », lot 2
     * J6), moteur {@link EditorSession#moveSelectedLines(boolean)} :
     * sélection multi-lignes → bloc entier, caret → sa ligne,
     * sélection ré-épinglée sur le bloc (aller-retour stable),
     * estompée en première ligne du document (no-op visuelle).
     */
    public static SelectionAction moveLinesUp(EditorView view) {
        return new SelectionAction("builtin.move.up",
                view.getContext().getString(jo.codeeditor.ui.R.string.cel_builtin_move_up),
                () -> {
                    if (view.session == null) return;
                    view.session.moveSelectedLines(false);
                    view.onTextChanged();
                },
                canMoveLines(view, false));
    }

    /**
     * Action « Move down » — même moteur, une ligne vers le bas ;
     * estompée en dernière ligne du document.
     */
    public static SelectionAction moveLinesDown(EditorView view) {
        return new SelectionAction("builtin.move.down",
                view.getContext().getString(jo.codeeditor.ui.R.string.cel_builtin_move_down),
                () -> {
                    if (view.session == null) return;
                    view.session.moveSelectedLines(true);
                    view.onTextChanged();
                },
                canMoveLines(view, true));
    }

    /**
     * Action « Comment » — bascule les commentaires de ligne de la
     * sélection, avec la syntaxe du langage courant (préfixe de ligne,
     * encadrement de bloc en repli XML/HTML/Markdown). Désactivée pour
     * un langage sans syntaxe de commentaire (JSON) : l'item est
     * estompé et son tap rejeté sans refermer la pill.
     */
    public static SelectionAction toggleComment(EditorView view) {
        return new SelectionAction("builtin.comment",
                view.getContext().getString(jo.codeeditor.ui.R.string.cel_builtin_comment),
                () -> {
                    if (view.session == null) return;
                    view.session.toggleLineComment();
                    view.onTextChanged();
                },
                commentable(view));
    }

    /**
     * Action de conversion de casse — UPPER / lower / TOGGLE sur la
     * sélection (moteur {@link #convertCase(String, CaseMode)}). La
     * sélection est ré-épinglée sur le texte transformé pour permettre
     * l'aller-retour (le pattern du commenteur de lignes).
     */
    public static SelectionAction convertCase(EditorView view, CaseMode mode) {
        String id;
        String label;
        // T3 : libellés canoniques en ressources (défauts anglais
        // identiques — la casse affichée fait partie du libellé).
        switch (mode) {
            case UPPER:
                id = "builtin.case.upper";
                label = view.getContext().getString(
                        jo.codeeditor.ui.R.string.cel_case_upper);
                break;
            case LOWER:
                id = "builtin.case.lower";
                label = view.getContext().getString(
                        jo.codeeditor.ui.R.string.cel_case_lower);
                break;
            default:
                id = "builtin.case.toggle";
                label = view.getContext().getString(
                        jo.codeeditor.ui.R.string.cel_case_toggle);
                break;
        }
        return new SelectionAction(id, label,
                () -> {
                    if (view.session == null) return;
                    String part = view.session.selectedText();
                    if (part == null || part.isEmpty()) return;
                    String converted = convertCase(part, mode);
                    if (converted.equals(part)) return;
                    Selection sel = view.session.getSelection();
                    view.session.replaceRange(sel.start, sel.end, converted);
                    view.session.setSelection(Selection.range(
                            sel.start, sel.start + converted.length()));
                    view.onTextChanged();
                },
                selectionAlive(view));
    }

    /**
     * Action « Share » (OPT-IN) — Intent système {@code ACTION_SEND}
     * ({@code text/plain}) sur le texte sélectionné, derrière un
     * sélecteur ({@code Intent.createChooser}). Une bibliothèque ne
     * force pas d'interaction système : l'action n'est installée que
     * si l'hôte l'injecte explicitement. L'échec de résolution
     * (aucune application cible, contexte sans activité) est
     * journalisé sans crash.
     */
    public static SelectionAction share(EditorView view) {
        return new SelectionAction("builtin.share",
                view.getContext().getString(jo.codeeditor.ui.R.string.cel_builtin_share),
                () -> {
                    if (view.session == null) return;
                    String text = view.session.selectedText();
                    if (text == null || text.isEmpty()) return;
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_TEXT, text);
                        Intent chooser = Intent.createChooser(send,
                                view.getContext().getString(
                                        jo.codeeditor.ui.R.string.cel_builtin_share));
                        // Le contexte d'une vue peut ne pas être une
                        // Activity (SurfaceView, preview, hôte applicatif)
                        // : NEW_TASK est exigé par Android sur l'intent
                        // LANCÉ (le chooser) dans ce cas, et sans dommage
                        // depuis une Activity réelle.
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        view.getContext().startActivity(chooser);
                    } catch (Exception e) {
                        // Aucun handler ACTION_SEND ou contexte hôte
                        // sans activité : journalisé, pas de crash —
                        // le texte reste sélectionné, la pill refermée.
                        EditorLog.warn("BuiltinActions",
                                "partage du texte sélectionné impossible", e);
                    }
                },
                selectionAlive(view));
    }

    /**
     * Moteur pur de conversion de casse — UPPER / LOWER via
     * {@code Locale.ROOT} (déterministe hors locale système, pas de
     * surprise du i turc), TOGGLE inverse chaque lettre (les
     * non-lettres sont inchangés).
     *
     * @return le texte converti (jamais null ; l'entrée null retourne "")
     */
    public static String convertCase(String text, CaseMode mode) {
        if (text == null) return "";
        switch (mode) {
            case UPPER:  return text.toUpperCase(Locale.ROOT);
            case LOWER:  return text.toLowerCase(Locale.ROOT);
            default: {
                StringBuilder sb = new StringBuilder(text.length());
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    sb.append(Character.isUpperCase(c)
                            ? Character.toLowerCase(c)
                            : Character.toUpperCase(c));
                }
                return sb.toString();
            }
        }
    }

    // ── Fournisseurs d'activation ─────────────────────────────────

    /** Activée dès qu'une session vit sur la vue. */
    private static BooleanSupplier sessionAlive(EditorView view) {
        return () -> view.session != null;
    }

    /** Activée quand la sélection est une vraie plage (non-caret). */
    private static BooleanSupplier selectionAlive(EditorView view) {
        return () -> view.session != null
                && !view.session.getSelection().isCursor();
    }

    /** Activée quand le bloc de lignes de la sélection PEUT bouger
     *  dans cette direction (estompée aux bords du document). */
    private static BooleanSupplier canMoveLines(EditorView view, boolean down) {
        return () -> {
            if (view.session == null) return false;
            jo.codeeditor.document.Selection sel =
                    view.session.getSelection();
            jo.codeeditor.document.EditorDocument doc =
                    view.session.getDocument();
            int startLine = doc.lineForOffset(sel.start);
            int endLine = doc.lineForOffset(sel.end);
            return down ? endLine < doc.lineCount() - 1 : startLine > 0;
        };
    }

    /** Activée quand le langage courant a une syntaxe de commentaire. */
    private static BooleanSupplier commentable(EditorView view) {
        return () -> {
            if (view.session == null) return false;
            CommentSyntax syntax = CommentSyntax.forLanguage(
                    view.session.getLanguage());
            return syntax.hasLine() || syntax.hasBlock();
        };
    }
}
