package dev.vineengine.vine.internal.driver1211.neoforge.combat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import dev.vineengine.vine.combat.CombatActorRef;
import dev.vineengine.vine.combat.CombatOwnership;
import dev.vineengine.vine.combat.CombatProfile;
import dev.vineengine.vine.combat.CombatResult;
import dev.vineengine.vine.combat.VineCombat;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.internal.driver1211.neoforge.entity.VineEntity;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 NeoForge cell's melee normalization (sub-10 Stage D): NeoForge's two damage
 * paths translated into the engine's SWEEP → RESOLVE → MODIFY → APPLY pipeline — once per
 * hit, never twice — and the pipeline's answer applied back through the native damage path
 * so health, the damage flash and death react exactly as they do for any other hit.
 *
 * <p><b>Which path an item takes is the item's own declaration.</b> A {@code VINE}-owned
 * weapon is intercepted before vanilla attacks ({@link AttackEntityEvent}, canceled): the
 * engine owns that item's sweep and timing, so vanilla's attack must not also run. A
 * {@code BETTER_COMBAT}-owned weapon is <em>not</em> intercepted there — the partner owns
 * that item's swing, and the engine installs none of its own rules for it — so its hit is
 * routed once from the native damage event instead ({@link LivingIncomingDamageEvent}),
 * where the partner's own computation has already produced the hit.
 *
 * <p><b>The reentrancy guard.</b> Applying the engine's answer means calling {@code hurt},
 * which fires the native damage event again. The guard is set for exactly that call, so the
 * damage the engine just decided is never re-submitted to the pipeline as a second hit — in
 * either direction (a {@code BETTER_COMBAT} item's swing, or an engine-owned one's).
 *
 * <p><b>Minimal Footprint (§5.1).</b> Both listeners answer "not mine" and return without
 * touching the event for: a target that is not a VINE actor, an attacker that is not a
 * player, and an item with no {@code combat()} profile (every vanilla weapon, every tool).
 * Nothing is registered per item or per entity, so unregistered content is behaviorally
 * identical to vanilla.
 *
 * <p><b>Vanilla's own invulnerability timer is not consulted.</b> For an opted-in actor the
 * engine's i-frames are the authority (sub-10 §2), so the timer is cleared before the
 * engine's damage is applied; otherwise vanilla's post-hit bookkeeping would silently halve
 * a number the engine already decided lands. Nothing else about vanilla's damage sequence is
 * bypassed: the damage travels {@code hurt(...)} and every other listener sees it.
 */
public final class NeoForgeMeleeHooks {

    /** Set while this class is applying the engine's damage — the native path must not re-enter. */
    private static final AtomicBoolean ENGINE_DAMAGE = new AtomicBoolean();

    private static volatile boolean installed;

    private NeoForgeMeleeHooks() {
    }

    /**
     * Installs both hooks; called once from the cell's driver bootstrap, before any world can
     * be attacked in. Idempotent, so a second call cannot double-register a listener.
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(AttackEntityEvent.class, NeoForgeMeleeHooks::preAttack);
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, NeoForgeMeleeHooks::incomingDamage);
    }

    /** Whether this class is currently applying engine damage through the native path. */
    public static boolean engineDamageApplying() {
        return ENGINE_DAMAGE.get();
    }

    /**
     * Pre-attack interception for a {@code VINE}-owned weapon: cancel vanilla's attack, run
     * the engine's strike, and apply its damage natively.
     */
    private static void preAttack(AttackEntityEvent event) {
        Player attacker = event.getEntity();
        VineEntity body = actorBody(event.getTarget());
        if (body == null) {
            // Not a VINE actor: vanilla's attack, untouched (Minimal Footprint).
            return;
        }
        Optional<CombatProfile> profile = heldProfile(attacker);
        if (profile.isEmpty() || profile.get().owner() != CombatOwnership.VINE) {
            return;
        }
        VineEntityRef target = body.vineActorRef();
        if (target == null) {
            // A VINE body the engine's spawn path never tagged (a client-side copy): no actor
            // to strike, and guessing one would strike an identity the engine does not have.
            return;
        }
        // The engine owns this item's melee behaviour: vanilla must not damage the target a
        // second time, and must not advance its own attack for a hit it did not decide.
        event.setCanceled(true);
        CombatResult result = strike(attacker, target, profile.get());
        if (result.landed()) {
            applyDamage(body, attacker, result.damage());
        }
    }

