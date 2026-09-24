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
        List<CommandDescriptor> descriptors = CommandBridge.commandsForNativePass();
        // Aliases (redirect nodes, sub-06 Stage B) need their target registered
        // before the alias builder is built: Brigadier bakes the redirect node at
        // build time. The engine already resolved targets and cycles, so plain
        // roots go first and alias roots resolve against what is registered.
        List<CommandDescriptor> plain = new ArrayList<>(descriptors.size());
        List<CommandDescriptor> aliases = new ArrayList<>();
        for (CommandDescriptor descriptor : descriptors) {
            if (hasAlias(descriptor.root())) {
                aliases.add(descriptor);
            } else {
                plain.add(descriptor);
            }
        }
        BuildContext<S> rootContext = new BuildContext<>(factory, name -> null);
        for (CommandDescriptor descriptor : plain) {
            dispatcher.register(buildLiteral(descriptor.root(), rootContext, List.of()));
            log.info("[VINE] command attached: {} (/{})", descriptor.id(), descriptor.root().name());
        }
        BuildContext<S> aliasContext = new BuildContext<>(factory, dispatcher.getRoot()::getChild);
        for (CommandDescriptor descriptor : aliases) {
            for (CommandDescriptor.Node node : descriptor.root().children()) {
                if (node instanceof CommandDescriptor.Literal literal && literal.redirect() != null
                    && dispatcher.getRoot().getChild(literal.redirect()) == null) {
                    // The engine only emits aliases whose target is registered;
                    // reaching here means engine/driver skew — the alias attaches
                    // as a plain literal instead of failing the dispatcher build.
                    log.warn("[VINE] redirect target '{}' missing for '/{}' — alias attached as a literal",
                        literal.redirect(), literal.name());
                }
            }
            dispatcher.register(buildLiteral(descriptor.root(), aliasContext, List.of()));
            log.info("[VINE] command attached: {} (/{})", descriptor.id(), descriptor.root().name());
        }
    }

    /** Whether any node in this subtree is a redirect alias. */
    private static boolean hasAlias(CommandDescriptor.Node node) {
        if (node instanceof CommandDescriptor.Literal literal && literal.redirect() != null) {
            return true;
        }
        for (CommandDescriptor.Node child : node.children()) {
            if (hasAlias(child)) {
                return true;
            }
        }
        return false;
    }

    /** Per-build state: the cell's native factory plus alias-target resolution. */
    private record BuildContext<S>(NativeFactory<S> factory,
                                   java.util.function.Function<String, com.mojang.brigadier.tree.CommandNode<S>>
                                       redirects) {
    }

    private static <S> LiteralArgumentBuilder<S> buildLiteral(CommandDescriptor.Literal node,
                                                              BuildContext<S> context,
                                                              List<ArgumentSpec> pathArgs) {
        LiteralArgumentBuilder<S> builder = context.factory().literal(node.name());
        if (node.redirect() != null) {
            com.mojang.brigadier.tree.CommandNode<S> target = context.redirects().apply(node.redirect());
            if (target != null) {
                builder.redirect(target);
            }
        }
        decorate(node, builder, context, pathArgs);
        return builder;
    }

    /** One argument on the path to an executor: its name and declared engine type. */
    private record ArgumentSpec(String name, ArgumentTypeRef<?> type) {
    }

    private static <S> ArgumentBuilder<S, ?> buildArgument(CommandDescriptor.Argument node,
                                                           BuildContext<S> context,
                                                           List<ArgumentSpec> pathArgs) {
        NativeFactory<S> factory = context.factory();
        // The factory owns native mapping for every mirror; unknown types never
        // reach here (the engine rejects them at registration).
        ArgumentBuilder<S, ?> builder = factory.argument(node.name(), node.type(), node.suggestions());
        List<ArgumentSpec> args = new ArrayList<>(pathArgs);
        args.add(new ArgumentSpec(node.name(), node.type()));
        decorate(node, builder, context, List.copyOf(args));
        return builder;
    }

    private static <S> void decorate(CommandDescriptor.Node node, ArgumentBuilder<S, ?> builder,
                                     BuildContext<S> buildCtx, List<ArgumentSpec> pathArgs) {
        NativeFactory<S> factory = buildCtx.factory();
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
                CommandBridge.NativeSource source = factory.adapt(context.getSource());
                Map<String, Object> arguments = new HashMap<>();
                for (ArgumentSpec spec : pathArgs) {
                    try {
                        arguments.put(spec.name(), factory.getArg(context, spec.name(), spec.type()));
                    } catch (IllegalArgumentException invalid) {
                        // Engine validation of a natively-parsed value (sub-06
                        // Stage C): a clean error to the source, never a crash.
                        return CommandBridge.invalidArgument(source, spec.name(), invalid.getMessage());
                    }
                }
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
                builder.then(buildLiteral(literal, buildCtx, pathArgs));
            } else {
                builder.then(buildArgument((CommandDescriptor.Argument) child, buildCtx, pathArgs));
            }
        }
    }
}
