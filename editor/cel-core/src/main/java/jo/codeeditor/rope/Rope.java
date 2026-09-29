package jo.codeeditor.rope;

import java.util.ArrayList;
import java.util.List;

/**
 * Rope : arbre binaire équilibré immuable pour l'édition efficace de grands
 * textes.
 * <p>
 * Rope équilibrée par poids. Utilise un arbre
 * feuilles/branches avec invariant d'équilibre de Fibonacci et fusion
 * d'épines (spine-merge) à la concaténation.
 * <p>
 * Toutes les opérations retournent de nouvelles instances de Rope ;
 * l'instance d'origine n'est jamais mutée.
 */
public abstract class Rope implements CharSequence {

    /** Nombre maximum de caractères par nœud feuille. */
    public static final int MAX_LEAF = 512;

    // Nombres de Fibonacci pour la vérification d'équilibre (précalculés jusqu'à une profondeur ~40)
    private static final long[] FIB = buildFibTable(50);

    private static long[] buildFibTable(int n) {
        long[] f = new long[n];
        f[0] = 0;
        f[1] = 1;
        for (int i = 2; i < n; i++) {
            f[i] = f[i - 1] + f[i - 2];
        }
        return f;
    }

    /** Longueur mise en cache pour un accès O(1). */
    protected final int length;
    /** Profondeur mise en cache pour les vérifications d'équilibre. */
    protected final int depth;

    protected Rope(int length, int depth) {
        this.length = length;
        this.depth = depth;
    }

    // ── Visite feuille par feuille (port amont c4bec0cf7) ───────

    /**
     * Visiteur des feuilles — appelé feuille par feuille avec le contenu
     * complet de la feuille et son offset global (début de feuille).
     */
    public interface LeafVisitor {
        /**
         * @param leafData    contenu complet de la feuille (accès direct au
         *                    String — pas de descente d'arbre par caractère)
         * @param globalStart offset documentaire du 1er caractère de la feuille
         * @return {@code false} pour interrompre la visite
         */
        boolean visit(String leafData, int globalStart);
    }

    /**
     * Visite les feuilles couvrant {@code [from, min(to, length))} en ordre
     * croissant, puis s'arrête.
     *
     * <p>Le coût est O(feuilles visitées + longueur visitée) : chaque
     * feuille expose son String en accès direct, au lieu d'une descente
     * d'arbre PAR CARACTÈRE ({@code charAt(i)} = O(log n) par appel, soit
     * O(n·log n) pour un scan complet). Le visiteur clippe lui-même sa
     * fenêtre aux positions globales couvertes par la feuille : le champ
     * couvre {@code [globalStart, globalStart + leafData.length())}.</p>
     */
    public void visitLeaves(int from, int to, LeafVisitor visitor) {
        if (visitor == null || to <= from) return;
        if (from < 0) from = 0;
        if (to > length) to = length;
        visitSub(this, 0, from, to, visitor, true);
    }

    /** Pareil en ordre DÉCROISSANT (scans arrière — appariement de crochets). */
    public void visitLeavesBackward(int from, int to, LeafVisitor visitor) {
        if (visitor == null || to <= from) return;
        if (from < 0) from = 0;
        if (to > length) to = length;
        visitSub(this, 0, from, to, visitor, false);
    }

    private static boolean visitSub(Rope node, int nodeStart,
                                    int from, int to, LeafVisitor v,
                                    boolean forward) {
        if (node instanceof Leaf) {
            return v.visit(((Leaf) node).data, nodeStart);
        }
        Branch b = (Branch) node;
        // Fin GLOBALE du sous-arbre gauche — les comparaisons se font en
        // coordonnées documentaires (l'invariant d'appel garantit que le
        // nœud courant recouvre [from, to) au moins partiellement).
        int leftEnd = nodeStart + b.left.length;
        if (forward) {
            if (from < leftEnd
                    && !visitSub(b.left, nodeStart, from, to, v, true)) {
                return false;
            }
            if (to > leftEnd
                    && !visitSub(b.right, leftEnd, from, to, v, true)) {
                return false;
            }
        } else {
            if (to > leftEnd
                    && !visitSub(b.right, leftEnd, from, to, v, false)) {
                return false;
            }
            if (from < leftEnd
                    && !visitSub(b.left, nodeStart, from, to, v, false)) {
                return false;
            }
        }
        return true;
    }

