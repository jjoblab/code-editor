package jo.codeeditor.view.popup.kit;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.completion.SignatureHelpController;
import jo.codeeditor.doc.QuickDoc;
import jo.codeeditor.session.EditorSession;

/**
 * Sections structurées de la documentation de l'aide de signature
 * (phase 4) : PARAMÈTRE ACTIF (nom + type scindés du libellé,
 * description), RÉSUMÉ (≤ 2 lignes), ligne RETOURNE dédiée, et
 * sections secondaires réservées à la vue peek. Le plafond commun
 * ({@code maxLignes}) borne les lignes de CONTENU.
 *
 * <p>Calcul PUR (mesure injectée) : la géométrie reste au painter,
 * les listes ici — testable sans Canvas.</p>
 */
public final class SignatureDocSections {

    /** Nom du paramètre actif (partie après le type du libellé). */
    public final String nomParamètre;
    /** Type du paramètre actif (premier mot du libellé, vide sinon). */
    public final String typeParamètre;
    /** Description wrappée du paramètre actif. */
    public final List<String> lignesParamètre;
    /** Résumé wrappé (≤ maxRésumé lignes). */
    public final List<String> lignesRésumé;
    /** Ligne RETOURNE dédiée (une ligne, vide si absente). */
    public final String ligneRetour;
    /** Sections secondaires (throws / see / depuis…) — vue peek. */
    public final List<String> lignesExtra;

    private SignatureDocSections(String nomParamètre, String typeParamètre,
            List<String> lignesParamètre, List<String> lignesRésumé,
            String ligneRetour, List<String> lignesExtra) {
        this.nomParamètre = nomParamètre;
        this.typeParamètre = typeParamètre;
        this.lignesParamètre = lignesParamètre;
        this.lignesRésumé = lignesRésumé;
        this.ligneRetour = ligneRetour;
        this.lignesExtra = lignesExtra;
    }

    /** Sections vides (aucune documentation). */
    public static SignatureDocSections vide() {
        return new SignatureDocSections("", "", new ArrayList<>(),
                new ArrayList<>(), "", new ArrayList<>());
    }

    /**
     * Calcule les sections de la documentation d'une signature : le
     * paramètre ACTIF d'abord (scission type / nom du libellé,
     * description wrappée), puis le RÉSUMÉ (≤ maxRésumé), la ligne
     * RETOUR dédiée, et les sections secondaires en peek seulement —
     * modèle de rendu unifié ({@link DocRenderModel}), langue de la
     * session.
     *
     * @param paramètre le paramètre ACTIF (peut être null — pas de section)
     * @param documentation la javadoc brute de la signature (peut être null)
     * @param session la session (porte la langue d'analyse)
     * @param docWidth la largeur de wrap des lignes (px)
     * @param measurer la mesure de texte injectée
     * @param maxLignes le plafond commun de lignes de contenu
     * @param maxRésumé le plafond du résumé (2, maquette)
     * @param peek vrai = les sections secondaires sont conservées
     */
    public static SignatureDocSections of(
            SignatureHelpController.Parameter paramètre,
            String documentation, EditorSession session, float docWidth,
            TextLayoutKit.Measurer measurer, int maxLignes,
            int maxRésumé, boolean peek) {
        String nom = "";
        String type = "";
        List<String> lignesParam = new ArrayList<>();
        List<String> lignesRésumé = new ArrayList<>();
        String retour = "";
        List<String> extra = new ArrayList<>();
        int lignes = 0;

        // ── Paramètre actif : scission type / nom du libellé
        // (« String pattern » → type String, nom pattern).
        if (paramètre != null) {
            String label = paramètre.label == null ? "" : paramètre.label.trim();
            int sep = label.indexOf(' ');
            if (sep > 0) {
                type = label.substring(0, sep);
                nom = label.substring(sep + 1).trim();
            } else {
                nom = label;
            }
            String doc = paramètre.documentation == null ? "" : paramètre.documentation;
            if (!doc.isEmpty()) {
                for (String w : TextLayoutKit.wrap(doc, docWidth, measurer)) {
                    if (lignes >= maxLignes) break;
                    lignesParam.add(w);
                    lignes++;
                }
            }
        }

        // ── Documentation de la signature (modèle unifié, langue de
        // session) : SUMMARY et RETURNS dédiés, le reste en peek.
        if (documentation != null && !documentation.isEmpty()) {
            for (DocRenderModel.Block b : DocRenderModel.blocks(
                    DocRenderModel.parse(documentation, session))) {
                if (lignes >= maxLignes) break;
                if (b.kind == DocRenderModel.KIND_SUMMARY) {
                    for (String w : TextLayoutKit.wrap(b.text, docWidth, measurer)) {
                        if (lignesRésumé.size() >= maxRésumé || lignes >= maxLignes) break;
                        lignesRésumé.add(w);
                        lignes++;
                    }
                } else if (b.kind == DocRenderModel.KIND_RETURNS) {
                    if (retour.isEmpty() && lignes < maxLignes) {
                        retour = b.text;
                        lignes++;
                    }
                } else if (peek) {
                    for (String w : TextLayoutKit.wrap(b.ligne(), docWidth, measurer)) {
                        if (lignes >= maxLignes) break;
                        extra.add(w);
                        lignes++;
                    }
                }
            }
        }
        return new SignatureDocSections(nom, type, lignesParam, lignesRésumé,
                retour, extra);
    }

    /** Le nombre de sections présentes (paramètre / résumé / retour / extra). */
    public int nbSections() {
        return (nomParamètre.isEmpty() && lignesParamètre.isEmpty() ? 0 : 1)
                + (lignesRésumé.isEmpty() ? 0 : 1)
                + (ligneRetour.isEmpty() ? 0 : 1)
                + (lignesExtra.isEmpty() ? 0 : 1);
    }

    /** Y a-t-il un paramètre actif (section paramètre présente) ? */
    public boolean aParamètre() {
        return !nomParamètre.isEmpty() || !typeParamètre.isEmpty()
                || !lignesParamètre.isEmpty();
    }
}
