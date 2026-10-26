package jo.codeeditor.shift;

import java.util.*;

/**
 * Décale les offsets de diagnostics, jetons sémantiques, régions pliables
 * et inlay hints à travers les éditions de texte. Reprend le design du
 * le décalage des annotations après édition.
 * <p>
 * Utilise la gravité droite pour les débuts (ils suivent l'édition) et la
 * gravité gauche pour les fins (ils restent en place à la frontière de
 * l'édition).
 */
public final class DiagnosticShift {

    private DiagnosticShift() {}

    /**
     * Retrouve un EditSpan minimal en comparant ancien et nouveau texte.
     * Utilise préfixe/suffixe communs.
     */
    public static EditSpan diffEdit(CharSequence oldText, CharSequence newText) {
        int oldLen = oldText.length();
        int newLen = newText.length();

        // Préfixe commun
        int prefix = 0;
        int maxPrefix = Math.min(oldLen, newLen);
        while (prefix < maxPrefix && oldText.charAt(prefix) == newText.charAt(prefix)) {
            prefix++;
        }

        // Suffixe commun (sans chevaucher le préfixe)
        int suffix = 0;
        int maxSuffix = Math.min(oldLen - prefix, newLen - prefix);
        while (suffix < maxSuffix
            && oldText.charAt(oldLen - 1 - suffix) == newText.charAt(newLen - 1 - suffix)) {
            suffix++;
        }

        int removed = oldLen - prefix - suffix;
        int added = newLen - prefix - suffix;

        return new EditSpan(prefix, removed, added);
    }

    /**
     * Mappe un offset de début à travers un EditSpan (gravité droite).
     * Si l'offset est au début de l'édition, il suit l'édition.
     */
    public static int mapStart(int offset, EditSpan span) {
        if (offset <= span.start) return offset;
        if (offset <= span.end()) return span.start + span.added;
        return offset + span.delta();
    }

    /**
     * Mappe un offset de fin à travers un EditSpan (gravité gauche).
     * Si l'offset est à la fin de l'édition, il reste en place.
     */
    public static int mapEnd(int offset, EditSpan span) {
        if (offset < span.start) return offset;
        if (offset < span.end()) return span.start;
        return offset + span.delta();
    }

    /**
     * Diagnostic avec offsets à décaler.
     */
    public static final class Diagnostic {
        public int start;
        public int end;
        public final int severity;
        public final String message;
        /** Tag LSP Unnecessary (code inutilisé) — la plage est peinte
         * estompée (grisé Android Studio) au lieu du soulignement de
         * sévérité. @since 3.51.0 */
        public final boolean unnecessary;
        /** D4 (lot 2, J5) — code du diagnostic (ex. « ecj:P1234 »)
         * transporté jusqu'au rendu (en-tête enrichi du popup/fiche).
         * Vide par défaut (constructeurs historiques). @since 3.56.0 */
        public final String code;
        /** D4 (lot 2, J5) — source du diagnostic (ex. « java », « ecj »).
         * Vide par défaut. @since 3.56.0 */
        public final String source;

        public Diagnostic(int start, int end, int severity, String message) {
            this(start, end, severity, message, false);
        }

        public Diagnostic(int start, int end, int severity, String message,
                boolean unnecessary) {
            this(start, end, severity, message, unnecessary, "", "");
        }

        /** Variante complète (D4) : code et source transportés — le
         *  décalage incrémental les conserve à travers les éditions. */
        public Diagnostic(int start, int end, int severity, String message,
                boolean unnecessary, String code, String source) {
            this.start = start;
            this.end = end;
            this.severity = severity;
            this.message = message;
            this.unnecessary = unnecessary;
            this.code = code != null ? code : "";
            this.source = source != null ? source : "";
        }
    }

