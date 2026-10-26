package jo.codeeditor.snippet;

import java.util.Objects;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Le contexte de résolution des macros de gabarits — les valeurs que
 * l'hôte fournit pour matérialiser {@code $FILE$}, {@code $DATE$} et
 * {@code $USER$} à l'expansion d'un snippet.
 *
 * <p>Sans contexte (ou champ non renseigné), la macro est conservée
 * LITTÉRALEMENT dans le texte inséré — jamais effacée silencieusement :
 * un hôte qui oublie de poser le contexte voit « {@code $FILE$} »
 * apparaître dans le document au lieu d'un nom de fichier fantôme.</p>
 *
 * <ul>
 *   <li>{@code $FILE$} — le nom du fichier en cours d'édition (l'hôte
 *       choisit le nom court ou le chemin complet) ;</li>
 *   <li>{@code $USER$} — le nom de l'auteur (compte du système,
 *       profil de l'application…) ;</li>
 *   <li>{@code $DATE$} — la date du jour, résolue À L'EXPANSION (pas à
 *       la construction du contexte) via un fournisseur : les tests
 *       injectent une valeur fixe, l'hôte peut imposer son format.</li>
 * </ul>
 *
 * <p>Immuable ; le fournisseur de date est appelé une fois par macro
 * rencontrée, sur le fil qui exécute l'expansion.</p>
 *
 * @since 4.5.0
 */
public final class SnippetMacroContext {

    private final String fileName;
    private final String userName;
    private final java.util.function.Supplier<String> dateText;

    /**
     * Un contexte complet.
     *
     * @param fileName le nom du fichier (null = macro conservée littéralement)
     * @param userName le nom de l'auteur (null = littéral)
     * @param dateText le fournisseur de date (null = littéral)
     */
    public SnippetMacroContext(String fileName, String userName,
                               java.util.function.Supplier<String> dateText) {
        this.fileName = fileName;
        this.userName = userName;
        this.dateText = dateText;
    }

    /**
     * Un contexte minimal nom de fichier + auteur ; la date du jour est
     * résolue au format ISO-8601 étendu (« {@code 2026-10-27} »).
     *
     * @param fileName le nom du fichier
     * @param userName le nom de l'auteur
     * @return le contexte correspondant
     */
    public static SnippetMacroContext of(String fileName, String userName) {
        return new SnippetMacroContext(fileName, userName,
                () -> LocalDateTime.now()
                        .format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    /** Le nom du fichier — null si non renseigné (macro littérale). */
    public String fileName() {
        return fileName;
    }

    /** Le nom de l'auteur — null si non renseigné (macro littérale). */
    public String userName() {
        return userName;
    }

    /**
     * Résout la macro date à l'instant de l'appel — null si aucun
     * fournisseur n'a été posé (macro littérale). Le résultat null
     * d'un fournisseur est traité comme non renseigné.
     */
    String resolveDate() {
        if (dateText == null) return null;
        try {
            return dateText.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SnippetMacroContext)) return false;
        SnippetMacroContext that = (SnippetMacroContext) o;
        return Objects.equals(fileName, that.fileName)
                && Objects.equals(userName, that.userName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, userName);
    }
}
