package dev.vineengine.vine.testmod.content;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.BlockTuning;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.content.ItemTuning;
import dev.vineengine.vine.content.ModelHint;
import dev.vineengine.vine.content.Property;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

import java.util.List;

/**
 * Block/item registration for the sub-07 Stage A exemplars: exactly one block
 * and one item, authored as engine descriptors and materialized by whichever
 * cell is running — the same sources on every driver (Prime Invariant).
 *
 * <p>Ids are pinned by the TCK scenarios {@code vine_test:block_place_break}
 * and {@code vine_test:save_reload} ({@code PlaceBlock vine_test:testblock});
 * the item id follows the same convention. Both land in the engine store here
 * (Java path) and in the vanilla BLOCK/ITEM registries through the driver's
 * materializer — the testmod never touches a loader or game class.
 *
 * <p>Stage A shape: id + minimal tuning + placeholder model hint only; the
 * descriptor types are engine-owned (defined by vine-core at boot), so this
 * code only registers — never defines.
 */
public final class TestContent {

    /** The single block exemplar; pinned by the TCK block scenarios. */
    public static final VineId TESTBLOCK_ID = VineId.of("vine_test", "testblock");

    /** The single item exemplar. */
    public static final VineId TESTITEM_ID = VineId.of("vine_test", "testitem");

    /**
     * The Stage B state exemplar: one block with a three-property flattened state
     * model, so the state table, the engine state API and the cells' native state
     * definitions have something real to carry. 2 × 4 × 3 = 24 states — well under
     * the 512-state budget, and every value list is declared in the order the
     * engine then uses as the state order.
     */
    public static final VineId STATEBLOCK_ID = VineId.of("vine_test", "stateblock");

    /** The state exemplar's properties, in flattening order — the scenarios pin this order. */
    public static final List<Property<?>> STATEBLOCK_PROPERTIES = List.of(
        // Default true, matching vanilla's own lit-axis convention (a campfire's
        // default state is lit).
        Property.bool("lit"),
        Property.intRange("level", 0, 3),
        Property.ofEnum("mode", TestStateMode.class));

    /**
     * The rejected descriptor's id: never registered, only attempted — the proof
     * that an over-budget state model is refused with a diagnostic naming the
     * product terms (sub-07 Stage B), which the scenario asserts from the boot log.
     */
    public static final VineId OVERBUDGET_ID = VineId.of("vine_test", "overbudget");

    /** The Java-registered action/attack (sub-10 Stage A): the ember cleave. */
    public static VineId emberCleaveId() {
        return VineId.of("vine_test", "ember_cleave");
    }

    /** The Java-registered weapon item: the ember blade, owned by the engine. */
    public static final VineId EMBER_BLADE_ID = VineId.of("vine_test", "ember_blade");

    /** A partner-owned weapon: the engine cooks Better Combat's preset for it instead. */
    public static final VineId PARTNER_BLADE_ID = VineId.of("vine_test", "partner_blade");

    /** The clip whose markers drive the cleave's windows (sub-09): clips are named, not identified. */
    private static final String STRIKE_CLIP = "animation.vine_test.wyvern_stub.strike";

    private TestContent() {
    }

