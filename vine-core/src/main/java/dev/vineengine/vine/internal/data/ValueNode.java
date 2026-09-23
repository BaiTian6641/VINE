package dev.vineengine.vine.internal.data;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable scalar leaf: one boxed value per {@link VoxelType} (arrays carry
 * defensive copies made at construction). Immutability is what makes
 * {@code snapshot()} and the resolve cache cheap — a leaf never changes in
 * place, so sharing it is always safe and zero-copy.
 *
 * <p><b>Invariants:</b> value class matches {@link #type} exactly (enforced
 * by the factories); the array payload belongs to this node — callers mutate
 * it only through the documented live-share on read, never through put.
 */
final class ValueNode extends VoxelNode {

    private final VoxelType type;
    private final Object value;

    private ValueNode(VoxelType type, Object value) {
        this.type = type;
        this.value = value;
    }

    static ValueNode of(byte value) {
        return new ValueNode(VoxelType.BYTE, value);
    }

    static ValueNode of(short value) {
        return new ValueNode(VoxelType.SHORT, value);
    }

    static ValueNode of(int value) {
        return new ValueNode(VoxelType.INT, value);
    }

    static ValueNode of(long value) {
        return new ValueNode(VoxelType.LONG, value);
    }

    static ValueNode of(float value) {
        return new ValueNode(VoxelType.FLOAT, value);
    }

    static ValueNode of(double value) {
        return new ValueNode(VoxelType.DOUBLE, value);
    }

    static ValueNode of(String value) {
        return new ValueNode(VoxelType.STRING, Objects.requireNonNull(value, "value"));
    }

    static ValueNode of(byte[] value) {
        Objects.requireNonNull(value, "value");
        return new ValueNode(VoxelType.BYTE_ARRAY, value.clone());
    }

    static ValueNode of(int[] value) {
        Objects.requireNonNull(value, "value");
        return new ValueNode(VoxelType.INT_ARRAY, value.clone());
    }

    static ValueNode of(long[] value) {
        Objects.requireNonNull(value, "value");
        return new ValueNode(VoxelType.LONG_ARRAY, value.clone());
    }

    @Override
    VoxelType type() {
        return type;
    }

    @Override
    ValueNode copyAttached(String newKey, VoxelNode newParent, TreeState targetTree) {
        ValueNode copy = switch (type) {
            case BYTE_ARRAY -> new ValueNode(VoxelType.BYTE_ARRAY, byteArrayValue().clone());
            case INT_ARRAY -> new ValueNode(VoxelType.INT_ARRAY, intArrayValue().clone());
            case LONG_ARRAY -> new ValueNode(VoxelType.LONG_ARRAY, longArrayValue().clone());
            default -> this; // immutable — sharing is the copy
        };
        copy.key = newKey;
        copy.parent = newParent;
        copy.tree = targetTree;
        return copy;
    }

    byte byteValue() {
        return (Byte) value;
    }

    short shortValue() {
        return (Short) value;
    }

    int intValue() {
        return (Integer) value;
    }

    long longValue() {
        return (Long) value;
    }

    float floatValue() {
        return (Float) value;
    }

    double doubleValue() {
        return (Double) value;
    }

    String stringValue() {
        return (String) value;
    }

    byte[] byteArrayValue() {
        return (byte[]) value;
    }

    int[] intArrayValue() {
        return (int[]) value;
    }

    long[] longArrayValue() {
        return (long[]) value;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ValueNode other
            && type == other.type
            && switch (type) {
                case BYTE_ARRAY -> Arrays.equals(byteArrayValue(), other.byteArrayValue());
                case INT_ARRAY -> Arrays.equals(intArrayValue(), other.intArrayValue());
                case LONG_ARRAY -> Arrays.equals(longArrayValue(), other.longArrayValue());
                default -> value.equals(other.value);
            };
    }

    @Override
    public int hashCode() {
        return 31 * type.ordinal() + switch (type) {
            case BYTE_ARRAY -> Arrays.hashCode(byteArrayValue());
            case INT_ARRAY -> Arrays.hashCode(intArrayValue());
            case LONG_ARRAY -> Arrays.hashCode(longArrayValue());
            default -> value.hashCode();
        };
    }
}
