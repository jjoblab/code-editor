package jo.codeeditor.rope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable balanced binary tree rope for efficient large-text editing.
 * <p>
 * Based on the CodeAssist editor architecture. Uses a leaf/branch tree
 * with Fibonacci-balance invariant and spine-merge on concat.
 * <p>
 * All operations return new Rope instances; the original is never mutated.
 
 *
 * @since v1.0.0
*/
public abstract class Rope implements CharSequence {

    /** Maximum characters per leaf node. */
    public static final int MAX_LEAF = 512;

    // Fibonacci numbers for balance checking (precomputed up to depth ~40)
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

    /** Cached length for O(1) access. */
    protected final int length;
    /** Cached depth for balance checks. */
    protected final int depth;

    protected Rope(int length, int depth) {
        this.length = length;
        this.depth = depth;
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

    // ── Core operations ───────────────────────────────────────────

    /**
     * Returns the substring as a new Rope.
     */
    public abstract Rope sub(int start, int end);

    /**
     * Materializes the rope content as a String.
     */
    public String toString() {
        char[] buf = new char[length];
        writeTo(buf, 0);
        return new String(buf);
    }

    /** Write this rope's content into buf at the given offset. */
    public abstract void writeTo(char[] buf, int offset);

    /**
     * Replaces the range [start, end) with the given insertion text.
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
     * Concatenates two ropes with spine-merge optimization.
     */
    public static Rope concat(Rope left, Rope right) {
        if (left == EMPTY) return right;
        if (right == EMPTY) return left;

        // Spine merge: if the right tree's left spine is a small leaf,
        // fold it into the left tree's rightmost leaf when possible.
        if (right instanceof Leaf) {
            Leaf rLeaf = (Leaf) right;
            if (left instanceof Leaf) {
                Leaf lLeaf = (Leaf) left;
                if (lLeaf.length + rLeaf.length <= MAX_LEAF) {
                    return new Leaf(lLeaf.data + rLeaf.data);
                }
            } else if (left instanceof Branch) {
                // Try to fold into the right edge of left
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
            // Try to fold left leaf into the leftmost leaf of right
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
     * Rebuilds the rope into a balanced tree.
     * Collects all leaves, coalesces small neighbors, then rebuilds.
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
     * Coalesces adjacent small leaves to improve balance.
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

    // ── Factory ───────────────────────────────────────────────────

    /** The empty rope singleton. */
    public static final Rope EMPTY = new Leaf("");

    /**
     * Creates a Rope from a String, splitting into MAX_LEAF-sized leaves.
     */
    public static Rope fromString(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        if (text.length() <= MAX_LEAF) return new Leaf(text);

        // Split into leaves
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
        // v3.33.5: equals/hashCode supprimés (code mort).
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
        // v3.33.5: equals/hashCode supprimés (code mort — materialise les deux ropes pour comparer).
    }
}
