package dev.vineengine.vine.internal.driver1211.fabric.command;

import java.util.UUID;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import dev.vineengine.vine.internal.command.CommandBridge;
import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;

/**
 * The Fabric {@link EngineCommands.NativeFactory} (sub-06 Stage A): the four
 * native touch points of the command walker over Yarn
 * {@link ServerCommandSource} — Brigadier builders, the op-level gate, argument
 * reads, and the source adapter fed to {@code CommandBridge}.
 */
public final class FabricCommandFactory implements EngineCommands.NativeFactory<ServerCommandSource> {

    private static final FabricCommandFactory INSTANCE = new FabricCommandFactory();

    private FabricCommandFactory() {
    }

    public static FabricCommandFactory instance() {
        return INSTANCE;
    }

    @Override
    public LiteralArgumentBuilder<ServerCommandSource> literal(String name) {
        return CommandManager.literal(name);
    }

    @Override
    public RequiredArgumentBuilder<ServerCommandSource, String> stringArgument(String name) {
        return CommandManager.argument(name, StringArgumentType.string());
    }

    @Override
    public Predicate<ServerCommandSource> hasPermission(int level) {
        return source -> source.hasPermissionLevel(level);
    }

    @Override
    public String getStringArg(CommandContext<ServerCommandSource> context, String name) {
        return StringArgumentType.getString(context, name);
    }

    @Override
    public CommandBridge.NativeSource adapt(ServerCommandSource source) {
        return new CommandBridge.NativeSource() {
            @Override
            public String name() {
                return source.getName();
            }

            @Override
            public boolean isPlayer() {
                return source.getEntity() instanceof ServerPlayerEntity;
            }

            @Override
            public UUID playerUniqueId() {
                return source.getEntity() instanceof ServerPlayerEntity player ? player.getUuid() : null;
            }

            @Override
            public void sendFeedback(String message) {
                source.sendFeedback(() -> Text.literal(message), false);
            }

            @Override
            public void sendError(String message) {
                source.sendError(Text.literal(message));
            }
        };
    }
}
