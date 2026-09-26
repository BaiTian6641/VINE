package dev.vineengine.vine.internal.driver1211.fabric.combat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;

import dev.vineengine.vine.combat.CombatActorRef;
import dev.vineengine.vine.combat.CombatOwnership;
import dev.vineengine.vine.combat.CombatProfile;
import dev.vineengine.vine.combat.CombatResult;
import dev.vineengine.vine.combat.VineCombat;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.internal.driver1211.fabric.entity.VineEntity;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 Fabric cell's combat normalization (sub-10 Stage D): the loader's two native
 * sources map a player's swing onto the engine's SWEEP → RESOLVE → MODIFY → APPLY pipeline
 * exactly once, and the engine's verdict is applied back through vanilla's own damage path
 * so health, HUD and death behave natively.
 *
 * <p><b>VINE-owned weapons — the pre-attack hook.</b> {@link AttackEntityCallback} is
 * Fabric's counterpart of NeoForge's {@code AttackEntityEvent}: it fires before vanilla
 * processes the attack and {@code FAIL} cancels it (on the server from the interaction
 * packet's handler, on the client before the packet is sent). When the held item declares a
 * {@link CombatOwnership#VINE} profile and the target is a live VINE actor, the swing is
 * resolved by {@link VineCombat#strike} and the vanilla attack is cancelled — one pipeline
 * pass per swing, and vanilla never deals its own damage behind the engine's back.
 *
 * <p><b>Partner-owned weapons — the native damage hook.</b> A partner (Better Combat) owns
 * its weapons' swing, range and timing, and the engine installs none of that for them: it
 * cooks the partner's preset and consumes only the result. Fabric has no
 * damage-<em>modification</em> event (the plan's §4 table), so that result arrives at
 * {@link ServerLivingEntityEvents#ALLOW_DAMAGE}, which is where a partner-owned weapon's
 * blow is routed into the pipeline; the partner's raw damage is then cancelled because the
 * engine's APPLY <em>is</em> the hit. Handling such a weapon in the pre-attack hook instead
 * would fight the partner for the same swing, and handling it in both would be two passes.
 *
 * <p><b>Re-entrancy.</b> The damage this class applies re-enters {@code ALLOW_DAMAGE} on the
 * same thread; {@link #APPLYING} turns that re-entry into a plain vanilla pass, so damage
 * the engine has already resolved can never start a second pipeline pass.
 *
 * <p><b>Minimal Footprint (§5.1).</b> Everything this class does not own answers vanilla: a
 * target that is not a VINE actor (dismissed first — one {@code instanceof}, so every
 * vanilla mob pays exactly that), an item with no {@code combat()} profile, a
 * {@code BETTER_COMBAT}-owned item in the pre-attack hook, a VINE-owned item in the native
 * hook, a non-player attacker, and a spectator. Nothing is installed for them and no
 * vanilla cooldown or hurt-immunity bookkeeping is touched.
 */
public final class FabricCombatHooks {

    /**
     * Whether this cell is currently applying damage the engine resolved. While set, both
     * hooks let damage through untouched — that is what stops the engine's own hit from
     * re-entering the pipeline as a second one.
     */
    private static final AtomicBoolean APPLYING = new AtomicBoolean();

    private FabricCombatHooks() {
    }

    /** Binds this cell's two combat sources; called once from driver bootstrap. */
    public static void install() {
        AttackEntityCallback.EVENT.register(FabricCombatHooks::attackEntity);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(FabricCombatHooks::allowDamage);
    }

    /**
     * The pre-attack hook: a player swinging a VINE-owned weapon at a VINE actor is
     * cancelled here and resolved by the engine instead. Every other swing answers
     * {@code PASS}, i.e. vanilla's own attack path, unchanged.
     *
     * <p>The hook fires on both sides. The client half must answer {@code PASS} — that is
     * what sends the interaction packet — so only the server's verdict decides the fight
     * (§4: server-authoritative, no client hit claims).
     */
    private static ActionResult attackEntity(PlayerEntity player, World world, Hand hand, Entity entity,
            EntityHitResult hitResult) {
        if (world.isClient() || player.isSpectator() || APPLYING.get()) {
            return ActionResult.PASS;
        }
        if (!(entity instanceof VineEntity actor)) {
            return ActionResult.PASS;
        }
        VineEntityRef target = actor.vineRef();
        if (target == null) {
            // Tagged by nothing: not an actor yet, so it is not ours to fight.
            return ActionResult.PASS;
        }
        CombatProfile profile = weapon(player.getMainHandStack(), CombatOwnership.VINE);
        if (profile == null) {
            return ActionResult.PASS;
        }
        strike(player, actor, target, profile, player.getDamageSources().playerAttack(player));
        // Cancelled either way: an engine that missed or refused the hit (out of reach, in
        // i-frames) has still decided the swing, and vanilla damage behind its back would
        // be a second, contradictory verdict.
        return ActionResult.FAIL;
    }

    /**
     * The native damage hook: a partner-owned weapon's blow is routed into the pipeline
     * once, and the partner's own damage is cancelled because the engine's APPLY is the
     * hit. Everything else — damage this cell is applying, a non-player source, a target
     * that is not a VINE actor, an item with no partner profile — returns {@code true} and
     * is exactly vanilla.
     */
    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (APPLYING.get()) {
            return true;
        }
        if (!(entity instanceof VineEntity actor)) {
            return true;
        }
        VineEntityRef target = actor.vineRef();
        if (target == null) {
            return true;
        }
        if (!(source.getAttacker() instanceof PlayerEntity attacker) || attacker.isSpectator()) {
            return true;
        }
        CombatProfile profile = weapon(attacker.getMainHandStack(), CombatOwnership.BETTER_COMBAT);
        if (profile == null) {
            return true;
        }
        strike(attacker, actor, target, profile, source);
        return false;
    }

    /**
     * Runs one pipeline pass for {@code profile} and, when it landed, applies its damage
     * through vanilla's own damage path. The application happens under {@link #APPLYING},
     * so the {@code ALLOW_DAMAGE} it fires is passed through untouched and the hit lands
     * once; knockback and hitstop are the engine's own (the pipeline applies hitstop to the
     * target's state, and {@code EntityRuntime} reads it).
     */
    private static void strike(PlayerEntity attacker, LivingEntity target, VineEntityRef targetRef,
            CombatProfile profile, DamageSource source) {
        CombatResult result = VineCombat.strike(CombatActorRef.player(attacker.getUuid()), targetRef,
            profile.attack(), profile.baseDamage(),
            Vec3.of(attacker.getX(), attacker.getY(), attacker.getZ()), engineYaw(attacker));
        if (!result.landed()) {
            return;
        }
        APPLYING.set(true);
        try {
            target.damage(source, (float) result.damage());
        } finally {
            APPLYING.set(false);
        }
    }

    /**
     * The attacker's facing in the convention {@link VineCombat#strike} documents — the one
     * {@code CombatPipelineImpl}'s sweep volume is composed with, so a swing and a target's
     * part boxes can never disagree about which way the fight points.
     *
     * <p><b>Why a conversion is needed here and not in the engine.</b> A Minecraft player's yaw
     * {@code 0} faces world {@code +Z}, while the engine's yaw {@code 0} maps the attacker's
     * model space straight onto world space — and every authored weapon sweep offset and every
     * part in the plan points along model-space {@code −Z} (the ember cleave's box offset is
     * {@code (0, 1.6, −1.5)}, the testbeast's head is at {@code z = −0.9}). So the engine's yaw
     * {@code 0} has the actor facing world {@code −Z}, and the two conventions differ by half a
     * turn: passing the native angle through straight would put the sweep <em>behind</em> the
     * player, and a weapon swung at a creature in front of it would hit nothing — a zero wound
     * on the part the swing was aimed at, with every other line of the run looking correct.
     *
     * <p>The engine cannot do this itself: it is handed degrees by two loaders whose players
     * agree on this convention only by accident of both being Minecraft, and its own pose
     * evaluator's convention is fixed by the authoring format. The conversion belongs where the
     * native angle enters the engine, which is exactly here and in the other cell's equivalent.
     * Parts need no such adjustment — {@code FabricPartHost} hands the actor's own yaw to
     * {@code PoseEvaluator.partBox}, which maps model space with the same rotation the sweep
     * uses, so a cell's parts and its sweeps already agree; only the player angle, which arrives
     * from the cell's native convention rather than the evaluator's, has to be converted.
     */
    private static float engineYaw(LivingEntity attacker) {
        return attacker.getYaw() + 180.0F;
    }

    /**
     * The engine combat profile of the weapon in {@code stack} when it is owned by
     * {@code owner}, else {@code null}. This cell materializes every engine item under its
     * own {@code VineId} as the native registry key
     * ({@code FabricContentMaterializer#registerItems}), so the native id <em>is</em> the
     * engine id, and an item the engine never registered resolves to nothing — which is
     * what keeps every other mod's weapon out of this path.
     */
    private static CombatProfile weapon(ItemStack stack, CombatOwnership owner) {
        if (stack.isEmpty()) {
            return null;
        }
        Identifier nativeId = Registries.ITEM.getId(stack.getItem());
        Optional<Holder<ItemDescriptor>> descriptor = VineRegistries.get(VineContent.ITEM_TYPE,
            VineId.parse(nativeId.toString()));
        CombatProfile profile = descriptor.flatMap(holder -> holder.value().combat()).orElse(null);
        return profile != null && profile.owner() == owner ? profile : null;
    }
}
