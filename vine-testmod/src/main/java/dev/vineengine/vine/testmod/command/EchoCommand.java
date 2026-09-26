package dev.vineengine.vine.testmod.command;

import dev.vineengine.vine.command.VineArgumentTypes;
import dev.vineengine.vine.command.VineCommand;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.testmod.net.EchoPacket;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * Canonical command exemplar (sub-06, M0 minimal): {@code /vine_test echo <msg>}
 * replies {@code "echo: <msg>"} to the source, plus the TCK drive child
 * {@code /vine_test tck_echo <number> <text>} that fires one echo payload into
 * the engine's C2S path (sub-21 Stage B — the scenario runner invokes it on a
 * headless server launched with {@code vine.tck.loopback}).
 *
 * <p>The engine's merge policy (sub-06 §2) merges descriptors that claim the
 * same root literal child-by-child and keeps the first-registered shape on a
 * clash, so several consumers can extend one root; this tree is the first
 * {@code vine_test} descriptor and therefore owns every name it declares
 * (the merge/conflict cases live in
 * {@link CommandTreeExemplar}).
 */
public final class EchoCommand {

    private EchoCommand() {
    }

    /** Registers the vine_test command tree with the engine. */
    public static void register() {
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("echo")
                .then(VineCommand.argument("msg", VineArgumentTypes.STRING)
                    .executes(ctx -> {
                        ctx.feedback("echo: " + ctx.argument("msg", String.class));
                        return 1;
                    })))
            .then(VineCommand.literal("tck_echo")
                .then(VineCommand.argument("number", VineArgumentTypes.STRING)
                    .then(VineCommand.argument("text", VineArgumentTypes.STRING)
                        .executes(ctx -> {
                            int number = Integer.parseInt(ctx.argument("number", String.class));
                            String text = ctx.argument("text", String.class);
                            EchoPacket.sendToServer(new EchoPacket.Payload(number, text));
                            ctx.feedback("tck: sent echo number=" + number + " text=" + text);
                            return 1;
                        }))))
            .then(VineCommand.literal("tck_anim_probe")
                .executes(ctx -> {
                    try {
                        dev.vineengine.vine.testmod.animation.AnimationExemplar.probe();
                    } catch (java.io.IOException e) {
                        // The exemplar reads a resource; a cell without it is a packaging
                        // bug, and saying so beats a stack trace in a command result.
                        throw new IllegalStateException("animation exemplar could not read its asset", e);
                    }
                    return 1;
                }))
            .then(VineCommand.literal("tck_beast_walk")
                .then(VineCommand.argument("x", VineArgumentTypes.INT)
                    .then(VineCommand.argument("y", VineArgumentTypes.INT)
                        .then(VineCommand.argument("z", VineArgumentTypes.INT)
                            .then(VineCommand.argument("tx", VineArgumentTypes.INT)
                                .then(VineCommand.argument("ty", VineArgumentTypes.INT)
                                    .then(VineCommand.argument("tz", VineArgumentTypes.INT)
                                        .executes(ctx -> {
                                            boolean spawned = dev.vineengine.vine.testmod.brain.EntityBrainExemplar
                                                .spawnAndWalk(
                                                    dev.vineengine.vine.world.Vec3.of(
                                                        ctx.argument("x", Integer.class) + 0.5D,
                                                        ctx.argument("y", Integer.class),
                                                        ctx.argument("z", Integer.class) + 0.5D),
                                                    dev.vineengine.vine.world.Vec3.of(
                                                        ctx.argument("tx", Integer.class) + 0.5D,
                                                        ctx.argument("ty", Integer.class),
                                                        ctx.argument("tz", Integer.class) + 0.5D));
                                            ctx.feedback("tck: beast walk started=" + spawned);
                                            return 1;
                                        }))))))))
            .then(VineCommand.literal("tck_parts_spawn")
                .then(VineCommand.argument("x", VineArgumentTypes.INT)
                    .then(VineCommand.argument("y", VineArgumentTypes.INT)
                        .then(VineCommand.argument("z", VineArgumentTypes.INT)
                            .executes(ctx -> {
                                boolean spawned = dev.vineengine.vine.testmod.parts.PartsExemplar.spawn(
                                    dev.vineengine.vine.world.Vec3.of(
                                        ctx.argument("x", Integer.class) + 0.5D,
                                        ctx.argument("y", Integer.class),
                                        ctx.argument("z", Integer.class) + 0.5D));
                                ctx.feedback("tck: parts spawn started=" + spawned);
                                return 1;
                            })))))
            .then(VineCommand.literal("tck_parts_hit")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.parts.PartsExemplar.hit();
                    return 1;
                }))
            .then(VineCommand.literal("tck_two_players")
                .then(VineCommand.argument("x", VineArgumentTypes.INT)
                    .then(VineCommand.argument("y", VineArgumentTypes.INT)
                        .then(VineCommand.argument("z", VineArgumentTypes.INT)
                            .executes(ctx -> {
                                boolean spawned = dev.vineengine.vine.testmod.combat.TwoPlayerFight.spawn(
                                    dev.vineengine.vine.world.Vec3.of(
                                        ctx.argument("x", Integer.class) + 0.5D,
                                        ctx.argument("y", Integer.class),
                                        ctx.argument("z", Integer.class) + 0.5D));
                                ctx.feedback("tck: two-player spawn started=" + spawned);
                                return 1;
                            })))))
            .then(VineCommand.literal("tck_two_players_fight")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.combat.TwoPlayerFight.fight();
                    return 1;
                }))
            .then(VineCommand.literal("tck_parts_host")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.parts.PartsExemplar.host();
                    return 1;
                }))
            .then(VineCommand.literal("tck_parts_status")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.parts.PartsExemplar.status();
                    return 1;
                }))
            .then(VineCommand.literal("tck_parts_track")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.parts.PartsExemplar.track();
                    return 1;
                }))
            .then(VineCommand.literal("tck_combat_spawn")
                .then(VineCommand.argument("tx", VineArgumentTypes.INT)
                    .then(VineCommand.argument("ty", VineArgumentTypes.INT)
                        .then(VineCommand.argument("tz", VineArgumentTypes.INT)
                            .then(VineCommand.argument("ax", VineArgumentTypes.INT)
                                .then(VineCommand.argument("ay", VineArgumentTypes.INT)
                                    .then(VineCommand.argument("az", VineArgumentTypes.INT)
                                        .executes(ctx -> {
                                            boolean spawned =
                                                dev.vineengine.vine.testmod.combat.CombatExemplar.spawn(
                                                    dev.vineengine.vine.world.Vec3.of(
                                                        ctx.argument("tx", Integer.class) + 0.5D,
                                                        ctx.argument("ty", Integer.class),
                                                        ctx.argument("tz", Integer.class) + 0.5D),
                                                    dev.vineengine.vine.world.Vec3.of(
                                                        ctx.argument("ax", Integer.class) + 0.5D,
                                                        ctx.argument("ay", Integer.class),
                                                        ctx.argument("az", Integer.class) + 0.5D));
                                            ctx.feedback("tck: combat spawn started=" + spawned);
                                            return 1;
                                        }))))))))
            .then(VineCommand.literal("tck_combat_strike")
                .then(VineCommand.argument("tx", VineArgumentTypes.INT)
                    .then(VineCommand.argument("ty", VineArgumentTypes.INT)
                        .then(VineCommand.argument("tz", VineArgumentTypes.INT)
                            .executes(ctx -> {
                                dev.vineengine.vine.testmod.combat.CombatExemplar.strike(
                                    dev.vineengine.vine.world.Vec3.of(
                                        ctx.argument("tx", Integer.class) + 0.5D,
                                        ctx.argument("ty", Integer.class),
                                        ctx.argument("tz", Integer.class) + 0.5D));
                                return 1;
                            })))))
            .then(VineCommand.literal("tck_combat_after")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.combat.CombatExemplar.after();
                    return 1;
                }))
            .then(VineCommand.literal("tck_campaign_verify")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.quest.CampaignExemplar.verify();
                    return 1;
                }))
            .then(VineCommand.literal("tck_cutscene_play")
                .executes(ctx -> {
                    // The player who ran the command watches too, when there is one: the fixed
                    // viewers keep the headless scenario's assertions true, and the caller is
                    // what lets a real client be shown a cutscene it is actually addressed in.
                    dev.vineengine.vine.testmod.cutscene.CutsceneExemplar.play(
                        ctx.source().player().map(dev.vineengine.vine.VinePlayer::uniqueId).orElse(null));
                    return 1;
                }))
            .then(VineCommand.literal("tck_cutscene_finish")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.cutscene.CutsceneExemplar.finish();
                    return 1;
                }))
            .then(VineCommand.literal("tck_campaign_run")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.quest.CampaignExemplar.run();
                    return 1;
                }))
            .then(VineCommand.literal("tck_brain_trace")
                .then(VineCommand.argument("ticks", VineArgumentTypes.INT)
                    .executes(ctx -> {
                        dev.vineengine.vine.testmod.brain.BrainExemplar.run(ctx.argument("ticks", Integer.class));
                        return 1;
                    })))

            .then(VineCommand.literal("tck_entity_spawn")
                .then(VineCommand.argument("id", VineArgumentTypes.VINE_ID)
                    .then(VineCommand.argument("x", VineArgumentTypes.INT)
                        .then(VineCommand.argument("y", VineArgumentTypes.INT)
                            .then(VineCommand.argument("z", VineArgumentTypes.INT)
                                .executes(ctx -> {
                                    var id = ctx.argument("id", VineId.class);
                                    int x = ctx.argument("x", Integer.class);
                                    int y = ctx.argument("y", Integer.class);
                                    int z = ctx.argument("z", Integer.class);
                                    var spawned = dev.vineengine.vine.entity.VineEntities.spawn(id,
                                        dev.vineengine.vine.world.VineWorlds.overworld(),
                                        dev.vineengine.vine.world.Vec3.of(x + 0.5D, y, z + 0.5D));
                                    ctx.feedback("tck: entity spawn id=" + id + " at=" + x + "," + y + "," + z
                                        + " ok=" + spawned.isPresent() + " ref="
                                        + spawned.map(Object::toString).orElse("none"));
                                    return 1;
                                }))))))

            .then(VineCommand.literal("tck_counter_get")
                .then(VineCommand.argument("x", VineArgumentTypes.INT)
                    .then(VineCommand.argument("y", VineArgumentTypes.INT)
                        .then(VineCommand.argument("z", VineArgumentTypes.INT)
                            .executes(ctx -> {
                                dev.vineengine.vine.testmod.content.CounterExemplar.read(
                                    ctx.argument("x", Integer.class),
                                    ctx.argument("y", Integer.class),
                                    ctx.argument("z", Integer.class));
                                return 1;
                            })))))
            .then(VineCommand.literal("tck_state_get")
                .then(VineCommand.argument("x", VineArgumentTypes.INT)
                    .then(VineCommand.argument("y", VineArgumentTypes.INT)
                        .then(VineCommand.argument("z", VineArgumentTypes.INT)
                            .executes(ctx -> {
                                dev.vineengine.vine.testmod.content.BlockStateExemplar.read(
                                    ctx.argument("x", Integer.class),
                                    ctx.argument("y", Integer.class),
                                    ctx.argument("z", Integer.class));
                                return 1;
                            })))))
            .then(VineCommand.literal("tck_state_set")
                .then(VineCommand.argument("x", VineArgumentTypes.INT)
                    .then(VineCommand.argument("y", VineArgumentTypes.INT)
                        .then(VineCommand.argument("z", VineArgumentTypes.INT)
                            .then(VineCommand.argument("property", VineArgumentTypes.STRING)
                                .then(VineCommand.argument("value", VineArgumentTypes.STRING)
                                    .executes(ctx -> {
                                        dev.vineengine.vine.testmod.content.BlockStateExemplar.set(
                                            ctx.argument("x", Integer.class),
                                            ctx.argument("y", Integer.class),
                                            ctx.argument("z", Integer.class),
                                            ctx.argument("property", String.class),
                                            ctx.argument("value", String.class));
                                        return 1;
                                    })))))))
            .then(VineCommand.literal("tck_caps")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.capability.CapabilityExemplar.runProof();
                    return 1;
                }))
            .then(VineCommand.literal("tck_sessions")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.session.SessionExemplar.runProof();
                    return 1;
                }))
            .then(VineCommand.literal("tck_sessions_persist")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.session.SessionPersistenceExemplar.createAndFlush();
                    return 1;
                }))
            .then(VineCommand.literal("tck_sessions_restored")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.session.SessionPersistenceExemplar.reportRestored();
                    return 1;
                }))
            .then(VineCommand.literal("tck_hooks")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.hook.HookExemplar.subscribe();
                    return 1;
                }))
            .then(VineCommand.literal("tck_hooks_fire")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.hook.HookExemplar.fire();
                    return 1;
                }))
            .then(VineCommand.literal("tck_registry_ids")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.registry.IdMapExemplar.report();
                    return 1;
                }))
            .then(VineCommand.literal("tck_design_probe")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.design.DesignProbeExemplar.report();
                    return 1;
                }))
            .then(VineCommand.literal("tck_handshake_match")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.net.HandshakeExemplar.match();
                    return 1;
                }))
            .then(VineCommand.literal("tck_handshake_optional")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.net.HandshakeExemplar.optionalMismatch();
                    return 1;
                }))
            .then(VineCommand.literal("tck_handshake_require")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.net.HandshakeExemplar.requireMismatch();
                    return 1;
                }))
            .then(VineCommand.literal("tck_handshake_vanilla")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.net.HandshakeExemplar.vanillaJoin();
                    return 1;
                }))
            .then(VineCommand.literal("tck_join")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.net.SyncChunkExemplar.join();
                    return 1;
                }))
            .then(VineCommand.literal("tck_bulk")
                .then(VineCommand.argument("kib", VineArgumentTypes.STRING)
                    .executes(ctx -> {
                        dev.vineengine.vine.testmod.net.SyncChunkExemplar.bulk(
                            Integer.parseInt(ctx.argument("kib", String.class)));
                        return 1;
                    })))
            .then(VineCommand.literal("tck_flood")
                .then(VineCommand.argument("count", VineArgumentTypes.STRING)
                    .executes(ctx -> {
                        dev.vineengine.vine.testmod.net.ValidationExemplar.flood(
                            Integer.parseInt(ctx.argument("count", String.class)));
                        return 1;
                    })))
            .then(VineCommand.literal("tck_fuzz")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.net.ValidationExemplar.fuzz();
                    return 1;
                }))
            .then(VineCommand.literal("tck_codecs")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.net.CodecExemplar.runProof();
                    return 1;
                }))
            .then(VineCommand.literal("tck_features")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.feature.FeatureExemplar.runProof();
                    return 1;
                }))
            .then(VineCommand.literal("tck_fixture_write")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.fixture.FixtureExemplar.write();
                    return 1;
                }))
            .then(VineCommand.literal("tck_fixture_read")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.fixture.FixtureExemplar.read();
                    return 1;
                }))
            .build(VineId.of("vinetest", MOD_ID)));
    }
}