    /**
     * Décale une liste de diagnostics à travers une édition.
     * Supprime les diagnostics réduits à longueur nulle.
     */
    public static List<Diagnostic> shiftDiagnostics(List<Diagnostic> diagnostics, EditSpan span) {
        List<Diagnostic> result = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            int newStart = mapStart(d.start, span);
            int newEnd = mapEnd(d.end, span);
            if (newEnd > newStart) {
                result.add(new Diagnostic(newStart, newEnd, d.severity, d.message,
                        d.unnecessary, d.code, d.source));
            }
        }
        return result;
    }

    /**
     * Jeton sémantique avec offsets.
     *
     * <p><b>Modificateurs (4.4.0)</b> : le 5ᵉ élément du quintuplet LSP
     * (bitmask de modificateurs du serveur) est résolu PAR NOM via la
     * légende du serveur vers ce masque CANONIQUE — indépendant de
     * l'ordre de la légende d'un serveur particulier. Les bits non
     * interprétés aujourd'hui sont ignorés (le masque brut n'est PAS
     * conservé : seuls les modificateurs que le rendu sait appliquer
     * voyagent). Les jetons construits par l'hôte (injection directe)
     * posent ces constantes directement.</p>
     */
    public static final class SemanticToken {
        /** Modificateur « déprécié » — rendu barré (surcouche thème
         *  {@code semantic.modifier.deprecated}). */
        public static final int MOD_DEPRECATED = 1 << 0;
        /** Modificateur « statique » — rendu gras (surcouche thème
         *  {@code semantic.modifier.static}). */
        public static final int MOD_STATIC = 1 << 1;
        /** Modificateur « abstrait » — rendu italique (surcouche thème
         *  {@code semantic.modifier.abstract}). */
        public static final int MOD_ABSTRACT = 1 << 2;

        public int start;
        public int length;
        public final int type;
        /** Masque canonique de modificateurs (0 = aucun). @since 4.4.0 */
        public final int modifiers;

        public SemanticToken(int start, int length, int type) {
            this(start, length, type, 0);
        }

        /** Variante complète (4.4.0) — transporte les modificateurs. */
        public SemanticToken(int start, int length, int type, int modifiers) {
            this.start = start;
            this.length = length;
            this.type = type;
            this.modifiers = modifiers;
        }

        public int end() { return start + length; }
    }

    /**
     * Décale les jetons sémantiques à travers une édition — les
     * modificateurs voyagent tels quels (ils décrivent le symbole, pas
     * la position).
     */
    public static List<SemanticToken> shiftSemanticTokens(List<SemanticToken> tokens, EditSpan span) {
        List<SemanticToken> result = new ArrayList<>();
        for (SemanticToken t : tokens) {
            int newStart = mapStart(t.start, span);
            int newEnd = mapEnd(t.start + t.length, span);
            int newLen = newEnd - newStart;
            if (newLen > 0) {
                result.add(new SemanticToken(newStart, newLen, t.type, t.modifiers));
            }
        }
        return result;
    }

    /**
     * Région pliable avec offsets.
     */
    public static final class FoldRegion {
        public int start;
        public int end;
        public final String placeholder;
        /** Kind de pli : « block », « comment », « imports », « region »… (purement informatif). */
        public final String kind;
        /** true quand le pli est actuellement réduit (masqué). */
        public final boolean collapsed;
        /**
         * Repliée PAR DÉFAUT :
         * appliquée UNE SEULE FOIS par document (imports) puis l'état
         * utilisateur l'emporte — cf. {@code EditorSession.applyCodeFolds}.
         */
        public final boolean collapsedByDefault;

        public FoldRegion(int start, int end, String placeholder) {
            this(start, end, placeholder, "block", false);
        }

        public FoldRegion(int start, int end, String placeholder, String kind, boolean collapsed) {
            this(start, end, placeholder, kind, collapsed, false);
        }

        /**
         * Variante complète (collapsedByDefault pour le pliage
         * serveur : groupe d'imports replié à l'ouverture par
         * défaut).
         */
        public FoldRegion(int start, int end, String placeholder, String kind,
                boolean collapsed, boolean collapsedByDefault) {
            this.start = start;
            this.end = end;
            this.placeholder = placeholder != null ? placeholder : "...";
            this.kind = kind != null ? kind : "block";
            this.collapsed = collapsed;
            this.collapsedByDefault = collapsedByDefault;
        }
    }

    /**
     * Décale les régions pliables à travers une édition.
     */
    public static List<FoldRegion> shiftFoldRegions(List<FoldRegion> regions, EditSpan span) {
        List<FoldRegion> result = new ArrayList<>();
        for (FoldRegion r : regions) {
            int newStart = mapStart(r.start, span);
            int newEnd = mapEnd(r.end, span);
            if (newEnd > newStart) {
                result.add(new FoldRegion(newStart, newEnd, r.placeholder, r.kind, r.collapsed));
            }
        }
        return result;
    }

    /**
     * Inlay hint avec offset.
     *
     * <p>I1 (lot 2, J3) : le genre du hint ({@code "type"} par défaut,
     * {@code "parameter"}) est porté jusqu'au rendu — le constructeur
     * 3-arg historique reste « type » (zéro rupture) ; le décalage
     * incrémental le conserve à travers les éditions. I2 (J3) : le champ
     * {@code tooltip} (null par défaut) porte le tooltip direct du serveur
     * — un tooltip null est résolu paresseusement par le provider au
     * premier appui long. I3 (J6) : les parts structurées du libellé
     * ({@code parts}, null par défaut) portent le tooltip PROPRE à
     * chaque segment — consulté par l'appui long quand le doigt tombe
     * sur la sous-plage tissée de la part (invariant : concat des
     * {@link InlayHintPart#text} == {@code text}).</p>
     */
    public static final class InlayHint {
        public int offset;
        public final String text;
        public final boolean before;
        /** Le genre du hint : « type » (droit) ou « parameter » (italique). */
        public final String kind;
        /** Tooltip du hint (null = pas encore résolu — I2, J3). */
        public final String tooltip;
        /** Parts structurées du libellé (I3, J6) — null pour un hint plat. */
        public final java.util.List<InlayHintPart> parts;
        /**
         * Commande du hint (I4, J6) — exécutée au tap simple sur la
         * plage tissée, AVANT le placement du caret. Null : hint passif.
         */
        public final Runnable command;

        public InlayHint(int offset, String text, boolean before) {
            this(offset, text, before, "type", null, null, null);
        }

        public InlayHint(int offset, String text, boolean before, String kind) {
            this(offset, text, before, kind, null, null, null);
        }

        public InlayHint(int offset, String text, boolean before, String kind,
                String tooltip) {
            this(offset, text, before, kind, tooltip, null, null);
        }

        public InlayHint(int offset, String text, boolean before, String kind,
                String tooltip, java.util.List<InlayHintPart> parts) {
            this(offset, text, before, kind, tooltip, parts, null);
        }

        public InlayHint(int offset, String text, boolean before, String kind,
                String tooltip, java.util.List<InlayHintPart> parts,
                Runnable command) {
            this.offset = offset;
            this.text = text != null ? text : "";
            this.before = before;
            this.kind = kind != null ? kind : "type";
            this.tooltip = tooltip;
            this.parts = parts != null && !parts.isEmpty()
                    ? java.util.Collections.unmodifiableList(parts) : null;
            this.command = command;
        }
    }

    /** Une part de libellé d'inlay hint (I3, lot 2 J6) — segment de
     *  texte avec son PROPRE tooltip (le hit-test de l'appui long
     *  consulte celui de la part sous le doigt). */
    public static final class InlayHintPart {
        /** Le texte de la part (jamais null — peut être un blanc de padding). */
        public final String text;
        /** Le tooltip propre à la part (null si aucun). */
        public final String tooltip;

        public InlayHintPart(String text, String tooltip) {
            this.text = text != null ? text : "";
            this.tooltip = tooltip;
        }
    }

    /**
     * Décale les inlay hints à travers une édition (genre, tooltip et
     * parts conservés).
     */
    public static List<InlayHint> shiftInlayHints(List<InlayHint> hints, EditSpan span) {
        List<InlayHint> result = new ArrayList<>();
        for (InlayHint h : hints) {
            int newOffset = mapStart(h.offset, span);
            result.add(new InlayHint(newOffset, h.text, h.before, h.kind,
                    h.tooltip, h.parts, h.command));
        }
        return result;
    }
}
