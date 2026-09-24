package dev.vineengine.vine.internal.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelDataFixer;
import dev.vineengine.vine.data.VoxelType;
import dev.vineengine.vine.registry.VineId;

/**
 * Wire/save format of a VoxelData tree (sub-03 §2): an engine header
 * (magic, format version, schema id, schema version) followed by an
 * NBT-compatible anonymous-root compound tag stream.
 *
 * <p>The payload is byte-for-byte a plain vanilla {@code CompoundTag} body —
 * every cell's save files and component sync carry it unchanged; the header
 * is what stays engine-owned and routes fix-on-load, keeping Mojang's DFU
 * data versions out of the picture entirely.
 *
 * <p><b>Byte stability:</b> insertion-ordered maps plus fixed-width,
 * big-endian encodings make {@code save(load(save(t))) == save(t)} — the
 * Stage-A acceptance invariant and the property golden fixtures pin. Strings
 * use Java modified UTF-8 ({@code writeUTF}), exactly NBT's string encoding.
 * Floats/doubles go through {@code writeFloat}/{@code writeDouble}, matching
 * vanilla NBT's bit layout.
 *
 * <p><b>Hostile input:</b> every variable-length prefix is validated against
 * the remaining bytes before allocating (same policy as the net stack);
 * nesting is depth-capped so no blob can recurse the reader off the stack;
 * trailing bytes are rejected.
 */
public final class VoxelBlobCodec {

    private static final System.Logger LOG = System.getLogger("vine.data");

    /** 'VOXD' — bytes every blob starts with; anything else is not a VoxelData blob. */
    static final byte[] MAGIC = {'V', 'O', 'X', 'D'};

    /** Delta blobs (sub-03 Stage E) carry their own magic: they hold a sparse
     * compound that is *applied* onto a tree, never loaded as one. */
    static final byte[] DELTA_MAGIC = {'V', 'O', 'X', 'A'};

    /**
     * Holder payloads are *bundles* (sub-03 Stage E): one holder can carry trees
     * of several schemas — an entity with engine data and a capability state, for
     * instance — so the stored bytes are a small container keyed by schema id
     * rather than a single tree. A single-tree payload from an older build is
     * still read as a one-entry bundle, so nothing in the field breaks.
     */
    static final byte[] BUNDLE_MAGIC = {'V', 'O', 'X', 'B'};

    /** Header layout version; bumps only on a header-shape break, never silently. */
    static final int FORMAT_VERSION = 1;

    /** NBT tag id that terminates a compound body. */
    private static final int TAG_END = 0;

    /** Nesting cap for both compounds and lists — hostile-input guard, far above any real schema. */
    private static final int MAX_DEPTH = 512;

    private VoxelBlobCodec() {
    }

    // ------------------------------------------------------------------ write

    /** Serializes {@code data} to its engine-headed blob form. */
    public static byte[] save(VoxelData data) {
        VoxelDataImpl root = VoxelDataImpl.asImpl(Objects.requireNonNull(data, "data"));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try {
            DataOutputStream out = new DataOutputStream(buffer);
            out.write(MAGIC);
            out.writeByte(FORMAT_VERSION);
            out.writeUTF(root.tree.schemaId.toString());
            out.writeInt(root.tree.version);
            out.writeByte(VoxelType.COMPOUND.nbtId());
            writeCompound(out, root);
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory write cannot fail", e); // BAOS never throws
        }
        return buffer.toByteArray();
    }

