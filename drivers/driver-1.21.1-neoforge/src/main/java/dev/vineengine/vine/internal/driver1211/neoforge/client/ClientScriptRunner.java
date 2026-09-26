package dev.vineengine.vine.internal.driver1211.neoforge.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.client.ClientScript;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.cutscene.VineCutscenes;
import dev.vineengine.vine.internal.ui.UiLayout;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.ui.ScreenDescriptor;

/**
 * The 1.21.1 NeoForge client half of the scripted run (sub-21 Stage F): drives a real dev
 * client through a {@link ClientScript}, then quits it.
 *
 * <p><b>Why the client owns this and not the engine.</b> Every step here is a thing only a
 * client can do — create a single-player world, type a command as the player, save the
 * framebuffer — and the script's loader-neutral vocabulary is parsed by the engine's own
 * codec, so both cells run the same file. The runner is armed by the entry point only when
 * {@code -Dvine.tck.clientScript=<path>} is set ({@link #PROPERTY}); otherwise the driver's
 * client class never even constructs it and a dev client behaves exactly as before.
 *
 * <p><b>Waiting means ticking.</b> {@code wait(N)} and every "is it done yet" phase poll on
 * client ticks, never sleep the thread: the world only advances because the client keeps
 * rendering and ticking, and a blocked thread would deadlock the integrated server. That is
 * also why the pause-on-lost-focus menu is turned off — an unfocused window must not pause
 * the world the script is waiting on.
 *
 * <p><b>{@code play_cutscene} (sub-23's client half).</b> The step triggers on the integrated
 * server for this client's own player — the viewer the frame will actually be addressed to —
 * and the run advances whatever cutscene is playing once per client tick
 * ({@link VineCutscenes#tick()}, issued on the server thread) across every step, because the
 * runtime is pure and has no clock of its own. Keeping the clock out of the step is what lets
 * a script screenshot a cinematic mid-play; keeping it out of the <em>driver</em> is what keeps
 * the sub-23 scenario's own frame counts and sample ticks true. A cutscene's visual application
 * is {@code VineCutsceneClient}'s job (armed from the client entrypoint): this class proves the
 * step runs and the client receives, the receiver proves the camera, title and sound were
 * applied.
 *
 * <p><b>{@code open_screen} (sub-16's client half).</b> The descriptor's native screen is
 * materialized by {@link VineScreensClient} on this client and closed again {@code waitTicks}
 * after it opened — the hold the following steps can look at, not a sleep in this one.
 * {@link #beginOpenScreen} states it in full, because a step's semantics have to be readable
 * from the step.
 */
public final class ClientScriptRunner {

