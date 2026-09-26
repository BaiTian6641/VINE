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

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.client.ClientScript;
import dev.vineengine.vine.cutscene.VineCutscenes;

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

    private enum Phase {
        AWAIT_READY, RUN, WAIT, AWAIT_WORLD, AWAIT_CUTSCENE, AWAIT_SCREENSHOT, DONE
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
            switch (phase) {
                case AWAIT_READY -> {
                    if (clientReady(minecraft)) {
                        phase = Phase.RUN;
                        runSteps(minecraft);
                    }
                }
                case RUN -> runSteps(minecraft);
                case WAIT -> {
                    if (--remainingTicks <= 0) {
                        phase = Phase.RUN;
                        index++;
                        runSteps(minecraft);
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
                    // The engine has no server-tick driver for cutscenes yet (nothing calls
                    // VineCutscenes#tick), so the run advances the clock it is waiting on. When
                    // a server-side tick lands this degrades to a plain poll.
                    VineCutscenes.tick();
                    if (!VineCutscenes.playing()) {
                        phase = Phase.RUN;
                        index++;
                        runSteps(minecraft);
                    } else if (clock > deadline) {
                        throw new StepFailure(stepName(index), "the cutscene was still playing after "
                            + (CUTSCENE_TIMEOUT_TICKS / 20) + "s");
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
                    phase = Phase.WAIT;
                    return;
                }
                case ClientScript.Step.Command command -> {
                    runCommand(minecraft, command.command());
                    index++;
                    continue;
                }
                case ClientScript.Step.PlayCutscene cutscene -> {
                    playCutscene(minecraft, cutscene);
                    if (!cutscene.waitForEnd()) {
                        index++;
                        continue;
                    }
                    phase = Phase.AWAIT_CUTSCENE;
                    deadline = clock + CUTSCENE_TIMEOUT_TICKS;
                    return;
                }
                case ClientScript.Step.OpenScreen screen -> throw new StepFailure(
                    "open_screen(" + screen.screen() + ")",
                    "this cell has no screen materialization yet — opening a Vine screen is sub-16 work");
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

    private void createWorld(Minecraft minecraft, ClientScript.Step.CreateWorld step) {
        // Cheats on: the script's commands are the point, and an integrated server without
        // them refuses every one of them. Creative + peaceful keeps a scripted run from
        // being ended by a mob while a step is waiting.
        LevelSettings settings = new LevelSettings(step.worldName(), GameType.CREATIVE, false, Difficulty.PEACEFUL,
            true, new GameRules(), WorldDataConfiguration.DEFAULT);
        WorldOpenFlows flows = minecraft.createWorldOpenFlows();
        flows.createFreshLevel(step.worldName(), settings, WorldOptions.defaultWithRandomSeed(),
            registry -> dimensions(registry, step.generateTerrain()), null);
        LOGGER.info("vine-tck: world '{}' requested (cheats on, terrain={})", step.worldName(), step.generateTerrain());
    }

    /** The named world preset's dimensions — normal terrain, or flat when the script asks. */
    private static WorldDimensions dimensions(RegistryAccess registry, boolean generateTerrain) {
        return registry.registryOrThrow(Registries.WORLD_PRESET)
            .getHolderOrThrow(generateTerrain ? WorldPresets.NORMAL : WorldPresets.FLAT)
            .value()
            .createWorldDimensions();
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

    private static void playCutscene(Minecraft minecraft, ClientScript.Step.PlayCutscene step) {
        if (minecraft.player == null) {
            throw new StepFailure("play_cutscene(" + step.cutscene() + ")", "there is no client player to watch it");
        }
        // The integrated server shares this JVM, so the engine call reaches the same runtime
        // the server tick would; the viewer is this player, which is what makes the cutscene
        // something this client could apply once the visual half lands.
        UUID viewer = minecraft.player.getUUID();
        VineCutscenes.play(step.cutscene(), Set.of(viewer));
        LOGGER.info("vine-tck: cutscene {} playing for {}", step.cutscene(), viewer);
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
