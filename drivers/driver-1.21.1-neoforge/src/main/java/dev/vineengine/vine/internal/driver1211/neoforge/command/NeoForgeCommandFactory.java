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
        String raw = StringArgumentType.getString(context, name);
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
