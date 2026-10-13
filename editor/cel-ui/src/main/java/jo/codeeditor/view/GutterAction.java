package jo.codeeditor.view;

import java.util.function.BooleanSupplier;

import jo.codeeditor.view.icons.EditorIcons;

/**
 * Une action de <b>gouttière injectée par l'hôte</b> (B15, P2-2) — ancrée
 * à une ligne du document, façon Android Studio : point d'arrêt, « exécuter
 * ce test », flèche de navigation de diff…
 *
 * <p>Le registre vit sur {@link EditorView#gutterActions}
 * ({@link EditorGutterActions#add(GutterAction)}) :</p>
 *
 * <ul>
 *   <li>l'action se dessine en PILL dans la zone des numéros de la ligne
 *       ancrée (géométrie B9c de l'œil d'aperçu — remplace le numéro,
 *       comme le run ▶ d'Android Studio) ;</li>
 *   <li>une seule action visible PAR LIGNE : {@code add} retire toute
 *       action de même {@code id} OU posée sur la même ligne — la
 *       dernière inscription gagne ;</li>
 *   <li>{@link #active} est un fournisseur <b>relu au rendu</b> : l'hôte
 *       change l'état (point d'arrêt armé/désarmé) puis appelle
 *       {@code EditorView.invalidate()} — sans ré-enregistrement ;</li>
 *   <li>un tap exécute {@link #perform} et n'appartient PAS au texte :
 *       le caret n'est pas déplacé et le tap ne tombe jamais dans la
 *       cascade multi-tap (précédent B9c / bande de fold) ;</li>
 *   <li>les lignes au-delà du document ne sont ni dessinées ni
 *       touchables (pas de pill fantôme) ; les actions survivent aux
 *       changements de {@code setSession} — données d'hôte, c'est à
 *       l'hôte de les retirer quand il change de fichier (la démo vide
 *       le registre à chaque échantillon).</li>
 * </ul>
 *
 * <p>CodeIDE est le premier consommateur naturel : le débogueur pose
 * ses points d'arrêt sans toucher au renderer de l'éditeur.</p>
 *
 * @author jjoblab
 */
public final class GutterAction {

    /** Identifiant stable de l'action (retrait ciblé éventuel). */
    public final String id;
    /** Ligne de document ancrée (0-based, clampée à 0 si négative). */
    public final int line;
    /** Glyphe du moteur d'icônes (null retombe sur l'œil — jamais de crash de rendu). */
    public final EditorIcons.Icon icon;
    /** Exécuté au tap (thread UI) — facultatif : le geste reste consommé. */
    public final Runnable perform;
    /** Facultatif : {@code true} → icône allumée à l'accent (pill voilée).
     *  ReLU À CHAQUE RENDU — fournisseur vivant, null = sobre. */
    public final BooleanSupplier active;

    public GutterAction(String id, int line, EditorIcons.Icon icon, Runnable perform) {
        this(id, line, icon, perform, null);
    }

    public GutterAction(String id, int line, EditorIcons.Icon icon,
            Runnable perform, BooleanSupplier active) {
        this.id = id != null ? id : "";
        this.line = Math.max(0, line);
        this.icon = icon != null ? icon : EditorIcons.Icon.EYE;
        this.perform = perform;
        this.active = active;
    }

    /** @return {@code true} si le fournisseur d'état est défini et actif
     *         (null = état sobre). */
    public boolean isActive() {
        return active != null && active.getAsBoolean();
    }

    @Override
    public String toString() {
        return "GutterAction(\"" + id + "\", line=" + line + ")";
    }
}
