package dev.vineengine.vine.internal.driver1211.neoforge.command;

import java.util.UUID;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
    public com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> argument(String name,
            dev.vineengine.vine.command.ArgumentTypeRef<?> type,
            dev.vineengine.vine.command.SuggestionSource suggestions) {
        String id = type.id();
        RequiredArgumentBuilder<CommandSourceStack, ?> builder;
        if (id.equals("int")) {
            builder = Commands.argument(name, IntegerArgumentType.integer());
        } else if (id.equals("long")) {
            builder = Commands.argument(name, LongArgumentType.longArg());
        } else if (id.equals("double")) {
            builder = Commands.argument(name, DoubleArgumentType.doubleArg());
        } else if (id.equals("bool")) {
            builder = Commands.argument(name, BoolArgumentType.bool());
        } else if (id.equals("greedy")) {
            builder = Commands.argument(name, StringArgumentType.greedyString());
        } else if (id.equals("string") || id.startsWith("enum:")) {
            // Enums ride a string argument: Brigadier has no native enum type,
            // and parsing/validation happens in getArg against the declaration.
            builder = Commands.argument(name, StringArgumentType.string());
        } else if (id.equals("vine_id")) {
            // Engine ids are resource locations on every cell: an unmodded client
            // parses and completes them with no client-side engine code.
            builder = Commands.argument(name, net.minecraft.commands.arguments.ResourceLocationArgument.id());
        } else if (id.equals("voxel_path")) {
            builder = Commands.argument(name, StringArgumentType.greedyString());
        } else if (id.equals("player_in_session")) {
            builder = Commands.argument(name, net.minecraft.commands.arguments.EntityArgument.player());
        } else {
            throw new IllegalStateException("NeoForge cell cannot map engine argument type " + id);
        }
        if (suggestions != null) {
            builder.suggests((context, suggestionsBuilder) -> {
                var source = CommandBridge.sourceRef(adapt(context.getSource()));
                var ctx = new dev.vineengine.vine.command.SuggestionContext(
                    suggestionsBuilder.getRemaining(), source);
                for (String candidate : suggestions.suggest(ctx)) {
                    suggestionsBuilder.suggest(candidate);
                }
                return suggestionsBuilder.buildFuture();
            });
        }
        return builder;
    }

    @Override
    public Object getArg(CommandContext<CommandSourceStack> context, String name,
            dev.vineengine.vine.command.ArgumentTypeRef<?> type) {
        String id = type.id();
        if (id.equals("int")) {
            return IntegerArgumentType.getInteger(context, name);
        }
        if (id.equals("long")) {
            return LongArgumentType.getLong(context, name);
        }
        if (id.equals("double")) {
            return DoubleArgumentType.getDouble(context, name);
        }
        if (id.equals("bool")) {
            return BoolArgumentType.getBool(context, name);
        }
        if (id.equals("vine_id")) {
            return dev.vineengine.vine.registry.VineId.parse(
                net.minecraft.commands.arguments.ResourceLocationArgument.getId(context, name).toString());
        }
        if (id.equals("player_in_session")) {
            // Brigadier has already validated the selector; a failure here is an
            // engine-level resolution problem, reported through the executor's
            // error path rather than as an unchecked Brigadier crash.
            try {
                return playerOf(net.minecraft.commands.arguments.EntityArgument.getPlayer(context, name));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        String raw = StringArgumentType.getString(context, name);
        if (id.equals("voxel_path")) {
            return dev.vineengine.vine.command.VoxelPath.parse(raw);
        }
        if (id.startsWith("enum:")) {
            for (String constant : type.enumValues()) {
                if (constant.equalsIgnoreCase(raw)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException(raw + " is not one of " + type.enumValues());
        }
        return raw;
    }

    @Override
    public Predicate<CommandSourceStack> hasPermission(int level) {
        return source -> source.hasPermission(level);
    }


    /**
     * NeoForge's permission API has no string-node lookup to offer (sub-06 Stage
     * D): 1.21 replaced string nodes with typed {@code PermissionNode}s that
     * consumers register at startup
     * ({@code PermissionAPI.getRegisteredNodes()}), addressed by an active
     * handler id ({@code getActivePermissionHandler()}). Consumers under the
     * Prime Invariant are loader-free and cannot mint those types, so this cell
     * reports "no provider" and node gates resolve through the engine's
     * permission bridge (a plugin installs one) or the descriptor's fallback op
     * level. Pretending to check an unregistered node string here would silently
     * deny every gate — the failure mode this design exists to avoid.
     */
    @Override
    public EngineCommands.NativeNodePermission<CommandSourceStack> nodePermission() {
        return null;
    }

    /** The engine's player facade over a native player (argument values, suggestions). */
    static dev.vineengine.vine.VinePlayer playerOf(ServerPlayer player) {
        return new dev.vineengine.vine.VinePlayer() {
            @Override
            public java.util.UUID uniqueId() {
                return player.getUUID();
            }

            @Override
            public String name() {
                return player.getGameProfile().getName();
            }
        };
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
