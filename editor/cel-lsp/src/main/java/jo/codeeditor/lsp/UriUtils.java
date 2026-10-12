package jo.codeeditor.lsp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Conversion URI LSP ↔ fichier — l'unique point du module autorisé à
 * manipuler les préfixes {@code file://} .
 *
 * <p>Le protocole LSP échange des URI RFC 3986 ENCODÉES en pourcentage :
 * un espace s'écrit {@code %20}, « é » {@code %C3%A9}, « # »
 * {@code %23}. L'ancien code découpait la chaîne à la main
 * ({@code uri.substring("file://".length())}) : tout chemin contenant un
 * espace ou un caractère non ASCII était introuvable à la lecture, et
 * les URI construites par concaténation ({@code "file://" + chemin})
 * produisaient des URI invalides que certains serveurs rejetaient.</p>
 *
 * <p>Politique : <b>tolérant en entrée, strict en sortie</b>. La
 * conversion vers {@link File} accepte la forme encodée ET le chemin nu
 * historique (pris tel quel, sans décodage — un « % » littéral d'un
 * chemin nu n'est pas un encodage). La conversion vers URI produit
 * toujours la forme canonique encodée {@code file:///chemin} via
 * {@link File#toURI()}.</p>
 *
 * <p>Compatible API 24 : {@code java.net.URI} et {@code URLDecoder}
 * existent à tous les niveaux, mais {@code URLDecoder} décode « + » en
 * espace (sémantique de formulaire, PAS des URI) — le décodage est donc
 * implémenté à la main, octet par octet.</p>
 */
final class UriUtils {

    private UriUtils() {
        // Classe utilitaire.
    }

    /**
     * URI → fichier. Retourne {@code null} (jamais d'exception) si
     * l'URI est null/vide, de schéma autre que {@code file}, porte une
     * autorité non vide (forme réseau {@code file://host/chemin}) ou un
     * chemin relatif.
     *
     * <p>Formes acceptées : {@code file:///chemin} (canonique),
     * {@code file:/chemin}, et le chemin nu {@code /chemin} (tolérance
     * historique, sans décodage). Un {@code %XX} invalide reste
     * littéral — aucune perte de données silencieuse.</p>
     */
    static File toFile(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        if (!uri.startsWith("file:")) {
            // Autre schéma (« http: », « untitled: »…) → refusé ;
            // chemin nu POSIX (appelant historique) — pris tel quel.
            if (looksLikeUriScheme(uri)) return null;
            return new File(uri);
        }
        String rest = uri.substring(5);
        if (rest.startsWith("//")) {
            int finAutorite = rest.indexOf('/', 2);
            if (finAutorite < 0) return null; // file://host (sans chemin)
            String autorite = rest.substring(2, finAutorite);
            if (!autorite.isEmpty()) return null; // file://host/… : chemin réseau, refusé
            rest = rest.substring(finAutorite);
        } else if (!rest.startsWith("/")) {
            return null; // file:chemin-relatif → refusé (pas absolu)
        }
        if (!rest.startsWith("/") || rest.length() < 2) {
            // file:/ sans rien, ou file:// (racine seule) : pas un fichier.
            return null;
        }
        return new File(percentDecode(rest));
    }

    /**
     * Vrai si la chaîne commence par un schéma d'URI
     * ({@code lettres suivies de ':'}) — {@code http:}, {@code untitled:}…
     * Un chemin POSIX absolu ne peut pas matcher (pas de ':' avant le
     * premier '/').
     */
    private static boolean looksLikeUriScheme(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':') return i > 0;
            if (c == '/' || c == '?' || c == '#') return false;
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return false;
    }

    /**
     * Fichier → URI canonique encodée (forme {@code file:///chemin}),
     * via {@link File#toURI()}. Toujours absolu (les chemins relatifs
     * sont résolus contre le répertoire courant).
     */
    static String fromFile(File file) {
        if (file == null) return null;
        String uri = file.toURI().toString();
        // {@code File.toURI()} produit la forme compacte « file:/… » ;
        // la convention LSP usuelle est « file:///… » — les deux sont
        // légales, on émet la forme à trois barres pour maximiser la
        // compatibilité avec les serveurs.
        if (uri.startsWith("file:/") && !uri.startsWith("file://")) {
            uri = "file:///" + uri.substring("file:/".length());
        }
        return uri;
    }

    /**
     * Fichier → URI canonique encodée à partir d'un chemin.
     * @see #fromFile(File)
     */
    static String fromPath(String path) {
        if (path == null || path.isEmpty()) return null;
        return fromFile(new File(path));
    }

    /**
     * Décodage pourcent RFC 3986 : {@code %XX} → octet, séquence
     * d'octets réinterprétée en UTF-8. Un « % » non suivi de deux
     * chiffres hexadécimaux reste littéral (tolérance de décodage).
     */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s; // chemin courant, rien à décoder
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() + 1 && i + 2 <= s.length() - 1) {
                int hi = hexValue(s.charAt(i + 1));
                int lo = hexValue(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    bos.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            // Caractère littéral : conserve tel quel (UTF-8 du char ≤ 0x7F
            // ; au-delà, réencode via la chaîne).
            if (c <= 0x7F) {
                bos.write(c);
            } else {
                byte[] encoded = String.valueOf(c).getBytes(Charset.forName("UTF-8"));
                bos.write(encoded, 0, encoded.length);
            }
        }
        return new String(bos.toByteArray(), Charset.forName("UTF-8"));
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
