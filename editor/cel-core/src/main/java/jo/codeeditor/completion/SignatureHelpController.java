package jo.codeeditor.completion;

import java.util.*;

/**
 * Contrôleur du popup d'info paramètres.
 * Affiche l'aide de signature de fonction quand le caret est à
 * l'intérieur d'un appel de fonction. Gère le déclenchement explicite
 * (Ctrl+P), la résolution automatique au déplacement du caret, et le
 * rejet par appel.
 * Contrôleur d'aide de signature : suivi de parenthèse active + rafraîchissement débouncé.
 */
public class SignatureHelpController {

    // ── Types de données ──────────────────────────────────────────

    /** Un paramètre d'une signature. */
    public static final class Parameter {
        public final String label;
        public final String documentation;

        public Parameter(String label, String documentation) {
            this.label = label != null ? label : "";
            this.documentation = documentation != null ? documentation : "";
        }

        @Override
        public String toString() {
            return "Parameter(\"" + label + "\")";
        }
    }

    /** Une signature de fonction avec ses paramètres. */
    public static final class Signature {
        public final String label;
        public final String documentation;
        public final List<Parameter> parameters;
        public final int activeParameter;

        public Signature(String label, String documentation, List<Parameter> parameters, int activeParameter) {
            this.label = label != null ? label : "";
            this.documentation = documentation != null ? documentation : "";
            this.parameters = parameters != null ? Collections.unmodifiableList(parameters) : Collections.emptyList();
            this.activeParameter = activeParameter;
        }

        @Override
        public String toString() {
            return "Signature(\"" + label + "\", params=" + parameters.size() + ", active=" + activeParameter + ")";
        }
    }

    /** Réponse complète d'aide de signature. */
    public static final class SignatureHelp {
        public final List<Signature> signatures;
        public final int activeSignature;
        public final int activeParameter;

        public SignatureHelp(List<Signature> signatures, int activeSignature, int activeParameter) {
            this.signatures = signatures != null ? Collections.unmodifiableList(signatures) : Collections.emptyList();
            this.activeSignature = activeSignature;
            this.activeParameter = activeParameter;
        }

        /** Renvoie la signature active courante. */
        public Signature getActiveSignature() {
            if (signatures.isEmpty()) return null;
            int idx = Math.min(activeSignature, signatures.size() - 1);
            return signatures.get(idx);
        }

        @Override
        public String toString() {
            return "SignatureHelp(sigs=" + signatures.size() + ", active=" + activeSignature + ")";
        }
    }

    // ── État ───────────────────────────────────────────────────────

    /** Aide de signature courante ou null. */
    private SignatureHelp help;

    /** Indique si l'utilisateur a rejeté le popup pour l'appel courant. */
    private boolean dismissed = false;

    /**
     * Override de la signature active choisie par l'utilisateur.
     * Quand {@code >= 0}, le renderer utilise cet index au lieu de
     * {@link SignatureHelp#activeSignature}.
     *
     * <p>C'est ce qui permet la navigation clavier Haut/Bas entre les
     * surcharges : le serveur LSP renvoie son « meilleur guess »
     * d'activeSignature dans chaque réponse, mais l'utilisateur peut
     * changer de surcharge manuellement. L'override est conservé à
     * travers les rafraîchissements de {@link #resolve(CharSequence, int)}
     * au sein du même appel, pour que la surcharge choisie ne « retombe »
     * pas sur la préférence du serveur pendant que l'utilisateur tape.
     *
     * <p>Remis à {@code -1} quand la frontière de l'appel change
     * (nouvelle parenthèse ouvrante) ou dans {@link #reset()}.
     */
    private int userOverrideActiveSignature = -1;

    /** Compteur d'époque pour éviter les résultats périmés. */
    private long epoch = 0;

    /** Listener de résolution de l'aide de signature. */
    private Resolver listener;

    /** Dernière position connue de la parenthèse ouvrante de l'appel (pour suivre l'appel courant). */
    private int lastCallOpenPos = -1;

    /**
     * Interface de résolution de l'aide de signature depuis le serveur de langage.
     */
    public interface Resolver {
        /**
         * Résout l'aide de signature à l'offset donné.
         * @param offset offset du caret
         * @param callOpenPos offset de la '(' ouvrante
         * @return l'aide de signature, ou null
         */
        SignatureHelp resolve(int offset, int callOpenPos);
    }

    // ── Constructeurs ─────────────────────────────────────────────

    public SignatureHelpController() {
        this(null);
    }

    public SignatureHelpController(Resolver listener) {
        this.listener = listener;
    }

    // ── Accesseurs ────────────────────────────────────────────────

    public SignatureHelp getHelp() { return help; }
    public boolean isDismissed() { return dismissed; }
    public long getEpoch() { return epoch; }

    public void setListener(Resolver listener) { this.listener = listener; }

