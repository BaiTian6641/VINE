package dev.vineengine.vine.testmod.command;

import java.util.List;

import dev.vineengine.vine.command.SuggestionContext;
import dev.vineengine.vine.command.SuggestionSource;
import dev.vineengine.vine.command.VineArgumentTypes;
import dev.vineengine.vine.command.VineCommand;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.registry.VineId;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * Command-tree exemplar (sub-06 Stage B): the full descriptor DSL on one nested
 * tree — every vanilla-mirror argument type, an engine enum, completion
 * sources, and requirement predicates — plus the merge/conflict cases the
 * engine's resolution policy has to survive.
 *
 * <p>Three descriptors claim the {@code vine_test} root on purpose:
 * {@link EchoCommand}'s tree, this tree (merges in as a literal-compatible
 * sibling set), and {@link #CONFLICT_ID} (attaches a second {@code tree}
 * subtree whose {@code num} argument has a different shape, and an {@code echo}
 * argument where the first consumer has a literal). Expected handling (sub-06
 * §2): children merge by name, the first-registered shape wins each clash, and
 * one conflict report per clash names both descriptor ids. The TCK scenario
 * asserts the log lines and that the surviving children still execute.
 *
 * <p>Requirements are consumer predicates over {@link dev.vineengine.vine.command.CommandSourceRef}
 * — evaluated server-side after the permission gate, before the executor, so a
 * denied call never reaches consumer behavior.
 */
public final class CommandTreeExemplar {

    /** The tree descriptor's id (diagnostics, conflict report). */
    public static final VineId TREE_ID = VineId.of(MOD_ID, "tree");
    /** The conflicting second consumer's id (diagnostics, conflict report). */
    public static final VineId CONFLICT_ID = VineId.of(MOD_ID, "tree_conflict");

    /** What {@code tck_suggest} completes — also reused by the tree's enum argument. */
    public static final List<String> MODES = List.of("ON", "OFF", "AUTO");

    /** The mode argument's completion source: fixed candidates filtered by the typed prefix. */
    public static final SuggestionSource MODE_SUGGESTIONS = SuggestionSource.of(MODES);

    private CommandTreeExemplar() {
    }

