package jo.codeeditor.view.icons;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Moteur d'icônes vectorielles de la bibliothèque — remplace les glyphes
 * texte/emoji des barres ({@code "↶"}, {@code "👁"}…) dont le rendu varie
 * selon les polices de l'appareil, par des <b>paths dessinés au Canvas</b>,
 * nets à toute densité et teintés du thème de l'éditeur.
 *
 * <p><b>B6 (retour terrain)</b> : la refonte de la barre de fil d'Ariane et
 * de la feuille d'aperçu s'appuie sur ce moteur. Les paths dérivent du jeu
 * <em>Material Symbols</em> (Apache 2.0 — voir NOTICE), viewport 24×24.</p>
 *
 * <p>Deux familles :
 * <ul>
 *   <li><b>FILL</b> — formes pleines (annuler, rétablir, œil) ; rendues
 *       avec {@code Paint.Style.FILL} et {@code FillType.EVEN_ODD} pour les
 *       creux internes ;</li>
 *   <li><b>STROKE</b> — traits fins (scinder, étendre, fermer) ; rendues
 *       avec {@code Paint.Style.STROKE}, épaisseur {@code 2/24} de la
 *       taille — cohérents avec Material Symbols Weight 400.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
 * p.setColor(accent);
 * EditorIcons.draw(canvas, EditorIcons.UNDO, cx, cy, 20 * density, p);
 * }</pre>
 *
 * <p>Le path de chaque icône est parsé UNE fois puis mis en cache
 * (viewport 24×24 centré) ; le dessin applique une translation + homothétie
 * vers {@code (cx, cy, sizePx)} — aucun recalcul de géométrie par frame.</p>
 *
 * @author jo@Dev
 */
public final class EditorIcons {

    private EditorIcons() {
        // Moteur statique — pas d'instanciation.
    }

    /** Une icône du moteur — porte son path SVG (viewport 24×24) et son style de rendu. */
    public enum Icon {
        /**
         * Annuler — flèche incurvée vers la gauche (flèche de retour).
         * Famille FILL, dérivée de Material « undo ».
         */
        UNDO(true,
                "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62"
                + "c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5"
                + "l2.37-.78C21.08 11.03 17.15 8 12.5 8z"),

        /**
         * Rétablir — flèche incurvée vers la droite (miroir de {@link #UNDO}).
         * Famille FILL, dérivée de Material « redo ».
         */
        REDO(true,
                "M11.5 8c2.65 0 5.05.99 6.9 2.6L22 7v9h-9l3.62-3.62"
                + "c-1.39-1.16-3.16-1.88-5.12-1.88-3.54 0-6.55 2.31-7.6 5.5"
                + "l-2.37-.78C2.92 11.03 6.85 8 11.5 8z"),

        /**
         * Aperçu — l'œil de visibilité.
         * Famille FILL, dérivée de Material « visibility » : amande pleine,
         * pupille en creux (EVEN_ODD), iris plein.
         */
        EYE(true,
                "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5 "
                + "s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5z "
                + "M12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z "
                + "M12 9c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"),

        /**
         * Aperçu scindé — panneau avec séparateur central (l'éditeur à
         * gauche, l'aperçu à droite). Famille STROKE.
         */
        SPLIT(false,
                "M3.5 5.5h17v13h-17z",
                "M12 5.5v13"),

        /**
         * Plein écran — quatre coins d'expansion. Famille STROKE.
         */
        EXPAND(false,
                "M9 5H5v4",
                "M15 5h4v4",
                "M5 15v4h4",
                "M19 15v4h-4"),

        /**
         * Fermer — croix. Famille STROKE.
         */
        CLOSE(false,
                "M6.4 6.4l11.2 11.2",
                "M17.6 6.4L6.4 17.6"),

        /**
         * Rechercher — la loupe : cercle approché par QUATRE cubiques
         * (le parseur du moteur rejette les arcs elliptiques — famille
         * STROKE, parité scinder/étendre/fermer) + manche vers le coin.
         */
        SEARCH(false,
                "M4,10.5 C4,6.9 6.9,4 10.5,4 C14.1,4 17,6.9 17,10.5 "
                + "C17,14.1 14.1,17 10.5,17 C6.9,17 4,14.1 4,10.5 Z",
                "M15.5,15.5 L20.5,20.5"),

