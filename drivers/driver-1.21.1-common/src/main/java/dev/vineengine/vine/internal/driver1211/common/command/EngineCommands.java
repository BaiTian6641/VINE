package dev.vineengine.vine.internal.driver1211.common.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import org.slf4j.Logger;

import dev.vineengine.vine.command.ArgumentTypeRef;
import dev.vineengine.vine.command.CommandDescriptor;
import dev.vineengine.vine.command.VineArgumentTypes;
import dev.vineengine.vine.command.VineCommandExecutor;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.internal.command.CommandBridge;
import dev.vineengine.vine.hook.HookEvents;

/**
 * Loader-neutral Brigadier walker (sub-06 Stage A, driver delta A): pulls the
 * engine's conflict-resolved descriptor snapshot at a native dispatcher build and
 * attaches one Brigadier subtree per descriptor. Brigadier itself is
 * mappings-stable, so the entire walk lives in the shared source set; only the
 * four native touch points are per-loader (the {@link NativeFactory}).
 *
 * <p>Execution routes back into the engine: the Brigadier {@code executes}
 * callback collects the path's declared arguments and delegates to
 * {@link CommandBridge#execute}, which applies engine context semantics and error
 * isolation. Permission gates become native {@code requires} predicates, so a
 * denied source never reaches engine code (sub-06 §2).
 *
 * <p>Wired from each loader's native command-dispatcher build event
 * ({@code RegisterCommandsEvent} / {@code CommandRegistrationCallback}); re-attaches
 * idempotently on dispatcher rebuilds ({@code /reload}).
 */
public final class EngineCommands {

    /**
     * Per-loader adapter over the native command source type {@code S}
     * (Mojmap {@code CommandSourceStack} / Yarn {@code ServerCommandSource}).
     */
    public interface NativeFactory<S> {

        /** A native literal builder. */
        LiteralArgumentBuilder<S> literal(String name);

        /**
         * A native argument builder for one engine argument type (sub-06 Stage B:
         * every vanilla mirror plus engine enums), with the engine's completion
         * source attached when the descriptor declared one — suggestions are
         * computed server-side so vanilla clients tab-complete unchanged.
         */
        ArgumentBuilder<S, ?> argument(String name, ArgumentTypeRef<?> type,
            dev.vineengine.vine.command.SuggestionSource suggestions);

        /** A native op-level gate predicate. */
        Predicate<S> hasPermission(int level);

        /** Reads a declared argument from a native context, typed by the engine declaration. */
        Object getArg(CommandContext<S> context, String name, ArgumentTypeRef<?> type);

        /** Adapts the native source for the engine bridge. */
        CommandBridge.NativeSource adapt(S source);
    }

    /**
     * The {@code commandExecute} hook sink while subscribed — the real install
     * for that slot. {@code null} = zero consumers = zero posts, and the command
     * path skips event construction entirely (Minimal Footprint §5.1).
     */
    private static volatile Consumer<HookEvents.CommandExecute> executeHook;

    private EngineCommands() {
    }

    /** Installs the commandExecute sink ({@code null} clears it). */
    public static void executeHook(Consumer<HookEvents.CommandExecute> sink) {
        executeHook = sink;
    }

    /**
     * Attaches every descriptor in the engine's native-pass snapshot to
     * {@code dispatcher}, logging one {@code [VINE] command attached: <id> (/<root>)}
     * line per descriptor.
     */
    public static <S> void attach(CommandDispatcher<S> dispatcher, NativeFactory<S> factory, Logger log) {
        for (CommandDescriptor descriptor : CommandBridge.commandsForNativePass()) {
            dispatcher.register(buildLiteral(descriptor.root(), factory, List.of()));
            log.info("[VINE] command attached: {} (/{})", descriptor.id(), descriptor.root().name());
        }
    }

    private static <S> LiteralArgumentBuilder<S> buildLiteral(CommandDescriptor.Literal node,
                                                              NativeFactory<S> factory,
                                                              List<ArgumentSpec> pathArgs) {
        LiteralArgumentBuilder<S> builder = factory.literal(node.name());
        decorate(node, builder, factory, pathArgs);
        return builder;
    }

    /** One argument on the path to an executor: its name and declared engine type. */
    private record ArgumentSpec(String name, ArgumentTypeRef<?> type) {
    }

    private static <S> ArgumentBuilder<S, ?> buildArgument(CommandDescriptor.Argument node,
                                                           NativeFactory<S> factory,
                                                           List<ArgumentSpec> pathArgs) {
        // The factory owns native mapping for every mirror; unknown types never
        // reach here (the engine rejects them at registration).
        ArgumentBuilder<S, ?> builder = factory.argument(node.name(), node.type(), node.suggestions());
        List<ArgumentSpec> args = new ArrayList<>(pathArgs);
        args.add(new ArgumentSpec(node.name(), node.type()));
        decorate(node, builder, factory, List.copyOf(args));
        return builder;
    }

    private static <S> void decorate(CommandDescriptor.Node node, ArgumentBuilder<S, ?> builder,
                                     NativeFactory<S> factory, List<ArgumentSpec> pathArgs) {
        switch (node.permission()) {
            case null -> {
            }
            case VinePermission.Level level -> builder.requires(factory.hasPermission(level.level()));
        }
        VineCommandExecutor executor = node.executor();
        if (executor != null) {
            java.util.List<java.util.function.Predicate<dev.vineengine.vine.command.CommandSourceRef>>
                requirements = node.requirements();
            builder.executes(context -> {
                Map<String, Object> arguments = new HashMap<>();
                for (ArgumentSpec spec : pathArgs) {
                    arguments.put(spec.name(), factory.getArg(context, spec.name(), spec.type()));
                }
                CommandBridge.NativeSource source = factory.adapt(context.getSource());
                if (!CommandBridge.requirementsMet(requirements, source)) {
                    // Stage B gates: denied before any engine code sees the call.
                    return 0;
                }
                Consumer<HookEvents.CommandExecute> hook = executeHook;
                if (hook != null) {
                    HookEvents.CommandExecute event =
                        new HookEvents.CommandExecute(context.getInput(), source.name());
                    hook.accept(event);
                    if (event.isCancelled()) {
                        // Veto semantics (sub-01 Stage C): a cancelled pre-event
                        // suppresses execution; nothing unwinds.
                        return 0;
                    }
                }
                return CommandBridge.execute(executor, arguments, source);
            });
        }
        for (CommandDescriptor.Node child : node.children()) {
            if (child instanceof CommandDescriptor.Literal literal) {
                builder.then(buildLiteral(literal, factory, pathArgs));
            } else {
                builder.then(buildArgument((CommandDescriptor.Argument) child, factory, pathArgs));
            }
        }
    }
}
