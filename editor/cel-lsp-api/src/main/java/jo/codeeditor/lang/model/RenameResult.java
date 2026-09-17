package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.RenameProvider;

import java.util.Collections;
import java.util.List;

/**
 * Le résultat d'une opération de renommage. Retourné par
 * {@link RenameProvider}.
 */
public final class RenameResult {

    /**
     * Les éditions de texte à appliquer (triées par offset décroissant
     * pour ne pas décaler les offsets restants lors de l'application).
     */
    public final List<TextEdit> edits;

    public RenameResult(List<TextEdit> edits) {
        this.edits = edits != null ? Collections.unmodifiableList(edits) : Collections.emptyList();
    }
}
