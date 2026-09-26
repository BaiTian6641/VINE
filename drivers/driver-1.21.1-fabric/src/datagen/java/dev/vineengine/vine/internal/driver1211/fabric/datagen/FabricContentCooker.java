package dev.vineengine.vine.internal.driver1211.fabric.datagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * This cell's {@code datagenContent} entry point (sub-02 Stage E cooking + sub-10 Stage D
 * partner presets).
 *
 * <p><b>Why this class exists next to the shared cooker.</b> The loader-neutral
 * {@link ContentCooker} cooks a consumer's source assets and needs nothing but the
 * filesystem. A partner preset (Better Combat reads
 * {@code data/<ns>/weapon_attributes/<item>.json}) is cooked from <em>registered</em>
 * descriptors — the item's {@code combat()} declaration and the attack it names — so this
 * pass has to ask the engine, and asking the engine means booting it the way this cell's
 * entrypoint does: {@link DriverBoot#boot} runs the consumer initializers, and the datagen
 * driver's structural snapshot ({@link VineDriver.DriverContext#structuralRegistries}) is
 * then the complete list of what was registered (Java and JSON both). The bytes themselves
 * are never cooked here — {@link PartnerPresetCooking} owns them, so both cells write the
 * same file.
 *
 * <p><b>Where it lives.</b> This class is compiled into the cell's {@code datagen} source
 * set, not its mod jar, and it binds {@link FabricDatagenDriver} — never
 * {@code Fabric1211Driver}, whose {@code bootstrap} needs a live loader. The cooking run has
 * no game and no loader, so it must not touch either; the inert driver supplies the one
 * thing this pass needs from a driver, the structural view.
 *
 * <p><b>Deterministic.</b> Entries are visited in the structural view's own order (sorted
 * by {@code VineId}), items without a partner-owned profile are skipped, and each file is
 * written verbatim. The same descriptors therefore cook the same tree on every run and on
 * every cell — the property {@code :vine-tck:verifyDatagen} byte-compares against its
 * goldens.
 */
public final class FabricContentCooker {

    private static final String DEFAULT_CELL = "1.21.1-fabric";

    private FabricContentCooker() {
    }

    public static void main(String[] args) throws IOException {
        // Source assets first: that pass is the shared one both cells run, and it stays
        // exactly as it was.
        ContentCooker.main(args);
        String cell = argument(args, "--cell", DEFAULT_CELL);
        Path out = Path.of(argument(args, "--out", null));
        System.out.println("[datagen] " + cell + ": partner presets "
            + cookPartnerPresets(cell, out) + " file(s)");
    }

    /**
     * Writes the partner file of every registered partner-owned item, under the same output
     * root the asset cooking uses (so one tree is compared against one golden tree).
     *
     * @return how many files were written
     */
    private static int cookPartnerPresets(String cell, Path out) throws IOException {
        // The engine this cell's entrypoint boots: consumers register their content here,
        // and the datagen driver's handle is then the registry as the running game sees it.
        DriverBoot.boot();
        VineDriver.DriverContext ctx = FabricDatagenDriver.context();
        if (ctx == null) {
            throw new IllegalStateException("the cooking run bound no datagen driver — the content-cooking"
                + " classpath must carry the datagen driver service and no loader driver service");
        }
        StructuralRegistryView items = ctx.structuralRegistries();
        int cooked = 0;
        for (StructuralRegistryView.StructuralType type : items.types()) {
            if (type.type() != VineContent.ITEM_TYPE) {
                continue;
            }
            for (Holder<?> holder : type.entries()) {
                ItemDescriptor item = (ItemDescriptor) holder.value();
                CombatProfile profile = item.combat()
                    .filter(declared -> declared.owner() != CombatOwnership.VINE)
                    .orElse(null);
                if (profile == null) {
                    continue;
                }
                AttackDescriptor attack = attackOf(item, profile);
                PartnerPresetCooking.CookedFile file = PartnerPresetCooking.cook(item.id(), profile, attack)
                    .orElseThrow(() -> new IllegalStateException("item " + item.id() + " declares a "
                        + profile.owner() + "-owned profile but cooked no partner file — the ownership rule"
                        + " and the cooker disagree about what is a partner weapon"));
                write(out.resolve(file.path()), file.content());
                System.out.println("[datagen] " + cell + ": cooked partner preset " + file.path()
                    + " for " + item.id());
                cooked++;
            }
        }
        return cooked;
    }

    /**
     * The attack a partner weapon's preset is cooked against — the engine's descriptor, not
     * a copy: the preset's attack range <em>is</em> the attack's sweep, and a partner weapon
     * whose attack is not registered cannot be cooked into a fight the engine would run.
     */
    private static AttackDescriptor attackOf(ItemDescriptor item, CombatProfile profile) {
        return VineRegistries.<AttackDescriptor>get(VineContent.ATTACK_TYPE, profile.attack())
            .map(holder -> holder.value())
            .orElseThrow(() -> new IllegalStateException("item " + item.id() + " declares the "
                + profile.owner() + "-owned attack " + profile.attack() + ", which is not registered —"
                + " there is no sweep to cook its preset from"));
    }

    private static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** One {@code --name value} argument; {@code fallback} when absent. */
    private static String argument(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        if (fallback == null) {
            // Unreachable: ContentCooker refuses a command line without --out before this.
            throw new IllegalArgumentException("missing required argument " + name);
        }
        return fallback;
    }
}
