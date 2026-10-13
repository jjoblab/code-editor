package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.languages.LanguageProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Détecteur de lignes sticky (en-têtes de bloc de code qui restent
 * visibles en haut pendant le scroll).
 *
 * <p>Une ligne sticky est une ligne qui ouvre un bloc de code et
 * contient un identifiant significatif (nom de classe, méthode,
 * fonction). Quand cette ligne est scrollée hors du viewport, elle
 * est redessinée en haut (pinned) pour garder le contexte.</p>
 *
 * <p><b>Détection (S5, lot 2 J6)</b> : la question « cette ligne
 * ouvre-t-elle un bloc ? » est déléguée au profil de langue
 * ({@code LanguageProfile.isBlockHeaderLine}) — les tables par défaut
 * C_LIKE/Python y vivent désormais, et un hôte peut déclarer les
 * en-têtes de sa propre langue via
 * {@code LanguageProfile.Builder.stickyHeaderTest}. Le détecteur ne
 * fait plus aucune décision linguistique : il orchestre le scan, la
 * garde de périmètre ({@code hasStickyHeaders}) et le cache S1.</p>
 *
 * <p>Comportement par défaut (inchangé) :</p>
 * <ul>
 *   <li><b>C_LIKE</b> : ligne avec accolade ouvrante {@code {}
 *       commençant par un mot-clé de définition ;</li>
 *   <li><b>Python</b> : ligne finissant par {@code :} commençant par
 *       un mot-clé de définition ;</li>
 *   <li>autres familles sans SPI : aucune ligne sticky.</li>
 * </ul>
 *
 * <p><b>Limitation mobile</b> : au plus 2 lignes sticky simultanées
 * (classe + méthode) pour ne pas consommer trop d'espace sur petit
 * écran.</p>
 *
 * @author jjoblab
 */
final class StickyLineDetector {

    private final EditorView view;

    // ── S1 : cache de détection ──────────────────────────────────
    // Le scan est coûteux (une String allouée par ligne, parcours
    // complet du document) et le renderer l'appelle À CHAQUE frame.
    // Or la liste des en-têtes ne dépend que du contenu du document
    // (immuable par révision) et du profil de langue : le scroll, le
    // zoom, le wrap et le pliage ne la changent jamais. Clé du
    // cache : [instance du document, révision, lineCount, profil] —
    // une édition produit un nouveau document (révision +1), un
    // changement de langue un autre profil : miss automatique.
    private EditorDocument cachedDoc;
    private int cachedRevision = Integer.MIN_VALUE;
    private int cachedLineCount = -1;
    private LanguageProfile cachedProfile;
    private List<int[]> cached;

    /**
     * Nombre de scans complets exécutés (les « miss » du cache) —
     * instrumentation des tests S1 : une suite de frames ne doit
     * l'incrémenter qu'une fois tant que le document et la langue
     * ne changent pas.
     */
    int scanCount;

    StickyLineDetector(EditorView view) {
        this.view = view;
    }

    /**
     * Détecte les lignes sticky pour le document courant.
     *
     * @param doc le document
     * @return liste de {@code [docLine, yOffset]} — les lignes sticky
     *         triées par position (haut → bas). {@code yOffset} est la
     *         position Y de la ligne dans l'espace contenu (avant
     *         vOffset).
     */
    List<int[]> detect(EditorDocument doc) {
        if (view.session == null || doc == null) return new ArrayList<>();

        LanguageProfile profile = view.session.getLanguageProfile();
        if (isCached(doc, profile)) {
            // Hit : les yOffset (seule partie dépendante de la
            // géométrie : plis, wrap, métriques) sont rafraîchis en
            // place, sans allocation ni re-scan.
            refreshYOffsets(cached);
            return cached;
        }

        scanCount++;
        List<int[]> stickyLines = scan(doc, profile);
        cachedDoc = doc;
        cachedRevision = doc.getRevision();
        cachedLineCount = doc.lineCount();
        cachedProfile = profile;
        cached = stickyLines;
        return stickyLines;
    }

    /** Le cache ne survit ni à un autre document, ni à une édition
     *  (révision), ni à un changement de langue (profil) — le
     *  lineCount complète la clé par ceinture. */
    private boolean isCached(EditorDocument doc, LanguageProfile profile) {
        return cached != null
                && cachedDoc == doc
                && cachedRevision == doc.getRevision()
                && cachedLineCount == doc.lineCount()
                && cachedProfile == profile;
    }

    /** Hit de cache : yOffset recalculés en place (zéro allocation)
     *  car ils dépendent de la géométrie (plis, wrap, métriques). */
    private void refreshYOffsets(List<int[]> lines) {
        for (int[] pair : lines) {
            pair[1] = (int) view.docLineToY(pair[0]);
        }
    }

    /**
     * Scan complet du document — le chemin coûteux, exécuté une fois
     * par [document × langue], jamais par frame. La décision
     * « en-tête de bloc » appartient au profil (S5) ; ce scan ne
     * garde que les gardes génériques (longueur, vide).
     *
     * @param doc le document
     * @param profile le profil de langue (peut être null)
     * @return liste de {@code [docLine, yOffset]} triée par position
     */
    private List<int[]> scan(EditorDocument doc, LanguageProfile profile) {
        List<int[]> stickyLines = new ArrayList<>();
        if (profile == null) return stickyLines;
        // Garde de périmètre (S5) : une langue sans en-têtes déclarés
        // (ni SPI ni famille à tables par défaut) ne paie pas le scan.
        if (!profile.hasStickyHeaders()) return stickyLines;

        CharSequence chars = doc.charSequence();
        int lineCount = doc.lineCount();

        for (int li = 0; li < lineCount; li++) {
            int lineStart = doc.lineStart(li);
            int lineEnd = doc.lineEnd(li);
            if (lineEnd - lineStart > 200) continue; // ligne trop longue, skip

            CharSequence lineText = chars.subSequence(lineStart, lineEnd);
            // La ligne est un en-tête de bloc SI LA LANGUE le dit —
            // tables par défaut de la famille ou test de l'hôte.
            if (profile.isBlockHeaderLine(lineText)) {
                float y = view.docLineToY(li);
                stickyLines.add(new int[]{li, (int) y});
            }
        }
        return stickyLines;
    }
}