        /**
         * Occurrence précédente — chevron vers le haut. Famille STROKE.
         */
        CHEVRON_UP(false,
                "M6,16 L12,8 L18,16"),

        /**
         * Occurrence suivante — chevron vers le bas. Famille STROKE.
         */
        CHEVRON_DOWN(false,
                "M6,8 L12,16 L18,8"),

        /**
         * Bascule remplacer — deux flèches verticales opposées (l'aller
         * et le retour du remplacement). Famille FILL.
         */
        SWAP_VERT(true,
                "M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3z "
                + "M21 9l-3.99-4v3H10v2h7.01v3L21 9z"),

        /**
         * Point d'arrêt — disque plein (Material « fiber_manual_record »
         * approché par QUATRE cubiques : le parseur du moteur rejette
         * les arcs elliptiques, parité SEARCH). Famille FILL, B15 :
         * l'icône canonique des {@code GutterAction} d'hôte.
         */
        BREAKPOINT(true,
                "M12 4 C16.42 4 20 7.58 20 12 C20 16.42 16.42 20 12 20 "
                + "C7.58 20 4 16.42 4 12 C4 7.58 7.58 4 12 4 Z"),

        /**
         * Dupliquer — deux rectangles superposés (feuille + copie,
         * parité Material « content_copy »). Famille STROKE. B16 :
         * icône de l'action intégrée « Duplicate » dans le
         * débordement de la pill de sélection.
         */
        DUPLICATE(false,
                "M4.5 3.5h11v13h-11z",
                "M8.5 7.5h11v13h-11z"),

        /**
         * Commenter — double barre oblique (commentaire de ligne
         * « // », indépendant du langage). Famille STROKE. B16 :
         * icône de l'action intégrée « Comment » dans le
         * débordement de la pill de sélection.
         */
        COMMENT(false,
                "M9.5 5L6.5 19",
                "M15.5 5L12.5 19"),

        /**
         * Casse — grand A + petit a (bascule de casse, parité
         * « Toggle Case » des IDE de bureau ; le a est approché par
         * un bol en quatre cubiques + tige). Famille STROKE. B16 :
         * icône de l'action intégrée « Convert case » dans le
         * débordement de la pill de sélection.
         */
        CASE(false,
                "M3.5 19.5L8 5L12.5 19.5",
                "M5.2 14.5H10.8",
                "M13.7 16.2C13.7 14.37 15.17 12.9 17 12.9"
                + "C18.83 12.9 20.3 14.37 20.3 16.2"
                + "C20.3 18.03 18.83 19.5 17 19.5"
                + "C15.17 19.5 13.7 18.03 13.7 16.2Z",
                "M20.3 12.2V19.5"),

        /**
         * Partager — trois nœuds reliés (le classique « share »,
         * chaque nœud approché par quatre cubiques, parité
         * SEARCH/BREAKPOINT sur les arcs). Famille STROKE. B16 :
         * icône de l'action intégrée « Share » dans le débordement
         * de la pill de sélection.
         */
        SHARE(false,
                "M3.9 12C3.9 10.73 4.93 9.7 6.2 9.7C7.47 9.7 8.5 10.73 8.5 12"
                + "C8.5 13.27 7.47 14.3 6.2 14.3C4.93 14.3 3.9 13.27 3.9 12Z",
                "M15.5 6.2C15.5 4.93 16.53 3.9 17.8 3.9C19.07 3.9 20.1 4.93 20.1 6.2"
                + "C20.1 7.47 19.07 8.5 17.8 8.5C16.53 8.5 15.5 7.47 15.5 6.2Z",
                "M15.5 17.8C15.5 16.53 16.53 15.5 17.8 15.5C19.07 15.5 20.1 16.53 20.1 17.8"
                + "C20.1 19.07 19.07 20.1 17.8 20.1C16.53 20.1 15.5 19.07 15.5 17.8Z",
                "M8.5 10.6L15.5 7.6",
                "M8.5 13.4L15.5 16.4"),

        /**
         * Déplacer le bloc vers le haut — flèche montante + barre de
         * destination au-dessus (parité « Move Line Up » IntelliJ).
         * Famille STROKE. B16 : icône de l'action intégrée
         * « Move up » dans le débordement de la pill.
         */
        MOVE_UP(false,
                "M5 20.5H19",
                "M12 16V5",
                "M8 9L12 5L16 9"),

