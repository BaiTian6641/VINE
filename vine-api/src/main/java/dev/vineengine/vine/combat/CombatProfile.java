package dev.vineengine.vine.combat;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * An item's combat declaration (sub-10 §2): is it a weapon, whose rules does it follow,
 * and how hard does it hit before the attack's own motion value is applied.
 *
 * <p>An item without this profile deals no engine melee damage at all — the engine does
 * not guess that a random item is a weapon, and Minimal Footprint means an undeclared
 * item stays exactly as vanilla as it was.
 *
 * @param attack       the attack this item performs
 * @param owner        who owns its melee behaviour
 * @param baseDamage   the damage before the attack's motion value multiplies it
 * @param partnerPreset for a partner-owned item, the partner's own preset this weapon
 *                      should behave like (for Better Combat: {@code bettercombat:claymore});
 *                      empty for {@link CombatOwnership#VINE}
 */
public record CombatProfile(VineId attack, CombatOwnership owner, double baseDamage,
        java.util.Optional<VineId> partnerPreset) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<CombatProfile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("attack").forGetter(CombatProfile::attack),
        Codec.STRING.optionalFieldOf("owner", "vine").forGetter(profile -> profile.owner().serializedName()),
        Codec.DOUBLE.optionalFieldOf("baseDamage", 1.0D).forGetter(CombatProfile::baseDamage),
        VineId.CODEC.optionalFieldOf("partnerPreset").forGetter(CombatProfile::partnerPreset)
    ).apply(instance, (attack, owner, baseDamage, preset) -> new CombatProfile(attack, CombatOwnership.parse(owner),
        baseDamage, preset)));

    public CombatProfile {
        Objects.requireNonNull(attack, "attack");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(partnerPreset, "partnerPreset");
        if (!(baseDamage > 0.0D)) {
            throw new IllegalArgumentException("CombatProfile for attack " + attack
                + ": baseDamage must be positive, got " + baseDamage);
        }
        if (owner == CombatOwnership.VINE && partnerPreset.isPresent()) {
            throw new IllegalArgumentException("CombatProfile for attack " + attack + ": partnerPreset is only"
                + " meaningful for a partner-owned item, and this one is VINE-owned");
        }
        if (owner != CombatOwnership.VINE && partnerPreset.isEmpty()) {
            throw new IllegalArgumentException("CombatProfile for attack " + attack + ": a " + owner
                + "-owned item must name the partner preset it follows — without it the engine would have to"
                + " guess the partner's rules, which is exactly what ownership exists to avoid");
        }
    }

    /** The profile an author writes when a plain weapon follows the engine's own rules. */
    public static CombatProfile vine(VineId attack, double baseDamage) {
        return new CombatProfile(attack, CombatOwnership.VINE, baseDamage, java.util.Optional.empty());
    }

    /** The profile a partner-owned weapon declares: the partner's preset plus our attack. */
    public static CombatProfile partner(VineId attack, CombatOwnership owner, double baseDamage,
            VineId partnerPreset) {
        if (owner == CombatOwnership.VINE) {
            throw new IllegalArgumentException("partner(...) is for partner-owned items; use vine(...) for a"
                + " VINE-owned weapon");
        }
        return new CombatProfile(attack, owner, baseDamage, java.util.Optional.of(partnerPreset));
    }
}
