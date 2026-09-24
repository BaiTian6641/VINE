package dev.vineengine.vine.internal.driver1211.fabric.command;

import java.util.UUID;
import java.util.function.Predicate;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
    public com.mojang.brigadier.builder.ArgumentBuilder<ServerCommandSource, ?> argument(String name,
            dev.vineengine.vine.command.ArgumentTypeRef<?> type,
            dev.vineengine.vine.command.SuggestionSource suggestions) {
        String id = type.id();
        RequiredArgumentBuilder<ServerCommandSource, ?> builder;
        if (id.equals("int")) {
            builder = CommandManager.argument(name, IntegerArgumentType.integer());
        } else if (id.equals("long")) {
            builder = CommandManager.argument(name, LongArgumentType.longArg());
        } else if (id.equals("double")) {
            builder = CommandManager.argument(name, DoubleArgumentType.doubleArg());
        } else if (id.equals("bool")) {
            builder = CommandManager.argument(name, BoolArgumentType.bool());
        } else if (id.equals("greedy")) {
            builder = CommandManager.argument(name, StringArgumentType.greedyString());
        } else if (id.equals("string") || id.startsWith("enum:")) {
            // Enums ride a string argument: Brigadier has no native enum type,
            // and parsing/validation happens in getArg against the declaration.
            builder = CommandManager.argument(name, StringArgumentType.string());
        } else {
            throw new IllegalStateException("Fabric cell cannot map engine argument type " + id);
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
    public Object getArg(CommandContext<ServerCommandSource> context, String name,
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
    public Predicate<ServerCommandSource> hasPermission(int level) {
        return source -> source.hasPermissionLevel(level);
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