    /**
     * Serializes only the entries named by {@code paths} as a delta blob
     * (sub-03 Stage E): same header shape, own magic, sparse compound holding the
     * dirty slice — the values as of now, so applying it onto a peer's tree
     * yields exactly this state for those paths.
     */
    public static byte[] saveDelta(VoxelData data, java.util.Set<String> paths) {
        VoxelDataImpl root = VoxelDataImpl.asImpl(Objects.requireNonNull(data, "data"));
        Objects.requireNonNull(paths, "paths");
        VoxelDataImpl sparse = VoxelDataImpl.detached(root.tree.schemaId);
        java.util.List<String> present = new java.util.ArrayList<>(paths.size());
        java.util.List<String> deleted = new java.util.ArrayList<>(0);
        for (String path : paths) {
            VoxelNode node = root.resolve(path);
            if (node == null) {
                // A dirty path that no longer exists is a deletion: named in the
                // header (there is no value to carry) and applied as a removal.
                deleted.add(path);
            } else {
                present.add(path);
            }
        }
        java.util.Collections.sort(present);
        java.util.Collections.sort(deleted);
        for (String path : present) {
            sparse.putNodeCopy(path, root.resolve(path));
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try {
            DataOutputStream out = new DataOutputStream(buffer);
            out.write(DELTA_MAGIC);
            out.writeByte(FORMAT_VERSION);
            out.writeUTF(root.tree.schemaId.toString());
            out.writeInt(root.tree.version);
            // Only deletions need a name: the sparse compound below carries its
            // own keys, so carrying present paths twice would make a one-field
            // delta cost more than the tree it describes.
            out.writeInt(deleted.size());
            for (String path : deleted) {
                out.writeUTF(path);
            }
            out.writeByte(VoxelType.COMPOUND.nbtId());
            writeCompound(out, sparse);
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory write cannot fail", e);
        }
        return buffer.toByteArray();
    }

    /**
     * Applies a {@link #saveDelta} blob onto {@code target}: entries present in
     * the delta overwrite the target's, entries the delta names but does not
     * carry are removed (they were deleted on the writer's side).
     *
     * @return the number of paths applied
     */
    public static int applyDelta(VoxelData target, byte[] blob) {
        VoxelDataImpl root = VoxelDataImpl.asImpl(Objects.requireNonNull(target, "target"));
        Objects.requireNonNull(blob, "blob");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob));
            byte[] magic = new byte[DELTA_MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, DELTA_MAGIC)) {
                throw new IllegalArgumentException("not a VoxelData delta blob");
            }
            in.readByte(); // header version
            String schemaId = in.readUTF();
            in.readInt();  // schema version: deltas ride the receiver's tree
            int deletedCount = in.readInt();
            java.util.List<String> deleted = new java.util.ArrayList<>(deletedCount);
            for (int i = 0; i < deletedCount; i++) {
                deleted.add(in.readUTF());
            }
            int tagId = in.readByte();
            if (tagId != VoxelType.COMPOUND.nbtId()) {
                throw new IllegalArgumentException("delta payload is not a compound");
            }
            VoxelDataImpl sparse = readCompound(in, new TreeState(VineId.parse(schemaId), 1), 0);
            int applied = applySparse(root, sparse);
            for (String path : deleted) {
                root.remove(path);
                applied++;
            }
            return applied;
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory read cannot fail", e);
        }
    }

    /**
     * Merges a sparse delta compound into {@code target} recursively; returns
     * entries applied. Paths are resolved *relative to the receiver* at every
     * level (that is what {@code VoxelData.put} means), so the recursion walks
     * both trees in lockstep rather than re-resolving absolute paths.
     */
    private static int applySparse(VoxelDataImpl target, VoxelDataImpl sparse) throws IOException {
        int applied = 0;
        for (Map.Entry<String, VoxelNode> entry : sparse.childEntries()) {
            VoxelNode value = entry.getValue();
            if (value instanceof VoxelDataImpl nested && target.resolve(entry.getKey()) instanceof VoxelDataImpl into) {
                // Compound-on-compound merges: a delta never deletes siblings it
                // did not touch.
                applied += applySparse(into, nested);
            } else {
                target.putNodeCopy(entry.getKey(), value);
                applied++;
            }
        }
        return applied;
    }

    /** Encodes a holder payload: {@code schemaId -> tree blob}, in stable order. */
    public static byte[] saveBundle(java.util.Map<VineId, byte[]> trees) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try {
            DataOutputStream out = new DataOutputStream(buffer);
            out.write(BUNDLE_MAGIC);
            out.writeByte(FORMAT_VERSION);
            java.util.List<VineId> ids = new java.util.ArrayList<>(trees.keySet());
            ids.sort(java.util.Comparator.comparing(VineId::toString));
            out.writeInt(ids.size());
            for (VineId id : ids) {
                out.writeUTF(id.toString());
                byte[] blob = trees.get(id);
                out.writeInt(blob.length);
                out.write(blob);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory write cannot fail", e);
        }
        return buffer.toByteArray();
    }

    /**
     * Decodes a holder payload into {@code schemaId -> tree blob}. A bare tree
     * blob (no bundle magic) decodes as a one-entry bundle under {@code fallbackId}
     * — the shape older builds wrote, read rather than rejected.
     */
    public static java.util.Map<VineId, byte[]> loadBundle(byte[] payload, VineId fallbackId) {
        Objects.requireNonNull(payload, "payload");
        if (!startsWith(payload, BUNDLE_MAGIC)) {
            return java.util.Map.of(fallbackId, payload);
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            byte[] magic = new byte[BUNDLE_MAGIC.length];
            in.readFully(magic);
            in.readByte(); // header version
            int count = in.readInt();
            if (count < 0 || count > 64) {
                throw new IllegalArgumentException("bundle entry count out of range: " + count);
            }
            java.util.Map<VineId, byte[]> out = new java.util.LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                VineId id = VineId.parse(in.readUTF());
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    throw new IllegalArgumentException("bundle entry length out of range: " + length);
                }
                byte[] blob = new byte[length];
                in.readFully(blob);
                out.put(id, blob);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory read cannot fail", e);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static void writeCompound(DataOutputStream out, VoxelDataImpl node) throws IOException {
        for (Map.Entry<String, VoxelNode> entry : node.childEntries()) {
            out.writeByte(entry.getValue().type().nbtId());
            out.writeUTF(entry.getKey());
            writeBody(out, entry.getValue());
        }
        out.writeByte(TAG_END);
    }

    private static void writeBody(DataOutputStream out, VoxelNode node) throws IOException {
        // The sealed model gives an exhaustive switch: a new node kind cannot
        // compile without extending the wire format deliberately.
        switch (node.type()) {
            case BYTE -> out.writeByte(((ValueNode) node).byteValue());
            case SHORT -> out.writeShort(((ValueNode) node).shortValue());
            case INT -> out.writeInt(((ValueNode) node).intValue());
            case LONG -> out.writeLong(((ValueNode) node).longValue());
            case FLOAT -> out.writeFloat(((ValueNode) node).floatValue());
            case DOUBLE -> out.writeDouble(((ValueNode) node).doubleValue());
            case STRING -> out.writeUTF(((ValueNode) node).stringValue());
            case BYTE_ARRAY -> {
                byte[] value = ((ValueNode) node).byteArrayValue();
                out.writeInt(value.length);
                out.write(value);
            }
            case INT_ARRAY -> {
                int[] value = ((ValueNode) node).intArrayValue();
                out.writeInt(value.length);
                for (int element : value) {
                    out.writeInt(element);
                }
            }
            case LONG_ARRAY -> {
                long[] value = ((ValueNode) node).longArrayValue();
                out.writeInt(value.length);
                for (long element : value) {
                    out.writeLong(element);
                }
            }
            case LIST -> writeList(out, (ListNode) node);
            case COMPOUND -> writeCompound(out, (VoxelDataImpl) node);
        }
    }

    private static void writeList(DataOutputStream out, ListNode list) throws IOException {
        VoxelType elementType = list.elementType();
        out.writeByte(elementType == null ? TAG_END : elementType.nbtId());
        out.writeInt(list.size());
        for (VoxelNode element : list.elementNodes()) {
            writeBody(out, element);
        }
    }

    // ------------------------------------------------------------------- read

    /**
     * Parses a blob and applies version routing: older blobs fix up to the
     * registered current version (lazily, in chain order), equal blobs load
     * as-is, and newer-than-registered blobs open read-only with a warning —
     * never destructive.
     *
     * @throws IllegalArgumentException on a non-blob, unknown schema, or
     *         malformed payload
     * @throws IllegalStateException if a fixer returns {@code null} or a tree
     *         of a different schema
     */
    public static VoxelData load(byte[] blob, SchemaRegistry registry) {
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(registry, "registry");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob));
            for (byte magicByte : MAGIC) {
                if (in.readUnsignedByte() != (magicByte & 0xFF)) {
                    throw new IllegalArgumentException("not a VoxelData blob (bad magic)");
                }
            }
            int format = in.readUnsignedByte();
            if (format != FORMAT_VERSION) {
                throw new IllegalArgumentException(
                    "unsupported VoxelData blob format " + format + " (this engine reads " + FORMAT_VERSION + ")");
            }
            VineId schemaId = VineId.parse(in.readUTF());
            int version = in.readInt();
            int rootTagId = in.readUnsignedByte();
            if (rootTagId != VoxelType.COMPOUND.nbtId()) {
                throw new IllegalArgumentException(
                    "malformed VoxelData payload: root tag id " + rootTagId + ", expected compound "
                        + VoxelType.COMPOUND.nbtId());
            }
            TreeState tree = new TreeState(schemaId, version);
            VoxelDataImpl root = readCompound(in, tree, 1);
            if (in.available() != 0) {
                throw new IllegalArgumentException("malformed VoxelData payload: trailing bytes after root compound");
            }
            return route(root, registry);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed VoxelData payload: " + e, e);
        }
    }

    /** Header-derived routing: fix older, accept equal, guard newer read-only. */
    private static VoxelData route(VoxelDataImpl parsed, SchemaRegistry registry) {
        VineId schemaId = parsed.tree.schemaId;
        SchemaRegistry.SchemaEntry entry = registry.get(schemaId).orElseThrow(
            () -> new IllegalArgumentException(
                "VoxelData blob of unknown schema " + schemaId
                    + " — its consumer is not present; refusing to guess at the data"));
        int current = entry.schema().version();
        VoxelDataImpl root = parsed;
        if (root.tree.version > current) {
            root.tree.readOnly = true;
            LOG.log(System.Logger.Level.WARNING,
                "[VINE] schema " + schemaId + " blob version " + root.tree.version
                    + " is newer than registered version " + current
                    + " — opened read-only, mutations refused (never destructive)");
            return root;
        }
        for (int from = root.tree.version; from < current; from++) {
            VoxelDataFixer fixer = entry.fixers().get(from - 1);
            VoxelData fixed = fixer.fix(root, from);
            if (fixed == null) {
                throw new IllegalStateException(
                    "fixer for " + root.tree.schemaId + " v" + from + "→v" + (from + 1) + " returned null");
            }
            VoxelDataImpl next = VoxelDataImpl.asImpl(fixed);
            if (!next.tree.schemaId.equals(root.tree.schemaId)) {
                throw new IllegalStateException(
                    "fixer for " + root.tree.schemaId + " v" + from + "→v" + (from + 1)
                        + " returned a tree of schema " + next.tree.schemaId);
            }
            next.tree.version = from + 1;
            root = next;
        }
        return root;
    }

    private static VoxelDataImpl readCompound(DataInputStream in, TreeState tree, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw malformed("nesting deeper than " + MAX_DEPTH);
        }
        VoxelDataImpl node = new VoxelDataImpl(tree);
        while (true) {
            int tagId = in.readUnsignedByte();
            if (tagId == TAG_END) {
                return node;
            }
            VoxelType type = VoxelType.byNbtId(tagId);
            String key = in.readUTF();
            node.attachParsed(key, readBody(in, tree, type, depth));
        }
    }

    private static ListNode readList(DataInputStream in, TreeState tree, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw malformed("nesting deeper than " + MAX_DEPTH);
        }
        int elementTagId = in.readUnsignedByte();
        int count = in.readInt();
        if (count < 0 || count > in.available()) {
            throw malformed("list length " + count + " exceeds remaining payload");
        }
        ListNode list = new ListNode(tree);
        if (elementTagId == TAG_END) {
            if (count != 0) {
                throw malformed("untyped list claims " + count + " elements");
            }
            return list; // empty and never-filled: element type stays null
        }
        VoxelType elementType = VoxelType.byNbtId(elementTagId);
        list.fixElementType(elementType);
        for (int i = 0; i < count; i++) {
            // Elements carry no tag id of their own in NBT — parse as the
            // declared type; that is also the homogeneity guarantee.
            list.addNode(readBody(in, tree, elementType, depth + 1));
        }
        return list;
    }

    private static VoxelNode readBody(DataInputStream in, TreeState tree, VoxelType type, int depth)
            throws IOException {
        return switch (type) {
            case BYTE -> ValueNode.of(in.readByte());
            case SHORT -> ValueNode.of(in.readShort());
            case INT -> ValueNode.of(in.readInt());
            case LONG -> ValueNode.of(in.readLong());
            case FLOAT -> ValueNode.of(in.readFloat());
            case DOUBLE -> ValueNode.of(in.readDouble());
            case STRING -> ValueNode.of(in.readUTF());
            case BYTE_ARRAY -> ValueNode.of(readExact(in, requireLength(in, 1)));
            case INT_ARRAY -> {
                int[] value = new int[requireLength(in, 4)];
                for (int i = 0; i < value.length; i++) {
                    value[i] = in.readInt();
                }
                yield ValueNode.of(value);
            }
            case LONG_ARRAY -> {
                long[] value = new long[requireLength(in, 8)];
                for (int i = 0; i < value.length; i++) {
                    value[i] = in.readLong();
                }
                yield ValueNode.of(value);
            }
            case LIST -> readList(in, tree, depth + 1);
            case COMPOUND -> readCompound(in, tree, depth + 1);
        };
    }

    /** Array length prefix checked against remaining bytes before allocating. */
    private static int requireLength(DataInputStream in, int bytesPerElement) throws IOException {
        int length = in.readInt();
        if (length < 0 || (long) length * bytesPerElement > in.available()) {
            throw malformed("array length " + length + " exceeds remaining payload");
        }
        return length;
    }

    private static byte[] readExact(DataInputStream in, int length) throws IOException {
        byte[] value = in.readNBytes(length);
        if (value.length != length) {
            throw malformed("truncated byte array");
        }
        return value;
    }

    private static IllegalArgumentException malformed(String detail) {
        return new IllegalArgumentException("malformed VoxelData payload: " + detail);
    }
}
