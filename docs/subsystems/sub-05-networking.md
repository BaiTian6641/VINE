# SUB-05 — Networking

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 minimal → M1 full · **Depends on:** SUB-01, SUB-02, SUB-03 · **Blocks:** SUB-08, SUB-10, SUB-14, SUB-16
> **Cells:** all (M0 stages land on 1.21.1 first) · **Loaders:** both
> **Master plan:** §5.6 · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

Engine **channels + codec DSL** over a VINE `ByteBuf` facade; consumers never see `FriendlyByteBuf`/`StreamCodec` or loader networking (Prime Invariant, §5.1). Engine owns payload registration, per-channel protocol versioning with a configuration-phase handshake (orthogonal to the MC protocol — ViaVersion territory untouched, §5.6), a server-authoritative C2S validation pipeline, late-join sync primitives (sub-14/15 build on them), and chunked transport. Non-goals: raw sockets, proxies/voice, cross-server messaging, unbounded streaming, MC-protocol translation.

## 2. Design

### API surface (`vine-api`, `dev.vineengine.vine.net`)

```java
public interface VineNet {
    Channel channel(ChannelSpec spec);           // idempotent per VineId
    boolean isReady(VinePlayer player);          // handshake completed
}
public record ChannelSpec(VineId id, int protocol, VersionPolicy policy) {}
public enum VersionPolicy { REQUIRE_MATCH, OPTIONAL, SERVER_AUTHORITATIVE }
public interface Channel {
    <P> void message(VineId id, Class<P> type, PayloadCodec<P> codec,
                     Endpoint endpoint, MessageHandler<P> handler,
                     Validator<P>... validators);
    <P> void send(VinePlayer to, P payload);     // S2C
    <P> void sendToServer(P payload);            // C2S
    <P> void sync(VineId id, Class<P> type, PayloadCodec<P> codec, SyncSource<P> snapshot); // late-join
}
public enum Endpoint { SERVER, CLIENT }          // handler side
```

- `VineBuf` — facade, no `net.minecraft` types; ops `varInt/long/bool/utf(≤32767)/bytes/id` + `<T> read/write(PayloadCodec<T>)`; every variable-length read bounds-checked. `PayloadCodec<T>` = `decode(VineBuf)`/`encode(VineBuf,T)`; `VineCodecs` statics: `VAR_INT, UTF, ID, VOXEL` (sub-03 tree), `list(e,max)`, `optional(e)`, `record()` builder.
- Handlers get `NetContext { VinePlayer sender(); void enqueue(Runnable); }` — `enqueue` is the only sanctioned thread hop. `Validator<P>` chain on C2S; engine ships `Validators.rateLimit(perSecond, burst)`.
- `VinePlayer` = sub-01's player-event handle (not defined here); `VoxelData` per contract (sub-03).

### Internals (`vine-core`, `dev.vineengine.vine.internal.net`)

- `ChannelRegistry`: `VineId → ChannelSpec + message table`; registration closes at `REGISTRIES_FROZEN` (sub-01); dormant with zero consumers (Minimal Footprint). Codecs built once, cached, pooled buffers; drivers move opaque bytes only.
- **Handshake:** engine-owned `vine:handshake` in the configuration phase (all cells have it). Client advertises `Map<VineId,Integer>`; server negotiates, replies, `isReady` flips. `REQUIRE_MATCH` mismatch → clean disconnect with engine reason; `OPTIONAL` → channel disabled per connection; send-before-ready = no-op + one log line.
- **Late-join sync:** `Channel.sync` registers a `SyncSource<P>`; on join (after handshake, before consumer traffic) `snapshot(player)` is delivered; multiple syncs in registration order.
- **Chunking:** >256 KiB and ≤4 MiB split into `vine:chunk` frames (16 KiB), reassembled before delivery; per-player reassembly cap 8 MiB + timeout; >4 MiB rejected at encode. Rationale in §4.

