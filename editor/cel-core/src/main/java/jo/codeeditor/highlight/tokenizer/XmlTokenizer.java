package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur XML/HTML — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 13) : la grammaire (balises au nom qualifié éclaté,
 * attributs avec valeur STRING, commentaires, CDATA et instructions de
 * traitement, entités à fenêtre de 12, états inter-lignes XML_TAG et
 * XML_STRING) vit désormais dans le template déclaratif
 * {@link TokenizerTemplate#XML}, exécuté par {@link GenericTokenizer}
 * en <b>mode balisage</b> — la quatrième saveur du moteur. Le
 * dispatcher ({@code SyntaxHighlighter}) continue d'appeler cette
 * entrée — signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code XmlTokenizerTest}).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class XmlTokenizer {

    /**
     * Tokénise une ligne XML — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#XML}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>balises {@code <tag>}/{@code </tag>}/{@code <tag attr="v">},
     *       noms qualifiés éclatés ({@code android:text} → NAMESPACE +
     *       PUNCT + PROPERTY)</li>
     *   <li>commentaires {@code <!-- -->} (état inter-lignes)</li>
     *   <li>CDATA (état dédié, contenu non re-lexé)</li>
     *   <li>instructions de traitement {@code <?xml ?>} (sans état)</li>
     *   <li>valeurs d'attribut à échappements apparents, l'orpheline
     *       déborde en XML_STRING puis la balise se referme</li>
     *   <li>entités {@code &amp;} à fenêtre de 12 caractères</li>
     *   <li>balises multi-lignes : XML_TAG porte la liste d'attributs</li>
     * </ul></p>
     */
    public static StyledLine styleXml(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.XML, line, entryState);
    }

    private XmlTokenizer() {}
}
