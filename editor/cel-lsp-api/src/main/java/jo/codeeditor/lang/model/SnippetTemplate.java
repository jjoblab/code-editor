package jo.codeeditor.lang.model;

import java.util.Objects;

/**
 * Un gabarit utilisateur de complétion — un snippet proposé par son
 * libellé et inséré par son corps EXPANSÉ.
 *
 * <p>Le corps suit la syntaxe des snippets de la bibliothèque :
 * champs {@code $1}, {@code ${1:placeholder}}, {@code $0} (arrêt
 * final), miroirs par index répété, variables de choix « {@code
 * ${1|alpha,beta|}} » (champ initialisé à la première option, les
 * autres proposées dans le popup de choix) et macros {@code $FILE$},
 * {@code $DATE$}, {@code $USER$} résolues à l'expansion depuis le
 * contexte posé par l'hôte (sans contexte, la macro reste
 * littérale).</p>
 *
 * <p>Immuable — le stockage (préférences, base de données, fichiers
 * de gabarits…) appartient à l'hôte qui implémente le provider ; ce
 * modèle ne fait que transporter libellé et corps.</p>
 *
 * @since 4.5.0
 */
public final class SnippetTemplate {

    /** Le libellé proposé dans le popup (jamais vide). */
    public final String label;
    /** Le corps inséré et expansé à la sélection (jamais null). */
    public final String body;
    /** Un détail optionnel affiché sous le libellé (null = aucun). */
    public final String detail;

    /**
     * Un gabarit utilisateur.
     *
     * @param label  le libellé proposé (non vide)
     * @param body   le corps à expansion (non null)
     * @param detail le détail optionnel (null = aucun)
     */
    public SnippetTemplate(String label, String body, String detail) {
        this.label = Objects.requireNonNull(label, "label");
        this.body = Objects.requireNonNull(body, "body");
        this.detail = detail;
    }

    /** Un gabarit sans détail. */
    public SnippetTemplate(String label, String body) {
        this(label, body, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SnippetTemplate)) return false;
        SnippetTemplate that = (SnippetTemplate) o;
        return label.equals(that.label) && body.equals(that.body)
                && Objects.equals(detail, that.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, body, detail);
    }

    @Override
    public String toString() {
        return "SnippetTemplate(" + label + ")";
    }
}
