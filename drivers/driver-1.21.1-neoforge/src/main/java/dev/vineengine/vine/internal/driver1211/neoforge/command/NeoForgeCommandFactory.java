package dev.vineengine.vine.internal.driver1211.neoforge.command;

import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import dev.vineengine.vine.internal.command.CommandBridge;
import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;

import java.util.function.Predicate;

/**
 * The NeoForge {@link EngineCommands.NativeFactory} (sub-06 Stage A): the four
 * native touch points of the command walker over Mojmap
 * {@link CommandSourceStack} — Brigadier builders, the op-level gate, argument
 * reads, and the source adapter fed to {@code CommandBridge}.
 */
public final class NeoForgeCommandFactory implements EngineCommands.NativeFactory<CommandSourceStack> {

    private static final NeoForgeCommandFactory INSTANCE = new NeoForgeCommandFactory();

    private NeoForgeCommandFactory() {
    }

    public static NeoForgeCommandFactory instance() {
        return INSTANCE;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    @Override
    public RequiredArgumentBuilder<CommandSourceStack, String> stringArgument(String name) {
        return Commands.argument(name, StringArgumentType.string());
    }

    @Override
    public Predicate<CommandSourceStack> hasPermission(int level) {
        return source -> source.hasPermission(level);
    }

    @Override
    public String getStringArg(CommandContext<CommandSourceStack> context, String name) {
        return StringArgumentType.getString(context, name);
    }

    @Override
    public CommandBridge.NativeSource adapt(CommandSourceStack source) {
        return new CommandBridge.NativeSource() {
            @Override
            public String name() {
                return source.getTextName();
            }

            @Override
            public boolean isPlayer() {
                return source.getEntity() instanceof ServerPlayer;
            }

            @Override
            public UUID playerUniqueId() {
                return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
            }

            @Override
            public void sendFeedback(String message) {
                source.sendSystemMessage(Component.literal(message));
            }

            @Override
            public void sendError(String message) {
                source.sendFailure(Component.literal(message));
            }
        };
    }
}
