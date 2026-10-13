package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.InlayHintProvider;

import java.util.Collections;
import java.util.List;

/**
 * Un inlay hint (texte fantôme inséré à un offset). Retourné par
 * {@link InlayHintProvider}.
 *
 * <p>I2 (lot 2, J3) : le champ {@code tooltip} additionnel (null par
 * défaut — zéro rupture) porte soit le tooltip servi directement par le
 * serveur, soit celui résolu paresseusement par
 * {@link InlayHintProvider#resolveHint} au premier appui long.</p>
 *
 * <p>I4 (lot 2, J6) : un hint peut être CLIQUABLE ({@code command},
 * null par défaut — zéro rupture) : un tap simple sur sa plage tissée
 * exécute la commande AVANT le placement du caret (précédent complétion).
 * Usage type : hints « … » d'expansion. Le Runnable tourne sur le thread
 * UI ; une commande qui édite le document doit laisser l'éditeur
 * rafraîchir (le tap-resolveur appelle {@code onTextChanged()} après
 * l'exécution).</p>
 *
 * <p>I3 (lot 2, J6) : le libellé peut être STRUCTURÉ en parts
 * ({@code parts}, null par défaut — zéro rupture). Le texte affiché
 * reste {@code text} (l'assemblage des parts) — le rendu ne change
 * pas d'un iota ; chaque part porte son PROPRE tooltip, consulté par
 * l'appui long (I2) quand le doigt tombe sur sa sous-plage tissée.
 * Un serveur riche (IntelliJ-like) peut ainsi lier « le nom du
 * paramètre » et « son type » à deux explications distinctes dans un
 * même hint. L'invariant : la concaténation des
 * {@link Part#text} des parts est ÉGALE à {@code text} quand
 * {@code parts != null} (le pont LSP l'assure, padding compris —
 * voir la javadoc de {@link Part}).</p>
 */
public final class InlayHint {

    /** L'offset où le hint est inséré. */
    public final int offset;
    /** Le texte du hint (ex. « : String »). */
    public final String text;
    /** Le type de hint : « type », « parameter », « decorator ». */
    public final String kind;
    /** Le tooltip du hint (null si le serveur n'en fournit pas). */
    public final String tooltip;
    /**
     * Les parts structurées du libellé (I3, lot 2 J6) — null quand le
     * serveur sert un libellé plat : le hint reste monolithique et seul
     * {@code tooltip} s'applique. Jamais vide (une liste vide serait
     * un libellé vide — {@code text} vide fait ce travail).
     */
    public final List<Part> parts;
    /**
     * Commande du hint (I4, lot 2 J6) — exécutée au TAP simple sur la
     * plage tissée, avant le placement du caret. Null par défaut : un
     * hint sans commande reste du texte fantôme passif.
     */
    public final Runnable command;

    public InlayHint(int offset, String text, String kind) {
        this(offset, text, kind, null, null, null);
    }

    public InlayHint(int offset, String text, String kind, String tooltip) {
        this(offset, text, kind, tooltip, null, null);
    }

    public InlayHint(int offset, String text, String kind, String tooltip,
            List<Part> parts) {
        this(offset, text, kind, tooltip, parts, null);
    }

    public InlayHint(int offset, String text, String kind, String tooltip,
            List<Part> parts, Runnable command) {
        this.offset = offset;
        this.text = text != null ? text : "";
        this.kind = kind != null ? kind : "type";
        this.tooltip = tooltip;
        this.parts = parts != null && !parts.isEmpty()
                ? Collections.unmodifiableList(parts) : null;
        this.command = command;
    }

    /**
     * Une part de libellé d'inlay hint (I3, lot 2 J6) : un segment de
     * texte avec son PROPRE tooltip. La concaténation des
     * {@code text} des parts reconstitue le {@code text} du hint —
     * le padding LSP (espaces de respiration autour du hint) est
     * porté par des parts de blanc SANS tooltip, pour que l'invariant
     * tienne aussi quand le serveur demande du padding.
     */
    public static final class Part {

        /** Le texte de cette part (jamais null, peut être un blanc de padding). */
        public final String text;
        /** Le tooltip propre à cette part (null : rien à consulter ici). */
        public final String tooltip;

        public Part(String text) {
            this(text, null);
        }

        public Part(String text, String tooltip) {
            this.text = text != null ? text : "";
            this.tooltip = tooltip;
        }
    }
}
