package dev.vineengine.vine.testmod.design;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.DescriptorClass;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

/**
 * Design-descriptor exemplar (sub-02 Stage C, sub-22 content contract): one
 * DESIGN type {@code vine_test:probe} whose entries live in datapacks at
 * {@code data/<entry-ns>/vine_test/probe/<entry>.json}. The testmod bundles
 * {@code vinetest:base}; the TCK installs an override pack and reboots — the
 * override must win, and a later rewrite must be re-read.
 */
public final class DesignProbeExemplar {

    /** One datapack-decoded entry. */
    public record ProbeEntry(int value, String label) {
    }

    public static final VineId PROBE_REGISTRY = VineId.of("vine_test", "probe");

    /** The bundled entry every cell carries. */
    public static final VineId BASE_ENTRY = VineId.of("vinetest", "base");

    /** DESIGN type: synced to clients, decoded by this codec both from JSON and the wire. */
    public static final DescriptorType<ProbeEntry> PROBE_TYPE = new DescriptorType<>() {
        @Override
        public VineId registryId() {
            return PROBE_REGISTRY;
        }

        @Override
        public Codec<ProbeEntry> codec() {
            return RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("value").forGetter(ProbeEntry::value),
                Codec.STRING.optionalFieldOf("label", "").forGetter(ProbeEntry::label)
            ).apply(instance, ProbeEntry::new));
        }

        @Override
        public DescriptorClass descriptorClass() {
            return DescriptorClass.DESIGN;
        }

        @Override
        public boolean syncToClient() {
            return true;
        }
    };

    private DesignProbeExemplar() {
    }

    /** Mod-init call: define the type (engine boot phase only). */
    public static void registerType() {
        VineRegistries.defineType(PROBE_TYPE);
    }

    /** Command entry: report the entry the datapack loader produced. */
    public static void report() {
        var holder = VineRegistries.get(PROBE_TYPE, BASE_ENTRY);
        if (holder.isEmpty()) {
            System.out.println("vine-testmod: design base present=false");
            return;
        }
        ProbeEntry entry = holder.get().value();
        System.out.println("vine-testmod: design base present=true value=" + entry.value()
            + " label=" + entry.label()
            + " runtimeId=" + holder.get().runtimeId());
    }
}
