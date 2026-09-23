package dev.vineengine.vine.internal.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelList;
import dev.vineengine.vine.data.VoxelSyncListener;
import dev.vineengine.vine.data.VoxelType;
import dev.vineengine.vine.data.VoxelView;

/**
 * Compound node — the only kind of node that can be a tree root, and the
 * whole mutable surface of {@link VoxelData} (sub-03 §2 internals:
 * insertion-ordered key map, interned segments, per-path cached child
 * resolution).
 *
 * <p><b>Insertion order</b> is preserved by a {@code LinkedHashMap} and is
 * what makes the wire encoding deterministic: the same tree always saves to
 * the same bytes.
 *
 * <p><b>Resolve cache:</b> each compound caches relative-path → node hits.
 * Soundness relies on every structural mutation walking up
 * ({@link #invalidateUp}) and clearing the caches of the mutation node and
 * all ancestors — any cache that could reference a replaced node lives on
 * that chain (caches hold nodes of their own subtree, and ancestor chains
 * are linear), while caches below the mutation point were orphaned with
 * their subtree. Hit reads therefore allocate nothing.
 *
 * <p><b>Equality</b> is structural and order-insensitive (map equality);
 * {@link #schemaVersion()} deliberately does not participate — two trees with
 * identical content are equal regardless of fixer history.
 */
final class VoxelDataImpl extends VoxelNode implements VoxelData {

    private final LinkedHashMap<String, VoxelNode> children = new LinkedHashMap<>();
    private final LinkedHashMap<String, VoxelNode> resolveCache = new LinkedHashMap<>();

    /** Fresh node with its own state; ownership fields are assigned on attach. */
    VoxelDataImpl(TreeState tree) {
        this.tree = tree;
    }

    @Override
    VoxelType type() {
        return VoxelType.COMPOUND;
    }

    @Override
    VoxelNode copyAttached(String newKey, VoxelNode newParent, TreeState targetTree) {
        VoxelDataImpl copy = new VoxelDataImpl(targetTree);
        copy.key = newKey;
        copy.parent = newParent;
        for (Map.Entry<String, VoxelNode> entry : children.entrySet()) {
            copy.children.put(entry.getKey(),
                entry.getValue().copyAttached(targetTree.intern(entry.getKey()), copy, targetTree));
        }
        return copy;
    }

    /** Produces the node to store, already owned by the target tree. */
    private interface NodeSource {
        VoxelNode attach(String key, VoxelNode parent, TreeState targetTree);
    }

    // ------------------------------------------------------------------ reads

    @Override
    public boolean contains(String path) {
        return resolve(path) != null;
    }

    @Override
    public VoxelType typeOf(String path) {
        VoxelNode node = resolve(path);
        return node == null ? null : node.type();
    }

    @Override
    public byte getByte(String path) {
        ValueNode leaf = leaf(path, VoxelType.BYTE);
        return leaf == null ? 0 : leaf.byteValue();
    }

    @Override
    public short getShort(String path) {
        ValueNode leaf = leaf(path, VoxelType.SHORT);
        return leaf == null ? 0 : leaf.shortValue();
    }

    @Override
    public int getInt(String path) {
        ValueNode leaf = leaf(path, VoxelType.INT);
        return leaf == null ? 0 : leaf.intValue();
    }

    @Override
    public long getLong(String path) {
        ValueNode leaf = leaf(path, VoxelType.LONG);
        return leaf == null ? 0 : leaf.longValue();
    }

    @Override
    public float getFloat(String path) {
        ValueNode leaf = leaf(path, VoxelType.FLOAT);
        return leaf == null ? 0 : leaf.floatValue();
    }

    @Override
    public double getDouble(String path) {
        ValueNode leaf = leaf(path, VoxelType.DOUBLE);
        return leaf == null ? 0 : leaf.doubleValue();
    }

    @Override
    public String getString(String path) {
        ValueNode leaf = leaf(path, VoxelType.STRING);
        return leaf == null ? "" : leaf.stringValue();
    }

    @Override
    public byte[] getByteArray(String path) {
        ValueNode leaf = leaf(path, VoxelType.BYTE_ARRAY);
        return leaf == null ? new byte[0] : leaf.byteArrayValue();
    }

    @Override
    public int[] getIntArray(String path) {
        ValueNode leaf = leaf(path, VoxelType.INT_ARRAY);
        return leaf == null ? new int[0] : leaf.intArrayValue();
    }

    @Override
    public long[] getLongArray(String path) {
        ValueNode leaf = leaf(path, VoxelType.LONG_ARRAY);
        return leaf == null ? new long[0] : leaf.longArrayValue();
    }