        /**
         * Déplacer le bloc vers le bas — miroir de {@link #MOVE_UP}.
         * Famille STROKE. B16 : icône de l'action intégrée
         * « Move down » dans le débordement de la pill.
         */
        MOVE_DOWN(false,
                "M5 3.5H19",
                "M12 8V19",
                "M8 15L12 19L16 15"),

        /**
         * Déplacer par glisser-déposer — quatre chevrons divergents
         * (parité Material « open_with » : le bloc peut partir dans
         * les quatre directions). Famille STROKE. B16 : icône de
         * l'action intégrée « Move » (drag de bloc) dans le
         * débordement de la pill.
         */
        MOVE_BLOCK(false,
                "M8.5 7L12 3.5L15.5 7",
                "M8.5 17L12 20.5L15.5 17",
                "M7 8.5L3.5 12L7 15.5",
                "M17 8.5L20.5 12L17 15.5");

        /** true = famille FILL (formes pleines), false = STROKE (traits). */
        final boolean fill;
        /** Sous-paths SVG concaténés (viewport 24×24) — un par segment de dessin. */
        final String[] pathData;

        Icon(boolean fill, String... pathData) {
            this.fill = fill;
            this.pathData = pathData;
        }
    }

    /** Viewport de référence des paths source (Material 24×24). */
    static final float VIEWPORT = 24f;

    /** Cache des paths par icône — construit au premier accès (UI thread). */
    private static final Path[] CACHE = new Path[Icon.values().length];

    /**
     * Retourne le path 24×24 de l'icône (partagé, ne pas muter) — construit
     * au premier appel. {@code FillType.EVEN_ODD} pour les familles FILL
     * (creux de la pupille de l'œil) ; STROKE garde le winding par défaut.
     */
    public static Path path(Icon icon) {
        Path cached = CACHE[icon.ordinal()];
        if (cached != null) return cached;
        Path path = new Path();
        for (String d : icon.pathData) {
            SvgPathParser.parse(d, path);
        }
        if (icon.fill) path.setFillType(Path.FillType.EVEN_ODD);
        CACHE[icon.ordinal()] = path;
        return path;
    }

