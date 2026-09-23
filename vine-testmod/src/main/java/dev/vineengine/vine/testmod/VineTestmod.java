package dev.vineengine.vine.testmod;

import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.VineInitializer;
import dev.vineengine.vine.testmod.event.PhaseTrace;
import dev.vineengine.vine.testmod.net.EchoPacket;
import dev.vineengine.vine.testmod.registry.Testmarkers;
import dev.vineengine.vine.testmod.command.EchoCommand;

/**
 * vine-testmod entrypoint (sub-22) — the consumer-side proof of the Prime
 * Invariant: this module compiles against vine-api alone, carries zero loader
 * or game-class references and zero version conditionals, and the same
 * sources build into every per-cell jar under {@code versions/}.
 *
 * <p>Consumer discovery (sub-01/sub-18 seam, pinned with sub-02 Stage B): the
 * engine ServiceLoader-discovers this class via
 * {@code META-INF/services/dev.vineengine.vine.VineInitializer} and calls
 * {@link #init()} exactly once per process while REGISTRIES_OPEN is in
 * effect. Registration calls here are direct — no phase anchoring needed —
 * because that invocation moment is the contract.
 *
 * <p>Content contract (sub-22 §2): exactly one canonical exemplar per surface.
 * Exemplars whose API surface has not landed yet are marked seams in
 * {@link #init()} — they activate with their owning subsystem, guarded by
 * {@code VineEngine.supports(...)} probes once sub-01 Stage D lands the probe
 * API; probes are for incubating surfaces only, never shipped ones.
 */
public final class VineTestmod implements VineInitializer {

    /** Mod id and VineId namespace for the testmod's own content. */
    public static final String MOD_ID = "vine_test";

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public VineTestmod() {
    }

    @Override
    public void init() {
        VineEngine engine = VineEngine.get();
        PhaseTrace.subscribe(engine);  // engine-event exemplar (sub-01, M0)
        Testmarkers.register(engine);  // registry exemplar (sub-02, M0): Java path
        EchoPacket.register();       // packet echo exemplar (sub-05 Stage A): vinetest:echo C2S→S2C
        EchoCommand.register();      // command exemplar (sub-06, M0): /vine_test echo <msg>, op-level 2
        // Seam — testblock/testitem exemplars (sub-07, M0 minimal): pending the vine-api
        //   content surface; guarded by a supports(...) probe while incubating.
    }
}
