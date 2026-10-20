package jo.codeeditor.textmate.regex;

import java.nio.charset.StandardCharsets;

/**
 * Ligne de travail encodée pour le moteur (plan 10 §3.2).
 *
 * <p>Le moteur travaille sur des octets UTF-8 : la ligne est prolongée
 * du « \n » virtuel (sémantique « fin de ligne » du tokéniseur — les
 * fermantes {@code \n}/{@code $} des règles de ligne doivent pouvoir
 * matcher), encodée UNE fois par passe, et servie avec un index de
 * préfixes char ↔ octet : {@link #byteOffset(int)} en O(1),
 * {@link #charIndex(int)} en O(log n). C'est la réponse à l'impôt
 * octet du modèle moteur — index construit pendant l'encodage même.</p>
 *
 * <p>Toutes les positions exposées par la couture sont en CARACTÈRES de
 * la ligne : la conversion n'appartient qu'ici.</p>
 */
public final class EncodedLine {

    private final String text;
    private final byte[] bytes;
    /** byteOffset du préfixe de longueur i — length = caractères + 1. */
    private final int[] prefix;

    private EncodedLine(String text, byte[] bytes, int[] prefix) {
        this.text = text;
        this.bytes = bytes;
        this.prefix = prefix;
    }

    /** Encode {@code ligne + "\n"} en UTF-8 avec son index de préfixes. */
    public static EncodedLine of(String line) {
        String text = line + "\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] prefix = new int[text.length() + 1];
        int byteAt = 0;
        for (int i = 0; i < text.length(); i++) {
            prefix[i] = byteAt;
            char c = text.charAt(i);
            if (c >= 0xD800 && c <= 0xDBFF && i + 1 < text.length()
                    && text.charAt(i + 1) >= 0xDC00 && text.charAt(i + 1) <= 0xDFFF) {
                // Paire de substitution : UN point de code, 4 octets, DEUX
                // chars Java. Le bas porte le préfixe du haut (le point de
                // code commence au haut) ; une correspondance ne commence
                // jamais au milieu d'un point de code.
                byteAt += 4;
                prefix[i + 1] = prefix[i];
                i++;
                continue;
            }
            if (c >= 0xD800 && c <= 0xDFFF) {
                // Surrogate isolé : l'encodeur Java le remplace par '?'
                // (1 octet) — garder l'invariant index == encodage réel.
                byteAt += 1;
            } else if (c < 0x80) {
                byteAt += 1;
            } else if (c < 0x800) {
                byteAt += 2;
            } else {
                byteAt += (c < 0x10000) ? 3 : 4;
            }
        }
        prefix[text.length()] = byteAt;
        if (byteAt != bytes.length) {
            // Sécurité de construction : l'index doit couvrir exactement
            // l'encodage Java (écart = bug de ce code, jamais du moteur).
            throw new IllegalStateException("index char↔octet incohérent : "
                    + byteAt + " ≠ " + bytes.length);
        }
        return new EncodedLine(text, bytes, prefix);
    }

    /** La ligne nue (sans le « \n » virtuel). */
    public String line() {
        return text.substring(0, text.length() - 1);
    }

    /** Le texte de travail (ligne + « \n » virtuel). */
    public String text() {
        return text;
    }

    /** Les octets UTF-8 du texte de travail. */
    public byte[] bytes() {
        return bytes;
    }

    /** Longueur du texte de travail, en caractères (ligne + 1). */
    public int length() {
        return text.length();
    }

    /** Position OCTET du début du caractère d'indice charIndex. */
    public int byteOffset(int charIndex) {
        if (charIndex < 0 || charIndex >= prefix.length) {
            throw new IndexOutOfBoundsException("charIndex " + charIndex
                    + " hors [0, " + prefix.length + ")");
        }
        return prefix[charIndex];
    }

    /**
     * Position CARACTÈRE de l'octet byteOffset. Un octet au milieu d'un
     * caractère multiple arrondit au caractère qui le CONTIENT ; sur une
     * PAIRE de substitution (positions préfixes égales pour le haut et
     * le bas), la recherche rend le HAUT — une correspondance finissant
     * au début de l'emoji s'exprime à l'indice du haut.
     */
    public int charIndex(int byteOffset) {
        if (byteOffset < 0) return 0;
        if (byteOffset > bytes.length) return text.length();
        int lo = 0, hi = prefix.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (prefix[mid] <= byteOffset) lo = mid;
            else hi = mid - 1;
        }
        // Premier indice de la série égale (paires de substitution) :
        // la position d'un point de code est celle de son surrogate HAUT.
        while (lo > 0 && prefix[lo - 1] == prefix[lo]) lo--;
        return lo;
    }
}