    // ── CharSequence ──────────────────────────────────────────────

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Length: " + length);
        }
        return charAtInternal(index);
    }

    protected abstract char charAtInternal(int index);

    @Override
    public Rope subSequence(int start, int end) {
        return sub(start, end);
    }

    // ── Opérations de base ───────────────────────────────────────

    /**
     * Retourne la sous-chaîne comme nouveau Rope.
     */
    public abstract Rope sub(int start, int end);

    /**
     * Matérialise le contenu du rope en String.
     */
    public String toString() {
        char[] buf = new char[length];
        writeTo(buf, 0);
        return new String(buf);
    }

    /** Écrit le contenu de ce rope dans buf à l'offset donné. */
    public abstract void writeTo(char[] buf, int offset);

    /**
     * Remplace la plage [start, end) par le texte d'insertion donné.
     */
    public Rope replace(int start, int end, String insertion) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException(
                "start=" + start + ", end=" + end + ", length=" + length);
        }
        if (start == end && insertion.isEmpty()) {
            return this;
        }
        Rope left = (start > 0) ? sub(0, start) : EMPTY;
        Rope right = (end < length) ? sub(end, length) : EMPTY;
        Rope mid = insertion.isEmpty() ? EMPTY : fromString(insertion);
        return concat(concat(left, mid), right);
    }

    /**
     * Concatène deux ropes avec l'optimisation spine-merge.
     */
    public static Rope concat(Rope left, Rope right) {
        if (left == EMPTY) return right;
        if (right == EMPTY) return left;

        // Fusion d'épine : si l'épine gauche de l'arbre droit est une petite
        // feuille, la replier dans la feuille la plus à droite de l'arbre
        // gauche quand c'est possible.
        if (right instanceof Leaf) {
            Leaf rLeaf = (Leaf) right;
            if (left instanceof Leaf) {
                Leaf lLeaf = (Leaf) left;
                if (lLeaf.length + rLeaf.length <= MAX_LEAF) {
                    return new Leaf(lLeaf.data + rLeaf.data);
                }
            } else if (left instanceof Branch) {
                // Tenter de replier dans le bord droit de left
                Branch lBranch = (Branch) left;
                Rope merged = tryMergeRight(lBranch.right, rLeaf);
                if (merged != null) {
                    return new Branch(lBranch.left, merged);
                }
            }
        }
        if (left instanceof Leaf && right instanceof Branch) {
            Leaf lLeaf = (Leaf) left;
            Branch rBranch = (Branch) right;
            // Tenter de replier la feuille gauche dans la feuille la plus à gauche de right
            Rope merged = tryMergeLeft(lLeaf, rBranch.left);
            if (merged != null) {
                return new Branch(merged, rBranch.right);
            }
        }

        return new Branch(left, right);
    }

    private static Rope tryMergeRight(Rope rightmost, Leaf leaf) {
        if (rightmost instanceof Leaf) {
            Leaf r = (Leaf) rightmost;
            if (r.length + leaf.length <= MAX_LEAF) {
                return new Leaf(r.data + leaf.data);
            }
        }
        return null;
    }

    private static Rope tryMergeLeft(Leaf leaf, Rope leftmost) {
        if (leftmost instanceof Leaf) {
            Leaf l = (Leaf) leftmost;
            if (leaf.length + l.length <= MAX_LEAF) {
                return new Leaf(leaf.data + l.data);
            }
        }
        return null;
    }

    /**
     * Reconstruit le rope en un arbre équilibré.
     * Collecte toutes les feuilles, coalescence des petites voisines, puis
     * reconstruction.
     */
    public Rope rebalance() {
        if (isBalanced()) return this;
        List<Leaf> leaves = new ArrayList<>();
        collectLeaves(leaves);
        coalesceLeaves(leaves);
        return buildBalanced(leaves, 0, leaves.size());
    }

    private boolean isBalanced() {
        return isBalancedNode(this);
    }

    private static boolean isBalancedNode(Rope node) {
        if (node instanceof Leaf) return true;
        Branch b = (Branch) node;
        return fibCheck(b.depth, b.length)
            && isBalancedNode(b.left)
            && isBalancedNode(b.right);
    }

    private static boolean fibCheck(int depth, int length) {
        if (depth >= FIB.length) return true;
        return length >= FIB[depth];
    }

    private void collectLeaves(List<Leaf> out) {
        if (this instanceof Leaf) {
            out.add((Leaf) this);
        } else {
            Branch b = (Branch) this;
            b.left.collectLeaves(out);
            b.right.collectLeaves(out);
        }
    }

    /**
     * Coalescence des petites feuilles adjacentes pour améliorer l'équilibre.
     */
    private static void coalesceLeaves(List<Leaf> leaves) {
        int i = 0;
        while (i < leaves.size() - 1) {
            Leaf cur = leaves.get(i);
            Leaf next = leaves.get(i + 1);
            if (cur.length + next.length <= MAX_LEAF) {
                leaves.set(i, new Leaf(cur.data + next.data));
                leaves.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    private static Rope buildBalanced(List<Leaf> leaves, int from, int to) {
        int count = to - from;
        if (count == 0) return EMPTY;
        if (count == 1) return leaves.get(from);
        int mid = from + count / 2;
        Rope left = buildBalanced(leaves, from, mid);
        Rope right = buildBalanced(leaves, mid, to);
        return new Branch(left, right);
    }

    // ── Fabrique ───────────────────────────────────────────────

    /** Singleton du rope vide. */
    public static final Rope EMPTY = new Leaf("");

    /**
     * Crée un Rope depuis une String, en découpant en feuilles de taille MAX_LEAF.
     */
    public static Rope fromString(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        if (text.length() <= MAX_LEAF) return new Leaf(text);

        // Découper en feuilles
        List<Leaf> leaves = new ArrayList<>();
        for (int i = 0; i < text.length(); i += MAX_LEAF) {
            int end = Math.min(i + MAX_LEAF, text.length());
            leaves.add(new Leaf(text.substring(i, end)));
        }
        return buildBalanced(leaves, 0, leaves.size());
    }

    // ── Leaf ──────────────────────────────────────────────────────

    public static final class Leaf extends Rope {
        final String data;

        Leaf(String data) {
            super(data.length(), 1);
            this.data = data;
        }

        @Override
        protected char charAtInternal(int index) {
            return data.charAt(index);
        }

        @Override
        public Rope sub(int start, int end) {
            if (start == 0 && end == length) return this;
            if (start >= end) return EMPTY;
            return new Leaf(data.substring(start, end));
        }

        @Override
        public void writeTo(char[] buf, int offset) {
            data.getChars(0, length, buf, offset);
        }
    }

    // ── Branch ────────────────────────────────────────────────────

    public static final class Branch extends Rope {
        final Rope left;
        final Rope right;

        Branch(Rope left, Rope right) {
            super(left.length + right.length, 1 + Math.max(left.depth, right.depth));
            this.left = left;
            this.right = right;
        }

        @Override
        protected char charAtInternal(int index) {
            if (index < left.length) {
                return left.charAtInternal(index);
            }
            return right.charAtInternal(index - left.length);
        }

        @Override
        public Rope sub(int start, int end) {
            if (start == 0 && end == length) return this;
            if (start >= end) return EMPTY;

            if (end <= left.length) {
                return left.sub(start, end);
            }
            if (start >= left.length) {
                return right.sub(start - left.length, end - left.length);
            }
            Rope l = left.sub(start, left.length);
            Rope r = right.sub(0, end - left.length);
            return concat(l, r);
        }

        @Override
        public void writeTo(char[] buf, int offset) {
            left.writeTo(buf, offset);
            right.writeTo(buf, offset + left.length);
        }
    }
}
