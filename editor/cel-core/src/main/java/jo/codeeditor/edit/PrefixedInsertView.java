package jo.codeeditor.edit;

/**
 * Vue {@link CharSequence} paresseuse : un préfixe de document suivi du
 * texte d'une insertion, SANS copie du préfixe.
 *
 * <p><b>P-01 (audit 3.69.0)</b> : la complétion postfix simule le
 * document après insertion en passant « préfixe + texte frappé » à
 * l'analyseur. L'ancien chemin matérialisait ce texte simulé en
 * {@code String} — copie du PRÉFIXE ENTIER du document (O(pos)) à
 * CHAQUE frappe multi-caractères, soit ~1 Mo copié par frappe sur un
 * document d'un million de caractères. Or l'analyse postfix ne lit que
 * l'arrière-garde : le postfix (≤ 8 caractères), l'expression courante
 * et le retrait de la ligne — quelques dizaines de caractères au pire.
 * Cette vue sert ces lectures via {@code charAt} sur la rope sous-jacente
 * (O(log n) par caractère) et ne matérialise QUE les tranches
 * effectivement demandées ({@code subSequence}), bornées à la taille de
 * l'expression.</p>
 *
 * <p><b>Contrat</b> : le préfixe doit être immuable pendant toute la
 * durée de vie de la vue (une vue sur la rope d'une révision donnée le
 * garantit — l'édition substitue la rope, elle ne la mute pas). La vue
 * n'est pas conçue pour être conservée entre deux éditions.</p>
 */
final class PrefixedInsertView implements CharSequence {

    private final CharSequence prefix;
    private final String inserted;

    /**
     * Construit la vue « préfixe + insertion ».
     *
     * @param prefix  préfixe du document (immuable pendant la lecture)
     * @param inserted texte inséré après le préfixe
     */
    PrefixedInsertView(CharSequence prefix, String inserted) {
        this.prefix = prefix;
        this.inserted = inserted;
    }

    @Override
    public int length() {
        return prefix.length() + inserted.length();
    }

    @Override
    public char charAt(int index) {
        int plen = prefix.length();
        if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException(
                "Index: " + index + ", Length: " + length());
        }
        return index < plen ? prefix.charAt(index) : inserted.charAt(index - plen);
    }

    /**
     * Tranche [start, end) : déléguée au préfixe, à l'insertion, ou
     * matérialisée si elle CHEVAUCHE la frontière (l'expression simulée
     * peut commencer dans le document et finir dans le texte frappé —
     * p. ex. « foo » collé à « bar.if »). La matérialisation est bornée
     * à la taille de la tranche demandée, jamais au document entier.
     */
    @Override
    public CharSequence subSequence(int start, int end) {
        int plen = prefix.length();
        if (start < 0 || end > length() || start > end) {
            throw new IndexOutOfBoundsException(
                "start=" + start + ", end=" + end + ", length=" + length());
        }
        if (end <= plen) return prefix.subSequence(start, end);
        if (start >= plen) return inserted.subSequence(start - plen, end - plen);
        // Tranche à cheval : préfixe[start..plen) + inserted[0..end-plen).
        return prefix.subSequence(start, plen).toString()
                + inserted.substring(0, end - plen);
    }

    @Override
    public String toString() {
        return prefix.toString() + inserted;
    }
}