    /**
     * Renvoie l'index de signature active effectif — l'override de
     * l'utilisateur s'il est défini et dans les bornes, sinon le
     * {@link SignatureHelp#activeSignature} du serveur LSP.
     *
     * <p>Les renderers doivent lire ceci au lieu de
     * {@code help.activeSignature} afin que la navigation clavier
     * Haut/Bas (voir {@link #cycleActiveSignature}) soit reflétée dans
     * le popup.
     *
     * @return l'index de signature active, ou {@code -1} si aucune aide n'est disponible
     */
    public int getEffectiveActiveSignature() {
        if (help == null || help.signatures.isEmpty()) return -1;
        int max = help.signatures.size() - 1;
        if (userOverrideActiveSignature >= 0 && userOverrideActiveSignature <= max) {
            return userOverrideActiveSignature;
        }
        return Math.max(0, Math.min(help.activeSignature, max));
    }

    /**
     * Renvoie la signature active choisie par l'utilisateur, ou
     * {@code -1} si l'utilisateur n'a pas surchargé le choix du serveur.
     * Utile pour les tests.
     */
    public int getUserOverrideActiveSignature() {
        return userOverrideActiveSignature;
    }

    /**
     * Fait cycler la signature active de {@code +1} ou {@code -1},
     * avec bouclage. Utilisé par la navigation clavier Haut/Bas dans
     * le popup d'aide de signature.
     *
     * <p>L'override persiste à travers les rafraîchissements de
     * {@link #resolve} au sein du même appel (la surcharge choisie
     * reste sélectionnée pendant que l'utilisateur tape d'autres
     * arguments) et est remis à {@code -1} quand le caret passe à un
     * autre appel.
     *
     * <p>Sans effet si aucune aide n'est disponible ou s'il n'existe
     * qu'une seule signature.
     *
     * @param direction {@code +1} pour la surcharge suivante (Bas),
     *                  {@code -1} pour la précédente (Haut)
     * @return le nouvel index de signature active effectif, ou {@code -1}
     *         si aucun cyclage n'a eu lieu
     */
    public int cycleActiveSignature(int direction) {
        if (help == null || help.signatures.isEmpty()) return -1;
        int n = help.signatures.size();
        if (n == 1) return 0;
        int current = getEffectiveActiveSignature();
        if (current < 0) current = 0;
        // Bouclage : ((current + direction) % n + n) % n
        int next = ((current + direction) % n + n) % n;
        userOverrideActiveSignature = next;
        return next;
    }

    /**
     * Définit explicitement l'index de surcharge choisi par
     * l'utilisateur. Utilisé par les tests et les clients programmatiques
     * (ex. une UI d'onglets « 1/3 » dans le popup).
     *
     * @param idx index de signature (base 0), ou {@code -1} pour
     *            effacer l'override et revenir au choix du serveur
     */
    public void setUserActiveSignature(int idx) {
        if (idx < -1) idx = -1;
        userOverrideActiveSignature = idx;
    }

    // ── Déclenchement ─────────────────────────────────────────────

    /**
     * Force l'affichage de l'aide de signature (Ctrl+P ou déclenchement
     * explicite). Réinitialise l'état « rejeté » et incrémente l'époque.
     *
     * @param text   texte du document
     * @param caret  offset courant du caret
     */
    public void triggerExplicit(CharSequence text, int caret) {
        dismissed = false;
        epoch++;
        resolve(text, caret);
    }

    /**
     * Rejette l'aide de signature pour l'appel courant. Positionne le
     * drapeau « rejeté » pour qu'elle ne réapparaisse pas tant que le
     * caret ne passe pas à un autre appel.
     */
    public void dismiss() {
        dismissed = true;
        help = null;
    }

    /**
     * Re-résout l'aide de signature au déplacement du caret.
     * Ne résout que si le caret est à l'intérieur d'un appel et que le
     * popup n'a pas été rejeté pour l'appel courant.
     *
     * <p>Quand le caret passe à un autre appel (la position de la
     * parenthèse ouvrante change), l'override de surcharge choisi par
     * l'utilisateur est réinitialisé pour que le popup affiche la
     * signature active par défaut du nouvel appel.
     *
     * @param text   texte du document
     * @param caret  offset courant du caret
     */
    public void resolve(CharSequence text, int caret) {
        // Trouve la parenthèse ouvrante englobante EN PREMIER — même
        // rejeté, il faut savoir si le caret est passé à un autre appel
        // pour pouvoir réinitialiser le drapeau « rejeté ».
        int callOpen = findCallOpen(text, caret);
        if (callOpen < 0) {
            help = null;
            lastCallOpenPos = -1;
            // Quitter complètement l'appel → réinitialise aussi l'override.
            userOverrideActiveSignature = -1;
            return;
        }

        // Si on est passé à un autre appel, réinitialise « rejeté » pour
        // que le popup puisse réapparaître pour le nouvel appel.
        if (callOpen != lastCallOpenPos) {
            dismissed = false;
            lastCallOpenPos = callOpen;
            // Nouvel appel → oublie le choix de surcharge précédent de l'utilisateur.
            userOverrideActiveSignature = -1;
        }

        if (dismissed) return;

        // Résolution
        if (listener != null) {
            epoch++;
            help = listener.resolve(caret, callOpen);
        }
    }