    @Override
    public VoxelList getList(String path) {
        VoxelNode node = resolve(path);
        if (node == null) {
            return detachedList();
        }
        return (ListNode) requireType(path, node, VoxelType.LIST);
    }

    @Override
    public VoxelData getCompound(String path) {
        VoxelNode node = resolve(path);
        if (node == null) {
            return detachedChild();
        }
        return (VoxelDataImpl) requireType(path, node, VoxelType.COMPOUND);
    }

    @Override
    public int schemaVersion() {
        return tree.version;
    }

    @Override
    public VoxelView snapshot() {
        return new VoxelViewImpl(this);
    }

    /**
     * Registration only — dirty-path dispatch activates with sub-03 Stage E;
     * until then listeners are dormant (Minimal Footprint). Tree-scoped: the
     * set lives on the shared tree state, so live children registering reach
     * the same dispatch point.
     */
    @Override
    public void addChangeListener(VoxelSyncListener listener) {
        tree.addListener(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public VoxelData copy() {
        VoxelDataImpl detached = new VoxelDataImpl(new TreeState(tree.schemaId, tree.version, tree.readOnly));
        for (Map.Entry<String, VoxelNode> entry : children.entrySet()) {
            detached.children.put(entry.getKey(),
                entry.getValue().copyAttached(detached.tree.intern(entry.getKey()), detached, detached.tree));
        }
        return detached;
    }

    // ----------------------------------------------------------------- writes

    @Override
    public void put(String path, byte value) {
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, short value) {
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, int value) {
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, long value) {
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, float value) {
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, double value) {
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, String value) {
        Objects.requireNonNull(value, "value");
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, byte[] value) {
        Objects.requireNonNull(value, "value");
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, int[] value) {
        Objects.requireNonNull(value, "value");
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, long[] value) {
        Objects.requireNonNull(value, "value");
        putNode(path, (key, parent, target) -> ValueNode.of(value));
    }

    @Override
    public void put(String path, VoxelList value) {
        ListNode source = asListNode(Objects.requireNonNull(value, "value"));
        putNode(path, source::copyAttached);
    }

    @Override
    public void put(String path, VoxelData value) {
        VoxelDataImpl source = asImpl(Objects.requireNonNull(value, "value"));
        putNode(path, source::copyAttached);
    }

    @Override
    public void remove(String path) {
        checkWritable();
        VoxelNode node = resolve(path);
        if (node == null) {
            return; // removing an absent path is a no-op, never an error
        }
        VoxelDataImpl owner = (VoxelDataImpl) node.parent;
        owner.children.remove(node.key);
        invalidateUp(owner);
    }

    // -------------------------------------------------------------- internals

    /** Resolve a dot-path relative to this compound; {@code null} when absent. */
    VoxelNode resolve(String path) {
        Objects.requireNonNull(path, "path");
        VoxelNode cached = resolveCache.get(path);
        if (cached != null) {
            return cached;
        }
        validatePath(path);
        int length = path.length();
        int segmentStart = 0;
        VoxelDataImpl node = this;
        StringBuilder relative = new StringBuilder();
        while (true) {
            int dot = path.indexOf('.', segmentStart);
            String segment = path.substring(segmentStart, dot < 0 ? length : dot);

            if (relative.length() > 0) {
                relative.append('.');
            }
            relative.append(segment);
            VoxelNode child = node.children.get(segment);
            if (child == null) {
                return null;
            }
            if (dot < 0) {
                resolveCache.put(relative.toString(), child);
                return child;
            }
            if (!(child instanceof VoxelDataImpl compound)) {
                throw new IllegalArgumentException(
                    "path '" + path + "' crosses '" + relative + "' which holds "
                        + child.type() + ", not COMPOUND");
            }
            resolveCache.put(relative.toString(), child);
            node = compound;
            segmentStart = dot + 1;
        }
    }

    private void putNode(String path, NodeSource source) {
        checkWritable();
        Objects.requireNonNull(path, "path");
        validatePath(path);
        int lastDot = path.lastIndexOf('.');
        String leafKey = lastDot < 0 ? path : path.substring(lastDot + 1);
        requireSegment(path, leafKey);
        VoxelDataImpl owner = lastDot < 0 ? this : createPath(path, path.substring(0, lastDot));
        String internedKey = tree.intern(leafKey);
        VoxelNode node = source.attach(internedKey, owner, tree);
        // Copy-attached nodes arrive owned; fresh scalar factories ignore the
        // attach args, so ownership is (re-)asserted here either way.
        node.key = internedKey;
        node.parent = owner;
        node.tree = tree;
        owner.children.put(internedKey, node);
        invalidateUp(owner);
    }

    /** Walk a parent path, auto-creating compounds; a blocking non-compound throws. */
    private VoxelDataImpl createPath(String path, String parentPath) {
        if (parentPath.isEmpty()) {
            requireSegment(path, ""); // leading dot — the first segment is empty
        }
        VoxelDataImpl node = this;
        int segmentStart = 0;
        int length = parentPath.length();
        while (segmentStart < length) {
            int dot = parentPath.indexOf('.', segmentStart);
            String segment = parentPath.substring(segmentStart, dot < 0 ? length : dot);
            requireSegment(path, segment);
            VoxelNode child = node.children.get(segment);
            if (child == null) {
                String interned = tree.intern(segment);
                VoxelDataImpl created = new VoxelDataImpl(tree);
                created.key = interned;
                created.parent = node;
                node.children.put(interned, created);
                node = created;
            } else if (child instanceof VoxelDataImpl compound) {
                node = compound;
            } else {
                throw new IllegalArgumentException(
                    "path '" + path + "' crosses '" + segment + "' which holds "
                        + child.type() + ", not COMPOUND — refusing to destroy it");
            }
            if (dot < 0) {
                break;
            }
            segmentStart = dot + 1;
        }
        return node;
    }

    /** Wire-writer access to children in insertion order. */
    Set<Map.Entry<String, VoxelNode>> childEntries() {
        return children.entrySet();
    }

    /** Wire-reader attach: takes ownership directly, no path semantics. */
    void attachParsed(String key, VoxelNode node) {
        String interned = tree.intern(key);
        node.key = interned;
        node.parent = this;
        node.tree = tree;
        children.put(interned, node);
    }

    private void checkWritable() {
        if (tree.readOnly) {
            throw new IllegalStateException(
                "VoxelData tree of schema " + tree.schemaId + " is read-only: blob version "
                    + tree.version + " is newer than any registered schema — the engine never destroys unknown data");
        }
    }

    /** Detached empty child for {@code getCompound} on a missing path. */
    private VoxelDataImpl detachedChild() {
        return new VoxelDataImpl(new TreeState(tree.schemaId, tree.version, tree.readOnly));
    }

    /** Detached empty list for {@code getList} on a missing path. */
    private ListNode detachedList() {
        return new ListNode(new TreeState(tree.schemaId, tree.version, tree.readOnly));
    }

    /** Scalar-or-{@code null} helper; {@code null} means missing (default value). */
    private ValueNode leaf(String path, VoxelType expected) {
        VoxelNode node = resolve(path);
        return node == null ? null : (ValueNode) requireType(path, node, expected);
    }

    private static VoxelNode requireType(String path, VoxelNode node, VoxelType expected) {
        if (node.type() != expected) {
            throw new IllegalArgumentException(
                "path '" + path + "' holds " + node.type() + ", not " + expected
                    + " — VoxelData never coerces between types");
        }
        return node;
    }

    private static void requireSegment(String path, String segment) {
        if (segment.isEmpty()) {
            throw new IllegalArgumentException(
                "path '" + path + "' must be non-empty dot-separated segments (no '', leading or trailing dots)");
        }
    }

    /** Structural path check: non-empty, no leading/trailing/inner empty segments. */
    private static void validatePath(String path) {
        if (path.isEmpty() || path.charAt(0) == '.' || path.charAt(path.length() - 1) == '.'
            || path.indexOf("..") >= 0) {
            throw new IllegalArgumentException(
                "path '" + path + "' must be non-empty dot-separated segments (no '', leading or trailing dots)");
        }
    }

    private static ListNode asListNode(VoxelList value) {
        if (!(value instanceof ListNode node)) {
            throw new IllegalArgumentException(
                "foreign VoxelList implementation " + value.getClass().getName()
                    + " — trees only accept engine-owned nodes");
        }
        return node;
    }

    static VoxelDataImpl asImpl(VoxelData value) {
        if (!(value instanceof VoxelDataImpl node)) {
            throw new IllegalArgumentException(
                "foreign VoxelData implementation " + value.getClass().getName()
                    + " — trees only accept engine-owned nodes");
        }
        return node;
    }

    /** Clear resolve caches on this node and every ancestor after a structural change. */
    static void invalidateUp(VoxelNode from) {
        for (VoxelNode node = from; node != null; node = node.parent) {
            if (node instanceof VoxelDataImpl compound) {
                compound.resolveCache.clear();
            }
        }
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof VoxelDataImpl other && children.equals(other.children);
    }

    @Override
    public int hashCode() {
        return children.hashCode();
    }
}
