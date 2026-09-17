package jo.codeeditor.view;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keymap rebindable piloté par les données (portage de l'
 * {@code EditorKeymap} de CodeAssist).
 *
 * <p>Un keymap est une table ordonnée de {@link Binding}s (identifiant de
 * commande + code touche + modificateurs ctrl/shift). {@link EditorKeyHandler}
 * résout chaque événement touche matérielle via le keymap de la vue et
 * exécute la commande correspondante — la cascade historique de blocs
 * {@code if}/{@code switch} a disparu, et les hôtes peuvent rebinder,
 * ajouter ou retirer des raccourcis à l'exécution :</p>
 *
 * <pre>{@code
 * EditorKeymap km = EditorKeymap.defaults()
 *         .unbind(EditorCommands.UNDO)
 *         .bind(EditorCommands.UNDO, KeyEvent.KEYCODE_BACK, false, false);
 * view.setKeymap(km);
 * }</pre>
 *
 * <h2>Ordre de résolution</h2>
 * <p>{@link #resolve(int, boolean, boolean)} parcourt quatre passes pour
 * que la table puisse être exacte tout en gardant les habitudes
 * historiques de modificateurs :</p>
 * <ol>
 *   <li>correspondance exacte (keyCode + ctrl + shift) ;</li>
 *   <li>ignorer ctrl, garder shift — ex. Ctrl+Gauche déplace quand même
 *       le caret, Ctrl+Tab indente quand même ;</li>
 *   <li>garder ctrl, ignorer shift — ex. Ctrl+Shift+A sélectionne quand
 *       même tout (le switch ctrl historique ignorait shift) ;</li>
 *   <li>ignorer les deux — ex. Shift+Entrée insère quand même une ligne.</li>
 * </ol>
 * <p>Alt/meta ne font jamais partie d'une liaison (indifférents).</p>
 *
 * <h2>Chords</h2>
 * <p>Séquences à deux touches (portage du {@code Outcome.Pending} de
 * CodeAssist) : la première touche arme un état en attente de courte
 * durée, la seconde complète (ou abandonne) la séquence — raccourcis
 * façon IntelliJ {@code Ctrl+K Ctrl+C}. Une touche qui possède déjà une
 * liaison mono-touche se résout TOUJOURS d'abord comme cette liaison ;
 * les chords ne capturent que les touches qui seraient sinon traitées
 * en repli, donc la table par défaut (sans chord) reste 100 % compatible
 * au comportement historique :</p>
 *
 * <pre>{@code
 * EditorKeymap km = EditorKeymap.defaults()
 *         .bindChord(EditorCommands.TOGGLE_LINE_COMMENT,
 *                 KeyStroke.of(KeyEvent.KEYCODE_K, true, false),
 *                 KeyStroke.of(KeyEvent.KEYCODE_C, true, false));
 * view.setKeymap(km);   // Ctrl+K puis Ctrl+C = commenter les lignes
 * }</pre>
 *
 * <p>Pendant qu'un chord est en attente, Échap l'annule et l'état en
 * attente expire après 2 s de silence. La table par défaut reproduit le
 * comportement historique de {@code EditorKeyHandler} : Ctrl+Z/Y/A/C/X/V/D/F/S,
 * Ctrl+Space, Ctrl+P, Ctrl+., Ctrl+Shift+O/I/L, Ctrl+Plus/Minus/0,
 * Ctrl+G, Retour arrière/Suppr/Entrée/Tab/Espace, flèches + Home/End/PageUp/
 * PageDown (Shift = étendre), F1/F2/F12 (Shift+F12 = références).</p>
 *
 * <p>Non thread-safe — lire et muter sur le thread UI, puis confier
 * l'instance à {@code EditorView.setKeymap}.</p>
 */
public final class EditorKeymap {

    /** Une ligne de table : {@code command} lié à {@code keyCode} + modificateurs. */
    public static final class Binding {
        public final String command;
        public final int keyCode;
        public final boolean ctrl;
        public final boolean shift;

        public Binding(String command, int keyCode, boolean ctrl, boolean shift) {
            this.command = command;
            this.keyCode = keyCode;
            this.ctrl = ctrl;
            this.shift = shift;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Binding)) return false;
            Binding b = (Binding) o;
            return keyCode == b.keyCode && ctrl == b.ctrl && shift == b.shift
                    && command.equals(b.command);
        }

        @Override
        public int hashCode() {
            int h = command.hashCode();
            h = 31 * h + keyCode;
            h = 31 * h + (ctrl ? 1 : 0);
            h = 31 * h + (shift ? 1 : 0);
            return h;
        }

        @Override
        public String toString() {
            return command + "@" + KeyEvent.keyCodeToString(keyCode)
                    + (ctrl ? "+ctrl" : "") + (shift ? "+shift" : "");
        }
    }

    /**
     * Un segment d'événement touche physique d'un chord : code touche +
     * modificateurs ctrl/shift (alt/meta indifférents, comme les liaisons
     * simples). Classe de valeur avec égalité structurelle.
     */
    public static final class KeyStroke {
        public final int keyCode;
        public final boolean ctrl;
        public final boolean shift;

        public KeyStroke(int keyCode, boolean ctrl, boolean shift) {
            this.keyCode = keyCode;
            this.ctrl = ctrl;
            this.shift = shift;
        }

        /** Fabrique (lisible aux sites d'appel : {@code KeyStroke.of(KEYCODE_K, true, false)}). */
        public static KeyStroke of(int keyCode, boolean ctrl, boolean shift) {
            return new KeyStroke(keyCode, ctrl, shift);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof KeyStroke)) return false;
            KeyStroke k = (KeyStroke) o;
            return keyCode == k.keyCode && ctrl == k.ctrl && shift == k.shift;
        }

        @Override
        public int hashCode() {
            int h = keyCode;
            h = 31 * h + (ctrl ? 1 : 0);
            h = 31 * h + (shift ? 1 : 0);
            return h;
        }

        @Override
        public String toString() {
            return KeyEvent.keyCodeToString(keyCode)
                    + (ctrl ? "+ctrl" : "") + (shift ? "+shift" : "");
        }
    }

    /** Une ligne de table de chord : {@code command} lié à la séquence à
     * deux frappes {@code first}, puis {@code second}. */
    public static final class ChordBinding {
        public final String command;
        public final KeyStroke first;
        public final KeyStroke second;

        public ChordBinding(String command, KeyStroke first, KeyStroke second) {
            if (command == null || first == null || second == null) {
                throw new IllegalArgumentException("command/first/second is null");
            }
            this.command = command;
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChordBinding)) return false;
            ChordBinding c = (ChordBinding) o;
            return command.equals(c.command) && first.equals(c.first)
                    && second.equals(c.second);
        }

        @Override
        public int hashCode() {
            int h = command.hashCode();
            h = 31 * h + first.hashCode();
            h = 31 * h + second.hashCode();
            return h;
        }

        @Override
        public String toString() {
            return command + "@" + first + " " + second;
        }
    }

    private final List<Binding> table = new ArrayList<>();
    private final List<ChordBinding> chords = new ArrayList<>();

    /** Un keymap vide (toute touche retombe sur le chemin imprimable). */
    public EditorKeymap() {}

    /**
     * La table par défaut — un portage verbatim des cascades de switch
     * historiques de {@code EditorKeyHandler}.
     */
    public static EditorKeymap defaults() {
        EditorKeymap km = new EditorKeymap();
        // ── Historique ─────────────────────────────────────────
        km.bind(EditorCommands.UNDO, KeyEvent.KEYCODE_Z, true, false);
        km.bind(EditorCommands.REDO, KeyEvent.KEYCODE_Y, true, false);
        // ── Sélection / presse-papiers ───────────────────────────
        km.bind(EditorCommands.SELECT_ALL, KeyEvent.KEYCODE_A, true, false);
        km.bind(EditorCommands.COPY, KeyEvent.KEYCODE_C, true, false);
        km.bind(EditorCommands.CUT, KeyEvent.KEYCODE_X, true, false);
        km.bind(EditorCommands.PASTE, KeyEvent.KEYCODE_V, true, false);
        km.bind(EditorCommands.DUPLICATE, KeyEvent.KEYCODE_D, true, false);
        // ── Fichier / actions hôte ─────────────────────────────
        km.bind(EditorCommands.FIND, KeyEvent.KEYCODE_F, true, false);
        km.bind(EditorCommands.SAVE, KeyEvent.KEYCODE_S, true, false);
        // ── Intelligence du langage ───────────────────────────
        km.bind(EditorCommands.TRIGGER_COMPLETION, KeyEvent.KEYCODE_SPACE, true, false);
        km.bind(EditorCommands.TRIGGER_SIGNATURE_HELP, KeyEvent.KEYCODE_P, true, false);
        km.bind(EditorCommands.CODE_ACTIONS, KeyEvent.KEYCODE_PERIOD, true, false);
        km.bind(EditorCommands.GO_TO_SYMBOL, KeyEvent.KEYCODE_O, true, true);
        km.bind(EditorCommands.FORMAT_DOCUMENT, KeyEvent.KEYCODE_I, true, true);
        km.bind(EditorCommands.CODE_ACTIONS_AT_CARET, KeyEvent.KEYCODE_L, true, true);
        km.bind(EditorCommands.QUICK_DOC, KeyEvent.KEYCODE_F1, false, false);
        km.bind(EditorCommands.RENAME, KeyEvent.KEYCODE_F2, false, false);
        km.bind(EditorCommands.GO_TO_DEFINITION, KeyEvent.KEYCODE_F12, false, false);
        km.bind(EditorCommands.FIND_REFERENCES, KeyEvent.KEYCODE_F12, false, true);
        // ── Navigation ────────────────────────────────────────────
        km.bind(EditorCommands.GO_TO_LINE, KeyEvent.KEYCODE_G, true, false);
        // ── Zoom ──────────────────────────────────────────────────
        km.bind(EditorCommands.ZOOM_IN, KeyEvent.KEYCODE_EQUALS, true, false);
        km.bind(EditorCommands.ZOOM_IN, KeyEvent.KEYCODE_PLUS, true, false);
        km.bind(EditorCommands.ZOOM_IN, KeyEvent.KEYCODE_NUMPAD_ADD, true, false);
        km.bind(EditorCommands.ZOOM_OUT, KeyEvent.KEYCODE_MINUS, true, false);
        km.bind(EditorCommands.ZOOM_OUT, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, true, false);
        km.bind(EditorCommands.ZOOM_RESET, KeyEvent.KEYCODE_0, true, false);
        // ── Édition ─────────────────────────────────────────────
        km.bind(EditorCommands.BACKSPACE, KeyEvent.KEYCODE_DEL, false, false);
        km.bind(EditorCommands.DELETE_FORWARD, KeyEvent.KEYCODE_FORWARD_DEL, false, false);
        km.bind(EditorCommands.NEW_LINE, KeyEvent.KEYCODE_ENTER, false, false);
        km.bind(EditorCommands.NEW_LINE, KeyEvent.KEYCODE_NUMPAD_ENTER, false, false);
        km.bind(EditorCommands.NEW_LINE, KeyEvent.KEYCODE_DPAD_CENTER, false, false);
        km.bind(EditorCommands.INDENT, KeyEvent.KEYCODE_TAB, false, false);
        km.bind(EditorCommands.DEDENT, KeyEvent.KEYCODE_TAB, false, true);
        km.bind(EditorCommands.INSERT_SPACE, KeyEvent.KEYCODE_SPACE, false, false);
        // ── Déplacement du caret (Shift = étendre) ─────────────
        km.bind(EditorCommands.MOVE_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, false, false);
        km.bind(EditorCommands.EXTEND_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, false, true);
        km.bind(EditorCommands.MOVE_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, false, false);
        km.bind(EditorCommands.EXTEND_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, false, true);
        km.bind(EditorCommands.MOVE_UP, KeyEvent.KEYCODE_DPAD_UP, false, false);
        km.bind(EditorCommands.EXTEND_UP, KeyEvent.KEYCODE_DPAD_UP, false, true);
        km.bind(EditorCommands.MOVE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, false, false);
        km.bind(EditorCommands.EXTEND_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, false, true);
        km.bind(EditorCommands.LINE_START, KeyEvent.KEYCODE_MOVE_HOME, false, false);
        km.bind(EditorCommands.EXTEND_LINE_START, KeyEvent.KEYCODE_MOVE_HOME, false, true);
        km.bind(EditorCommands.LINE_END, KeyEvent.KEYCODE_MOVE_END, false, false);
        km.bind(EditorCommands.EXTEND_LINE_END, KeyEvent.KEYCODE_MOVE_END, false, true);
        km.bind(EditorCommands.PAGE_UP, KeyEvent.KEYCODE_PAGE_UP, false, false);
        km.bind(EditorCommands.EXTEND_PAGE_UP, KeyEvent.KEYCODE_PAGE_UP, false, true);
        km.bind(EditorCommands.PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, false, false);
        km.bind(EditorCommands.EXTEND_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, false, true);
        return km;
    }

    /**
     * Lie {@code keyCode}+modificateurs à {@code command} (fluent). Une
     * liaison ultérieure pour la même touche+modificateurs remplace la
     * précédente ; une même commande peut vivre sur plusieurs touches
     * (ex. ZOOM_IN sur =/+/pavé num.+).
     */
    public EditorKeymap bind(String command, int keyCode, boolean ctrl, boolean shift) {
        if (command == null) throw new IllegalArgumentException("command is null");
        for (int i = 0; i < table.size(); i++) {
            Binding b = table.get(i);
            if (b.keyCode == keyCode && b.ctrl == ctrl && b.shift == shift) {
                table.set(i, new Binding(command, keyCode, ctrl, shift));
                return this;
            }
        }
        table.add(new Binding(command, keyCode, ctrl, shift));
        return this;
    }

    /** Retire TOUTES les liaisons de {@code command} — lignes mono-touche ET
     * lignes de chord (fluent). */
    public EditorKeymap unbind(String command) {
        if (command == null) return this;
        table.removeIf(b -> b.command.equals(command));
        chords.removeIf(c -> c.command.equals(command));
        return this;
    }

    /** Vrai quand {@code command} possède au moins une liaison (mono-touche
     * ou chord). */
    public boolean isBound(String command) {
        for (Binding b : table) {
            if (b.command.equals(command)) return true;
        }
        for (ChordBinding c : chords) {
            if (c.command.equals(command)) return true;
        }
        return false;
    }

    /** La première liaison de {@code command} (introspection / UI), ou null. */
    public Binding bindingFor(String command) {
        for (Binding b : table) {
            if (b.command.equals(command)) return b;
        }
        return null;
    }

    /** Une vue non modifiable de toute la table (introspection / UI). */
    public List<Binding> bindings() {
        return Collections.unmodifiableList(table);
    }

    /** Le premier chord lié à {@code command} (introspection / UI),
     * ou null. */
    public ChordBinding chordBindingFor(String command) {
        for (ChordBinding c : chords) {
            if (c.command.equals(command)) return c;
        }
        return null;
    }

    /** Une vue non modifiable de la table de chords (introspection / UI). */
    public List<ChordBinding> chordBindings() {
        return Collections.unmodifiableList(chords);
    }

    // ── Chords ──────────────────────────────────────────────

    /**
     * Lie la séquence à deux touches {@code first} puis {@code second} à
     * {@code command} (fluent). Un chord ultérieur avec les mêmes deux
     * frappes remplace le précédent. La première touche d'un chord ne
     * capture que les événements qu'aucune liaison mono-touche ne résout —
     * délier d'abord la liaison simple si les deux existent (voir la
     * javadoc de la classe).
     */
    public EditorKeymap bindChord(String command, KeyStroke first, KeyStroke second) {
        ChordBinding chord = new ChordBinding(command, first, second);
        for (int i = 0; i < chords.size(); i++) {
            ChordBinding c = chords.get(i);
            if (c.first.equals(first) && c.second.equals(second)) {
                chords.set(i, chord);
                return this;
            }
        }
        chords.add(chord);
        return this;
    }

    /**
     * Résout la PREMIÈRE touche d'un chord potentiel : renvoie la première
     * frappe enregistrée quand un chord commence à cet événement touche
     * (même repli de modificateurs en quatre passes que {@link #resolve}),
     * sinon null. La frappe renvoyée est celle que {@link #resolveChord}
     * attend comme {@code first} — les modificateurs de l'événement
     * peuvent différer de ceux de la liaison.
     */
    public KeyStroke resolveChordStart(int keyCode, boolean ctrl, boolean shift) {
        KeyStroke s = matchChordStart(keyCode, ctrl, shift);
        if (s == null) s = matchChordStart(keyCode, false, shift);
        if (s == null) s = matchChordStart(keyCode, ctrl, false);
        if (s == null) s = matchChordStart(keyCode, false, false);
        return s;
    }

    private KeyStroke matchChordStart(int keyCode, boolean ctrl, boolean shift) {
        for (int i = 0; i < chords.size(); i++) {
            KeyStroke f = chords.get(i).first;
            if (f.keyCode == keyCode && f.ctrl == ctrl && f.shift == shift) return f;
        }
        return null;
    }

    /**
     * Complète un chord en attente : résout le second événement touche
     * contre chaque chord dont la première frappe est {@code first} (même
     * repli de modificateurs en quatre passes sur la seconde frappe), ou
     * null quand la séquence n'est pas un chord (la touche est alors
     * traitée comme une frappe fraîche).
     */
    public ChordBinding resolveChord(KeyStroke first, int keyCode, boolean ctrl, boolean shift) {
        ChordBinding c = matchChord(first, keyCode, ctrl, shift);
        if (c == null) c = matchChord(first, keyCode, false, shift);
        if (c == null) c = matchChord(first, keyCode, ctrl, false);
        if (c == null) c = matchChord(first, keyCode, false, false);
        return c;
    }

    private ChordBinding matchChord(KeyStroke first, int keyCode, boolean ctrl, boolean shift) {
        for (int i = 0; i < chords.size(); i++) {
            ChordBinding c = chords.get(i);
            if (!c.first.equals(first)) continue;
            if (c.second.keyCode == keyCode
                    && c.second.ctrl == ctrl && c.second.shift == shift) return c;
        }
        return null;
    }

    /**
     * Résout un événement touche en sa liaison, ou null quand la touche
     * n'est pas liée. Voir la javadoc de la classe pour le repli de
     * modificateurs en quatre passes (exact → sans ctrl → sans shift →
     * sans les deux).
     */
    public Binding resolve(int keyCode, boolean ctrl, boolean shift) {
        Binding b = match(keyCode, ctrl, shift);
        if (b == null) b = match(keyCode, false, shift);
        if (b == null) b = match(keyCode, ctrl, false);
        if (b == null) b = match(keyCode, false, false);
        return b;
    }

    private Binding match(int keyCode, boolean ctrl, boolean shift) {
        for (int i = 0; i < table.size(); i++) {
            Binding b = table.get(i);
            if (b.keyCode == keyCode && b.ctrl == ctrl && b.shift == shift) return b;
        }
        return null;
    }
}
