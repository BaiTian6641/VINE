package dev.vineengine.vine.combat;

import java.util.Locale;

/**
 * Who owns an item's melee behaviour (sub-10 §2, locked 2026-09-26).
 *
 * <p>The engine cannot share an item with a combat mod: two systems that both claim an
 * item's range, cooldown, dual-wield and animation logic conflict semantically, and no
 * amount of ordering merges them. So ownership is declared per item as data, and the
 * engine acts on the declaration instead of guessing:
 *
 * <ul>
 *   <li>{@link #VINE} — the engine installs sweep shape, cooldown and windows, and deals
 *       the damage through its own pipeline.</li>
 *   <li>{@link #BETTER_COMBAT} — the engine installs <em>none</em> of those; it cooks the
 *       partner's own preset from the same descriptor and consumes only the result, which
 *       is what routes a partner-driven swing through SWEEP→RESOLVE→MODIFY→APPLY exactly
 *       once.</li>
 * </ul>
 *
 * <p>The partner is soft: with Better Combat absent, a {@code BETTER_COMBAT}-owned item is
 * rejected at registration with a readable error rather than silently falling back to
 * engine rules the author did not ask for.
 */
public enum CombatOwnership {

    /** The engine owns this item's melee behaviour. */
    VINE,

    /** Better Combat owns it; the engine cooks its preset and consumes the result. */
    BETTER_COMBAT;

    /** The name a descriptor writes in JSON, lower snake case. */
    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a descriptor's spelling, or throws naming what it accepts. */
    public static CombatOwnership parse(String text) {
        for (CombatOwnership value : values()) {
            if (value.serializedName().equals(text)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown combat owner '" + text + "' — expected one of vine, better_combat");
    }
}
