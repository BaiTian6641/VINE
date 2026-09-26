package dev.vineengine.vine.client;

import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * A scripted client run (sub-21's client half, sub-16/17's verification): the steps a
 * development client performs so a feature can be shown working without a human holding the
 * mouse.
 *
 * <p><b>Why a script and not a test.</b> A client's interesting behaviour — a world loads, a
 * screen opens, a cinematic plays, a weapon swings — is only observable in a real client with
 * a real renderer. A script is the smallest thing that can drive one: each cell implements
 * {@link Step#kind()} for the handful of steps that need loader APIs, and every step that is
 * engine data (which cutscene, which screen, which command) comes from this one vocabulary, so
 * the two cells run the same file rather than two files that drift.
 *
 * <p>Steps are executed in order; a step that cannot be performed stops the run with a
 * readable failure rather than being skipped, because a script's whole purpose is to prove
 * that the thing it names actually happened.
 */
public record ClientScript(String name, List<Step> steps) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ClientScript> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("name").forGetter(ClientScript::name),
        Step.CODEC.listOf().fieldOf("steps").forGetter(ClientScript::steps)
    ).apply(instance, ClientScript::new));

    public ClientScript {
        Objects.requireNonNull(name, "name");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("ClientScript " + name + " has no steps — a run that does nothing"
                + " proves nothing");
        }
    }

    /**
     * One step of a client run. Sealed: every step is something the engine can name and a
     * cell can implement, and an unknown step is an authoring error the parser reports rather
     * than a silently ignored line.
     */
    public sealed interface Step permits Step.CreateWorld, Step.Wait, Step.Command, Step.PlayCutscene,
            Step.OpenScreen, Step.Screenshot, Step.Quit {

        /** The {@code "type"} discriminator an authored step writes. */
        String kind();

        /** Single source of truth for every representation of this data (sub-02 §2). */
        Codec<Step> CODEC = Codec.STRING.dispatch("type", Step::kind, Step::codecFor);

        private static com.mojang.serialization.MapCodec<? extends Step> codecFor(String kind) {
            return switch (kind) {
                case "create_world" -> CreateWorld.CODEC;
                case "wait" -> Wait.CODEC;
                case "command" -> Command.CODEC;
                case "play_cutscene" -> PlayCutscene.CODEC;
                case "open_screen" -> OpenScreen.CODEC;
                case "screenshot" -> Screenshot.CODEC;
                case "quit" -> Quit.CODEC;
                default -> throw new IllegalArgumentException("unknown client script step '" + kind + "' — expected"
                    + " one of: create_world, wait, command, play_cutscene, open_screen, screenshot, quit");
            };
        }

        /** Creates a single-player world (or joins one, when the script names a server). */
        record CreateWorld(String worldName, boolean generateTerrain) implements Step {

            /** Single source of truth for every representation of this data (sub-02 §2). */
            public static final com.mojang.serialization.MapCodec<CreateWorld> CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.STRING.optionalFieldOf("worldName", "vine-tck").forGetter(CreateWorld::worldName),
                    Codec.BOOL.optionalFieldOf("generateTerrain", true).forGetter(CreateWorld::generateTerrain)
                ).apply(instance, CreateWorld::new));

            @Override
            public String kind() {
                return "create_world";
            }
        }

        /** Waits {@code ticks} client ticks — a world needs a moment before anything works. */
        record Wait(int ticks) implements Step {

            /** Single source of truth for every representation of this data (sub-02 §2). */
            public static final com.mojang.serialization.MapCodec<Wait> CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.INT.fieldOf("ticks").forGetter(Wait::ticks)
                ).apply(instance, Wait::new));

            public Wait {
                if (ticks < 0) {
                    throw new IllegalArgumentException("Wait: ticks must be non-negative, got " + ticks);
                }
            }

            @Override
            public String kind() {
                return "wait";
            }
        }

        /** Runs a command as the client's player — the same text a human would type. */
        record Command(String command) implements Step {

            /** Single source of truth for every representation of this data (sub-02 §2). */
            public static final com.mojang.serialization.MapCodec<Command> CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.STRING.fieldOf("command").forGetter(Command::command)
                ).apply(instance, Command::new));

            public Command {
                Objects.requireNonNull(command, "command");
                if (command.isBlank()) {
                    throw new IllegalArgumentException("Command: the command must not be blank");
                }
            }

            @Override
            public String kind() {
                return "command";
            }
        }

        /** Plays a cutscene for this client's player, then waits for it to finish. */
        record PlayCutscene(VineId cutscene, boolean waitForEnd) implements Step {

            /** Single source of truth for every representation of this data (sub-02 §2). */
            public static final com.mojang.serialization.MapCodec<PlayCutscene> CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                    VineId.CODEC.fieldOf("cutscene").forGetter(PlayCutscene::cutscene),
                    Codec.BOOL.optionalFieldOf("waitForEnd", true).forGetter(PlayCutscene::waitForEnd)
                ).apply(instance, PlayCutscene::new));

            public PlayCutscene {
                Objects.requireNonNull(cutscene, "cutscene");
            }

            @Override
            public String kind() {
                return "play_cutscene";
            }
        }

        /**
         * Opens a screen descriptor for the client's player: the step completes once the screen
         * is up, and the run closes it again {@code waitTicks} client ticks after it opened — the
         * wait is how long the screen stays up for the steps that follow (a screenshot), not a
         * sleep inside this step. A cell that cannot materialize the descriptor fails the run
         * loudly; it never pretends the screen opened.
         */
        record OpenScreen(VineId screen, int waitTicks) implements Step {

            /** Single source of truth for every representation of this data (sub-02 §2). */
            public static final com.mojang.serialization.MapCodec<OpenScreen> CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                    VineId.CODEC.fieldOf("screen").forGetter(OpenScreen::screen),
                    Codec.INT.optionalFieldOf("waitTicks", 20).forGetter(OpenScreen::waitTicks)
                ).apply(instance, OpenScreen::new));

            public OpenScreen {
                Objects.requireNonNull(screen, "screen");
                if (waitTicks < 0) {
                    throw new IllegalArgumentException("OpenScreen: waitTicks must be non-negative, got " + waitTicks);
                }
            }

            @Override
            public String kind() {
                return "open_screen";
            }
        }

        /** Saves a screenshot under {@code name} (the cell adds its own directory and extension). */
        record Screenshot(String name) implements Step {

            /** Single source of truth for every representation of this data (sub-02 §2). */
            public static final com.mojang.serialization.MapCodec<Screenshot> CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.STRING.fieldOf("name").forGetter(Screenshot::name)
                ).apply(instance, Screenshot::new));

            public Screenshot {
                Objects.requireNonNull(name, "name");
                if (name.isBlank()) {
                    throw new IllegalArgumentException("Screenshot: the name must not be blank");
                }
            }

            @Override
            public String kind() {
                return "screenshot";
            }
        }

        /** Ends the run: the client shuts down and the process exits 0. */
        record Quit() implements Step {

            /** Single source of truth for every representation of this data (sub-02 §2). */
            public static final com.mojang.serialization.MapCodec<Quit> CODEC =
                com.mojang.serialization.MapCodec.unit(new Quit());

            @Override
            public String kind() {
                return "quit";
            }
        }
    }

}