    /**
     * Dessine l'icône centrée en {@code (cx, cy)}, taillée à
     * {@code sizePx} (côté du carré d'inscription), avec la peinture donnée
     * (couleur et antialias pilotés par l'appelant).
     *
     * <p>La peinture est <b>prêtée puis restaurée</b> : style et épaisseur
     * sont posés selon la famille de l'icône, le reste (couleur, filtres)
     * appartient à l'appelant. Les transformations Canvas se composent en
     * ordre d'appel (translation du centre, homothétie, recentrage du
     * viewport 24×24) — aucun recalcul de géométrie, jamais de mutation du
     * path mis en cache.</p>
     */
    public static void draw(Canvas canvas, Icon icon, float cx, float cy,
            float sizePx, Paint paint) {
        if (canvas == null || paint == null || sizePx <= 0f) return;
        Path path = path(icon);
        float scale = sizePx / VIEWPORT;

        Paint.Style restoreStyle = paint.getStyle();
        float restoreWidth = paint.getStrokeWidth();
        paint.setStyle(icon.fill ? Paint.Style.FILL : Paint.Style.STROKE);
        if (!icon.fill) {
            // Trait 2/24 de la taille (Material Symbols Weight 400), jamais
            // sous 1 px pour rester lisible en basse densité.
            paint.setStrokeWidth(Math.max(1f, 2f * scale));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        canvas.translate(-VIEWPORT * 0.5f, -VIEWPORT * 0.5f);
        canvas.drawPath(path, paint);
        canvas.restore();
        // Restauration null-safe : un Paint fraîchement construit rend un
        // style nul sous le shadow Robolectric (réel Android : FILL).
        if (restoreStyle != null) paint.setStyle(restoreStyle);
        paint.setStrokeWidth(restoreWidth);
    }

    /**
     * Retourne les bornes du path 24×24 de l'icône (rectangulaire
     * d'inscription dans le viewport source) — utile aux harnais de test
     * et aux alignements optiques.
     */
    public static RectF bounds(Icon icon) {
        RectF r = new RectF();
        path(icon).computeBounds(r, true);
        return r;
    }

    // ════════════════════════════════════════════════════════════════
    // Parser SVG « d » compact
    // ════════════════════════════════════════════════════════════════

    /**
     * Parser des données de path SVG ({@code d="M12 4.5C7 …"}) vers un
     * {@link Path} Android. Sert les besoins du jeu d'icônes de la
     * bibliothèque : commandes absolues et relatives {@code M m L l H h
     * V v C c S s Q q T t Z z}, séparateurs espace/virgule/sauts. Les arcs
     * {@code A a} ne sont PAS supportés (le jeu d'icônes n'en utilise pas
     * — un arc lèverait {@link IllegalArgumentException} pour être repéré
     * au plus tôt).
     *
     * <p>Chaque appel REPART du dernier point de {@code sink} : les
     * sous-paths d'une même icône se concatènent en passant le même
     {@code sink} (cf. {@link #path}).</p>
     */
    static final class SvgPathParser {

        private SvgPathParser() {
        }

        /** Parse {@code d} et ajoute les segments au {@code sink}. */
        static void parse(String d, Path sink) {
            if (d == null) throw new IllegalArgumentException("path d = null");
            float[] f = new float[8];
            char op = 0;
            int i = 0;
            int n = d.length();
            float curX = 0f, curY = 0f;       // dernier point posé
            float subX = 0f, subY = 0f;       // départ du sous-path courant
            float ctrlX = 0f, ctrlY = 0f;     // dernier point de contrôle (S/T)
            char lastCurve = 0;               // 'C', 'S', 'Q' ou 'T' précédent
            boolean hasPendingMove = false;   // un M est en attente de segment

            while (i < n) {
                // Saute séparateurs.
                while (i < n && isSep(d.charAt(i))) i++;
                if (i >= n) break;
                char c = d.charAt(i);

                if (isCommand(c)) {
                    op = c;
                    i++;
                    hasPendingMove = false;
                } else if (op == 0) {
                    throw new IllegalArgumentException(
                            "nombre avant toute commande : « " + d.substring(i) + " »");
                } else if (op == 'z' || op == 'Z') {
                    // z ne prend aucun paramètre : des nombres après lui
                    // sont du SVG invalide — rejet explicite (et sans lui,
                    // la boucle tournerait à l'infini).
                    throw new IllegalArgumentException(
                            "nombre après closepath : « " + d + " »");
                }

                switch (op) {
                    case 'M': case 'm': {
                        int k = readFloats(d, i, f, 2);
                        float x = f[0], y = f[1];
                        if (op == 'm') { x += curX; y += curY; op = 'l'; }
                        if (hasPendingMove) {
                            // Paires suivant M : lineTo implicite (spec SVG).
                            sink.lineTo(x, y);
                        } else {
                            sink.moveTo(x, y);
                            subX = x; subY = y;
                            hasPendingMove = true;
                        }
                        curX = x; curY = y;
                        i = k;
                        break;
                    }
                    case 'L': case 'l': {
                        int k = readFloats(d, i, f, 2);
                        float x = f[0], y = f[1];
                        if (op == 'l') { x += curX; y += curY; }
                        sink.lineTo(x, y);
                        curX = x; curY = y;
                        i = k;
                        break;
                    }
                    case 'H': case 'h': {
                        int k = readFloats(d, i, f, 1);
                        float x = op == 'h' ? f[0] + curX : f[0];
                        sink.lineTo(x, curY);
                        curX = x;
                        i = k;
                        break;
                    }
                    case 'V': case 'v': {
                        int k = readFloats(d, i, f, 1);
                        float y = op == 'v' ? f[0] + curY : f[0];
                        sink.lineTo(curX, y);
                        curY = y;
                        i = k;
                        break;
                    }
                    case 'C': case 'c': {
                        int k = readFloats(d, i, f, 6);
                        float x1 = f[0], y1 = f[1], x2 = f[2], y2 = f[3],
                              x = f[4], y = f[5];
                        if (op == 'c') {
                            x1 += curX; y1 += curY;
                            x2 += curX; y2 += curY;
                            x += curX; y += curY;
                        }
                        sink.cubicTo(x1, y1, x2, y2, x, y);
                        ctrlX = x2; ctrlY = y2;
                        curX = x; curY = y;
                        lastCurve = 'C';
                        i = k;
                        break;
                    }
                    case 'S': case 's': {
                        // S n'expose que le second point de contrôle ; le
                        // premier est le REFLET du dernier contrôle de C/S
                        // (point courant si la commande précédente n'était
                        // pas une courbe — spec SVG).
                        int k = readFloats(d, i, f, 4);
                        float x2 = f[0], y1 = f[1], x = f[2], y = f[3];
                        if (op == 's') {
                            x2 += curX; y1 += curY;
                            x += curX; y += curY;
                        }
                        float rX = curX, rY = curY;
                        if (lastCurve == 'C' || lastCurve == 'S') {
                            rX = 2 * curX - ctrlX;
                            rY = 2 * curY - ctrlY;
                        }
                        sink.cubicTo(rX, rY, x2, y1, x, y);
                        ctrlX = x2; ctrlY = y1;
                        curX = x; curY = y;
                        lastCurve = 'S';
                        i = k;
                        break;
                    }
                    case 'Q': case 'q': {
                        int k = readFloats(d, i, f, 4);
                        float x1 = f[0], y1 = f[1], x = f[2], y = f[3];
                        if (op == 'q') {
                            x1 += curX; y1 += curY;
                            x += curX; y += curY;
                        }
                        sink.quadTo(x1, y1, x, y);
                        ctrlX = x1; ctrlY = y1;
                        curX = x; curY = y;
                        lastCurve = 'Q';
                        i = k;
                        break;
                    }
                    case 'T': case 't': {
                        int k = readFloats(d, i, f, 2);
                        float x = f[0], y = f[1];
                        if (op == 't') { x += curX; y += curY; }
                        float rX = curX, rY = curY;
                        if (lastCurve == 'Q' || lastCurve == 'T') {
                            rX = 2 * curX - ctrlX;
                            rY = 2 * curY - ctrlY;
                        }
                        sink.quadTo(rX, rY, x, y);
                        ctrlX = rX; ctrlY = rY;
                        curX = x; curY = y;
                        lastCurve = 'T';
                        i = k;
                        break;
                    }
                    case 'Z': case 'z': {
                        // La lettre z a déjà été consommée par le bloc
                        // isCommand ci-dessus (z ne prend AUCUN paramètre,
                        // elle n'arrive jamais en continuation implicite) —
                        // pas de i++ ici, sinon le caractère SUIVANT
                        // (typiquement le M du sous-path suivant) sauterait.
                        sink.close();
                        curX = subX; curY = subY;
                        lastCurve = 0;
                        break;
                    }
                    case 'A': case 'a':
                        throw new IllegalArgumentException(
                                "arc elliptique non supporté : « " + d + " »");
                    default:
                        throw new IllegalArgumentException(
                                "commande inconnue « " + op + " » : " + d);
                }
            }
        }

        private static boolean isSep(char c) {
            return c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t';
        }

        private static boolean isCommand(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        /**
         * Lit {@code count} nombres depuis {@code d} à {@code i} — rend
         * l'indice APRÈS le dernier nombre consommé. Séparateurs
         * espace/virgule tolérés entre nombres ; le signe « − » sans
         * séparateur (style Material « 2.37-.78 ») démarre le nombre
         * SUIVANT ; « e± » n'est accepté qu'en exposant d'un nombre en
         * cours.
         */
        private static int readFloats(String d, int i, float[] out, int count) {
            int read = 0;
            int n = d.length();
            while (read < count) {
                while (i < n && isSep(d.charAt(i))) i++;
                if (i >= n) {
                    throw new IllegalArgumentException(
                            "nombres manquants : « " + d + " »");
                }
                int start = i;
                if (d.charAt(i) == '+' || d.charAt(i) == '-') i++;
                boolean digit = false, dot = false, exp = false;
                while (i < n) {
                    char c = d.charAt(i);
                    if (c >= '0' && c <= '9') { digit = true; i++; }
                    else if (c == '.' && !dot && !exp) { dot = true; i++; }
                    else if ((c == 'e' || c == 'E') && digit && !exp) {
                        exp = true; i++;
                        if (i < n && (d.charAt(i) == '+' || d.charAt(i) == '-')) i++;
                    } else break;
                }
                if (!digit) {
                    throw new IllegalArgumentException(
                            "nombre attendu à « " + d.substring(start) + " »");
                }
                try {
                    out[read] = Float.parseFloat(d.substring(start, i));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "nombre illisible « " + d.substring(start, i) + " »", e);
                }
                read++;
            }
            return i;
        }
    }
}
