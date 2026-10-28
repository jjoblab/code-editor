package jo.codeeditor.demo.lsp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L'AUTO-IMPORT du mini Java de la démo — le nom simple sous le caret
 * devient import, servi par le CLASSPATH.
 *
 * <p>Le caret sur un identifiant CAPITALISÉ qui n'est NI un type
 * déclaré dans le fichier, NI déjà couvert par un import existant
 * (import explicite du nom, ou wildcard du paquet résolu), NI une
 * classe de {@code java.lang} (l'import serait inutile) : la classe
 * est résolue par réflexion ({@link MiniJavaClasspath}) et l'action
 * propose « Importer {@code java.time.LocalDate} » — l'édition insère
 * la ligne d'import APRÈS la dernière importation existante (ou après
 * la déclaration de paquet, ou en tête de fichier), une seule étape
 * d'annulation.</p>
 *
 * <p>L'insertion vit AU-DESSUS du viewport le plus souvent : le client
 * compense le défilement (comptage des lignes insérées au-dessus de
 * l'écran, mécanisme générique du groupe d'éditions) — le texte lu ne
 * saute pas sous les yeux. Pur JVM, testable directement en JUnit.</p>
 */
public final class MiniJavaImports {

    private MiniJavaImports() {}

    /** Une importation à insérer : la ligne complète et son point
     *  d'insertion. */
    public static final class Importation {
        /** Le nom pleinement qualifié, ex. {@code java.time.LocalDate}. */
        public final String fqn;
        /** L'offset où la ligne d'import s'insère. */
        public final int insertion;
        /** La ligne à insérer, retours à la ligne compris. */
        public final String ligne;

        Importation(String fqn, int insertion, String ligne) {
            this.fqn = fqn;
            this.insertion = insertion;
            this.ligne = ligne;
        }

        @Override
        public String toString() {
            return "import " + fqn + ";";
        }
    }

    private static final Pattern TYPE_DÉCL = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern ANNOTATION_DÉCL = Pattern.compile(
            "@interface\\s+(\\w+)");
    private static final Pattern IMPORT_LIGNE = Pattern.compile(
            "^\\s*import\\s+([\\w.]+\\.(?:\\w+|\\*))\\s*;",
            Pattern.MULTILINE);
    private static final Pattern PAQUETAGE = Pattern.compile(
            "^\\s*package\\s+[\\w.]+\\s*;", Pattern.MULTILINE);

    /**
     * L'importation proposée au caret — null si le mot n'est pas un
     * nom simple importable (inconnu du classpath, déjà importé, type
     * du fichier, classe de java.lang, mot dans une chaîne ou un
     * commentaire).
     *
     * @param text  texte complet du document
     * @param caret offset du caret
     */
    public static Importation importation(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) return null;
        // Le mot du caret, hors chaînes et commentaires.
        boolean[] masque = masquer(text, text.length());
        int fin = Math.min(caret, text.length());
        int début = fin;
        while (début > 0
                && Character.isJavaIdentifierPart(text.charAt(début - 1))
                && !masque[début - 1]) {
            début--;
        }
        while (fin < text.length()
                && Character.isJavaIdentifierPart(text.charAt(fin))
                && !masque[fin]) {
            fin++;
        }
        if (début == fin) return null;
        String mot = text.substring(début, fin);
        // Un import concerne un nom SIMPLE capitalisé — pas un mot
        // clé, pas une variable (minuscule), pas un nom qualifié
        // complet (l'usage qualifié n'a pas besoin d'import).
        if (!Character.isUpperCase(mot.charAt(0))) return null;
        // Un type DÉCLARÉ dans le fichier n'a pas besoin d'import.
        if (estDéclaré(text, mot)) return null;
        // La classe du classpath — la réflexion tranche.
        Class<?> c = MiniJavaClasspath.classe(mot);
        if (c == null) return null;
        String fqn = null;
        try {
            fqn = c.getName();
        } catch (Throwable t) {
            return null;
        }
        if (fqn == null) return null;
        // java.lang est déjà visible — l'import serait du bruit.
        if (fqn.startsWith("java.lang.")) return null;
        // Déjà importé ? Le nom explicitement, ou le wildcard du
        // paquet résolu.
        String paquet = fqn.substring(0, fqn.lastIndexOf('.'));
        Matcher m = IMPORT_LIGNE.matcher(text);
        while (m.find()) {
            String cible = m.group(1);
            if (cible.equals(fqn)) return null;
            if (cible.equals(paquet + ".*")) return null;
            // « import a.b.Nom; » rend « Nom » visible : couvert.
            if (cible.endsWith("." + mot)) return null;
        }
        // L'insertion : après la DERNIÈRE importation, sinon après le
        // paquet, sinon en tête.
        int insertion = 0;
        m.reset();
        while (m.find()) {
            int finLigne = finDeLigne(text, m.end());
            insertion = Math.max(insertion, finLigne);
        }
        if (insertion == 0) {
            Matcher p = PAQUETAGE.matcher(text);
            if (p.find()) {
                insertion = finDeLigne(text, p.end());
            }
        }
        return new Importation(fqn, insertion,
                "import " + fqn + ";\n");
    }

    /** Le type est-il DÉCLARÉ dans le fichier ? */
    private static boolean estDéclaré(String text, String mot) {
        Matcher m = TYPE_DÉCL.matcher(text);
        while (m.find()) {
            if (m.group(1).equals(mot)) return true;
        }
        Matcher a = ANNOTATION_DÉCL.matcher(text);
        while (a.find()) {
            if (a.group(1).equals(mot)) return true;
        }
        return false;
    }

    /** La fin de la ligne qui suit l'offset (retour compris). */
    private static int finDeLigne(String text, int offset) {
        int i = Math.max(0, Math.min(offset, text.length()));
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i < text.length() ? i + 1 : i;
    }

    /** Masque chaînes et commentaires (offsets préservés). */
    private static boolean[] masquer(String text, int limite) {
        boolean[] out = new boolean[limite];
        boolean inString = false, inLine = false, inBlock = false;
        char quote = '\0';
        for (int i = 0; i < limite; i++) {
            char c = text.charAt(i);
            if (inLine) {
                out[i] = true;
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                out[i] = true;
                if (c == '*' && i + 1 < limite
                        && text.charAt(i + 1) == '/') {
                    out[i + 1] = true;
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out[i] = true;
                if (c == '\\') {
                    if (i + 1 < limite) out[i + 1] = true;
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < limite && text.charAt(i + 1) == '/') {
                out[i] = true;
                inLine = true;
                if (i + 1 < limite) out[i + 1] = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < limite && text.charAt(i + 1) == '*') {
                out[i] = true;
                inBlock = true;
                if (i + 1 < limite) out[i + 1] = true;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out[i] = true;
            }
        }
        return out;
    }
}
