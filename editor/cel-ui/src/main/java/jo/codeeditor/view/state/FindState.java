package jo.codeeditor.view.state;

import androidx.annotation.RestrictTo;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.find.Match;

/**
 * État de rendu des surlignages de recherche — objet d'état extrait
 * d'EditorView (plan 01 « Décomposer EditorView », étape 3).
 *
 * <p>Détient ce qu'EditorView exposait comme champs publics : la liste
 * des occurrences à teinter dans le viewport
 * ({@code theme.findMatch}) et l'index de l'occurrence « courante »
 * ({@code theme.findCurrent}). Le contrôleur de recherche pousse
 * l'état via {@code EditorView.setFindHighlights} ; les painters le
 * lisent pendant {@code onDraw}.</p>
 *
 * <p><b>Propriété de la voie (4.4.0)</b> : la voie est une ressource
 * partagée. Un drapeau {@link #searchOwned} marque la session de
 * recherche OUVERTE (contrôleur), un drapeau {@link #manualOwned}
 * marque un propriétaire manuel (hôte ayant poussé ses propres
 * surlignages non vides). Le producteur interne « occurrences du mot
 * sous le caret » pousse SANS revendiquer la propriété et s'efface
 * devant ces deux drapeaux — la recherche et l'hôte gardent la main
 * sur leurs surlignages.</p>
 *
 * <p><b>Invariant possédé</b> : l'index courant est TOUJOURS dans
 * {@code [-1, highlights.size() - 1]} — un index hors bornes transmis
 * par un hôte est ramené à la dernière occurrence (jamais de teinte
 * « courante » fantôme sur un index qui n'existe pas, jamais
 * d'IndexOutOfBoundsException côté rendu).</p>
 *
 * <p><b>Thread-safety</b> : mutabilité confinée au thread UI
 * (publication) ; lecture seule pendant le rendu. L'état décrit le
 * document COURANT — un changement de document doit passer par
 * {@link #clear()} (les offsets de l'ancien document sont périmés).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class FindState {

    /** Occurrences à teinter dans le viewport (ordre du document). */
    public final List<Match> highlights = new ArrayList<>();

    /**
     * Index de l'occurrence courante dans {@link #highlights},
     * -1 si aucune. Toujours dans les bornes de la liste.
     */
    public int currentIndex = -1;

    /**
     * (4.4.0) La voie est possédée par une session de recherche
     * OUVERTE — posé par le contrôleur de recherche (refresh), retiré à
     * sa fermeture. Le producteur interne « occurrences du mot » cède
     * le pas tant que ce drapeau est levé.
     */
    public boolean searchOwned;

    /**
     * (4.4.0) La voie est possédée par un hôte : un appel non vide de
     * {@code EditorView.setFindHighlights} revendique la voie jusqu'au
     * prochain appel VIDE (liste nulle ou vide) qui la rend.
     */
    public boolean manualOwned;

    /**
     * Remplace tout l'état (voie {@code EditorView.setFindHighlights} —
     * poussée par le contrôleur de recherche). La liste est COPIÉE :
     * l'appelant peut la muter ensuite sans corrompre le rendu.
     *
     * @param requestedIndex index souhaité de l'occurrence courante ;
     *        ramené à la dernière occurrence s'il dépasse les bornes,
     *        à -1 si la liste est vide ou l'index négatif
     */
    public void set(List<Match> matches, int requestedIndex) {
        set(matches, requestedIndex, true);
    }

    /**
     * Variante interne (4.4.0) : {@code claimOwnership = false} pousse
     * l'état SANS revendiquer la voie — le producteur « occurrences du
     * mot sous le caret » s'efface ainsi devant la recherche et l'hôte.
     * Une poussée non vide AVEC revendication lève {@link #manualOwned}
     * (l'hôte prend la main) ; une poussée vide le retire (la voie est
     * rendue).
     */
    public void set(List<Match> matches, int requestedIndex, boolean claimOwnership) {
        highlights.clear();
        if (matches != null) highlights.addAll(matches);
        currentIndex = clampIndex(requestedIndex);
        if (claimOwnership && !highlights.isEmpty()) {
            manualOwned = true;
        } else if (highlights.isEmpty()) {
            manualOwned = false;
        }
    }

    /** Purge : plus aucune occurrence, aucune courante, voie rendue. */
    public void clear() {
        highlights.clear();
        currentIndex = -1;
        manualOwned = false;
        searchOwned = false;
    }

    /** True si rien à dessiner. */
    public boolean isEmpty() {
        return highlights.isEmpty();
    }

    private int clampIndex(int index) {
        if (highlights.isEmpty() || index < 0) {
            return -1;
        }
        return Math.min(index, highlights.size() - 1);
    }
}