    /**
     * Registers both exemplars and arms the post-freeze probe: after
     * REGISTRIES_FROZEN both holders must resolve through the engine tokens,
     * proving the structural entries survived the freeze. The probe prints to
     * stdout so headless boot logs carry it regardless of the cell's logging
     * setup (same contract as the marker exemplar).
     */
    public static void register(VineEngine engine) {
        // The block carries engine storage (sub-07 Stage C / sub-22 Stage B): its
        // block entity is the same attach point every other holder uses, so a
        // placed block persists a tree across save/reload.
        VineRegistries.register(VineContent.BLOCK_TYPE, TESTBLOCK_ID,
            new BlockDescriptor(TESTBLOCK_ID, BlockTuning.STONE_LIKE, ModelHint.cubeAll(),
                dev.vineengine.vine.content.BlockEntityDescriptor.storage(
                    dev.vineengine.vine.testmod.data.VoxelExemplar.SCHEMA_ID)));
        VineRegistries.register(VineContent.ITEM_TYPE, TESTITEM_ID,
            new ItemDescriptor(TESTITEM_ID, new ItemTuning(64), ModelHint.generated()));
        // Combat descriptors, Java path (sub-10 Stage A): a beast's own action and attack,
        // plus a weapon item that declares the engine as its combat owner. The JSON path
        // registers vine_test:wide_slash/ember_cleave from data/vine_test/vine/{action,attack},
        // and the boot probe below prints both, which is this stage's acceptance.
        VineRegistries.register(VineContent.ACTION_TYPE, emberCleaveId(),
            new dev.vineengine.vine.combat.ActionDescriptor(emberCleaveId(), STRIKE_CLIP, 4, 8,
                java.util.Set.of(), 12));
        VineRegistries.register(VineContent.ATTACK_TYPE, emberCleaveId(),
            new dev.vineengine.vine.combat.AttackDescriptor(emberCleaveId(), 1.6D,
                dev.vineengine.vine.registry.VineId.parse("minecraft:player_attack"),
                dev.vineengine.vine.registry.VineId.parse("vine_test:ember"),
                // A swing that reaches where a standing creature's head is: the box is a
                // chest-height volume in front of the attacker, not one centred at the feet.
                new dev.vineengine.vine.combat.SweepShape.Box(
                    dev.vineengine.vine.world.Vec3.of(1.2D, 1.6D, 1.5D),
                    dev.vineengine.vine.world.Vec3.of(0.0D, 1.6D, -1.5D)),
                dev.vineengine.vine.world.Vec3.of(2.0D, 0.0D, 0.0D), 3));
        VineRegistries.register(VineContent.ITEM_TYPE, EMBER_BLADE_ID,
            new ItemDescriptor(EMBER_BLADE_ID, new ItemTuning(1), ModelHint.generated(),
                java.util.Optional.of(dev.vineengine.vine.combat.CombatProfile.vine(emberCleaveId(), 40.0D))));
        // A partner-owned weapon (sub-10 §2): the engine installs none of its own sweep or
        // cooldown logic for it and instead cooks Better Combat's own preset from this same
        // descriptor. The bytes are produced by the engine, so both cells write the same file.
        VineRegistries.register(VineContent.ITEM_TYPE, PARTNER_BLADE_ID,
            new ItemDescriptor(PARTNER_BLADE_ID, new ItemTuning(1), ModelHint.generated(),
                java.util.Optional.of(dev.vineengine.vine.combat.CombatProfile.partner(emberCleaveId(),
                    dev.vineengine.vine.combat.CombatOwnership.BETTER_COMBAT, 40.0D,
                    dev.vineengine.vine.registry.VineId.parse("bettercombat:claymore")))));
        VineRegistries.register(VineContent.BLOCK_TYPE, STATEBLOCK_ID,
            new BlockDescriptor(STATEBLOCK_ID, STATEBLOCK_PROPERTIES, BlockTuning.STONE_LIKE,
                ModelHint.cubeAll()));
        attemptOverBudgetRegistration();
        engine.onPhase(EnginePhase.REGISTRIES_FROZEN, change -> {
            var block = VineRegistries.get(VineContent.BLOCK_TYPE, TESTBLOCK_ID)
                .orElseThrow(() -> new IllegalStateException(
                    "testmod block vanished at freeze — structural registration broken"));
            var item = VineRegistries.get(VineContent.ITEM_TYPE, TESTITEM_ID)
                .orElseThrow(() -> new IllegalStateException(
                    "testmod item vanished at freeze — structural registration broken"));
            var attack = VineRegistries.get(VineContent.ATTACK_TYPE, emberCleaveId())
                .orElseThrow(() -> new IllegalStateException(
                    "testmod attack vanished at freeze — sub-10 Stage A registration broken"));
            var action = VineRegistries.get(VineContent.ACTION_TYPE, emberCleaveId())
                .orElseThrow(() -> new IllegalStateException(
                    "testmod action vanished at freeze — sub-10 Stage A registration broken"));
            System.out.println("vine-testmod: content holders resolved post-freeze block="
                + block.id() + " item=" + item.id()
                + " action=" + action.id() + " attack=" + attack.id()
                + " weapon=" + EMBER_BLADE_ID);
        });
    }

    /**
     * Attempts one deliberately over-budget descriptor — three 16-valued axes make
     * 4096 states — and reports the engine's refusal. The attempt happens here,
     * during registration, because an over-budget model is rejected at
     * registration time (sub-07 Stage B); after the freeze the registry would
     * refuse on the freeze instead, which would prove nothing. Nothing is
     * registered by this method: the engine throws before the entry exists.
     */
    private static void attemptOverBudgetRegistration() {
        List<Property<?>> axes = List.of(
            Property.intRange("a", 0, 15),
            Property.intRange("b", 0, 15),
            Property.intRange("c", 0, 15));
        try {
            VineRegistries.register(VineContent.BLOCK_TYPE, OVERBUDGET_ID,
                new BlockDescriptor(OVERBUDGET_ID, axes, BlockTuning.STONE_LIKE, ModelHint.cubeAll()));
            System.out.println("vine-testmod: over-budget descriptor was ACCEPTED — budget is not enforced");
        } catch (IllegalArgumentException rejected) {
            System.out.println("vine-testmod: over-budget rejected: " + rejected.getMessage());
        }
    }
}