### Driver contract (`vine-spi`)

`NetDriver` (bytes only; codec DSL never crosses): `openControlChannel(VineId)` · `register(ChannelSpec, List<MessageSpec>)` · `send(VinePlayer, VineId, byte[])` (+ `toServer`) · `InboundSink.accept(msgId, from, bytes)`.

- **1.21.1-NeoForge:** `RegisterPayloadHandlersEvent` → `PayloadRegistrar` (`playBidirectional`/`playToClient`/`playToServer`); registrar version ↔ `ChannelSpec.protocol`.
- **1.21.1-Fabric:** `PayloadTypeRegistry.playC2S()/playS2C()` + `ServerPlayNetworking`/`ClientPlayNetworking`.
- **26.x (both):** same shapes; Mojmap names at runtime, no remapping; Java 25 internals. Handler-thread/timing differences absorbed per driver; vine-core always re-dispatches via `NetContext.enqueue`.

## 3. Stages

### Stage A — M0 minimal: one packet echo

- [x] **Do:** `VineBuf` core ops + codec primitives; `Channel.message`/`send`/`sendToServer`; both 1.21.1 driver bindings; testmod `vine_test:echo` (int+String record) echoed C2S→S2C.
- **Acceptance:** TCK packet-echo scenario (§7) green headless on both 1.21.1 drivers.
- **Touches:** vine-api `net`, vine-core `internal.net`, vine-spi `NetDriver`, 1.21.1 drivers, testmod.
- **Bootstrap prompt:**
  > Implement networking stage A per `docs/subsystems/sub-05-networking.md` §2 (read plan §5.1/§5.6/§7 + `docs/README.md` conventions first). Build the `dev.vineengine.vine.net` API, vine-core codec + channel registry, `NetDriver` SPI, 1.21.1 NF (`PayloadRegistrar`) + Fabric (`PayloadTypeRegistry`/play networking) bindings; testmod `vine_test:echo` C2S→S2C record echo. Acceptance: headless TCK echo green on both 1.21.1 drivers; no net.minecraft/loader types in vine-api signatures.

### Stage B — full codec DSL

- [ ] **Do:** `record()` builder with field accessors; `list(max)`, `optional`, `VOXEL` hook; length-prefix sanity before every variable-length allocation.
- **Acceptance:** TCK codec round-trip (nested record/list/optional/VoxelData, byte-identical both loaders); negative/oversized length prefixes throw codec exceptions, never large-allocate.
- **Touches:** vine-api `VineCodecs`, vine-core codec machinery, TCK.
- **Bootstrap prompt:**
  > Implement stage B per `docs/subsystems/sub-05-networking.md`: record composition, bounded collection codecs, VoxelData codec (sub-03 tree); every variable-length read validates its length prefix against the remaining byte budget before allocating. Acceptance: TCK round-trip + hostile-input cases green on both 1.21.1 drivers.

### Stage C — handshake & payload versioning

- [ ] **Do:** `vine:handshake` control channel; `VersionPolicy` negotiation; `isReady` gating; vanilla-client join to a VINE server unaffected.
- **Acceptance:** TCK handshake scenario — `REQUIRE_MATCH` mismatch disconnects with engine reason; `OPTIONAL` disables only that channel; vanilla-client join is a clean no-op.
- **Touches:** vine-core handshake state machine, driver configuration-phase bindings (NF registrar tasks / Fabric configuration networking), TCK.
- **Bootstrap prompt:**
  > Implement stage C per `docs/subsystems/sub-05-networking.md`: engine handshake in the configuration phase, per-channel version negotiation per `VersionPolicy`, `isReady` gating. Acceptance: the three TCK handshake cases green on both 1.21.1 drivers.

### Stage D — validation pipeline & hardening

