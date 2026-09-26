package dev.vineengine.vine.internal.driver1211.neoforge.datagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.vineengine.vine.combat.AttackDescriptor;
import dev.vineengine.vine.combat.CombatOwnership;
import dev.vineengine.vine.combat.CombatProfile;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.internal.compat.PartnerPresetCooking;
import dev.vineengine.vine.internal.driver1211.common.DriverBoot;
import dev.vineengine.vine.internal.driver1211.common.datagen.ContentCooker;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.spi.VineDriver;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineRegistries;

/**
 * The 1.21.1 NeoForge cell's content cooking entry (sub-02 Stage E + sub-10 §2): the
 * assets the driver already cooked, then the combat partner's own data files for every
 * registered partner-owned weapon.
 *
 * <p><b>Why the cooking run boots the engine.</b> A partner preset is cooked from a
 * descriptor — the item's own, and the attack it names — so the cooking pass must ask the
 * engine what a consumer registered rather than re-read the consumer's sources with a second
 * parser (the two would disagree the day either changes). The engine therefore boots here
 * with {@link NeoForgeDatagenDriver} as its driver: no game, no loader lifecycle, just the
 * descriptor store and the structural view the game's materialization reads.
 *
 * <p><b>The bytes are the engine's.</b> {@link PartnerPresetCooking#cook} computes the file
 * path and its exact content in vine-core, so this cell only writes files: both cells emit
 * the same bytes for the same descriptor, which is the property the cross-cell check rests
 * on. This entry adds nothing to the content and makes no formatting decision.
 *
 * <p><b>Determinism.</b> Items are visited in the engine's own registration order (the
 * store's sorted {@code VineId} order), one file per item at the path the engine computed,
 * written without any transformation — the same inputs cook the same tree on every run.
 *
 * <p><b>Usage:</b> {@code --cell <id> --assets <consumer assets dir> --out <output root>} —
 * the vocabulary the asset cooking already takes, so one task runs both passes into one
 * output root, which is what lets the existing golden check see the presets at all.
 */
public final class NeoForge1211Datagen {

    private NeoForge1211Datagen() {
    }

    public static void main(String[] args) throws IOException {
        String cell = null;
        Path assets = null;
        Path out = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--cell" -> cell = args[++i];
                case "--assets" -> assets = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (cell == null || assets == null || out == null) {
            System.err.println("usage: NeoForge1211Datagen --cell <id> --assets <dir> --out <dir>");
            System.exit(2);
        }

        // The asset half is the existing pipeline, unchanged; this entry adds the presets
        // beside it, in the same output root.
        ContentCooker.main(new String[] {"--cell", cell, "--assets", assets.toString(), "--out", out.toString()});
        System.out.println("[datagen] " + cell + ": partner presets "
            + cookPartnerPresets(cell, out) + " file(s)");
    }

    /**
     * Cooks one partner file per registered item with a partner-owned combat profile, into
     * {@code out}, and returns how many files were written.
     */
    private static int cookPartnerPresets(String cell, Path out) throws IOException {
        DriverBoot.boot();
        VineDriver.DriverContext ctx = NeoForgeDatagenDriver.context();
        if (ctx == null) {
            throw new IllegalStateException("the cooking run bound no datagen driver — the content-cooking"
                + " classpath must carry the datagen driver service and no loader driver service");
        }
        // Snapshot: reading it also loads the consumer's structural JSON descriptors (the
        // attack a JSON-authored item names lives there, not in Java).
        List<? extends Holder<?>> items = itemsOf(ctx.structuralRegistries());
        int cooked = 0;
        for (Holder<?> holder : items) {
            if (!(holder.value() instanceof ItemDescriptor descriptor)) {
                continue;
            }
            Optional<CombatProfile> combat = descriptor.combat();
            if (combat.isEmpty() || combat.get().owner() != CombatOwnership.BETTER_COMBAT) {
                // Not a weapon, or a VINE-owned one: the engine installs its own rules and
                // the partner sees no file (sub-10 §2 — the two are never mixed).
                continue;
            }
            CombatProfile profile = combat.get();
            AttackDescriptor attack = VineRegistries.<AttackDescriptor>get(VineContent.ATTACK_TYPE,
                    profile.attack())
                .map(registered -> registered.value())
                .orElseThrow(() -> new IllegalStateException("item " + descriptor.id()
                    + " declares partner-owned attack " + profile.attack() + ", which is not registered —"
                    + " the attack descriptor must be registered with the item that names it"));
            PartnerPresetCooking.CookedFile file = PartnerPresetCooking.cook(descriptor.id(), profile, attack)
                .orElseThrow(() -> new IllegalStateException("item " + descriptor.id() + " declares a "
                    + profile.owner() + " profile but cooked no partner preset — the profile and the cooker"
                    + " disagree about what a partner-owned weapon is"));
            Path target = out.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.write(target, file.content().getBytes(StandardCharsets.UTF_8));
            System.out.println("[datagen] " + cell + ": cooked partner preset " + file.path()
                + " for " + descriptor.id());
            cooked++;
        }
        return cooked;
    }

    /** The registered {@code vine:item} entries, or none when the consumer registered no items. */
    private static List<? extends Holder<?>> itemsOf(StructuralRegistryView view) {
        for (StructuralRegistryView.StructuralType type : view.types()) {
            if (type.type() == VineContent.ITEM_TYPE) {
                return type.entries();
            }
        }
        return List.of();
    }
}
