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
