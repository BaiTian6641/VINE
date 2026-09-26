package dev.vineengine.vine.internal.driver1211.fabric.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.path.SymlinkValidationException;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;

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
 * The scripted client run for the 1.21.1 Fabric cell (sub-21's client half, sub-16/17's
 * verification path): drives an authored {@link ClientScript} through a real dev client —
 * create a single-player world with cheats, run the steps against the live client and its
 * integrated server, save the screenshots, quit.
 *
 * <p><b>Client-dist only.</b> Referenced exclusively from {@link
 * dev.vineengine.vine.internal.driver1211.fabric.VineFabricClient}, which Fabric loads from
 * the {@code "client"} entrypoint — a dedicated server never touches this class, and the load
 * is what the dist rule asks for rather than an annotation.
 *
 * <p><b>Trigger.</b> {@link #installIfRequested} arms the driver only when
 * {@code -Dvine.tck.clientScript} names a file; with the property absent this class does
 * nothing at all and the client behaves exactly like a normal dev client (no auto-quit).
 *
 * <p><b>Failures are loud and terminal.</b> Every step either completes or stops the run with
 * one line naming the step and the reason, and the process exits non-zero. A step the cell
 * cannot perform — an {@code open_screen} naming an unregistered descriptor, say — is refused
 * rather than skipped: a script's whole purpose is to prove that the thing it names happened.
 *
 * <p><b>Two honest notes on step implementations.</b>
 * <ul>
 *   <li>{@code open_screen} is sub-16's client half: the descriptor's native screen is
 *       materialized by {@link VineScreensClient} on this client and closed again
 *       {@code waitTicks} after it opened — the hold the following steps can look at, not a
 *       sleep in this one. {@link #beginOpenScreen} states it in full, because a step's
 *       semantics have to be readable from the step.</li>
 *   <li>{@code play_cutscene} triggers on the integrated server for this client's own player —
 *       the viewer the frame will actually be addressed to. The cutscene's clock is not the
 *       step's: this run advances whatever cutscene is playing once per client tick
 *       ({@link VineCutscenes#tick()}, issued on the server thread) across every step, because
 *       the runtime is pure and has no clock of its own. Keeping it out of the step is what
 *       lets a script screenshot a cinematic mid-play; keeping it out of the *driver* is what
 *       keeps the sub-23 scenario's own frame counts and sample ticks true (it drives the
 *       exemplar, with no client and no scripted run anywhere near it).</li>
 *   <li>{@code command} runs the text through the server's own dispatcher with the player's
 *       command source — byte for byte the work a chat command does once its packet lands
 *       ({@code CommandManager.executeWithPrefix(player.getCommandSource(), …)}) — except
 *       that a parse/permission refusal surfaces as a failed step instead of a line in the
 *       player's chat. With no integrated server (a remote server) it falls back to the plain
 *       chat path, where the result is the remote server's to report.</li>
 *   <li>{@code select_slot} and {@code attack} are the script's own weapon swings (sub-10's
 *       client proof). {@code select_slot} states which hotbar slot the hand holds — locally
 *       <em>and</em> on the server, because {@code /give} fills the first free slot and the
 *       engine resolves a weapon's profile from the server's main hand. {@code attack} presses
 *       the client's own attack key, because a swing issued any other way would bypass a
 *       combat partner that intercepts the client's attack input; both steps' own notes say so
 *       in full.</li>
 * </ul>
 * A cutscene's visual application is {@code VineCutsceneClient}'s job (sub-23's client half,
 * armed from the client entrypoint): this class proves the step runs and the client receives,
 * the receiver proves the camera, title and sound were applied.
 */
public final class ClientScriptRunner {

    /** The system property the harness sets; absent means "an ordinary dev client". */
    public static final String SCRIPT_PROPERTY = "vine.tck.clientScript";

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientScriptRunner.class);

    /** The one line every failure prints, so a harness can find it without parsing the log. */
    private static final String FAILED = "VINE client script FAILED";

    /** 60 s for the client to finish its first resource load and show a screen. */
    private static final int READY_TIMEOUT_TICKS = 1_200;
    /** 3 min for a freshly created world to generate, load and hand the client its player. */
    private static final int WORLD_TIMEOUT_TICKS = 3_600;
    /** 30 s for a screenshot to reach the disk. */
    private static final int SCREENSHOT_TIMEOUT_TICKS = 600;
    /** 5 s for the server to run a command step. */
    private static final int COMMAND_TIMEOUT_TICKS = 100;
    /** 5 s for a triggered cutscene to report itself playing. */
    private static final int CUTSCENE_START_TIMEOUT_TICKS = 100;
    /** 60 s for a cutscene to end — generous next to the exemplar's 60-tick length. */
    private static final int CUTSCENE_END_TIMEOUT_TICKS = 1_200;
    /**
     * Ticks between two swings of one {@code attack} step. The game's input loop turns one key
     * press into one swing on the following tick, and vanilla's own block-attack cooldown is
     * 10 ticks; a shorter gap would let one press's swing and the next press's swing share a
     * tick, i.e. stop being two attacks.
     */
    private static final int SWING_GAP_TICKS = 10;
    /**
     * 5 s for {@code MinecraftClient.attackCooldown} to reach zero before a swing. The field is
     * seeded with 10000 for every tick a screen is open, so a script that attacks while a screen
     * is still up must be told (see {@link #pollAttack}) instead of pretending it swung.
     */
    private static final int ATTACK_COOLDOWN_WAIT_TICKS = 100;
    /** 8 blocks: how far a swing's own log line looks for the bodies this client holds. */
    private static final double NEARBY_REPORT_RADIUS = 8.0D;

    private final ClientScript script;

    private boolean ready;
    private boolean finished;
    private boolean configured;
    private int readyTicks;
    private int nextStep;
    private int stepTicks;
    private boolean stepBegun;

    /** Client ticks the run has seen — the run's own clock (the screen hold is measured on it). */
    private int runTicks;
    /** The screen an {@code open_screen} step put up, and when the run closes it again. */
    private Screen openScreen;
    private int openScreenOpenedAt;
    private int openScreenCloseAt;

    // Per-step state. One step runs at a time, so these are fields rather than a bag of
    // little objects; each begin() resets the ones its step uses.
    private int waitTicks;
    private File screenshotFile;
    private volatile boolean screenshotWritten;
    private int screenshotSettledTicks;
    private volatile Boolean commandsAllowed;
    private volatile Outcome commandOutcome;
    private volatile Throwable cutsceneFailure;
    private boolean cutsceneStarted;
    private int cutsceneTicks;

    /** The swings an {@code attack} step still owes, and the gap before the next one. */
    private int swingsLeft;
    private int swingGapTicks;
    /** Ticks an {@code attack} step has waited for the client's own attack cooldown. */
    private int attackWaitTicks;

    private ClientScriptRunner(ClientScript script) {
        this.script = script;
    }

    /**
     * Arms the driver when {@code -Dvine.tck.clientScript=<path>} names a script; does
     * nothing otherwise, which is what keeps a plain {@code runClient} a plain client.
     *
     * <p>Called from the client entrypoint. A script that cannot be read or parsed is a
     * failure like any other: one line, non-zero exit, no half-run.
     */
    public static void installIfRequested() {
        String configured = System.getProperty(SCRIPT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path path = Path.of(configured);
        ClientScript script;
        try {
            script = parse(path);
        } catch (RuntimeException e) {
            fail("script '" + configured + "'", e.getMessage());
            return;
        }
        ClientScriptRunner runner = new ClientScriptRunner(script);
        ClientTickEvents.END_CLIENT_TICK.register(runner::tick);
        LOGGER.info("[VINE] client script armed: '{}' ({} steps) from {}",
            script.name(), script.steps().size(), path);
    }

    /** Reads and decodes one authored script; any problem is the caller's failure message. */
    private static ClientScript parse(Path path) {
        String json;
        try {
            json = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("the script could not be read: " + e);
        }
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("the script is not valid JSON: " + e.getMessage());
        }
        DataResult<ClientScript> decoded = ClientScript.CODEC.parse(JsonOps.INSTANCE, element);
        return decoded.result().orElseThrow(() -> new IllegalStateException(
            "the script does not parse: " + decoded.error().map(DataResult.Error::message).orElse("unknown error")));
    }

    /** One client tick: the state machine that carries the run forward. */
    private void tick(MinecraftClient client) {
        if (finished) {
            return;
        }
        try {
            if (!configured) {
                configured = true;
                configure(client);
            }
            if (!ready) {
                readyTicks++;
                if (!clientReady(client)) {
                    if (readyTicks < READY_TIMEOUT_TICKS) {
                        return;
                    }
                    throw new ScriptFailure("the client never finished loading"
                        + " within " + READY_TIMEOUT_TICKS + " ticks (screen: " + screenName(client) + ")");
                }
                ready = true;
                LOGGER.info("[VINE] client ready (screen {}) — starting script '{}'", screenName(client), script.name());
            }
            if (nextStep >= script.steps().size()) {
                // A run that never quits is a hung harness, not a result: say so instead.
                throw new ScriptFailure("the script ended without a quit step, so the client would never exit");
            }
            advanceCutsceneClock(client);
            runTicks++;
            ClientScript.Step step = script.steps().get(nextStep);
            if (!stepBegun) {
                stepBegun = true;
                stepTicks = 0;
                if (begin(client, step)) {
                    complete(step);
                } else {
                    stepTicks++;
                    if (poll(client, step)) {
                        complete(step);
                    }
                }
                closeScreenIfDue(client);
                return;
            }
            stepTicks++;
            if (poll(client, step)) {
                complete(step);
            }
            closeScreenIfDue(client);
        } catch (Throwable t) {
            fail(stepLabel(), t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    /**
     * Puts the client into the state a scripted run needs, once, as early as a client exists.
     *
     * <p>{@code pauseOnLostFocus} is the important one: vanilla pauses the integrated server
     * when the window loses focus, and a paused server stops draining its task queue — so
     * every step that has to reach the server (a command, the cutscene trigger) would sit
     * until it timed out. Observed exactly once, when a sibling client window took focus on
     * this machine. A harness-launched client cannot depend on being focused, and the world is
     * supposed to keep ticking regardless (the contract's "wait(N) advances N client ticks"
     * only means something if the world under them is alive).
     */
    private static void configure(MinecraftClient client) {
        client.options.pauseOnLostFocus = false;
        LOGGER.info("[VINE] scripted client: pause-on-lost-focus disabled so the world keeps ticking");
    }

    /** Whether the integrated server is paused — the one state that starves server steps. */
    private static boolean serverPaused(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        return server != null && server.isPaused();
    }

    /** Whether the client has finished loading and has a screen to act on. */
    private static boolean clientReady(MinecraftClient client) {
        // The first resource load finishing is the real gate: from then on the client has a
        // screen (title screen, or the first-run accessibility onboarding, which create_world
        // simply replaces) and the data packs a new level needs are loaded.
        return client.isFinishedLoading()
            || client.currentScreen instanceof TitleScreen
            || (client.world != null && client.player != null);
    }

    private void complete(ClientScript.Step step) {
        LOGGER.info("[VINE] step {} ok: {}", nextStep + 1, describe(step));
        nextStep++;
        stepBegun = false;
        stepTicks = 0;
    }

    /**
     * Issues whatever the step needs issuing, returning true when that alone satisfies the
     * step. A false return puts the step into {@link #poll}, which decides when it is done.
     */
    private boolean begin(MinecraftClient client, ClientScript.Step step) {
        if (step instanceof ClientScript.Step.Wait wait) {
            waitTicks = wait.ticks();
            return waitTicks == 0;
        }
        if (step instanceof ClientScript.Step.Command command) {
            beginCommand(client, command);
            return false;
        }
        if (step instanceof ClientScript.Step.SelectSlot slot) {
            beginSelectSlot(client, slot);
            return true;
        }
        if (step instanceof ClientScript.Step.Attack attack) {
            beginAttack(client, attack);
            return false;
        }
        if (step instanceof ClientScript.Step.CreateWorld world) {
            beginCreateWorld(client, world);
            return false;
        }
        if (step instanceof ClientScript.Step.Screenshot screenshot) {
            beginScreenshot(client, screenshot);
            return false;
        }
        if (step instanceof ClientScript.Step.PlayCutscene cutscene) {
            beginCutscene(client, cutscene);
            return false;
        }
        if (step instanceof ClientScript.Step.OpenScreen screen) {
            beginOpenScreen(client, screen);
            // The screen is up: the step completes here and the hold is the runner's clock
            // (see beginOpenScreen for why the wait is not a sleep in this step).
            return true;
        }
        if (step instanceof ClientScript.Step.Quit) {
            if (nextStep + 1 < script.steps().size()) {
                LOGGER.warn("[VINE] quit is step {} of {}: the remaining {} step(s) are unreachable",
                    nextStep + 1, script.steps().size(), script.steps().size() - nextStep - 1);
            }
            LOGGER.info("[VINE] client script '{}' complete — shutting the client down", script.name());
            finished = true;
            client.scheduleStop();
            return true;
        }
        throw new ScriptFailure("unknown step type " + step.getClass().getName());
    }

    /** Whether the current step is done yet. */
    private boolean poll(MinecraftClient client, ClientScript.Step step) {
        if (step instanceof ClientScript.Step.Wait) {
            // One client tick per call: wait(N) lets exactly N of them pass, with the world
            // ticking underneath rather than a slept thread.
            waitTicks--;
            return waitTicks <= 0;
        }
        if (step instanceof ClientScript.Step.Command) {
            return pollCommand(client);
        }
        if (step instanceof ClientScript.Step.Attack attack) {
            return pollAttack(client, attack);
        }
        if (step instanceof ClientScript.Step.CreateWorld) {
            return pollCreateWorld(client);
        }
        if (step instanceof ClientScript.Step.Screenshot) {
            return pollScreenshot(client);
        }
        if (step instanceof ClientScript.Step.PlayCutscene cutscene) {
            return pollCutscene(client, cutscene);
        }
        return true;   // quit and open_screen: begin() already completed the step
    }

    // ---------------------------------------------------------------------------------------
    // command
    // ---------------------------------------------------------------------------------------

    private void beginCommand(MinecraftClient client, ClientScript.Step.Command step) {
        String text = step.command().startsWith("/") ? step.command().substring(1) : step.command();
        if (text.isBlank()) {
            throw new ScriptFailure("the command is empty");
        }
        MinecraftServer server = client.getServer();
        if (server == null) {
            // A remote server's dispatcher and result are not ours to inspect: send the chat
            // command a human would type and let the remote server report.
            ClientPlayNetworkHandler handler = client.getNetworkHandler();
            if (handler == null) {
                throw new ScriptFailure("the client is not connected to a server");
            }
            commandOutcome = Outcome.sent();
            handler.sendChatCommand(text);
            LOGGER.info("[VINE] command (sent as chat, result not observable here): /{}", text);
            return;
        }
        if (client.player == null) {
            throw new ScriptFailure("the client has no player to run the command as");
        }
        java.util.UUID viewer = client.player.getUuid();
        commandOutcome = null;
        server.execute(() -> commandOutcome = runAsPlayer(server, viewer, text));
        LOGGER.info("[VINE] command: /{}", text);
    }

    /** The server thread's half of {@code command}: the same call the chat packet path makes. */
    private static Outcome runAsPlayer(MinecraftServer server, java.util.UUID playerUuid, String text) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player == null) {
            return Outcome.failed("the player is not on the server");
        }
        CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
        ParseResults<ServerCommandSource> parsed;
        try {
            parsed = dispatcher.parse(text, player.getCommandSource());
            // Unknown commands and refusals are parse-time facts: throw rather than let the
            // dispatcher's own swallowing path turn them into a chat line nobody asserts on.
            CommandManager.throwException(parsed);
        } catch (CommandSyntaxException e) {
            return Outcome.failed(e.getMessage());
        } catch (RuntimeException e) {
            return Outcome.failed(e.toString());
        }
        try {
            return Outcome.ran(dispatcher.execute(parsed));
        } catch (CommandSyntaxException e) {
            return Outcome.failed(e.getMessage());
        } catch (RuntimeException e) {
            return Outcome.failed(e.toString());
        }
    }

    private boolean pollCommand(MinecraftClient client) {
        Outcome outcome = commandOutcome;
        if (outcome == null) {
            return timeout(client, COMMAND_TIMEOUT_TICKS, "the server did not run the command");
        }
        if (outcome.error != null) {
            throw new ScriptFailure("the command failed: " + outcome.error);
        }
        if (outcome.result == 0) {
            // Performed, but nothing acted: worth saying, not worth failing a run over —
            // commands that legitimately report 0 exist (an `execute if`, a query).
            LOGGER.warn("[VINE] command reported no result (0)");
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // select_slot, attack — the script's own weapon swings
    // ---------------------------------------------------------------------------------------

    /**
     * Puts {@code step}'s hotbar slot in the player's hand — the step a script needs before it
     * can swing a weapon it just spawned, because {@code /give} fills the first free inventory
     * slot rather than the hand.
     *
     * <p><b>Two halves, because a hand has two.</b> The local
     * {@code PlayerInventory.selectedSlot} is what every client-side decision reads — the stack
     * the vanilla attack path swings and the one a combat partner looks up its weapon attributes
     * for — and the {@code UpdateSelectedSlotC2SPacket} is what makes the <em>server's</em>
     * player hold the same stack, which is the one the engine resolves a combat profile from
     * ({@code FabricCombatHooks.weapon}). The packet is sent here rather than left to vanilla's
     * own sync-on-attack so the server's hand is already right when the swing leaves this
     * client; vanilla's cache-driven sync may send the same packet again, which is harmless.
     *
     * <p>Nothing is guessed about what the slot holds: a script that names an empty slot gets a
     * swing with an empty hand, and the step's own log line names the stack it selected.
     */
    private void beginSelectSlot(MinecraftClient client, ClientScript.Step.SelectSlot step) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            throw new ScriptFailure("the client has no player whose slot could be selected");
        }
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) {
            throw new ScriptFailure("the client is not connected to a server, so a selected slot has nowhere"
                + " to go — this step cannot be a local-only change");
        }
        player.getInventory().selectedSlot = step.slot();
        handler.sendPacket(new UpdateSelectedSlotC2SPacket(step.slot()));
        LOGGER.info("[VINE] select_slot {}: the player's hand is now '{}'", step.slot(),
            player.getMainHandStack().getItem());
    }

    /**
     * Arms an {@code attack} step: {@code step.swings()} swings at whatever the player is
     * looking at. The swings themselves are issued from {@link #pollAttack}, one per
     * {@link #SWING_GAP_TICKS} ticks.
     */
    private void beginAttack(MinecraftClient client, ClientScript.Step.Attack step) {
        if (client.player == null) {
            throw new ScriptFailure("the client has no player to swing");
        }
        swingsLeft = step.swings();
        swingGapTicks = 0;
        attackWaitTicks = 0;
    }

    /**
     * One swing per {@link #SWING_GAP_TICKS} ticks, at whatever the player is looking at.
     *
     * <p><b>A swing is the client's own attack input, and it has to be.</b> The swing is issued
     * by pressing the attack key exactly as a click does ({@link KeyBinding#onKeyPressed}), and
     * the game's own input loop ({@code MinecraftClient.handleInputEvents}) turns that press into
     * one {@code MinecraftClient.doAttack} on the following tick. An attack issued any other way
     * — calling {@code ClientPlayerInteractionManager.attackEntity} directly, say — would
     * silently bypass a combat partner that intercepts the client's attack input, and a partner
     * the script claims to prove would then never have been involved. What a swing
     * <em>hits</em> stays the server's decision: this step only says the player swung.
     *
     * <p><b>The client's own bookkeeping, named rather than tripped over.</b>
     * {@code MinecraftClient.doAttack} returns without swinging while
     * {@code MinecraftClient.attackCooldown > 0}, and the client seeds that field with 10000 for
     * every tick a screen is open — a script that attacked with a screen still up would swing
     * into nothing, and a run that only checked the wound would read that as "the weapon is
     * broken". So the step waits for the field to reach zero and, if it does not within
     * {@link #ATTACK_COOLDOWN_WAIT_TICKS} ticks, fails loudly naming the value and the screen
     * that is holding it up. The gap between presses is the second half: the input loop drains
     * every pending press in one tick, so presses issued together would be one attack, not
     * several.
     */
    private boolean pollAttack(MinecraftClient client, ClientScript.Step.Attack step) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            throw new ScriptFailure("the client's player is gone mid-swing");
        }
        if (swingGapTicks > 0) {
            swingGapTicks--;
            return false;
        }
        if (swingsLeft == 0) {
            return true;
        }
        if (client.attackCooldown > 0) {
            if (attackWaitTicks++ >= ATTACK_COOLDOWN_WAIT_TICKS) {
                throw new ScriptFailure("the client's own attack cooldown (" + client.attackCooldown
                    + ") never reached zero in " + ATTACK_COOLDOWN_WAIT_TICKS + " ticks, so"
                    + " MinecraftClient.doAttack would swallow the swing (screen: "
                    + screenName(client) + ")");
            }
            return false;
        }
        KeyBinding.onKeyPressed(client.options.attackKey.getDefaultKey());
        swingsLeft--;
        swingGapTicks = SWING_GAP_TICKS;
        LOGGER.info("[VINE] attack {}: swing {} of {} at {} (hand: '{}')", step.swings(),
            step.swings() - swingsLeft, step.swings(), lookTarget(client), player.getMainHandStack().getItem());
        return false;
    }

    /**
     * What the player's crosshair is on — the swing's target, as the log line can name it.
     *
     * <p>When the crosshair is on nothing, the line also names the nearest body the
     * <em>client</em> knows about. A swing that hits nothing is either the wrong aim or an actor
     * this client was never sent, and the wound alone cannot tell the two apart; the distance to
     * the client's nearest entity can.
     */
    private static String lookTarget(MinecraftClient client) {
        if (client.crosshairTarget instanceof EntityHitResult hit) {
            return "entity '" + hit.getEntity().getName().getString() + "'";
        }
        String what = (client.crosshairTarget == null ? "no crosshair target"
            : client.crosshairTarget.getType().name().toLowerCase(java.util.Locale.ROOT))
            + " from " + describeClientPlayer(client) + ", client holds " + nearbyEntities(client);
        return what;
    }

    /** The client's own player: where it is and where it is looking — the swing's origin. */
    private static String describeClientPlayer(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return "no local player";
        }
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f yaw %.1f pitch %.1f",
            player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
    }

    /**
     * Every entity within {@link #NEARBY_REPORT_RADIUS} blocks that this client holds, nearest
     * first, plus every engine entity this client holds anywhere — the list that decides whether
     * a miss is the aim's fault or the client's.
     */
    private static String nearbyEntities(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        java.util.List<net.minecraft.entity.Entity> nearby = new java.util.ArrayList<>();
        java.util.List<net.minecraft.entity.Entity> engine = new java.util.ArrayList<>();
        int total = 0;
        for (net.minecraft.entity.Entity entity : client.world.getEntities()) {
            total++;
            if (entity != player && entity.squaredDistanceTo(player) <= NEARBY_REPORT_RADIUS * NEARBY_REPORT_RADIUS) {
                nearby.add(entity);
            }
            if (net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).getNamespace().equals("vine")
                || net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).getNamespace()
                    .equals("vine_test")) {
                engine.add(entity);
            }
        }
        nearby.sort(java.util.Comparator.comparingDouble(entity -> entity.squaredDistanceTo(player)));
        engine.sort(java.util.Comparator.comparingDouble(entity -> entity.squaredDistanceTo(player)));
        return total + " entities, " + named(nearby, " within " + NEARBY_REPORT_RADIUS + " blocks")
            + ", " + named(engine, " engine entities");
    }

    /** {@code count} of {@code entities} named with their ids and positions, nearest first. */
    private static String named(java.util.List<net.minecraft.entity.Entity> entities, String what) {
        if (entities.isEmpty()) {
            return "0" + what;
        }
        StringBuilder out = new StringBuilder(entities.size() + what + ":");
        for (int i = 0; i < entities.size() && i < 4; i++) {
            net.minecraft.entity.Entity entity = entities.get(i);
            out.append(i == 0 ? " " : ", ").append(net.minecraft.registry.Registries.ENTITY_TYPE.getId(
                entity.getType())).append(" at ")
                .append(String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                    entity.getX(), entity.getY(), entity.getZ()));
        }
        return entities.size() > 4 ? out.append(", +").append(entities.size() - 4).append(" more").toString()
            : out.toString();
    }

    // ---------------------------------------------------------------------------------------
    // create_world
    // ---------------------------------------------------------------------------------------

    private void beginCreateWorld(MinecraftClient client, ClientScript.Step.CreateWorld step) {
        Screen parent = client.currentScreen;
        // The create-world screen is the client's own factory for a GeneratorOptionsHolder:
        // it loads the data packs and builds the registry view a new level needs, so the run
        // creates a world the same way a human does instead of reassembling that state here.
        CreateWorldScreen.create(client, parent);
        if (!(client.currentScreen instanceof CreateWorldScreen screen)) {
            throw new ScriptFailure("the create-world screen did not open");
        }
        WorldCreator creator = screen.getWorldCreator();
        creator.setWorldName(step.worldName());
        creator.setCheatsEnabled(true);
        // Creative on purpose: a scripted run must not be able to die, drown or fall out of
        // the world it is measuring. Cheats (above) are what the script's commands need.
        creator.setGameMode(WorldCreator.Mode.CREATIVE);
        if (!step.generateTerrain()) {
            creator.setWorldType(flatPreset(creator));
            creator.setGenerateStructures(false);
        }
        if (!creator.areCheatsEnabled()) {
            throw new ScriptFailure("could not enable cheats on the new world");
        }
        // The script's world name *is* the save folder, and the folder is deleted before it is
        // created — that pair is what makes "create_world" mean "a new world".
        //
        // Not {@code creator.getWorldDirectoryName()}: that answers the first *free* name, so a
        // second run silently becomes "vine-tck (2)", a third "(3)" … — the world is fresh by
        // accident of the folder being new, every earlier world stays on disk, and nothing here
        // would notice if a run ever resolved to a folder that already existed. The session is
        // opened for the name the script asked for, the same name the level carries, and the
        // delete below is the guarantee rather than a side effect. (The other cell's
        // create-world path — {@code WorldOpenFlows.createFreshLevel} — *opens* an existing save
        // of that name, which is why the delete is the step both cells must take.)
        String folder = step.worldName().trim();
        Path saveDirectory = client.getLevelStorage().getSavesDirectory().resolve(folder);
        deleteSave(saveDirectory);
        GeneratorOptionsHolder holder = creator.getGeneratorOptionsHolder();
        LevelInfo levelInfo = new LevelInfo(creator.getWorldName().trim(), creator.getGameMode().defaultGameMode,
            creator.isHardcore(), creator.getDifficulty(), creator.areCheatsEnabled(), creator.getGameRules(),
            holder.dataConfiguration());
        commandsAllowed = null;
        LOGGER.info("[VINE] create_world: '{}' -> {} (cheats={}, mode={}, terrain={})",
            step.worldName(), saveDirectory, levelInfo.areCommandsAllowed(), levelInfo.getGameMode(),
            step.generateTerrain() ? "generated" : "flat");
        startWorld(client, folder, levelInfo, holder);
    }

    /**
     * Removes {@code save}'s tree, deepest entry first, so the world created next starts with
     * nothing of the previous run — no inventory, no entities, no forceload state, no stale
     * {@code session.lock}. A save that is not there is the common case for the first run of a
     * fresh checkout, and deleting nothing is exactly right then.
     *
     * <p><b>Why a refusal here is fatal to the step.</b> A save this cell cannot delete is a
     * save the next world would inherit state from, which is the one thing a scripted
     * acceptance may not silently accept — so the failure is named, with the path and the
     * reason, rather than logged and stepped over.
     */
    private static void deleteSave(Path save) {
        if (!Files.exists(save)) {
            return;
        }
        try {
            deleteRecursively(save);
        } catch (IOException e) {
            throw new ScriptFailure("cannot delete the previous save at " + save + " — create_world"
                + " would inherit its inventory, entities and forceload state: " + e);
        }
        LOGGER.info("[VINE] create_world: deleted the previous save at {}", save);
    }

    /** Removes one path, recursing into a directory — the client has no world open here. */
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

    /**
     * Creates and starts the world on the client's integrated server, the way
     * {@code CreateWorldScreen} itself does it: the holder's dimension options are folded into
     * a fresh {@code DIMENSIONS} registry layer, and both the layer and the data-pack contents
     * come from the same holder the level info did.
     *
     * <p>Why not {@code IntegratedServerLoader.createAndStart}: its dimensions supplier is
     * handed a registry manager from its <em>own</em> data-pack load, so a holder built by the
     * create-world screen names dimension types in a different registry instance — the server
     * then cannot encode the join packet and the player is disconnected with "Can't find id
     * for … in map" (observed). Creating the session and calling {@code startNewWorld} keeps
     * one registry set end to end, which is what vanilla's {@code createLevel → startServer →
     * startNewWorld} chain does.
     */
    private static void startWorld(MinecraftClient client, String folder, LevelInfo levelInfo,
            GeneratorOptionsHolder holder) {
        var dimensions = holder.selectedDimensions().toConfig(holder.dimensionOptionsRegistry());
        var registries = holder.combinedDynamicRegistries()
            .with(ServerDynamicRegistryType.DIMENSIONS, dimensions.toDynamicRegistryManager());
        Lifecycle lifecycle = FeatureFlags.isNotVanilla(holder.dataConfiguration().enabledFeatures())
            ? Lifecycle.experimental()
            : Lifecycle.stable();
        LevelProperties properties = new LevelProperties(levelInfo, holder.generatorOptions(),
            dimensions.specialWorldProperty(),
            registries.getCombinedRegistryManager().getRegistryLifecycle().add(lifecycle));
        LevelStorage.Session session;
        try {
            session = client.getLevelStorage().createSession(folder);
        } catch (IOException | SymlinkValidationException e) {
            throw new ScriptFailure("the save folder for '" + folder + "' could not be opened: " + e);
        }
        try {
            client.createIntegratedServerLoader().startNewWorld(session, holder.dataPackContents(), registries,
                properties);
        } catch (Throwable t) {
            session.tryClose();
            throw new ScriptFailure("the world '" + folder + "' could not be started: " + t);
        }
    }

    private static WorldCreator.WorldType flatPreset(WorldCreator creator) {
        for (WorldCreator.WorldType type : creator.getNormalWorldTypes()) {
            RegistryEntry<WorldPreset> preset = type.preset();
            if (preset != null && preset.matchesKey(WorldPresets.FLAT)) {
                return type;
            }
        }
        throw new ScriptFailure("this client has no flat world preset to select");
    }

    private boolean pollCreateWorld(MinecraftClient client) {
        boolean loaded = client.world != null && client.player != null
            && !(client.currentScreen instanceof LevelLoadingScreen);
        if (!loaded) {
            return timeout(client, WORLD_TIMEOUT_TICKS, "the world did not load");
        }
        MinecraftServer server = client.getServer();
        if (commandsAllowed == null) {
            if (server == null) {
                throw new ScriptFailure("the integrated server is not running");
            }
            server.execute(() -> commandsAllowed = server.getSaveProperties().areCommandsAllowed());
            return false;
        }
        if (!commandsAllowed) {
            throw new ScriptFailure("the world loaded without cheats — the script's commands would be refused");
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // screenshot
    // ---------------------------------------------------------------------------------------

    private void beginScreenshot(MinecraftClient client, ClientScript.Step.Screenshot step) {
        screenshotWritten = false;
        screenshotSettledTicks = 0;
        screenshotFile = new File(new File(client.runDirectory, ScreenshotRecorder.SCREENSHOTS_DIRECTORY),
            step.name() + ".png");
        LOGGER.info("[VINE] screenshot: {}", screenshotFile);
        ScreenshotRecorder.saveScreenshot(client.runDirectory, step.name() + ".png", client.getFramebuffer(),
            message -> screenshotWritten = true);
    }

    private boolean pollScreenshot(MinecraftClient client) {
        if (!screenshotWritten) {
            return timeout(client, SCREENSHOT_TIMEOUT_TICKS, "the screenshot was not taken");
        }
        // The write happens on the IO worker and the callback follows it; the tick of grace
        // is for a filesystem whose size is not visible the instant the stream closed.
        if ((!screenshotFile.isFile() || screenshotFile.length() == 0L) && ++screenshotSettledTicks < 20) {
            return false;
        }
        if (!screenshotFile.isFile() || screenshotFile.length() == 0L) {
            throw new ScriptFailure("the screenshot at " + screenshotFile + " is missing or empty");
        }
        LOGGER.info("[VINE] screenshot written: {} ({} bytes)", screenshotFile, screenshotFile.length());
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // open_screen
    // ---------------------------------------------------------------------------------------

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
     * {@code waitTicks} after this one.
     *
     * <p><b>Everything here is client-side.</b> A descriptor is content; this client is told to
     * draw it. Nothing about opening a screen is sent to any server.
     *
     * <p><b>Failures are the step's.</b> An unregistered id, and a descriptor this cell cannot
     * materialize, both stop the run with one line naming the step and the reason — never a
     * silent skip, which would make the proof the script exists for worthless.
     */
    private void beginOpenScreen(MinecraftClient client, ClientScript.Step.OpenScreen step) {
        ScreenDescriptor descriptor = VineRegistries.get(VineContent.SCREEN_TYPE, step.screen())
            .map(Holder::value)
            .orElseThrow(() -> new ScriptFailure("cannot open screen " + step.screen()
                + ": no screen descriptor is registered under that id in this cell"));
        Screen screen;
        try {
            screen = VineScreensClient.materialize(descriptor);
        } catch (RuntimeException e) {
            throw new ScriptFailure("cannot open screen " + step.screen() + ": materializing its descriptor failed: "
                + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        openScreen = screen;
        openScreenOpenedAt = runTicks;
        openScreenCloseAt = runTicks + step.waitTicks();
        client.setScreen(screen);
        LOGGER.info("[VINE] open_screen {}: {} widget(s), pausesGame={}, closing in {} client tick(s)",
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
    private void closeScreenIfDue(MinecraftClient client) {
        Screen held = openScreen;
        if (held == null || runTicks < openScreenCloseAt) {
            return;
        }
        openScreen = null;
        if (client.currentScreen != held) {
            LOGGER.info("[VINE] the screen this run opened is no longer current — nothing to close");
            return;
        }
        client.setScreen(null);
        LOGGER.info("[VINE] screen closed {} client tick(s) after it opened", runTicks - openScreenOpenedAt);
    }

    // ---------------------------------------------------------------------------------------
    // play_cutscene
    // ---------------------------------------------------------------------------------------

    private void beginCutscene(MinecraftClient client, ClientScript.Step.PlayCutscene step) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            throw new ScriptFailure("no integrated server: this cell cannot play a cutscene for a remote client");
        }
        if (client.player == null) {
            throw new ScriptFailure("the client has no player to watch the cutscene");
        }
        java.util.UUID viewer = client.player.getUuid();
        cutsceneFailure = null;
        cutsceneStarted = false;
        cutsceneTicks = 0;
        server.execute(() -> {
            try {
                VineCutscenes.play(step.cutscene(), java.util.Set.of(viewer));
            } catch (Throwable t) {
                // The trigger's own refusal (an unregistered cutscene, say) reaches the step
                // through pollCutscene as a named failure rather than a stack trace in a log.
                cutsceneFailure = t;
            }
        });
        LOGGER.info("[VINE] play_cutscene: {} for viewer {}", step.cutscene(), viewer);
    }

    private boolean pollCutscene(MinecraftClient client, ClientScript.Step.PlayCutscene step) {
        if (cutsceneFailure != null) {
            throw new ScriptFailure("playing " + step.cutscene() + " failed: " + cutsceneFailure.getMessage());
        }
        cutsceneTicks++;
        boolean playing = VineCutscenes.playing();
        if (!cutsceneStarted) {
            // Until the server's play() lands, "not playing" means "not yet"; the moment it
            // plays the step advances from waiting-for-start to waiting-for-end. The two must
            // not be confused: a cutscene the step itself advanced to its end also reports
            // "not playing", and reading that as "never started" would fail a run that worked
            // (observed, before this flag existed).
            if (!playing) {
                if (cutsceneTicks < CUTSCENE_START_TIMEOUT_TICKS) {
                    return false;
                }
                throw new ScriptFailure("cutscene " + step.cutscene() + " never reported itself playing");
            }
            cutsceneStarted = true;
            LOGGER.info("[VINE] cutscene {} started for {} viewer(s)", step.cutscene(), VineCutscenes.viewers().size());
            if (!step.waitForEnd()) {
                return true;
            }
        } else if (!playing) {
            LOGGER.info("[VINE] cutscene {} ended on client tick {}", step.cutscene(), cutsceneTicks);
            return true;
        }
        if (client.getServer() == null) {
            throw new ScriptFailure("the integrated server stopped while the cutscene was playing");
        }
        return timeout(client, CUTSCENE_END_TIMEOUT_TICKS, "cutscene " + step.cutscene() + " never ended");
    }

    /**
     * Advances whatever cutscene is playing, once per client tick — the scripted run's own
     * clock for sub-23's runtime, which is pure and has no clock of its own.
     *
     * <p><b>Why it is here and not inside {@code play_cutscene}.</b> A step only completes when
     * it is done, so a clock owned by the step stops the moment the step ends — a script could
     * then never screenshot a cinematic mid-play (the frame the screenshot would capture would
     * always be the last one the step advanced to, or the first one after it). Ticking from the
     * run's tick loop instead advances the clock across *every* step, so {@code play_cutscene}
     * with {@code waitForEnd: false} followed by a {@code wait} lands the screenshot anywhere in
     * the cinematic. The tick is issued on the server thread because the runtime is server state.
     */
    private static void advanceCutsceneClock(MinecraftClient client) {
        if (!VineCutscenes.playing()) {
            return;
        }
        MinecraftServer server = client.getServer();
        if (server != null) {
            server.execute(VineCutscenes::tick);
        }
    }

    // ---------------------------------------------------------------------------------------
    // plumbing
    // ---------------------------------------------------------------------------------------

    /** True once the step has waited out {@code limit} client ticks; the failure names where. */
    private boolean timeout(MinecraftClient client, int limit, String what) {
        if (stepTicks < limit) {
            return false;
        }
        throw new ScriptFailure(what + " within " + limit + " client ticks (screen: " + screenName(client)
            + ", world: " + (client.world != null) + ", player: " + (client.player != null)
            + ", server paused: " + serverPaused(client) + ")");
    }

    private static String screenName(MinecraftClient client) {
        return client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName();
    }

    private String stepLabel() {
        if (nextStep >= script.steps().size()) {
            return "the end of script '" + script.name() + "'";
        }
        return "step " + (nextStep + 1) + " (" + describe(script.steps().get(nextStep)) + ")";
    }

    private static String describe(ClientScript.Step step) {
        if (step instanceof ClientScript.Step.Wait wait) {
            return "wait " + wait.ticks();
        }
        if (step instanceof ClientScript.Step.Command command) {
            return "command \"" + command.command() + "\"";
        }
        if (step instanceof ClientScript.Step.SelectSlot slot) {
            return "select_slot " + slot.slot();
        }
        if (step instanceof ClientScript.Step.Attack attack) {
            return "attack " + attack.swings();
        }
        if (step instanceof ClientScript.Step.CreateWorld world) {
            return "create_world \"" + world.worldName() + "\"";
        }
        if (step instanceof ClientScript.Step.Screenshot screenshot) {
            return "screenshot \"" + screenshot.name() + "\"";
        }
        if (step instanceof ClientScript.Step.PlayCutscene cutscene) {
            return "play_cutscene " + cutscene.cutscene() + " (waitForEnd=" + cutscene.waitForEnd() + ")";
        }
        if (step instanceof ClientScript.Step.OpenScreen screen) {
            return "open_screen " + screen.screen() + " (waitTicks=" + screen.waitTicks() + ")";
        }
        return step.kind();
    }

    /** Prints the one failure line and ends the process non-zero — never a silent stop. */
    private static void fail(String where, String reason) {
        String line = FAILED + " at " + where + ": " + reason;
        System.out.println(line);
        System.out.flush();
        LOGGER.error("[VINE] {}", line);
        // halt, not exit: a failed run must not run Minecraft's shutdown sequence (a world
        // save and a GL teardown racing the still-live render thread — the observed way to
        // turn a nice "exit 1" into a native abort), and it must report the code we chose.
        Runtime.getRuntime().halt(1);
    }

    /** A step refusing to run: the step's own report of why, already user-readable. */
    private static final class ScriptFailure extends RuntimeException {
        ScriptFailure(String message) {
            super(message);
        }
    }

    /** What the server reported for a {@code command} step. */
    private record Outcome(String error, int result) {

        static Outcome ran(int result) {
            return new Outcome(null, result);
        }

        static Outcome failed(String error) {
            return new Outcome(error, -1);
        }

        static Outcome sent() {
            return new Outcome(null, -1);
        }
    }
}
