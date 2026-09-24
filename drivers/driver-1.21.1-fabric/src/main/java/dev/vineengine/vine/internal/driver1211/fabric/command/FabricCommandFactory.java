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
        } else if (id.equals("vine_id")) {
            // Engine ids are resource locations on every cell: an unmodded
            // client parses and completes them with no client-side engine code.
            builder = CommandManager.argument(name, net.minecraft.command.argument.IdentifierArgumentType.identifier());
        } else if (id.equals("voxel_path")) {
            builder = CommandManager.argument(name, StringArgumentType.greedyString());
        } else if (id.equals("player_in_session")) {
            builder = CommandManager.argument(name, net.minecraft.command.argument.EntityArgumentType.player());
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
        if (id.equals("vine_id")) {
            return dev.vineengine.vine.registry.VineId.parse(
                net.minecraft.command.argument.IdentifierArgumentType.getIdentifier(context, name).toString());
        }
        if (id.equals("player_in_session")) {
            // Brigadier already validated the selector; a failure here is an
            // engine-level resolution problem, reported through the executor's
            // error path rather than as an unchecked Brigadier crash.
            try {
                return playerOf(net.minecraft.command.argument.EntityArgumentType.getPlayer(context, name));
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
    public Predicate<ServerCommandSource> hasPermission(int level) {
        return source -> source.hasPermissionLevel(level);
    }


    /**
     * fabric-permissions-api is optional (sub-06 Stage D): resolved reflectively
     * once, so VINE never hard-depends on it and a server without the mod keeps
     * working through the descriptor's fallback op level. The API is stable
     * across its releases ({@code Permissions.check(ServerCommandSource, String, int)}),
     * which is exactly the seam the engine's gate policy expects — a
     * {@code null} result means "this provider has no opinion about that node".
     */
    private static final class FabricNodePermission implements EngineCommands.NativeNodePermission<ServerCommandSource> {

        private final java.lang.reflect.Method check;
        private final String source;

        FabricNodePermission(java.lang.reflect.Method check, String source) {
            this.check = check;
            this.source = source;
        }

        @Override
        public Boolean has(ServerCommandSource commandSource, String node, int fallbackLevel) {
            try {
                // A provider returning the fallback means "no explicit entry" —
                // report no opinion so the engine's own fallback check runs (they
                // agree by construction, but the engine path stays the owner of
                // op-level semantics).
                boolean granted = (Boolean) check.invoke(null, commandSource, node, fallbackLevel);
                boolean fallback = commandSource.hasPermissionLevel(fallbackLevel);
                return granted == fallback ? null : granted;
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return null;
            }
        }

        @Override
        public String toString() {
            return source;
        }
    }

    private static final EngineCommands.NativeNodePermission<ServerCommandSource> NODE_PERMISSION = probe();

    private static EngineCommands.NativeNodePermission<ServerCommandSource> probe() {
        try {
            Class<?> permissions = Class.forName("net.fabricmc.fabric.api.permissions.v1.Permissions");
            java.lang.reflect.Method check = permissions.getMethod("check",
                net.minecraft.server.command.ServerCommandSource.class, String.class, int.class);
            return new FabricNodePermission(check, "fabric-permissions-api");
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            return null;
        }
    }

    @Override
    public EngineCommands.NativeNodePermission<ServerCommandSource> nodePermission() {
        return NODE_PERMISSION;
    }

    /** The engine's player facade over a native player (argument values, suggestions). */
    static dev.vineengine.vine.VinePlayer playerOf(net.minecraft.server.network.ServerPlayerEntity player) {
        return new dev.vineengine.vine.VinePlayer() {
            @Override
            public java.util.UUID uniqueId() {
                return player.getUuid();
            }

            @Override
            public String name() {
                return player.getGameProfile().getName();
            }
        };
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
