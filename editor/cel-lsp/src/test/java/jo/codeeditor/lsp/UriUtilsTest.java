package jo.codeeditor.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrats de {@link UriUtils} : conversion URI LSP ↔ fichier.
 *
 * <p>Le protocole LSP échange des URI {@code file:} ENCODÉES en
 * pourcentage ({@code %20} pour un espace, {@code %C3%A9} pour « é », …).
 * L'ancien code collait/découpait la chaîne {@code file://} à la main :
 * tout chemin contenant un espace, un {@code #} ou un caractère non ASCII
 * était introuvable à la lecture — et les écritures disque ciblaient un
 * fichier erroné. Ces tests épingle l'aller-retour encodé/décodé.</p>
 */
class UriUtilsTest {

    @TempDir
    File tmp;

    @Test
    void toFile_decodePourcentageEtEspaces() {
        File f = UriUtils.toFile("file://" + tmp.getAbsolutePath() + "/a%20b.txt");
        assertNotNull(f);
        assertEquals(new File(tmp, "a b.txt"), f);
    }

    @Test
    void toFile_decodeNonAscii() {
        File f = UriUtils.toFile("file://" + tmp.getAbsolutePath() + "/caf%C3%A9.md");
        assertNotNull(f);
        assertEquals(new File(tmp, "café.md"), f);
    }

    @Test
    void toFile_decodeDiese() {
        // '#' est un séparateur de fragment en RFC 3986 : dans une URI bien
        // formée il est encodé %23 et doit revenir comme caractère du chemin.
        File f = UriUtils.toFile("file://" + tmp.getAbsolutePath() + "/a%23b.txt");
        assertNotNull(f);
        assertEquals(new File(tmp, "a#b.txt"), f);
    }

    @Test
    void toFile_formeFileSlashSansDoubleSlash() {
        // « file:/chemin » est une forme légale (une seule barre).
        assertEquals(new File("/tmp/x"),
                UriUtils.toFile("file:/tmp/x"));
    }

    @Test
    void toFile_cheminNuTolere() {
        // Compatibilité : les appelants historiques peuvent passer un
        // chemin simple — il est pris tel quel, SANS décodage (un « % »
        // littéral d'un chemin nu n'est pas un encodage).
        assertEquals(new File("/tmp/a b.txt"), UriUtils.toFile("/tmp/a b.txt"));
        assertEquals(new File("/tmp/100%25.txt"), UriUtils.toFile("/tmp/100%25.txt"));
    }

    @Test
    void toFile_rejetteAutresSchemas() {
        assertNull(UriUtils.toFile("http://exemple.com/x"));
        assertNull(UriUtils.toFile("untitled:Untitled-1"));
        assertNull(UriUtils.toFile(null));
        assertNull(UriUtils.toFile(""));
    }

    @Test
    void toFile_rejetteAutoriteNonVide() {
        // file://host/chemin désigne un chemin RÉSEAU — jamais accepté ici.
        assertNull(UriUtils.toFile("file://serveur/partage/f.txt"));
    }

    @Test
    void toFile_rejetteCheminRelatif() {
        assertNull(UriUtils.toFile("file:relatif.txt"));
    }

    @Test
    void toFile_pourcentageInvalideGardeLitteral() {
        // %zz n'est pas un octet hexadécimal : tolérance de décodage, le
        // caractère reste littéral (jamais de perte de données silencieuse).
        assertEquals(new File("/tmp/a%zz.txt"), UriUtils.toFile("file:///tmp/a%zz.txt"));
    }

    @Test
    void fromFile_encodeStrictement() {
        String uri = UriUtils.fromFile(new File("/tmp/a b.txt"));
        assertEquals("file:///tmp/a%20b.txt", uri);
    }

    @Test
    void allerRetour_fromFilePuisToFile() throws IOException {
        File original = new File(tmp, "dossier avec espace/caf\u00e9 #2.txt");
        File retour = UriUtils.toFile(UriUtils.fromFile(original));
        assertEquals(original.getCanonicalPath(), retour.getCanonicalPath());
    }

    @Test
    void allerRetour_cheminAvecCaracteresExotiques() throws IOException {
        File original = new File(tmp, "sous dossier/héllo wörld (v2).java");
        File retour = UriUtils.toFile(UriUtils.fromFile(original));
        assertEquals(original.getCanonicalPath(), retour.getCanonicalPath());
    }
}
