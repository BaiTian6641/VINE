package dev.vineengine.vine.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * A {@link Primitives} that records what it was asked and answers either from a
 * recording (replay) or from a delegate it records (capture) — the instrument behind
 * sub-08 Stage B's determinism claim.
 *
 * <p>Why it exists: a behaviour tree's determinism cannot be checked by re-running it
 * against a live world, because the world is not the same the second time. Record one
 * run's primitive calls, replay them, and the <em>whole trace</em> — statuses,
 * memory, and the call sequence itself — must come out identical; a node that reads
 * a clock or an unseeded random source changes the call sequence, and
 * {@link #replaying} fails loudly on the first divergence instead of quietly
 * producing a different fight.
 *
 * <p><b>Invariants:</b> replay serves results in recorded order and rejects any call
 * that does not match the recording (primitive name and argument both); results are
 * encoded as strings so a recording is plain data that a golden file can hold;
 * a replay that runs out of tape fails rather than inventing an answer.
 */
public final class TapePrimitives implements Primitives {

    /** One recorded primitive call: what was asked, and what came back. */
    public record Call(String primitive, String argument, String result) {

        public Call {
            Objects.requireNonNull(primitive, "primitive");
            Objects.requireNonNull(argument, "argument");
            Objects.requireNonNull(result, "result");
        }
    }

    private final Primitives delegate;
    private final List<Call> calls = new ArrayList<>();
    private final List<Call> tape;
    private int cursor;

    private TapePrimitives(Primitives delegate, List<Call> tape) {
        this.delegate = delegate;
        this.tape = tape == null ? null : List.copyOf(tape);
    }

    /** Captures every call and serves the delegate's own answers. */
    public static TapePrimitives recording(Primitives delegate) {
        return new TapePrimitives(Objects.requireNonNull(delegate, "delegate"), null);
    }

    /** Replays {@code tape}: results come from the recording, and any divergence throws. */
    public static TapePrimitives replaying(List<Call> tape) {
        Objects.requireNonNull(tape, "tape");
        return new TapePrimitives(null, tape);
    }

    /** Every recorded call — the tape, in replay mode. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Whether a replay has consumed its tape. */
    public boolean exhausted() {
        return tape != null && cursor >= tape.size();
    }

    /** How many calls have been served (or recorded). */
    public int cursor() {
        return cursor;
    }

    /**
     * A stable digest of the call sequence — the value a golden test compares. Two
     * runs whose behaviours differ in any world interaction differ here, even when
     * their final position happens to match.
     */
    public String digest() {
        StringBuilder text = new StringBuilder();
        for (Call call : calls) {
            text.append(call.primitive()).append('|').append(call.argument()).append('|').append(call.result())
                .append('\n');
        }
        return Hashing.digest(text.toString());
    }

    @Override
    public Vec3 position() {
        String result = serve("position", "", () -> delegate.position().asString());
        String[] parts = result.split(",");
        return Vec3.of(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
    }

    @Override
    public PathOutcome requestPath(Vec3 target) {
        String argument = target.asString();
        return PathOutcome.valueOf(serve("requestPath", argument, () -> delegate.requestPath(target).name()));
    }

    @Override
    public StepOutcome stepPath() {
        return StepOutcome.valueOf(serve("stepPath", "", () -> delegate.stepPath().name()));
    }

    @Override
    public boolean hasLineOfSight(VineId target) {
        return Boolean.parseBoolean(serve("hasLineOfSight", target.toString(),
            () -> Boolean.toString(delegate.hasLineOfSight(target))));
    }

    @Override
    public List<VineId> queryTargets(double radius) {
        String result = serve("queryTargets", Double.toString(radius), () -> {
            StringBuilder out = new StringBuilder("[");
            List<VineId> found = delegate.queryTargets(radius);
            for (int i = 0; i < found.size(); i++) {
                out.append(i == 0 ? "" : ",").append(found.get(i));
            }
            return out.append(']').toString();
        });
        if (result.equals("[]")) {
            return List.of();
        }
        String body = result.substring(1, result.length() - 1);
        List<VineId> ids = new ArrayList<>();
        for (String id : body.split(",")) {
            ids.add(VineId.parse(id));
        }
        return List.copyOf(ids);
    }

    @Override
    public boolean consumeFlinch() {
        return Boolean.parseBoolean(serve("consumeFlinch", "", () -> Boolean.toString(delegate.consumeFlinch())));
    }

    @Override
    public void lookAt(VineId target) {
        serve("lookAt", target.toString(), () -> {
            delegate.lookAt(target);
            return "ok";
        });
    }

    /**
     * The one place a call is either recorded or checked: in capture mode the delegate
     * answers and the pair is stored; in replay mode the recorded pair must match
     * exactly, and its result is returned.
     */
    private String serve(String primitive, String argument, java.util.function.Supplier<String> compute) {
        if (delegate != null) {
            String result = compute.get();
            calls.add(new Call(primitive, argument, result));
            cursor++;
            return result;
        }
        if (cursor >= tape.size()) {
            throw new IllegalStateException("primitive tape exhausted after " + cursor + " call(s): the behaviour asked "
                + primitive + "(" + argument + ") but the recording has nothing left — the tree is not deterministic"
                + " with respect to its recorded run");
        }
        Call recorded = tape.get(cursor);
        if (!recorded.primitive().equals(primitive) || !recorded.argument().equals(argument)) {
            throw new IllegalStateException("primitive tape diverged at call " + cursor + ": recorded "
                + recorded.primitive() + "(" + recorded.argument() + ") but the behaviour asked " + primitive
                + "(" + argument + ") — the tree is not deterministic with respect to its recorded run");
        }
        cursor++;
        return recorded.result();
    }

    /** Small, dependency-free digest so a tape's identity is comparable across cells. */
    private static final class Hashing {

        private Hashing() {
        }

        static String digest(String text) {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                }
                return hex.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required on every supported JVM", e);
            }
        }
    }
}
