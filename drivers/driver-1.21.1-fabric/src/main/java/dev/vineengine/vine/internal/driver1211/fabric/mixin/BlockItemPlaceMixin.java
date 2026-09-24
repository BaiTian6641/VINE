package dev.vineengine.vine.internal.driver1211.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import dev.vineengine.vine.internal.driver1211.fabric.events.MixinHookTap;

/**
 * Backs the {@code blockPlace} hook slot (sub-18 Stage E, Mixin Quarantine
 * §5.9): Fabric API has no block-place callback, NeoForge has
 * {@code BlockEvent.EntityPlaceEvent}, so this Mixin is the only place on this
 * loader where the engine can observe a placement.
 *
 * <p>Seam: {@code BlockItem#place(ItemPlacementContext)}. Every item-driven
 * placement funnels through it exactly once — {@code ItemStack#useOnBlock},
 * {@code BlockPlacementDispenserBehavior}, and the {@code BedItem}/
 * {@code TallBlockItem} overrides of the protected {@code place(ctx, state)}
 * (which is why the seam is the public method and not that one: tall blocks call
 * {@code super}, beds do not, so a Mixin on the protected one would either miss
 * beds or fire twice). Rejected alternative: {@code World#setBlockState} — it is
 * the global block write and would patch every vanilla and modded write,
 * violating Minimal Footprint (§5.1) and the quarantine's "no global vanilla
 * behavior change" rule.
 *
 * <p>Injection point: the call to the protected {@code place(ctx, state)} — the
 * step that applies the block. NeoForge fires {@code EntityPlaceEvent} only
 * after {@code ItemPlacementContext} preconditions held, and realizes a veto by
 * rolling the placed snapshots back; gating the application itself is the same
 * observable contract (veto ⇒ no block, item not consumed, {@code FAIL}
 * returned) without a second world write, and it is the only honest option
 * because the Fabric Mixin has no snapshot capture to roll back. Ordering note:
 * the NeoForge cell reports after the vanilla write and reverts, this cell
 * reports before it — the normalized payload and the veto outcome are identical,
 * the intra-vanilla position is not, and the TCK asserts the payload.
 *
 * <p>{@code @WrapOperation} rather than {@code @Redirect} so a pack mod may mix
 * into the same call site without a conflict; MixinExtras ships with Fabric
 * Loader (≥0.15) and is on this project's compile classpath through Loom.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {

    /**
     * Veto gate in front of the block application. Handler params are the
     * wrapped call site's receiver and arguments (so the placement state comes
     * from vanilla instead of a re-derivation, and no local capture pins this
     * Mixin to the method's frame); {@code original} runs the wrapped call.
     */
    @WrapOperation(
        method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/item/BlockItem;place(Lnet/minecraft/item/ItemPlacementContext;Lnet/minecraft/block/BlockState;)Z"))
    private boolean vine$gateBlockPlace(BlockItem item, ItemPlacementContext context, BlockState state,
            Operation<Boolean> original) {
        // Client threads predict placements locally; NeoForge's site is server-side
        // only, and the payload must stay one event per placement (parity).
        if (context.getWorld() instanceof ServerWorld world) {
            PlayerEntity player = context.getPlayer();
            boolean vetoed = MixinHookTap.postBlockPlace(
                world.getRegistryKey().getValue().toString(),
                Registries.BLOCK.getId(state.getBlock()).toString(),
                context.getBlockPos().getX(), context.getBlockPos().getY(), context.getBlockPos().getZ(),
                player == null ? "" : player.getUuidAsString());
            if (vetoed) {
                // The public place() turns this into ActionResult.FAIL and skips the
                // block write, the placed-by callback, the sound and the item
                // consumption — NeoForge's cancelled EntityPlaceEvent outcome.
                return false;
            }
        }
        return original.call(item, context, state);
    }
}
