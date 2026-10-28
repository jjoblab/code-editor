package jo.codeeditor.demo.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Les ACTIONS DE REFACTORAGE du mini Java de la démo — entourer,
 * envelopper, transtyper, sur la sélection.
 *
 * <p>Trois familles servies selon la NATURE de la plage visée (le
 * client envoie la sélection active, sinon le mot sous le caret,
 * sinon la ligne) :</p>
 * <ul>
 *   <li><b>Entourer</b> (surround) — une plage de LIGNES COMPLÈTES
 *       (sélection multi-lignes, ou couvrant la ligne entière du
 *       premier au dernier caractère utile) est indentée d'un niveau
 *       et enveloppée : {@code if}, {@code if/else}, {@code for},
 *       {@code while}, {@code try/catch}, {@code try/finally} ;</li>
 *   <li><b>Envelopper</b> (wrap) — une sélection partielle de ligne
 *       devient l'argument d'une enveloppe : parenthèses,
 *       {@code String.valueOf(…)}, {@code Objects.requireNonNull(…)},
 *       {@code Math.abs(…)} ;</li>
 *   <li><b>Transtyper</b> (cast) — la même sélection partielle,
 *       précédée du cast demandé : {@code (int)}, {@code (long)},
 *       {@code (double)}.</li>
 * </ul>
 *
 * <p>Toutes de genre {@code refactor.rewrite} (la réécriture du code
 * sélectionné, sans diagnostic) — le menu du client les place dans le
 * groupe des refactorings, APRÈS les corrections. Les gabarits portent
 * des textes d'accueil lisibles ({@code condition}, {@code taille})
 * que l'utilisateur remplace — le mini serveur ne place pas de caret
 * (le protocole n'offre pas de position de caret dans une édition
 * d'espace de travail).</p>
 *
 * <p>Pur JVM, aucune dépendance Android : testable directement en
 * JUnit, servi par le handler {@code textDocument/codeAction}.</p>
 */
public final class MiniJavaActions {

    private MiniJavaActions() {}

    /** Le pas d'indentation des gabarits (espaces). */
    private static final String INDENT = "    ";

    /** Le genre LSP des trois familles. */
    public static final String GENRE = "refactor.rewrite";

    // ════════════════════════════════════════════════════════════════
    // Types de sortie
    // ════════════════════════════════════════════════════════════════

    /**
     * Une action de refactoring : le titre du menu, le genre LSP, la
     * plage REMPLACÉE et le texte de remplacement — l'édition vit
     * entière dans l'action, appliquée en un seul groupe d'annulation
     * par le client.
     */
    public static final class Action {
        /** Le titre du menu, ex. « Entourer avec if (…) ». */
        public final String titre;
        /** Le genre LSP ({@code refactor.rewrite}). */
        public final String genre;
        /** Le début de la plage remplacée (offset). */
        public final int début;
        /** La fin de la plage remplacée (offset, exclusive). */
        public final int fin;
        /** Le texte de remplacement. */
        public final String texte;

        Action(String titre, int début, int fin, String texte) {
            this.titre = titre;
            this.genre = GENRE;
            this.début = début;
            this.fin = fin;
            this.texte = texte;
        }

        @Override
        public String toString() {
            return titre;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Point d'entrée
    // ════════════════════════════════════════════════════════════════

    /**
     * Les actions de la plage visée — liste vide si la plage est vide
     * ou ne couvre que des lignes blanches.
     *
     * @param text  texte complet du document
     * @param début début de la plage (offset)
     * @param fin   fin de la plage (offset, exclusive)
     */
    public static List<Action> actions(String text, int début, int fin) {
        List<Action> out = new ArrayList<>();
        if (text == null || début < 0 || fin <= début
                || fin > text.length()) {
            return out;
        }
        String sélection = text.substring(début, fin);
        if (sélection.trim().isEmpty()) return out;
        if (couvreDesLignesComplètes(text, début, fin)) {
            return entourer(text, début, fin);
        }
        return envelopperEtTranstyper(début, fin, sélection);
    }

    // ════════════════════════════════════════════════════════════════
    // 1. Entourer — les lignes complètes
    // ════════════════════════════════════════════════════════════════

    /**
     * La sélection couvre-t-elle des LIGNES COMPLÈTES ? Multi-lignes
     * (le retour à la ligne tranche), ou ligne unique où la sélection
     * s'étend du premier au dernier caractère utile de la ligne (la
     * déclaration entière, indentation exclue).
     */
    private static boolean couvreDesLignesComplètes(String text,
            int début, int fin) {
        int premierRetour = text.indexOf('\n', début);
        boolean multiLignes = premierRetour >= 0 && premierRetour < fin;
        if (multiLignes) return true;
        // Ligne unique : la sélection commence-t-elle au premier
        // caractère utile et finit-elle au dernier ?
        int débutLigne = début;
        while (débutLigne > 0 && text.charAt(débutLigne - 1) != '\n') {
            débutLigne--;
        }
        int finLigne = fin;
        while (finLigne < text.length() && text.charAt(finLigne) != '\n') {
            finLigne++;
        }
        String ligne = text.substring(débutLigne, finLigne);
        int premierUtile = 0;
        while (premierUtile < ligne.length()
                && Character.isWhitespace(ligne.charAt(premierUtile))) {
            premierUtile++;
        }
        int dernierUtile = ligne.length();
        while (dernierUtile > premierUtile
                && Character.isWhitespace(ligne.charAt(dernierUtile - 1))) {
            dernierUtile--;
        }
        if (premierUtile == dernierUtile) return false; // ligne blanche
        return début - débutLigne <= premierUtile
                && finLigne - fin <= ligne.length() - dernierUtile;
    }

    /**
     * Les six enveloppes de bloc : la sélection étendue aux lignes
     * complètes, indentée d'un niveau, entre l'ouverture et la
     * fermeture — à l'indentation de la première ligne.
     */
    private static List<Action> entourer(String text, int début, int fin) {
        // Étendue aux lignes complètes.
        int ls = début;
        while (ls > 0 && text.charAt(ls - 1) != '\n') ls--;
        int le = fin;
        while (le < text.length() && text.charAt(le) != '\n') le++;
        String corps = text.substring(ls, le);
        // L'indentation de la première ligne porte les mots-clés.
        String indentation = "";
        for (int i = 0; i < corps.length()
                && (corps.charAt(i) == ' ' || corps.charAt(i) == '\t'); i++) {
            indentation += corps.charAt(i);
        }
        String corpsIndenté = indenter(corps);
        List<Action> out = new ArrayList<>();
        out.add(new Action("Entourer avec if (…)", ls, le,
                indentation + "if (condition) {\n" + corpsIndenté
                        + indentation + "}"));
        out.add(new Action("Entourer avec if/else", ls, le,
                indentation + "if (condition) {\n" + corpsIndenté
                        + indentation + "} else {\n\n"
                        + indentation + "}"));
        out.add(new Action("Entourer avec for (…)", ls, le,
                indentation + "for (var i = 0; i < taille; i++) {\n"
                        + corpsIndenté + indentation + "}"));
        out.add(new Action("Entourer avec while (…)", ls, le,
                indentation + "while (condition) {\n" + corpsIndenté
                        + indentation + "}"));
        out.add(new Action("Entourer avec try/catch", ls, le,
                indentation + "try {\n" + corpsIndenté
                        + indentation + "} catch (Exception e) {\n\n"
                        + indentation + "}"));
        out.add(new Action("Entourer avec try/finally", ls, le,
                indentation + "try {\n" + corpsIndenté
                        + indentation + "} finally {\n\n"
                        + indentation + "}"));
        return out;
    }

    /** Chaque ligne non vide d'un bloc, indentée d'un niveau. */
    private static String indenter(String bloc) {
        StringBuilder out = new StringBuilder(bloc.length() + 16);
        String[] lignes = bloc.split("\n", -1);
        for (int i = 0; i < lignes.length; i++) {
            if (i > 0) out.append('\n');
            if (lignes[i].trim().isEmpty()) continue; // ligne vide : telle quelle
            out.append(INDENT).append(lignes[i]);
        }
        return out.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Envelopper + 3. Transtyper — la sélection partielle
    // ════════════════════════════════════════════════════════════════

    /**
     * Les enveloppes d'EXPRESSION et les casts : la sélection
     * partielle devient l'argument (ou l'opérande du cast) — quatre
     * enveloppes, trois transtypages numériques.
     */
    private static List<Action> envelopperEtTranstyper(int début, int fin,
            String sélection) {
        List<Action> out = new ArrayList<>();
        out.add(new Action("Mettre entre parenthèses", début, fin,
                "(" + sélection + ")"));
        out.add(new Action("Envelopper dans String.valueOf(…)", début, fin,
                "String.valueOf(" + sélection + ")"));
        out.add(new Action("Envelopper dans Objects.requireNonNull(…)",
                début, fin,
                "Objects.requireNonNull(" + sélection + ")"));
        out.add(new Action("Envelopper dans Math.abs(…)", début, fin,
                "Math.abs(" + sélection + ")"));
        out.add(new Action("Transtyper en int", début, fin,
                "(int) " + sélection));
        out.add(new Action("Transtyper en long", début, fin,
                "(long) " + sélection));
        out.add(new Action("Transtyper en double", début, fin,
                "(double) " + sélection));
        return out;
    }
}
