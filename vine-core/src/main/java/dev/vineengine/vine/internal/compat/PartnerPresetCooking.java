package dev.vineengine.vine.internal.compat;

import java.util.Optional;

import dev.vineengine.vine.combat.AttackDescriptor;
import dev.vineengine.vine.combat.CombatOwnership;
import dev.vineengine.vine.combat.CombatProfile;
import dev.vineengine.vine.registry.VineId;

/**
 * Cooks a combat partner's own data file from one of our descriptors (sub-10 §2, the
 * combat-ownership rule).
 *
 * <p><b>Why this file exists at all.</b> Better Combat owns the weapons assigned to it: it
 * computes their range, their combos and their animation, and its own documentation says
 * that a mod which also implements attack range, attack timing or cooldown logic, dual
 * wielding or player-animation changes on the same item is <em>semantically incompatible</em>
 * with it. Two systems cannot share one item — but a descriptor can be <em>translated</em>,
 * and that translation is data. So the engine emits the partner's preset (for Better Combat:
 * {@code data/<ns>/weapon_attributes/<item>.json}, verified against its integration guide),
 * installs none of its own sweep or cooldown logic for that item, and consumes only what
 * the partner's swing produces.
 *
 * <p><b>The bytes are the engine's.</b> The file content is computed here, not in a cell:
 * both cells write the same string, so "the cooked presets are byte-identical between
 * cells" is a property of the design rather than a test that has to keep catching up.
 *
 * <p><b>No dependency.</b> This class names the partner only in a comment and in the string
 * it emits — no partner class appears on any compile classpath, which is what lets a cell
 * ship the integration without the partner installed.
 */
public final class PartnerPresetCooking {

    /** Where Better Combat reads weapon attributes from, verified 2026-09-26. */
    public static final String BETTER_COMBAT_DIRECTORY = "weapon_attributes";

    private PartnerPresetCooking() {
    }

    /**
     * The partner file an item needs, or empty when the item is not partner-owned (or is
     * not a weapon at all) — in which case the engine cooks nothing and the partner never
     * sees the item.
     *
     * @return the relative resource path and the exact file content
     */
    public static Optional<CookedFile> cook(VineId itemId, CombatProfile profile, AttackDescriptor attack) {
        if (profile.owner() != CombatOwnership.BETTER_COMBAT) {
            return Optional.empty();
        }
        VineId preset = profile.partnerPreset().orElseThrow(() -> new IllegalStateException(
            "a " + profile.owner() + "-owned item without a partner preset reached cooking — the profile's own"
                + " constructor refuses this, so a profile was built by hand somewhere"));
        String path = "data/" + itemId.namespace() + "/" + BETTER_COMBAT_DIRECTORY + "/" + itemId.path() + ".json";
        // Better Combat merges parent -> child: naming the preset keeps its combos and
        // animations, and the attack range is ours (the sweep the engine would have used
        // had the item been VINE-owned), so the fight the player sees matches the fight the
        // descriptor describes.
        String range = trim(attack.sweep().forward() + attack.sweep().halfExtents().z() * 2.0D);
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"parent\": \"").append(preset).append("\",\n");
        out.append("  \"attributes\": {\n");
        out.append("    \"attack_range\": ").append(range).append('\n');
        out.append("  }\n");
        out.append("}\n");
        return Optional.of(new CookedFile(path, out.toString()));
    }

    /** One partner file: where it goes, and exactly what belongs in it. */
    public record CookedFile(String path, String content) {

        public CookedFile {
            java.util.Objects.requireNonNull(path, "path");
            java.util.Objects.requireNonNull(content, "content");
        }
    }

    /** The actor's sweep reach as a plain decimal — no trailing zeros, no scientific form. */
    private static String trim(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}
