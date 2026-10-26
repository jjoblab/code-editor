package jo.codeeditor.lang.provider;

import java.util.List;

import jo.codeeditor.lang.model.SnippetTemplate;

/**
 * Fournit les gabarits utilisateur de complétion (snippets
 * enregistrés par l'utilisateur de l'application hôte).
 *
 * <p>Contrat : le STOCKAGE appartient à l'hôte (préférences, base de
 * données, fichiers…) — le provider n'est interrogé que pour LIRE la
 * liste courante du langage. {@link #snippets(String)} est appelée
 * sur le thread UI à chaque requête de complétion : elle doit être
 * immédiate (une lecture de cache, pas un accès disque).</p>
 *
 * <p>Les gabarits remontés sont proposés dans le popup avec les
 * candidats du serveur et les gabarits déclarés par le profil de
 * langage, filtrés par préfixe comme les autres ; à la sélection, le
 * corps est EXPANSÉ (champs {@code $1}/${1:placeholder}/$0, choix
 * « {@code ${1|a,b,c|}} », macros {@code $FILE$}/{@code $DATE$}/
 * {@code $USER$} du contexte posé par l'hôte).</p>
 *
 * @since 4.5.0
 */
public interface SnippetProvider {

    /**
     * Les gabarits utilisateur du langage donné.
     *
     * @param language le nom canonique du langage (« java », « xml »…)
     * @return la liste des gabarits (jamais null ; vide si l'utilisateur
     *         n'en a pas enregistré)
     */
    List<SnippetTemplate> snippets(String language);
}
