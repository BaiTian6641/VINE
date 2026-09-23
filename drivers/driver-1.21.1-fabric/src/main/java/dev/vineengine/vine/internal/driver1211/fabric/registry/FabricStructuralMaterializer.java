package dev.vineengine.vine.internal.driver1211.fabric.registry;

import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.spi.RegistryDriver;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;

/**
 * Fabric 1.21.1 structural materialization (sub-02 Stage B). Loader difference
 * absorbed (vs. NF's phased {@code RegisterEvent}): Fabric's registration moment
 * is plain mod init — one {@code FabricRegistryBuilder} simple registry per
 * structural descriptor type, entries via {@code Registry.register}. Invoked
 * from {@code onInitialize} after consumer initializers have run, so the store
 * snapshot is complete for this session and well before vanilla's
 * server-bootstrap freeze.
 */
public final class FabricStructuralMaterializer implements RegistryDriver {

    private static final Logger LOG = LoggerFactory.getLogger(FabricStructuralMaterializer.class);

    @Override
    public void materializeStructural(StructuralRegistryView structural) {
        int materialized = 0;
        for (StructuralRegistryView.StructuralType type : structural.types()) {
            RegistryKey<Registry<Object>> key = RegistryKey.ofRegistry(identifier(type.type().registryId()));
            Registry<Object> registry = FabricRegistryBuilder.createSimple(key).buildAndRegister();
            for (Holder<?> holder : type.entries()) {
                Registry.register(registry, identifier(holder.id()), holder.value());
                RegistryHookTap.dispatch(type.type().registryId().toString(), holder.id().toString());
                materialized++;
            }
        }
        if (materialized > 0) {
            LOG.info("vine: materialized {} structural entries", materialized);
        }
    }

    private static Identifier identifier(VineId id) {
        return Identifier.of(id.namespace(), id.path());
    }
}