    /** Registers the tree + conflict descriptors with the engine. */
    public static void register() {
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("tree")
                .then(VineCommand.literal("int")
                    .then(VineCommand.argument("num", VineArgumentTypes.INT)
                        .executes(ctx -> {
                            ctx.feedback("tree int=" + ctx.argument("num", Integer.class));
                            return 1;
                        })))
                .then(VineCommand.literal("long")
                    .then(VineCommand.argument("big", VineArgumentTypes.LONG)
                        .executes(ctx -> {
                            ctx.feedback("tree long=" + ctx.argument("big", Long.class));
                            return 1;
                        })))
                .then(VineCommand.literal("bool")
                    .then(VineCommand.argument("flag", VineArgumentTypes.BOOL)
                        .executes(ctx -> {
                            ctx.feedback("tree bool=" + ctx.argument("flag", Boolean.class));
                            return 1;
                        })))
                .then(VineCommand.literal("double")
                    .then(VineCommand.argument("ratio", VineArgumentTypes.DOUBLE)
                        .executes(ctx -> {
                            ctx.feedback("tree double=" + ctx.argument("ratio", Double.class));
                            return 1;
                        })))
                .then(VineCommand.literal("enum")
                    .then(VineCommand.argument("mode", VineArgumentTypes.enumeration(Mode.class))
                        .suggests(MODE_SUGGESTIONS)
                        .executes(ctx -> {
                            ctx.feedback("tree enum=" + ctx.argument("mode", String.class));
                            return 1;
                        })))
                .then(VineCommand.literal("suggest")
                    .then(VineCommand.argument("prefix", VineArgumentTypes.STRING)
                        .executes(ctx -> {
                            // Runs the completion source the tree's own enum
                            // argument is wired with, so the TCK can observe
                            // candidate computation server-side (client
                            // tab-completion itself needs the sub-21 client runner).
                            String prefix = ctx.argument("prefix", String.class);
                            List<String> candidates = MODE_SUGGESTIONS.suggest(
                                new SuggestionContext(prefix, ctx.source()));
                            ctx.feedback("suggest: " + String.join(",", candidates));
                            return 1;
                        })))
                .then(VineCommand.literal("gated")
                    // Denied for the headless console (not a player), allowed for
                    // players: the scenario drives the deny path live and the
                    // allow path through the trivially-true sibling below.
                    .requires(source -> source.isPlayer())
                    .executes(ctx -> {
                        ctx.feedback("tree gated ok");
                        return 1;
                    }))
                .then(VineCommand.literal("open")
                    .requires(source -> source.name() != null)
                    .executes(ctx -> {
                        ctx.feedback("tree open ok");
                        return 1;
                    }))
                // Declared last on purpose: a greedy argument consumes the rest
                // of the line, and the compiler rejects any sibling after one.
                .then(VineCommand.literal("greedy")
                    .then(VineCommand.argument("msg", VineArgumentTypes.GREEDY)
                        .executes(ctx -> {
                            ctx.feedback("tree greedy=" + ctx.argument("msg", String.class));
                            return 1;
                        }))))
            .build(TREE_ID));

        // Second consumer of the same root: `tree` merges (its `num` argument
        // clashes on type — first wins), `second` merges as a new child, and
        // `echo` clashes literal-vs-argument.
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("tree")
                .then(VineCommand.literal("int")
                    .then(VineCommand.argument("num", VineArgumentTypes.STRING)
                        .executes(ctx -> {
                            ctx.feedback("tree int (string)=" + ctx.argument("num", String.class));
                            return 1;
                        }))))
            .then(VineCommand.literal("second")
                .executes(ctx -> {
                    ctx.feedback("second ok");
                    return 1;
                }))
            .then(VineCommand.argument("echo", VineArgumentTypes.STRING)
                .executes(ctx -> {
                    ctx.feedback("echo argument ok");
                    return 1;
                }))
            .build(CONFLICT_ID));

        // Alias exemplar (sub-06 Stage B): /vt is the same tree as /vine_test, so
        // /vt echo hi behaves exactly as /vine_test echo hi — permissions,
        // suggestions and executors included. A dead target and a cycle are
        // resolved (and reported) by the engine at snapshot time.
        VineCommands.get().register(VineCommand.literal("vt")
            .permission(VinePermission.level(2))
            .redirect(MOD_ID)
            .build(VineId.of(MOD_ID, "alias")));
        VineCommands.get().register(VineCommand.literal("vt_dead")
            .permission(VinePermission.level(2))
            .redirect("vine_test_absent")
            .build(VineId.of(MOD_ID, "alias_dead")));
        VineCommands.get().register(VineCommand.literal("vt_cycle_a")
            .permission(VinePermission.level(2))
            .redirect("vt_cycle_b")
            .build(VineId.of(MOD_ID, "alias_cycle_a")));
        VineCommands.get().register(VineCommand.literal("vt_cycle_b")
            .permission(VinePermission.level(2))
            .redirect("vt_cycle_a")
            .build(VineId.of(MOD_ID, "alias_cycle_b")));

        // Rejection probe (registration-time validation): a descriptor whose
        // greedy argument has a declared sibling after it must be refused by the
        // engine compiler — never accepted and then silently shadowed at dispatch.
        String rejection;
        try {
            VineCommands.get().register(VineCommand.literal(MOD_ID)
                .then(VineCommand.literal("tck_greedy_reject")
                    .then(VineCommand.argument("tail", VineArgumentTypes.GREEDY)
                        .executes(ctx -> 1))
                    .then(VineCommand.literal("unreachable")
                        .executes(ctx -> 1)))
                .build(VineId.of(MOD_ID, "greedy_reject")));
            rejection = "ACCEPTED (bug)";
        } catch (IllegalArgumentException expected) {
            rejection = expected.getMessage().contains("must be the last child")
                ? "rejected" : "rejected (unexpected message: " + expected.getMessage() + ")";
        }
        System.getLogger("vine-testmod").log(System.Logger.Level.INFO,
            "[vine-testmod] greedy placement probe: " + rejection);
    }

    /** Enum mirror for {@code tree mode}: parsed by constant name, case-insensitive. */
    public enum Mode {
        ON,
        OFF,
        AUTO
    }
}
