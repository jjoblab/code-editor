package jo.codeeditor.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * L'appariement unifié des candidats de complétion — UNE seule
 * implémentation pour le filtre, le classement, la surbrillance et la
 * navigation par symboles.
 *
 * <p>Historiquement, quatre copies parallèles de la même logique
 * vivaient dans le dépôt (le filtre privé du popup, la session de
 * complétion, le calcul de positions du peintre, la vérification
 * camel-hump du menu de navigation) : elles divergeaient — la
 * surbrillance pouvait désigner d'autres caractères que ceux qui ont
 * fait matcher le candidat. Tout converge ici.</p>
 *
 * <h2>Identifiant de tête</h2>
 *
 * <p>Les libellés des candidats portent souvent une signature
 * (« {@code Text(text: String)} », « {@code foo()} ») : l'appariement
 * travaille sur l'{@link #leadingIdentifier(String) identifiant de
 * tête} — la suite de caractères d'identifiant au début du libellé,
 * étendue aux séparateurs de noms qualifiés ({@code : . / @ #}) quand
 * ils sont suivis d'un caractère d'identifiant (« {@code
 * android:layout_width} » et « {@code @string/foo} » sont des noms
 * complets ; « {@code x: Int} » s'arrête à « {@code x} »). La
 * signature ne participe JAMAIS à l'appariement flou — aucune
 * parenthèse en gras.</p>
 *
 * <h2>Paliers (échelle client à 6 niveaux)</h2>
 *
 * <ol>
 *   <li>{@link #TIER_EXACT} — identifiant (ou libellé) égal au préfixe,
 *       sensible à la casse ;</li>
 *   <li>{@link #TIER_EXACT_CI} — égal à la casse près ;</li>
 *   <li>{@link #TIER_PREFIX} — commence par le préfixe, sensible à la
 *       casse ;</li>
 *   <li>{@link #TIER_PREFIX_CI} — commence par le préfixe, à la casse
 *       près ;</li>
 *   <li>{@link #TIER_CAMEL_HUMP} — camel humps (voir ci-dessous) ;</li>
 *   <li>{@link #TIER_SUBSEQUENCE} — sous-séquence quelconque,
 *       insensible à la casse.</li>
 * </ol>
 *
 * <p>Le {@code filterText} posé par le fournisseur sert de repli quand
 * ni l'identifiant ni le libellé n'apparient : le palier retenu est le
 * meilleur des trois textes.</p>
 *
 * <h2>Définition canonique des camel humps</h2>
 *
 * <p>Chaque caractère du préfixe correspond, dans l'ordre, à un
 * caractère du texte de référence :</p>
 * <ul>
 *   <li>le PREMIER caractère du préfixe, à toute position du texte
 *       (retour arrière limité : si sa première occurrence ne mène à
 *       rien, les suivantes sont essayées) ;</li>
 *   <li>toute position de début de mot : majuscule, début du texte, ou
 *       juste après {@code _ . : / @} ou une espace ;</li>
 *   <li>ou immédiatement après le caractère correspondant précédent
 *       (run contigu).</li>
 * </ul>
 * <p>Ainsi « {@code gS} » (camel hump) et « {@code get} » (run contigu)
 * correspondent tous deux à « {@code getString} ». Les sous-séquences
 * quelconques (« {@code lm} » sur « {@code column} ») restent d'un
 * palier EN-DESSOUS des humps : les initiales de mots remontent
 * toujours devant les lettres prises au fil du texte.</p>
 *
 * <h2>Groupes (départage à palier égal)</h2>
 *
 * <p>À palier égal, un symbole résolu précède un mot-clé, qui précède
 * un snippet, qui précède un mot du buffer — voir
 * {@link #group(boolean, boolean, int)}. Un mot-clé tapé en entier
 * reste en tête de son palier (l'exact l'emporte sur tout palier
 * inférieur, et le mot-clé précède le snippet à palier égal).</p>
 *
 * <p>Le classement local est un <b>tri stable</b> : à l'intérieur d'un
 * couple (palier, groupe), l'ordre du serveur (ou du fournisseur) est
 * conservé.</p>
 *
 * <p>Toutes les méthodes sont statiques, pures et testables
 * unitairement ; aucun état, aucune dépendance Android.</p>
 *
 * @since 4.3.0
 */
public final class CompletionMatcher {

    // ── Paliers (échelle à 6 niveaux, plus l'absence de match) ─────
    /** Le texte de référence EST le préfixe (sensible à la casse). */
    public static final int TIER_EXACT = 0;
    /** Le texte de référence est le préfixe, à la casse près. */
    public static final int TIER_EXACT_CI = 1;
    /** Le texte de référence commence par le préfixe (sensible à la casse). */
    public static final int TIER_PREFIX = 2;
    /** Le texte de référence commence par le préfixe, à la casse près. */
    public static final int TIER_PREFIX_CI = 3;
    /** Camel humps : initiales de mots et runs contigus (définition canonique). */
    public static final int TIER_CAMEL_HUMP = 4;
    /** Sous-séquence quelconque, insensible à la casse. */
    public static final int TIER_SUBSEQUENCE = 5;
    /** Aucune correspondance. */
    public static final int TIER_NONE = 6;

    // ── Groupes (départage à palier égal) ──────────────────────────
    /** Symbole résolu par le backend (méthode, champ, type, package…). */
    public static final int GROUP_RESOLVED = 0;
    /** Mot-clé du langage. */
    public static final int GROUP_KEYWORD = 1;
    /** Snippet (gabarit d'expansion). */
    public static final int GROUP_SNIPPET = 2;
    /** Mot du buffer (kind LSP Text) — pas un symbole résolu. */
    public static final int GROUP_BUFFER_WORD = 3;

    private CompletionMatcher() {
        // Classe utilitaire statique.
    }

    // ── Identifiant de tête ────────────────────────────────────────

    /**
     * La suite de caractères d'identifiant au début du libellé :
     * « {@code Text(text: String)} » → « {@code Text} »,
     * « {@code foo()} » → « {@code foo} ». Les séparateurs de noms
     * qualifiés ({@code : . / @ #}) sont retenus seulement suivis d'un
     * caractère d'identifiant — « {@code android:layout_width} »,
     * « {@code com.example.Foo} » et « {@code @string/foo} » sont des
     * noms complets, tandis que « {@code x: Int} » s'arrête à
     * « {@code x} » (deux-points suivis d'une espace) et
     * « {@code foo.} » à « {@code foo} » (point final).
     *
     * @param label le libellé du candidat (null-safe)
     * @return l'identifiant de tête, éventuellement vide, jamais null
     */
    public static String leadingIdentifier(String label) {
        if (label == null || label.isEmpty()) return "";
        int end = 0;
        while (end < label.length()) {
            char c = label.charAt(end);
            if (isIdentifierChar(c)) {
                end++;
                continue;
            }
            if (isNameSeparator(c)
                    && end + 1 < label.length()
                    && isIdentifierChar(label.charAt(end + 1))) {
                end++;
                continue;
            }
            break;
        }
        return label.substring(0, end);
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isNameSeparator(char c) {
        return c == ':' || c == '.' || c == '/' || c == '@' || c == '#';
    }

    // ── Paliers ────────────────────────────────────────────────────

    /**
     * Le palier du libellé (identifiant de tête, puis libellé entier
     * pour les paliers exact/préfixe), sans texte de filtrage.
     *
     * @param label  le libellé du candidat
     * @param prefix le préfixe tapé (vide = tout correspond)
     * @return une constante {@code TIER_*}
     */
    public static int tier(String label, String prefix) {
        return tier(label, null, prefix);
    }

    /**
     * Le meilleur palier du candidat : identifiant de tête d'abord,
     * libellé entier ensuite (paliers exact/préfixe seulement — la
     * signature ne participe jamais au flou), {@code filterText} en
     * dernier recours.
     *
     * @param label      le libellé du candidat
     * @param filterText le texte de filtrage du fournisseur (null/vidé ignoré)
     * @param prefix     le préfixe tapé (vide = tout correspond)
     * @return une constante {@code TIER_*}
     */
    public static int tier(String label, String filterText, String prefix) {
        if (prefix == null || prefix.isEmpty()) return TIER_EXACT;
        if (label == null) label = "";
        String ident = leadingIdentifier(label);
        int best = tierOnReference(ident, prefix);
        if (!label.equals(ident)) {
            // Le libellé entier ne sert qu'aux paliers exact/préfixe :
            // jamais de humps ni de sous-séquence à travers une
            // signature (jamais de parenthèse en gras) — et pas de
            // palier du tout si le libellé lui-même n'apparie pas.
            int labelTier = tierOnReference(label, prefix);
            if (labelTier <= TIER_PREFIX_CI) {
                best = Math.min(best, labelTier);
            }
        }
        if (filterText != null && !filterText.isEmpty()) {
            best = Math.min(best, tierOnReference(filterText, prefix));
        }
        return best;
    }

    /**
     * Le candidat correspond-il au préfixe (palier quelconque) ?
     * Un préfixe vide correspond à tout.
     */
    public static boolean matches(String label, String filterText, String prefix) {
        return tier(label, filterText, prefix) != TIER_NONE;
    }

    /** Palier d'un texte de référence unique. */
    private static int tierOnReference(String reference, String prefix) {
        if (prefix.isEmpty()) return TIER_EXACT;
        if (reference == null || reference.isEmpty()) return TIER_NONE;
        if (reference.equals(prefix)) return TIER_EXACT;
        if (reference.equalsIgnoreCase(prefix)) return TIER_EXACT_CI;
        if (reference.startsWith(prefix)) return TIER_PREFIX;
        if (reference.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return TIER_PREFIX_CI;
        }
        if (camelHumpSubsequence(reference, prefix)) return TIER_CAMEL_HUMP;
        if (isSubsequence(reference, prefix)) return TIER_SUBSEQUENCE;
        return TIER_NONE;
    }

    // ── Camel humps (définition canonique) ─────────────────────────

    /**
     * Sous-séquence camel-hump canonique : chaque caractère du préfixe
     * correspond, dans l'ordre, soit au premier caractère du préfixe
     * (à toute position du texte), soit à un début de mot (majuscule,
     * début du texte, ou après {@code _ . : / @} ou une espace), soit
     * immédiatement après le caractère correspondant précédent (run
     * contigu). Ainsi « {@code gS} » et « {@code get} » correspondent
     * tous deux à « {@code getString} ».
     *
     * @param text   le texte de référence (identifiant de tête ou filterText)
     * @param prefix le préfixe tapé
     * @return vrai si le préfixe est une sous-séquence canonique
     */
    public static boolean camelHumpSubsequence(String text, String prefix) {
        return camelHumpWalk(text, prefix, null);
    }

    /**
     * La marche canonique, en collectant les positions. Le premier
     * caractère du préfixe est essayé à chacune de ses occurrences
     * (retour arrière limité au premier caractère : la première
     * occurrence qui mène à une correspondance complète gagne).
     */
    private static boolean camelHumpWalk(String text, String prefix, List<Integer> positions) {
        if (prefix == null || prefix.isEmpty()) return true;
        if (text == null || text.isEmpty()) return false;
        char first = Character.toLowerCase(prefix.charAt(0));
        for (int start = 0; start < text.length(); start++) {
            if (Character.toLowerCase(text.charAt(start)) != first) continue;
            List<Integer> walk = positions != null ? new ArrayList<>() : null;
            if (camelHumpFrom(text, prefix, start, walk)) {
                if (positions != null) {
                    positions.clear();
                    positions.addAll(walk);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Marche gloutonne depuis la position {@code start} du premier
     * caractère : chaque caractère suivant du préfixe doit correspondre
     * à un début de mot ou immédiatement après le précédent.
     */
    private static boolean camelHumpFrom(String text, String prefix, int start,
                                         List<Integer> positions) {
        if (positions != null) positions.add(start);
        int pi = 1;
        int lastPos = start;
        for (int li = start + 1; li < text.length() && pi < prefix.length(); li++) {
            char tc = text.charAt(li);
            char pc = prefix.charAt(pi);
            if (Character.toLowerCase(tc) == Character.toLowerCase(pc)) {
                if (isWordStart(text, li) || li == lastPos + 1) {
                    if (positions != null) positions.add(li);
                    pi++;
                    lastPos = li;
                }
            }
        }
        return pi == prefix.length();
    }

    /** Début de mot : majuscule, début du texte, ou après un séparateur/une espace. */
    private static boolean isWordStart(String text, int index) {
        char c = text.charAt(index);
        if (Character.isUpperCase(c)) return true;
        if (index == 0) return true;
        char prev = text.charAt(index - 1);
        return prev == '_' || prev == '.' || prev == ':' || prev == '/'
                || prev == '@' || prev == '#' || Character.isWhitespace(prev);
    }

    /** Sous-séquence quelconque, insensible à la casse. */
    private static boolean isSubsequence(String text, String prefix) {
        int pi = 0;
        for (int ti = 0; ti < text.length() && pi < prefix.length(); ti++) {
            if (Character.toLowerCase(text.charAt(ti))
                    == Character.toLowerCase(prefix.charAt(pi))) {
                pi++;
            }
        }
        return pi == prefix.length();
    }

    // ── Positions (surbrillance) ───────────────────────────────────

    /**
     * Les positions des caractères du texte de référence qui ont fait
     * matcher le préfixe — exactement ceux-là : le run initial pour un
     * préfixe, les débuts de mots et runs contigus pour les humps, la
     * marche gloutonne pour une sous-séquence quelconque. Un préfixe
     * vide renvoie toutes les positions (contrat historique).
     *
     * @param reference le texte de référence (identifiant de tête, libellé sans signature)
     * @param prefix    le préfixe tapé
     * @return les indices en gras, vide si aucune correspondance
     */
    public static List<Integer> matchPositions(String reference, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            List<Integer> all = new ArrayList<>();
            if (reference != null) {
                for (int i = 0; i < reference.length(); i++) all.add(i);
            }
            return all;
        }
        if (reference == null || reference.isEmpty()) return Collections.emptyList();
        // Préfixe (sensible ou non) : le run initial.
        if (reference.startsWith(prefix)
                || reference.toLowerCase(Locale.ROOT)
                        .startsWith(prefix.toLowerCase(Locale.ROOT))) {
            List<Integer> run = new ArrayList<>();
            for (int i = 0; i < prefix.length(); i++) run.add(i);
            return run;
        }
        List<Integer> positions = new ArrayList<>();
        if (camelHumpWalk(reference, prefix, positions)) return positions;
        if (isSubsequenceWalk(reference, prefix, positions)) return positions;
        return Collections.emptyList();
    }

    /**
     * Les positions en gras DANS LE LIBELLÉ d'un candidat : la
     * surbrillance s'appuie sur le même texte de référence que celui
     * qui a fait matcher — l'identifiant de tête ; à défaut le libellé
     * entier (paliers exact/préfixe) ; à défaut le {@code filterText},
     * dont les indices ne sont valables sur le libellé que si les deux
     * textes ont la même longueur (typique : le libellé neutralisé en
     * minuscules). Aucune correspondance : rien n'est gras.
     *
     * @param label      le libellé affiché du candidat
     * @param filterText le texte de filtrage du fournisseur (peut être null)
     * @param prefix     le préfixe tapé
     * @return les indices du libellé à mettre en gras
     */
    public static List<Integer> matchPositionsForItem(String label, String filterText,
                                                      String prefix) {
        if (prefix == null || prefix.isEmpty()) return matchPositions(label, prefix);
        if (label == null) label = "";
        String ident = leadingIdentifier(label);
        if (tierOnReference(ident, prefix) != TIER_NONE) {
            return matchPositions(ident, prefix);
        }
        if (!label.equals(ident) && tierOnReference(label, prefix) <= TIER_PREFIX_CI) {
            return matchPositions(label, prefix);
        }
        if (filterText != null && !filterText.isEmpty()
                && tierOnReference(filterText, prefix) != TIER_NONE) {
            List<Integer> positions = matchPositions(filterText, prefix);
            return filterText.length() == label.length()
                    ? positions : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    /** Sous-séquence gloutonne en collectant les positions. */
    private static boolean isSubsequenceWalk(String text, String prefix,
                                             List<Integer> positions) {
        positions.clear();
        int pi = 0;
        for (int ti = 0; ti < text.length() && pi < prefix.length(); ti++) {
            if (Character.toLowerCase(text.charAt(ti))
                    == Character.toLowerCase(prefix.charAt(pi))) {
                positions.add(ti);
                pi++;
            }
        }
        return pi == prefix.length();
    }

    // ── Groupes ────────────────────────────────────────────────────

    /**
     * Le groupe de départage à palier égal : symbole résolu (0) avant
     * mot-clé (1) avant snippet (2) avant mot du buffer (3). Un
     * mot-clé tapé en entier reste ainsi en tête de son palier devant
     * un snippet exact, et jamais derrière un symbole résolu de même
     * palier.
     *
     * @param isKeyword le drapeau mot-clé du candidat
     * @param isSnippet le drapeau snippet du candidat
     * @param kindCode  le kind LSP numérique (14 = mot-clé, 15 = snippet,
     *                  1 = texte/mot du buffer, 0 = inconnu)
     * @return une constante {@code GROUP_*}
     */
    public static int group(boolean isKeyword, boolean isSnippet, int kindCode) {
        if (isKeyword || kindCode == 14) return GROUP_KEYWORD;
        if (isSnippet || kindCode == 15) return GROUP_SNIPPET;
        if (kindCode == 1) return GROUP_BUFFER_WORD;
        return GROUP_RESOLVED;
    }
}
