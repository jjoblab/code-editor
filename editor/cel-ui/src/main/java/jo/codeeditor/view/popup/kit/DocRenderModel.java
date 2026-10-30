package jo.codeeditor.view.popup.kit;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.doc.QuickDoc;
import jo.codeeditor.session.EditorSession;

/**
 * Modèle de rendu UNIFIÉ de la documentation rapide : transforme un
 * {@link QuickDoc.QuickDocContent} en blocs typés ordonnés
 * [SIGNATURE, SUMMARY, PARAM*, RETURNS, THROWS, SEE, AUTRE*] —
 * consommés par la doc rapide, la bande doc de l'aide de signature et
 * le panneau de documentation de la complétion (trois présentations,
 * un seul modèle).
 *
 * <p>La LANGUE d'analyse vient toujours de la session
 * ({@link #parse(String, EditorSession)}) — jamais d'un identifiant
 * de langage en dur : le même éditeur affiche des documents Java,
 * Kotlin, Python… et la documentation doit suivre le document
 * courant.</p>
 *
 * <p>Classe pure (aucune dépendance Android) — testée sans Canvas.</p>
 */
public final class DocRenderModel {

    // ── Kinds de blocs ────────────────────────────────────────────
    /** Signature exacte (fence ``` du serveur). */
    public static final int KIND_SIGNATURE = 0;
    /** Description générale (avant le premier tag @). */
    public static final int KIND_SUMMARY = 1;
    /** Paramètre (@param nom description). */
    public static final int KIND_PARAM = 2;
    /** Valeur de retour (@return / @returns). */
    public static final int KIND_RETURNS = 3;
    /** Exception levée (@throws / @exception nom description). */
    public static final int KIND_THROWS = 4;
    /** Référence croisée (@see). */
    public static final int KIND_SEE = 5;
    /** Section non typée (@since, @author…) — contenu jamais perdu. */
    public static final int KIND_OTHER = 6;

    /**
     * Un bloc de documentation rendu : le TITRE de section brut
     * (« @param »…), le LIBELLÉ extrait (nom du paramètre / de
     * l'exception — vide sinon) et le TEXTE porté.
     */
    public static final class Block {
        public final int kind;
        /** Titre de section brut (« @param », « @return »…), vide pour SUMMARY/SIGNATURE. */
        public final String title;
        /** Libellé extrait — nom du paramètre ou de l'exception, vide sinon. */
        public final String label;
        /** Texte porté (description du paramètre, valeur de retour…). */
        public final String text;

        Block(int kind, String title, String label, String text) {
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.label = label == null ? "" : label;
            this.text = text == null ? "" : text;
        }

        /** Ligne plate du bloc (motif « titre libellé texte ») —
         *  compatibilité avec le rendu historique des sections. */
        public String ligne() {
            StringBuilder b = new StringBuilder();
            if (!title.isEmpty()) b.append(title).append(' ');
            if (!label.isEmpty()) b.append(label).append(' ');
            return b.append(text).toString().trim();
        }
    }

    private DocRenderModel() {}

    // ── Analyse (langue de la session) ────────────────────────────

    /**
     * Analyse la documentation brute AVEC LA LANGUE DE LA SESSION —
     * jamais un identifiant en dur : le document courant commande.
     *
     * @param raw la documentation brute (fences, tags @…)
     * @param session la session (peut être null → langue vide)
     * @return le contenu analysé (jamais null)
     */
    public static QuickDoc.QuickDocContent parse(String raw, EditorSession session) {
        return QuickDoc.parseQuickDoc(raw, langueDe(session));
    }

    /**
     * La langue d'une session (identifiant du profil de langage), ou
     * la chaîne vide si la session ou sa langue est absente.
     */
    public static String langueDe(EditorSession session) {
        if (session == null) return "";
        String langue = session.getLanguage();
        return langue != null ? langue : "";
    }

    // ── Transformation en blocs ───────────────────────────────────

    /**
     * Transforme un contenu analysé en blocs typés, dans l'ordre du
     * document : signature (fence) puis description, puis chaque
     * section dans son ordre d'apparition (les tags @param précèdent
     * naturellement @return dans une doc bien formée).
     *
     * @param c le contenu analysé (peut être null → liste vide)
     * @return les blocs (jamais null)
     */
    public static List<Block> blocks(QuickDoc.QuickDocContent c) {
        List<Block> out = new ArrayList<>();
        if (c == null) return out;
        if (!c.signature.isEmpty()) {
            out.add(new Block(KIND_SIGNATURE, "", "", c.signature));
        }
        if (!c.description.isEmpty()) {
            out.add(new Block(KIND_SUMMARY, "", "", c.description));
        }
        for (QuickDoc.DocSection s : c.sections) {
            String tag = s.title == null ? "" : s.title.trim().toLowerCase(java.util.Locale.ROOT);
            for (String item : s.items) {
                String it = item == null ? "" : item;
                switch (tag) {
                    case "@param":
                    case "@property":
                        out.add(blocLibellé(KIND_PARAM, s.title, it));
                        break;
                    case "@return":
                    case "@returns":
                        out.add(new Block(KIND_RETURNS, s.title, "", it));
                        break;
                    case "@throws":
                    case "@exception":
                        out.add(blocLibellé(KIND_THROWS, s.title, it));
                        break;
                    case "@see":
                        out.add(new Block(KIND_SEE, s.title, "", it));
                        break;
                    default:
                        out.add(new Block(KIND_OTHER, s.title, "", it));
                        break;
                }
            }
        }
        return out;
    }

    /**
     * Bloc à libellé de tête (@param nom description,
     * @throws Exception description) : le premier mot est le LIBELLÉ,
     * le reste le TEXTE — sans libellé, tout est texte.
     */
    private static Block blocLibellé(int kind, String title, String item) {
        int sep = item.indexOf(' ');
        if (sep <= 0) {
            return new Block(kind, title, "", item.trim());
        }
        return new Block(kind, title,
                item.substring(0, sep).trim(),
                item.substring(sep + 1).trim());
    }
}
