package dev.vineengine.vine.internal.data;

/**
 * The closed value-type system of the {@code VoxelData} tree (sub-03 §2):
 * NBT-shaped, nothing else — every cell post-1.20.5 speaks this vocabulary
 * natively, so a serialized tree is a plain {@code CompoundTag} everywhere.
 *
 * <p>The {@link #nbtId()} values are vanilla NBT tag ids (byte=1 … long
 * array=12). The wire codec depends on that mapping being stable and
 * NBT-compatible: payloads must survive vanilla save/load and component sync
 * untouched on every cell. Adding a type is a wire-format break — the blob
 * format version bumps with it, never silently.
 */
public enum VoxelType {
    BYTE(1),
    SHORT(2),
    INT(3),
    LONG(4),
    FLOAT(5),
    DOUBLE(6),
    BYTE_ARRAY(7),
    STRING(8),
    LIST(9),
    COMPOUND(10),
    INT_ARRAY(11),
    LONG_ARRAY(12);

    private final int nbtId;

    VoxelType(int nbtId) {
        this.nbtId = nbtId;
    }

    /** Vanilla NBT tag id this type serializes as; fixed by the wire format. */
    public int nbtId() {
        return nbtId;
    }

    /** Inverse of {@link #nbtId()}; unknown ids fail explicitly, never guess. */
    static VoxelType byNbtId(int nbtId) {
        for (VoxelType type : values()) {
            if (type.nbtId == nbtId) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown NBT tag id in VoxelData payload: " + nbtId);
    }
}