- [ ] **Do:** `Validator<P>` chain on C2S messages; `Validators.rateLimit` (per-player, per-message token bucket; engine-config defaults); reject = drop + structured log + optional kick; `enqueue` marshaling verified.
- **Acceptance:** TCK hostile-payload scenario — fuzzed bytes + C2S flood: server stays up, limiter trips at budget, handlers never run off-thread.
- **Touches:** vine-core validation dispatch, vine-api validator types, TCK.
- **Bootstrap prompt:**
  > Implement stage D per `docs/subsystems/sub-05-networking.md`: every C2S message runs codec bounds → validator chain → handler on the server thread; ship `Validators.rateLimit`. Acceptance: TCK hostile-payload scenario green on both 1.21.1 drivers.

### Stage E — late-join sync & chunking (may run parallel to D)

- [ ] **Do:** `Channel.sync` join-time snapshot delivery; `vine:chunk` transport with 8 MiB per-player reassembly cap and timeout; size-budget enforcement (256 KiB direct / 4 MiB chunked).
- **Acceptance:** TCK late-join scenario — joining player receives two sync snapshots in registration order before any consumer message; chunk scenario — 3 MiB payload intact, 5 MiB rejected at encode.
- **Touches:** vine-core sync orchestration + chunk transport, drivers, TCK.
- **Bootstrap prompt:**
  > Implement stage E per `docs/subsystems/sub-05-networking.md`: wire `Channel.sync` into player join (after handshake, before consumer traffic); add chunked transport over `vine:chunk` with capped reassembly buffers. Acceptance: TCK late-join + chunk scenarios green on both 1.21.1 drivers.

## 4. Problems & blockers

- **Play- vs login-phase channels:** consumer payloads are play-phase; the handshake must run in configuration phase. Mitigation: separate internal control channels; drivers bind each phase (spike in stage C).
- **Vanilla payload size limits:** play payloads capped near 1 MiB/packet plus connection compression. Decision (owner: this file): 256 KiB direct / 4 MiB chunked / larger is an explicit non-goal (§1).
- **Malicious payloads:** hostile clients own every byte. Mitigations: codec length-prefix bounds (B), rate limiting (D), reassembly caps (E); never trust `sender()` beyond session proof.
- **DSL ergonomics vs allocation cost:** codecs built once at registration, cached, pooled buffers; policed by the round-trip allocation budget (§5).
- **Handler thread differs per cell:** `NetContext.enqueue` is the only sanctioned hop; TCK asserts thread identity in D.

## 5. Verification

Owns TCK scenarios (§7/§8 — each ships only passing on ≥2 drivers, one per loader family): **packet echo**, **codec round-trip + hostile input**, **handshake/version negotiation**, **hostile flood (rate limit)**, **late-join sync ordering**, **chunked transfer**. Golden fixture: pinned byte encodings of the stage-B nested fixture, identical on all four cells. Budgets: echo ≤1 ms median server dispatch; round-trip ≤4 KB allocated per message. Client-smoke: vanilla client joins a VINE server cleanly; version-mismatched client gets the engine disconnect reason, not a crash.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.net` (public) / `dev.vineengine.vine.internal.net` (core) / `dev.vineengine.vine.internal.driver.<cell>.net` (drivers). Shared modules Java 21 bytecode; 26.x drivers may use Java 25. Channels/messages are descriptors (data), not behavior classes.
- **Comment policy:** javadoc on public API stating *why* and invariants (e.g. "send before `isReady` is a no-op"); driver comments name the loader mechanism absorbed (`PayloadRegistrar` vs `PayloadTypeRegistry`).
- **Forbidden:** `FriendlyByteBuf`/`StreamCodec`/loader networking in vine-api signatures; version-string parsing (feature probes, §5.12); Mixins outside drivers; interception of vanilla packets (Minimal Footprint); tests pinning buffer layout instead of round-trip behavior.
- **Done means:** all stage checkboxes ticked, all six TCK scenarios green on covered cells, status `done`, dashboard row updated in `docs/README.md`.
