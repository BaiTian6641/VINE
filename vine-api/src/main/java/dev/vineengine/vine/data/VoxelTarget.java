package dev.vineengine.vine.data;

/**
 * The five attach points a {@code VoxelData} tree can ride (sub-03 §2):
 * item stacks, block entities, entities, players, worlds. Sealed because the
 * driver dispatch ({@code VoxelStorageDriver}) switches exhaustively over
 * these — a new attach point is an SPI-visible event, never a silent default.
 *
 * <p><b>Interim seam (Stage C):</b> four of the five targets still carry the
 * raw per-cell object — the engine facade handles for entities, block
 * entities, item stacks, and worlds land with sub-07/08/13. Until then
 * consumers obtain these objects from engine surfaces (events, facades) and
 * drivers interpret them per cell; no game type appears in any signature
 * (Prime Invariant — the payload type is {@code Object}, interpreted only
 * inside driver jars).
 */
public sealed interface VoxelTarget permits ItemStackTarget, BlockEntityTarget,
        EntityTarget, PlayerTarget, WorldTarget {
}