    /** The system property the client entry point keys off. */
    public static final String PROPERTY = "vine.tck.clientScript";

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientScriptRunner.class);

    /** A first world has to generate its spawn chunks; two minutes is generous, not a target. */
    private static final int WORLD_LOAD_TIMEOUT_TICKS = 20 * 120;

    /** The screenshot write is async on the client's IO pool; a handful of ticks normally. */
    private static final int SCREENSHOT_TIMEOUT_TICKS = 20 * 30;

    /** A cutscene longer than this is a bug in the script, not a slow cinematic. */
    private static final int CUTSCENE_TIMEOUT_TICKS = 20 * 120;

    /** 5 s for a triggered cutscene to report itself playing. */
    private static final int CUTSCENE_START_TIMEOUT_TICKS = 100;

    /**
     * How long a {@code wait} keeps asking for world ticks after its own client ticks are up.
     * A server that has not run the asked-for ticks within this window is stalled rather than
     * merely behind, and a stalled server must leave a line in the log rather than hang the
     * run — the same reason every other phase has a deadline.
     */
    private static final int WAIT_SERVER_GRACE_TICKS = 20 * 30;

    /**
     * Ticks between the swings of one {@code attack} step: each swing is its own attack input,
     * spaced further apart than vanilla's own mis-swing lockout ({@code missTime}, 10 ticks)
     * so the client's cooldown bookkeeping cannot silently swallow the next one.
     */
    private static final int SWING_SPACING_TICKS = 12;

    private enum Phase {
        AWAIT_READY, RUN, WAIT, ATTACK, AWAIT_WORLD, AWAIT_CUTSCENE, AWAIT_SCREENSHOT, DONE
    }

    private final Path scriptFile;
    private ClientScript script;
    private Phase phase = Phase.AWAIT_READY;
    private int index;
    private int remainingTicks;
    private long clock;
    private long deadline;
    private Path screenshotFile;
    private boolean failed;

    /** The play_cutscene step the run is currently inside, while it waits for start/end. */
    private ClientScript.Step.PlayCutscene pendingCutscene;
    /** The trigger's own refusal (an unregistered cutscene), surfaced as a named failure. */
    private volatile Throwable cutsceneFailure;
    /** Whether the triggered cutscene has been observed playing — "not yet" and "ended" differ. */
    private boolean cutsceneStarted;
    /** Client ticks spent inside the current play_cutscene step. */
    private int cutsceneTicks;

    /** The screen an {@code open_screen} step put up, and when the run closes it again. */
    private Screen openScreen;
    private long openScreenOpenedAt;
    private long openScreenCloseAt;

    /** The {@code wait} step's outstanding world (integrated-server) ticks. */
    private int remainingServerTicks;
    /** The server tick this wait last saw — a wait counts ticks the world actually ran. */
    private long lastServerTick = -1L;
    /** When a wait stops insisting on world ticks: a stalled server must not hang the run. */
    private long waitServerDeadline;
    /** Where this wait started, so its log line can say how much the world actually ran. */
    private long waitStartedAt;
    private long waitServerStart;

    /** The {@code attack} step's pending swings once the first has been issued, and when. */
    private int swingsRemaining;
    private long nextSwingAt;
    /** Client tick at which the last swing's effect is read back, and the strength before it. */
    private long swingProbeAt = -1L;
    private float strengthBeforeSwing;

    public ClientScriptRunner(Path scriptFile) {
        this.scriptFile = Objects.requireNonNull(scriptFile, "scriptFile");
    }

    /** Advances the run one client tick. Called from the client tick event on the game thread. */
    public void tick(Minecraft minecraft) {
        if (failed || phase == Phase.DONE) {
            return;
        }
        clock++;
        try {
            if (script == null) {
                script = load();
                // An unfocused window must not open the pause screen: a paused world would
                // stop ticking (breaking `wait`) and put the menu in every screenshot.
                minecraft.options.pauseOnLostFocus = false;
                LOGGER.info("vine-tck: client script '{}' armed ({} steps from {})", script.name(),
                    script.steps().size(), scriptFile);
            }
            // The cutscene's clock (sub-23): the runtime is pure and has no clock of its own, and
            // this cell drives it from no server tick, so the scripted run advances whatever is
            // playing once per client tick — across *every* step, which is what lets a script
            // screenshot a cinematic mid-play (a clock owned by the play_cutscene step would stop
            // the moment that step ended). Issued on the server thread: the runtime is server state.
            advanceCutsceneClock(minecraft);
            // Read the previous swing's effect back: a landed swing resets the attack strength
            // and starts the swing animation, so a strength that did not move means the client
            // never turned the click into an attack.
            if (clock == swingProbeAt) {
                swingProbeAt = -1L;
                if (minecraft.player != null) {
                    LOGGER.info("vine-tck: swing aftermath at {} strength={} (was {}) swinging={} screen={}"
                        + " usingItem={}", describeCrosshair(minecraft.hitResult),
                        minecraft.player.getAttackStrengthScale(0.0F), strengthBeforeSwing,
                        minecraft.player.swinging, minecraft.screen, minecraft.player.isUsingItem());
                }
            }
            switch (phase) {
                case AWAIT_READY -> {
                    if (clientReady(minecraft)) {
                        phase = Phase.RUN;
                        runSteps(minecraft);
                    }
                }
                case RUN -> runSteps(minecraft);
                case WAIT -> {
                    // "Ticks" means ticks the world ran, not merely frames the client drew. The
                    // client's loop and the integrated server's are separate, so a client that is
                    // ahead of a lagging server (world generation, a chunk-load stall) can burn a
                    // whole `wait` between two of the server's ticks — and then two steps that
                    // were meant to be ticks apart, a `forceload` and the spawn that needs it,
                    // reach the server in the *same* tick and the second finds the world exactly
                    // as the first left it. A wait is therefore done when it has seen both its
                    // own client ticks and that many server ticks; the client count stays the
                    // floor, so rendering and animation timing are unchanged.
                    if (remainingTicks > 0) {
                        remainingTicks--;
                    }
                    if (remainingServerTicks > 0 && serverTicked(minecraft)) {
                        remainingServerTicks--;
                    }
                    if (remainingTicks > 0) {
                        return;
                    }
                    if (remainingServerTicks > 0 && clock < waitServerDeadline) {
                        return;
                    }
                    if (remainingServerTicks > 0) {
                        LOGGER.warn("vine-tck: wait: the integrated server ran {} fewer tick(s) than asked for"
                            + " within {}s; continuing anyway", remainingServerTicks, WAIT_SERVER_GRACE_TICKS / 20);
                    } else {
                        LOGGER.info("vine-tck: wait done: {} server tick(s) ran, {} client tick(s) spent",
                            serverTick(minecraft) - waitServerStart, clock - waitStartedAt);
                    }
                    phase = Phase.RUN;
                    index++;
                    runSteps(minecraft);
                }
                case ATTACK -> {
                    // One input per swing, spaced by SWING_SPACING_TICKS: the click is consumed
                    // by the client's own keybind tick, so each swing is a distinct attack and
                    // there is no second step that could race the hit's landing.
                    if (clock >= nextSwingAt) {
                        if (swingsRemaining > 0) {
                            swing(minecraft);
                            swingsRemaining--;
                            nextSwingAt = clock + SWING_SPACING_TICKS;
                        } else {
                            phase = Phase.RUN;
                            index++;
                            runSteps(minecraft);
                        }
                    }
                }
                case AWAIT_WORLD -> {
                    if (worldReady(minecraft)) {
                        phase = Phase.RUN;
                        index++;
                        runSteps(minecraft);
                    } else if (clock > deadline) {
                        throw new StepFailure(stepName(index), "the single-player world did not load within "
                            + (WORLD_LOAD_TIMEOUT_TICKS / 20) + "s");
                    }
                }
                case AWAIT_CUTSCENE -> {
                    // The clock is advanced once per client tick by advanceCutsceneClock, so this
                    // phase only watches the runtime: is the cutscene the step triggered playing
                    // yet, and has it ended? "Not playing" before the trigger lands means "not
                    // yet", never "already over" — the two must not be confused, or a cutscene the
                    // step itself advances to its end would fail a run that worked.
                    if (cutsceneFailure != null) {
                        throw new StepFailure(stepName(index),
                            "playing " + pendingCutscene.cutscene() + " failed: " + cutsceneFailure.getMessage());
                    }
                    cutsceneTicks++;
                    boolean playing = VineCutscenes.playing();
                    if (!cutsceneStarted) {
                        if (!playing) {
                            if (cutsceneTicks < CUTSCENE_START_TIMEOUT_TICKS) {
                                return;
                            }
                            throw new StepFailure(stepName(index),
                                "cutscene " + pendingCutscene.cutscene() + " never reported itself playing");
                        }
                        cutsceneStarted = true;
                        LOGGER.info("vine-tck: cutscene {} started for {} viewer(s)", pendingCutscene.cutscene(),
                            VineCutscenes.viewers().size());
                        if (!pendingCutscene.waitForEnd()) {
                            phase = Phase.RUN;
                            index++;
                            runSteps(minecraft);
                            return;
                        }
                    } else if (!playing) {
                        LOGGER.info("vine-tck: cutscene {} ended on client tick {}", pendingCutscene.cutscene(),
                            cutsceneTicks);
                        phase = Phase.RUN;
                        index++;
                        runSteps(minecraft);
                        return;
                    }
                    if (minecraft.getSingleplayerServer() == null) {
                        throw new StepFailure(stepName(index),
                            "the integrated server stopped while the cutscene was playing");
                    }
                    if (clock > deadline) {
                        throw new StepFailure(stepName(index), "cutscene " + pendingCutscene.cutscene()
                            + " was still playing after " + (CUTSCENE_TIMEOUT_TICKS / 20) + "s");
                    }
                }
                case AWAIT_SCREENSHOT -> {
                    if (screenshotWritten()) {
                        phase = Phase.RUN;
                        index++;
                        runSteps(minecraft);
                    } else if (clock > deadline) {
                        throw new StepFailure(stepName(index),
                            "no non-empty screenshot was written to " + screenshotFile);
                    }
                }
                case DONE -> {
                }
            }
            // The other half of open_screen's semantics: a screen this run put up is closed
            // again once its hold is up, whatever step the run has moved on to (see
            // closeScreenIfDue). Checked after the step machine so a screen opened this tick is
            // measured from this tick.
            closeScreenIfDue(minecraft);
        } catch (StepFailure failure) {
            fail(failure);
        } catch (RuntimeException unexpected) {
            fail(new StepFailure(stepName(index), String.valueOf(unexpected)));
        }
    }

    /** Executes steps until one needs the world to move, then waits. */
    private void runSteps(Minecraft minecraft) {
        while (true) {
            if (index >= script.steps().size()) {
                complete();
                return;
            }
            ClientScript.Step step = script.steps().get(index);
            switch (step) {
                case ClientScript.Step.CreateWorld create -> {
                    createWorld(minecraft, create);
                    phase = Phase.AWAIT_WORLD;
                    deadline = clock + WORLD_LOAD_TIMEOUT_TICKS;
                    return;
                }
                case ClientScript.Step.Wait wait -> {
                    if (wait.ticks() == 0) {
                        index++;
                        continue;
                    }
                    remainingTicks = wait.ticks();
                    // Only a wait that has a world to advance asks for world ticks: every script
                    // wait follows `create_world`, but the runner must not invent a server.
                    remainingServerTicks = minecraft.getSingleplayerServer() == null ? 0 : wait.ticks();
                    waitStartedAt = clock;
                    waitServerStart = serverTick(minecraft);
                    lastServerTick = waitServerStart;
                    waitServerDeadline = clock + wait.ticks() + WAIT_SERVER_GRACE_TICKS;
                    phase = Phase.WAIT;
                    return;
                }
                case ClientScript.Step.Command command -> {
                    runCommand(minecraft, command.command());
                    index++;
                    continue;
                }
                case ClientScript.Step.ServerCommand serverCommand -> {
                    runServerCommand(minecraft, stepName(index), serverCommand.command());
                    index++;
                    continue;
                }
                case ClientScript.Step.SelectSlot slot -> {
                    selectSlot(minecraft, slot);
                    index++;
                    continue;
                }
                case ClientScript.Step.Attack attack -> {
                    beginAttack(minecraft, attack);
                    // The step owns the world from here: the ATTACK phase issues the remaining
                    // swings on their tick boundaries and completes the step after the last.
                    return;
                }
                case ClientScript.Step.PlayCutscene cutscene -> {
                    beginCutscene(minecraft, cutscene);
                    // Even with waitForEnd=false the step waits for the start before it completes:
                    // a step that returned as soon as the trigger was queued would let the next
                    // step (a `wait`, then the screenshot) run against a cinematic that had not
                    // begun. The AWAIT_CUTSCENE poll advances it for waitForEnd=false.
                    phase = Phase.AWAIT_CUTSCENE;
                    deadline = clock + CUTSCENE_TIMEOUT_TICKS;
                    return;
                }
                case ClientScript.Step.OpenScreen screen -> {
                    beginOpenScreen(minecraft, screen);
                    // The step is done — the screen is up — but the run must not begin the next
                    // step inside this same tick's frame. Vanilla ticks and *then* renders, so
                    // the framebuffer a screenshot reads during a tick holds the previous
                    // frame: a following screenshot grabbed later in this tick would show the
                    // world before the screen was drawn, and the run could never show the screen
                    // it exists to prove. Yielding here lets that frame render first, which is
                    // what "the next step's screenshot sees the screen" means (sub-16's client
                    // half; the Fabric cell's one-step-per-tick runner has the same boundary by
                    // construction).
                    index++;
                    return;
                }
                case ClientScript.Step.Screenshot screenshot -> {
                    requestScreenshot(minecraft, screenshot.name());
                    phase = Phase.AWAIT_SCREENSHOT;
                    deadline = clock + SCREENSHOT_TIMEOUT_TICKS;
                    return;
                }
                case ClientScript.Step.Quit ignored -> {
                    complete();
                    return;
                }
            }
        }
    }

    private static boolean clientReady(Minecraft minecraft) {
        return minecraft.getOverlay() == null && minecraft.level == null && minecraft.getConnection() == null;
    }

    private static boolean worldReady(Minecraft minecraft) {
        return minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null
            && minecraft.hasSingleplayerServer();
    }

    /** The integrated server's tick count, or -1 while there is no server to count. */
    private static long serverTick(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        return server == null ? -1L : server.getTickCount();
    }

    /** Whether the integrated server advanced since this wait last looked. */
    private boolean serverTicked(Minecraft minecraft) {
        long now = serverTick(minecraft);
        if (now < 0L || now == lastServerTick) {
            return false;
        }
        lastServerTick = now;
        return true;
    }

    private void createWorld(Minecraft minecraft, ClientScript.Step.CreateWorld step) {
        // Cheats on: the script's commands are the point, and an integrated server without
        // them refuses every one of them. Creative + peaceful keeps a scripted run from
        // being ended by a mob while a step is waiting.
        LevelSettings settings = new LevelSettings(step.worldName(), GameType.CREATIVE, false, Difficulty.PEACEFUL,
            true, new GameRules(), WorldDataConfiguration.DEFAULT);
        // A *fresh* world, not the last run's. `createFreshLevel` opens an existing save of the
        // same name instead of replacing it, so a second run would inherit the first run's
        // inventory, entities and forceload state — a real leak (one run swung the previous
        // run's weapon because the hotbar it selected was still the old one). Deleting the save
        // first is what makes the script's world name mean "a new world".
        Path save = minecraft.gameDirectory.toPath().resolve("saves").resolve(step.worldName());
        try {
            if (Files.exists(save)) {
                deleteRecursively(save);
                LOGGER.info("vine-tck: deleted the previous world at {}", save);
            }
        } catch (IOException e) {
            throw new StepFailure("create_world(" + step.worldName() + ")",
                "cannot delete the previous save at " + save + ": " + e.getMessage());
        }
        WorldOpenFlows flows = minecraft.createWorldOpenFlows();
        flows.createFreshLevel(step.worldName(), settings, WorldOptions.defaultWithRandomSeed(),
            registry -> dimensions(registry, step.generateTerrain()), null);
        LOGGER.info("vine-tck: world '{}' requested (cheats on, terrain={})", step.worldName(), step.generateTerrain());
    }

    /** Removes a save tree, deepest entry first — the client holds no world open at create time. */
    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (java.util.stream.Stream<Path> children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /** The named world preset's dimensions — normal terrain, or flat when the script asks. */
    private static WorldDimensions dimensions(RegistryAccess registry, boolean generateTerrain) {
        return registry.registryOrThrow(Registries.WORLD_PRESET)
            .getHolderOrThrow(generateTerrain ? WorldPresets.NORMAL : WorldPresets.FLAT)
            .value()
            .createWorldDimensions();
    }

    /**
     * Runs {@code text} on the integrated server with console authority and returns whether its
     * result count was non-zero.
     *
     * <p>Console authority is the point: a player-source command may resolve entity selectors in
     * a context that sees no server entities (measured on this cell: {@code @e[type=...]} matched
     * nothing while the same command as the server matched), so a fixture that asserts server
     * truth has to speak as the server.
     */
    private static void runServerCommand(Minecraft minecraft, String step, String text) {
        net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new StepFailure(step, "no integrated server to run a server_command on");
        }
        String command = text.startsWith("/") ? text.substring(1) : text;
        LOGGER.info("vine-tck: server_command /{}", command);
        // Through the dispatcher rather than performPrefixedCommand: only this returns the result
        // count, which is what makes a fixture predicate an assertion.
        int result;
        try {
            result = server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new StepFailure(step, "server_command did not parse: /" + command + " — " + e.getMessage());
        }
        LOGGER.info("vine-tck: server_command result={}", result);
        if (result == 0) {
            throw new StepFailure(step, "server_command returned 0 (expected the command to have an"
                + " effect): /" + command);
        }
    }

    private static void runCommand(Minecraft minecraft, String text) {
        if (minecraft.player == null) {
            throw new StepFailure("command(" + text + ")", "there is no client player to run it");
        }
        // The step's leading '/' is optional; the packet wants the text without it.
        String command = text.startsWith("/") ? text.substring(1) : text;
        LOGGER.info("vine-tck: command /{}", command);
        minecraft.player.connection.sendCommand(command);
    }

    /**
     * Puts {@code step}'s hotbar slot in the player's hand — the same assignment the hotbar
     * keys make, so the carried-item packet travels the client's own game-mode tick and the
     * server's hand holds what this client does.
     *
     * <p><b>Why a step and not a command.</b> {@code /give} fills the first free slot rather
     * than the hand, and a scripted client can only swing what it holds; selecting the slot is
     * the smallest input that makes "swing this weapon" expressible.
     */
    private static void selectSlot(Minecraft minecraft, ClientScript.Step.SelectSlot step) {
        if (minecraft.player == null) {
            throw new StepFailure(selectSlotStep(step), "there is no client player to hold a slot");
        }
        minecraft.player.getInventory().selected = step.slot();
        LOGGER.info("vine-tck: select_slot {}: holding {}", step.slot(),
            minecraft.player.getMainHandItem().getItem());
    }

    /**
     * Begins {@code step}: issues its first swing now and leaves the rest to the {@link
     * Phase#ATTACK} phase, which issues one input per swing on its own tick boundary and then
     * advances the run. The step is done only after its last swing has had its spacing to be
     * consumed and sent, so a following step cannot race the hit.
     */
    private void beginAttack(Minecraft minecraft, ClientScript.Step.Attack step) {
        swingsRemaining = step.swings();
        swing(minecraft);
        swingsRemaining--;
        nextSwingAt = clock + SWING_SPACING_TICKS;
        phase = Phase.ATTACK;
        LOGGER.info("vine-tck: attack: {} swing(s), {} client tick(s) apart", step.swings(), SWING_SPACING_TICKS);
    }

    /**
     * Issues one attack input — {@link KeyMapping#click} on the attack binding, which is what a
     * left-click does: the client's own keybind tick consumes it and runs {@code startAttack},
     * so the swing travels the vanilla input path (and, when a partner is installed, whatever
     * hook the partner puts on that path) instead of a second attack mechanism this cell
     * invented. What a swing <em>hits</em> stays the server's own answer.
     */
    private void swing(Minecraft minecraft) {
        if (minecraft.player == null) {
            throw new StepFailure(stepName(index), "there is no client player to swing");
        }
        // What this swing is aimed at, read from the client's own pick: a scripted swing that
        // finds nothing is a miss, and "it swung at a miss" is the difference between a
        // tuning problem and a bug. The client state is logged with it because a swing that
        // the client never turns into an attack input looks exactly like a miss in the wound.
        strengthBeforeSwing = minecraft.player.getAttackStrengthScale(0.0F);
        LOGGER.info("vine-tck: swing at {} holding {} screen={} usingItem={} strength={}",
            describeCrosshair(minecraft.hitResult), minecraft.player.getMainHandItem().getItem(),
            minecraft.screen, minecraft.player.isUsingItem(), strengthBeforeSwing);
        KeyMapping.click(minecraft.options.keyAttack.getDefaultKey());
        swingProbeAt = clock + 1;
    }

    /** What the client's crosshair pick names, for the swing log line. */
    private static String describeCrosshair(HitResult hit) {
        if (hit == null) {
            return "nothing";
        }
        return switch (hit.getType()) {
            case ENTITY -> {
                net.minecraft.world.entity.Entity picked = ((EntityHitResult) hit).getEntity();
                yield "entity " + picked.getType() + "#" + picked.getUUID() + " at " + picked.position()
                    + " [" + picked.getClass().getSimpleName() + "]";
            }
            case BLOCK -> "block " + ((BlockHitResult) hit).getBlockPos();
            case MISS -> "miss";
        };
    }

    /** The select_slot step's name in a failure line: which slot. */
    private static String selectSlotStep(ClientScript.Step.SelectSlot step) {
        return "select_slot(" + step.slot() + ")";
    }

    /**
     * Triggers the step's cutscene for this client's own player — the viewer the frame will
     * actually be addressed to. The call is handed to the integrated server's thread because the
     * runtime is server state; the thread it runs on is therefore the one a real server tick
     * would have produced the frames on.
     */
    private void beginCutscene(Minecraft minecraft, ClientScript.Step.PlayCutscene step) {
        if (minecraft.player == null) {
            throw new StepFailure("play_cutscene(" + step.cutscene() + ")", "there is no client player to watch it");
        }
        var server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new StepFailure("play_cutscene(" + step.cutscene() + ")",
                "no integrated server: this cell cannot play a cutscene for a remote client");
        }
        UUID viewer = minecraft.player.getUUID();
        pendingCutscene = step;
        cutsceneFailure = null;
        cutsceneStarted = false;
        cutsceneTicks = 0;
        server.execute(() -> {
            try {
                VineCutscenes.play(step.cutscene(), Set.of(viewer));
            } catch (Throwable t) {
                // The trigger's own refusal (an unregistered cutscene, say) reaches the step
                // through the poll as a named failure rather than a stack trace in a log.
                cutsceneFailure = t;
            }
        });
        LOGGER.info("vine-tck: play_cutscene {} for viewer {}", step.cutscene(), viewer);
    }

    /**
     * Advances whatever cutscene is playing, once per client tick — the scripted run's own clock
     * for sub-23's runtime, which is pure and has no clock of its own.
     *
     * <p><b>Why it is here and not inside {@code play_cutscene}.</b> A step only completes when it
     * is done, so a clock owned by the step stops the moment the step ends — a script could then
     * never screenshot a cinematic mid-play (the frame the screenshot would capture would always
     * be the last one the step advanced to, or the first one after it). Ticking from the run's
     * tick loop instead advances the clock across <em>every</em> step, so {@code play_cutscene}
     * with {@code waitForEnd: false} followed by a {@code wait} lands the screenshot anywhere in
     * the cinematic. The tick is issued on the server thread because the runtime is server state.
     */
    private static void advanceCutsceneClock(Minecraft minecraft) {
        if (!VineCutscenes.playing()) {
            return;
        }
        var server = minecraft.getSingleplayerServer();
        if (server != null) {
            server.execute(VineCutscenes::tick);
        }
    }

    /**
     * Opens {@code step}'s screen descriptor for this client's player (sub-16's client half).
     *
     * <p><b>The step's semantics, in full.</b> An {@code open_screen} step completes as soon as
     * the screen is up, and the runner then closes it again {@code waitTicks} client ticks after
     * it opened. The wait is therefore how long the screen stays up <em>for the steps that
     * follow</em> — not a sleep inside this step. That is what makes the step usable for its own
     * purpose: vanilla ticks and then renders within one frame, so a screen closed at the end of
     * a blocking wait would already be absent from the frame a following {@code screenshot}
     * reads, and a run could never show the screen it exists to prove. The close still lands: it
     * is a runner-side deadline, executed by {@link #closeScreenIfDue} on the client tick
     * {@code waitTicks} after this one. For the same frame reason the run resumes the step
     * machine on the <em>next</em> tick (see the step loop): the frame that first shows this
     * screen is rendered after this tick ends, and only then does a screenshot read it.
     *
     * <p><b>Everything here is client-side.</b> A descriptor is content; this client is told to
     * draw it. Nothing about opening a screen is sent to any server.
     *
     * <p><b>Failures are the step's.</b> An unregistered id, and a descriptor this cell cannot
     * materialize, both stop the run with one line naming the step and the reason — never a
     * quietly skipped step.
     */
    private void beginOpenScreen(Minecraft minecraft, ClientScript.Step.OpenScreen step) {
        ScreenDescriptor descriptor = VineRegistries.get(VineContent.SCREEN_TYPE, step.screen())
            .map(Holder::value)
            .orElseThrow(() -> new StepFailure(screenStep(step), "cannot open screen " + step.screen()
                + ": no screen descriptor is registered under that id in this cell"));
        Screen screen;
        try {
            screen = VineScreensClient.materialize(descriptor);
        } catch (RuntimeException e) {
            throw new StepFailure(screenStep(step), "cannot open screen " + step.screen()
                + ": materializing its descriptor failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        openScreen = screen;
        openScreenOpenedAt = clock;
        openScreenCloseAt = clock + step.waitTicks();
        minecraft.setScreen(screen);
        LOGGER.info("vine-tck: open_screen {}: {} widget(s), pausesGame={}, closing in {} client tick(s)",
            step.screen(), UiLayout.widgetIds(descriptor).size(), descriptor.pausesGame(), step.waitTicks());
    }

    /**
     * Closes the screen this run opened once its hold is up — the other half of
     * {@link #beginOpenScreen}'s semantics.
     *
     * <p>A screen that is no longer the current one (a later step opened another, or the run is
     * quitting) is left alone: the close belongs to this run's own screen, and a step that
     * outlives it is not an error.
     */
    private void closeScreenIfDue(Minecraft minecraft) {
        Screen held = openScreen;
        if (held == null || clock < openScreenCloseAt) {
            return;
        }
        openScreen = null;
        if (minecraft.screen != held) {
            LOGGER.info("vine-tck: the screen this run opened is no longer current — nothing to close");
            return;
        }
        minecraft.setScreen(null);
        LOGGER.info("vine-tck: screen closed {} client tick(s) after it opened", clock - openScreenOpenedAt);
    }

    /** The open_screen step's name in a failure line: which screen, and its hold. */
    private static String screenStep(ClientScript.Step.OpenScreen step) {
        return "open_screen(" + step.screen() + ", waitTicks=" + step.waitTicks() + ")";
    }

    private void requestScreenshot(Minecraft minecraft, String name) {
        Path out = minecraft.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR).resolve(name + ".png");
        screenshotFile = out;
        LOGGER.info("vine-tck: screenshot {}", out);
        Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(),
            message -> LOGGER.info("vine-tck: screenshot: {}", message.getString()));
    }

    private boolean screenshotWritten() {
        try {
            return Files.isRegularFile(screenshotFile) && Files.size(screenshotFile) > 0L;
        } catch (IOException e) {
            return false;
        }
    }

    private ClientScript load() {
        String json;
        try {
            json = Files.readString(scriptFile);
        } catch (IOException e) {
            throw new StepFailure("script", "cannot read " + scriptFile + ": " + e.getMessage());
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new StepFailure("script", scriptFile + " did not parse: " + e.getMessage());
        }
        DataResult<ClientScript> decoded = ClientScript.CODEC.parse(JsonOps.INSTANCE, parsed);
        if (decoded.error().isPresent()) {
            throw new StepFailure("script", scriptFile + " did not parse: " + decoded.error().get().message());
        }
        return decoded.result().orElseThrow();
    }

    /** Ends the run: the client shuts down through its own stop path and the process exits 0. */
    private void complete() {
        phase = Phase.DONE;
        LOGGER.info("vine-tck: client script '{}' complete — stopping the client", script.name());
        System.out.println("vine-tck: client script '" + script.name() + "' complete");
        System.out.flush();
        Minecraft.getInstance().stop();
    }

    private void fail(StepFailure failure) {
        failed = true;
        String line = "vine-tck: client script failed at " + failure.step() + ": " + failure.reason();
        LOGGER.error(line);
        System.out.println(line);
        System.out.flush();
        System.exit(1);
    }

    private String stepName(int stepIndex) {
        if (script == null || stepIndex >= script.steps().size()) {
            return "#" + stepIndex;
        }
        return "#" + stepIndex + " " + script.steps().get(stepIndex).kind();
    }

    /** A step that cannot be performed — named, explained, and fatal. */
    private static final class StepFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String step;
        private final String reason;

        StepFailure(String step, String reason) {
            super(step + ": " + reason, null, false, false);
            this.step = step;
            this.reason = reason;
        }

        String step() {
            return step;
        }

        String reason() {
            return reason;
        }
    }
}
