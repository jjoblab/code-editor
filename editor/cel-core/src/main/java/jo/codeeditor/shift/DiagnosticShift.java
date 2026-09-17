package jo.codeeditor.shift;

import java.util.*;

/**
 * Décale les offsets de diagnostics, jetons sémantiques, régions pliables
 * et inlay hints à travers les éditions de texte. Reprend le design du
 * {@code DiagnosticShift.kt} de CodeAssist.
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

        public Diagnostic(int start, int end, int severity, String message) {
            this.start = start;
            this.end = end;
            this.severity = severity;
            this.message = message;
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
                result.add(new Diagnostic(newStart, newEnd, d.severity, d.message));
            }
        }
        return result;
    }

    /**
     * Jeton sémantique avec offsets.
     */
    public static final class SemanticToken {
        public int start;
        public int length;
        public final int type;

        public SemanticToken(int start, int length, int type) {
            this.start = start;
            this.length = length;
            this.type = type;
        }

        public int end() { return start + length; }
    }

    /**
     * Décale les jetons sémantiques à travers une édition.
     */
    public static List<SemanticToken> shiftSemanticTokens(List<SemanticToken> tokens, EditSpan span) {
        List<SemanticToken> result = new ArrayList<>();
        for (SemanticToken t : tokens) {
            int newStart = mapStart(t.start, span);
            int newEnd = mapEnd(t.start + t.length, span);
            int newLen = newEnd - newStart;
            if (newLen > 0) {
                result.add(new SemanticToken(newStart, newLen, t.type));
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
         * Repliée PAR DÉFAUT (CodeAssist {@code collapsedByDefault}) :
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
         * serveur : groupe d'imports replié à l'ouverture comme
         * CodeAssist/IntelliJ).
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
     */
    public static final class InlayHint {
        public int offset;
        public final String text;
        public final boolean before;

        public InlayHint(int offset, String text, boolean before) {
            this.offset = offset;
            this.text = text;
            this.before = before;
        }
    }

    /**
     * Décale les inlay hints à travers une édition.
     */
    public static List<InlayHint> shiftInlayHints(List<InlayHint> hints, EditSpan span) {
        List<InlayHint> result = new ArrayList<>();
        for (InlayHint h : hints) {
            int newOffset = mapStart(h.offset, span);
            result.add(new InlayHint(newOffset, h.text, h.before));
        }
        return result;
    }
}