    /**
     * Test rapide : le caret est-il à l'intérieur d'un appel de fonction ?
     * Scanne en arrière une '(' non appariée.
     *
     * @param chars texte du document
     * @param caret offset courant du caret
     * @return true si le caret est dans un appel
     */
    public static boolean caretInsideCall(CharSequence chars, int caret) {
        return findCallOpen(chars, caret) >= 0;
    }

    /**
     * Trouve la position de la '(' non appariée avant le caret.
     * Renvoie -1 si le caret n'est pas dans un appel.
     *
     * <p><b>Lot 3 (audit v3.41.0)</b> : le scan ignore désormais les
     * littéraux de chaîne/char, les commentaires de ligne et les
     * commentaires de bloc. Avant ce correctif, un {@code (} dans
     * une chaîne ({@code "abc(def"}), dans un commentaire
     * ({@code // foo(bar)} ou {@code /* foo( *\/}), ou un
     * {@code ;} dans une chaîne ({@code "abc;def"}) étaient
     * traités comme des frontières d'instruction/appel — faux
     * positifs et faux négatifs pour l'aide de signature.</p>
     *
     * <p>Le scan est forward depuis le début du document, tracking
     * l'état string/comment, et reset sur {@code ;} et {@code {}}
     * (uniquement hors chaîne/commentaire). La complexité est
     * O(caret) par appel, comme {@code isInString} — acceptable
     * pour le thread UI car les debounces limitent la fréquence.</p>
     */
    public static int findCallOpen(CharSequence chars, int caret) {
        if (caret <= 0) return -1;
        int limit = Math.min(caret, chars.length());
        int depth = 0;
        int callOpen = -1;
        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < limit; i++) {
            char ch = chars.charAt(i);
            if (inLineComment) {
                if (ch == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (ch == '*' && i + 1 < chars.length() && chars.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    i++; // sauter le caractère échappé
                } else if (ch == quoteChar) {
                    inString = false;
                }
                continue;
            }
            // Hors chaîne/commentaire.
            if (ch == '/' && i + 1 < chars.length()) {
                char next = chars.charAt(i + 1);
                if (next == '/') { inLineComment = true; i++; continue; }
                if (next == '*') { inBlockComment = true; i++; continue; }
            }
            if (ch == '"' || ch == '\'') {
                inString = true;
                quoteChar = ch;
                continue;
            }
            // Frontières d'instruction/bloc (hors chaîne/commentaire).
            if (ch == ';' || ch == '{') {
                depth = 0;
                callOpen = -1;
                continue;
            }
            if (ch == '(') {
                if (depth == 0) callOpen = i;
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) callOpen = -1;
            }
        }
        // Si le caret lui-même est dans une chaîne ou un commentaire,
        // ne pas déclencher l'aide de signature.
        if (inString || inLineComment || inBlockComment) return -1;
        return callOpen;
    }

    /**
     * Compte le nombre de virgules entre la parenthèse ouvrante et le
     * caret pour déterminer l'index du paramètre actif.
     *
     * <p><b>Lot 3 (audit v3.41.0)</b> : le scan ignore les littéraux
     * de chaîne/char, les commentaires de ligne et de bloc. Une
     * virgule dans {@code "hello, world"} ou dans
     * {@code // une, virgule} n'incrémente plus l'index du paramètre.</p>
     *
     * @param chars    texte du document
     * @param callOpen position de la '('
     * @param caret    offset courant du caret
     * @return index du paramètre (base 0)
     */
    public static int activeParameterIndex(CharSequence chars, int callOpen, int caret) {
        if (callOpen < 0 || caret <= callOpen) return 0;
        int depth = 0;
        int commas = 0;
        int start = callOpen + 1;
        int end = Math.min(caret, chars.length());
        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = start; i < end; i++) {
            char ch = chars.charAt(i);
            if (inLineComment) {
                if (ch == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (ch == '*' && i + 1 < chars.length() && chars.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quoteChar) {
                    inString = false;
                }
                continue;
            }
            if (ch == '/' && i + 1 < chars.length()) {
                char next = chars.charAt(i + 1);
                if (next == '/') { inLineComment = true; i++; continue; }
                if (next == '*') { inBlockComment = true; i++; continue; }
            }
            if (ch == '"' || ch == '\'') {
                inString = true;
                quoteChar = ch;
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
            else if (ch == ',' && depth == 0) commas++;
        }
        return commas;
    }

    // ── Réinitialisation ──────────────────────────────────────────

    /**
     * Réinitialise tout l'état du contrôleur : aide, drapeau « rejeté »,
     * époque, position de la parenthèse ouvrante du dernier appel, et
     * l'override de surcharge de l'utilisateur.
     */
    public void reset() {
        help = null;
        dismissed = false;
        epoch = 0;
        lastCallOpenPos = -1;
        // Efface aussi l'override de surcharge.
        userOverrideActiveSignature = -1;
    }
}