    /**
     * Native-damage routing for a {@code BETTER_COMBAT}-owned weapon: the partner's swing
     * already happened, so the hit enters the pipeline here — once — and the engine's answer
     * replaces the damage vanilla was about to apply.
     */
    private static void incomingDamage(LivingIncomingDamageEvent event) {
        if (ENGINE_DAMAGE.get()) {
            // The engine's own application: this is the hit the pipeline just decided.
            return;
        }
        if (!(event.getSource().getEntity() instanceof Player attacker)) {
            return;
        }
        VineEntity body = actorBody(event.getEntity());
        if (body == null) {
            return;
        }
        Optional<CombatProfile> profile = heldProfile(attacker);
        if (profile.isEmpty() || profile.get().owner() != CombatOwnership.BETTER_COMBAT) {
            // A VINE-owned weapon is handled on the pre-attack path, and anything without a
            // combat profile is not the engine's business.
            return;
        }
        VineEntityRef target = body.vineActorRef();
        if (target == null) {
            return;
        }
        CombatResult result = strike(attacker, target, profile.get());
        if (!result.landed()) {
            // The engine refused the hit (out of the sweep, inside its i-frames, vetoed by a
            // modifier): nothing may be applied, so the partner's swing is declined whole.
            event.setCanceled(true);
            return;
        }
        // The pipeline has already resolved the part and rounded the damage; what vanilla
        // applies from here is that number, through its own reduction sequence.
        event.setAmount((float) result.damage());
    }

    /**
     * Runs one pipeline pass for {@code attacker} against {@code target}, with the attacker's
     * live position and facing — the engine keeps no copy of a cell's transform.
     */
    private static CombatResult strike(Player attacker, VineEntityRef target, CombatProfile profile) {
        return VineCombat.strike(CombatActorRef.player(attacker.getUUID()), target, profile.attack(),
            profile.baseDamage(), Vec3.of(attacker.getX(), attacker.getY(), attacker.getZ()),
            // The engine reads yaw in the pose evaluator's convention: yaw 0 points the actor's
            // model-space forward (-Z) at world -Z, so a sweep's -Z offset lies in front of it.
            // A Minecraft player's yaw 0 faces world +Z, the opposite way, so the player's own
            // yaw has to be turned half a revolution before the engine can aim its sweep;
            // passing it straight through points the weapon behind the player and the swing
            // misses whatever is in front of it.
            attacker.getYRot() + 180.0F);
    }

    /**
     * Applies engine-decided damage through vanilla's own {@code hurt} path: one call, so
     * everything downstream of a hit (health, absorption, the damage flash, death, drops)
     * behaves as it does for damage from any other source.
     */
    private static void applyDamage(VineEntity body, Player attacker, double damage) {
        if (!(damage > 0.0D)) {
            return;
        }
        // The engine's i-frames decide whether an opted-in actor can be hit (sub-10 §2); the
        // native timer would otherwise halve a hit the engine already resolved.
        body.invulnerableTime = 0;
        ENGINE_DAMAGE.set(true);
        try {
            body.hurt(attacker.damageSources().playerAttack(attacker), (float) damage);
        } finally {
            ENGINE_DAMAGE.set(false);
        }
    }

    /**
     * The VINE body {@code entity} is, or the one its native part belongs to — {@code null}
     * for every other entity. A multipart actor's parts are entities of their own, and a
     * swing that hit a part is a swing at the body: which part it lands on is the engine's
     * own geometric answer, never the cell's.
     */
    private static VineEntity actorBody(Entity entity) {
        if (entity instanceof VineEntity body) {
            return body;
        }
        if (entity instanceof PartEntity<?> part && part.getParent() instanceof VineEntity body) {
            return body;
        }
        return null;
    }

    /**
     * The combat profile of the item {@code player} is swinging, or empty for an item that
     * declares none. An item is resolved by the registry key it was materialized under, which
     * is its descriptor id — so a vanilla or foreign item simply has no engine entry, and
     * "not a weapon" is answered without a per-item table.
     */
    private static Optional<CombatProfile> heldProfile(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(held.getItem());
        if (key == null) {
            return Optional.empty();
        }
        return VineRegistries.<ItemDescriptor>get(VineContent.ITEM_TYPE,
                VineId.of(key.getNamespace(), key.getPath()))
            .flatMap(holder -> holder.value().combat());
    }
}
