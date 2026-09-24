package dev.vineengine.vine.internal.driver1211.common.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.vineengine.vine.registry.VineId;

/**
 * The engine's block-entity carrier (sub-07 Stage C, minimal): a plain vanilla
 * block entity whose payload is the engine's attach point, exactly like the item
 * and entity holders — the payload rides the cell's attachment mechanism, so the
 * engine bundle, dirty tracking, save/load and the native-field mapping apply
 * without a behavior language existing yet.
 *
 * <p>Drivers register one block-entity type per flagged descriptor and record it
 * here, keyed by the block's engine id; nothing about the type crosses into
 * {@code vine-api} (Prime Invariant), and the class itself is loader-agnostic
 * vanilla code, so both cells share it.
 */
public final class VineBlockEntity {

    /** Engine id -> the cell's block-entity type for that block. */
    private static final Map<VineId, Object> TYPES = new ConcurrentHashMap<>();

    private VineBlockEntity() {
    }

    /** Records the materialized block-entity type for {@code id}. */
    public static void register(VineId id, Object blockEntityType) {
        TYPES.put(id, blockEntityType);
    }

    /** The block-entity type materialized for {@code id}, or {@code null}. */
    public static Object typeFor(VineId id) {
        return TYPES.get(id);
    }
}
