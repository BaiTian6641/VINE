package dev.vineengine.vine.internal.driver1211.fabric.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ProgressListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.vineengine.vine.internal.driver1211.fabric.events.MixinHookTap;

/**
 * Backs the {@code worldSave} hook slot (sub-18 Stage E, Mixin Quarantine §5.9):
 * Fabric API's {@code ServerWorldEvents} has Load/Unload only — no save
 * callback — while NeoForge observes {@code LevelEvent.Save}, so this Mixin is
 * the only place on this loader where the engine can observe a save.
 *
 * <p>Seam: {@code ServerWorld#save(ProgressListener, boolean, boolean)}, the
 * method NeoForge patches and documents for {@code LevelEvent.Save}. Rejected
 * alternative: the {@code saveLevel()} helper it calls — that is level.data
 * only, so a save that skips or cannot write level data (and the engine's
 * world-store flush, which rides this hook) would go unobserved.
 *
 * <p>Injection point: {@code RETURN}, guarded by the {@code skipSave} flag. The
 * whole vanilla body is one {@code if (!skipSave)} block, so re-testing the
 * flag is the only way to mean "inside that branch" without pinning the Mixin
 * to the method's frame with a local capture; {@code RETURN} is also where
 * NeoForge's own site sits in the sequence — after the chunk and entity
 * persistence, before {@code save} returns to the server. {@code @At("RETURN")}
 * for a void method needs no return capture, and vanilla has no early return
 * on the path, so the handler runs exactly once per call.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldSaveMixin {

    /** Posts one {@link dev.vineengine.vine.hook.HookEvents.WorldSave} per non-skipped save. */
    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V", at = @At("RETURN"))
    private void vine$worldSave(ProgressListener progressListener, boolean flush, boolean skipSave,
            CallbackInfo ci) {
        if (!skipSave) {
            // A save with skipSave=true persists nothing (vanilla returns without
            // writing); NeoForge's LevelEvent.Save is inside the same !skipSave
            // branch, so posting here would report a save that did not happen.
            MixinHookTap.postWorldSave(((ServerWorld) (Object) this).getRegistryKey().getValue().toString());
        }
    }
}
