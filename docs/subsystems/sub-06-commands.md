# SUB-06 — Commands

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 minimal → M1 full · **Depends on:** SUB-01, SUB-02, SUB-03 · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.7 · **Module(s):** vine-api, vine-client-api, vine-core, vine-spi, drivers

## 1. Purpose

A declarative **command DSL over Brigadier** (native on every cell, §5.7): consumers describe commands as data — literals, arguments, `requires`/permission gates, suggestion providers, executors — and drivers do near-mechanical translation into each loader's command-registration hook. Adds engine argument types (`VineId`, `VoxelData` path, player-in-session), a permission model bridging vanilla op levels and permission plugins, a server-side vs client-side command split, and a `/reload`-time refresh policy. Non-goals: replacing Brigadier parsing semantics, a permission plugin, custom text-component DSL (plain feedback strings only at M1), command-spam filtering, dispatching non-VINE mod commands.

## 2. Design

### API surface (`vine-api`, `dev.vineengine.vine.command`; client side in `vine-client-api`)

```java
public interface VineCommands {
    void register(CommandDescriptor descriptor);   // closes at REGISTRIES_FROZEN (sub-01)
}
// Builder produces a pure-data descriptor (Java + JSON paths, sub-02 machinery):
CommandDescriptor cmd = VineCommand.literal("vinequest")
    .permission(VinePermission.level(2))                 // or .node("vinequest.admin", 2)
    .then(VineCommand.literal("start")
        .then(VineCommand.argument("target", VineArgumentTypes.PLAYER_IN_SESSION)
            .suggests(VineSuggestions.ACTIVE_SESSION_PLAYERS)
            .executes(ctx -> { /* ... */ return 1; })))
    .build(VineId.of("myquest", "vinequest"));

public sealed interface VinePermission {
    record Level(int level) implements VinePermission {}                  // vanilla op levels
    record Node(String node, int fallbackLevel) implements VinePermission {} // plugins, see §2
}
public final class VineArgumentTypes {   // engine argument types
    public static ArgumentTypeRef<VineId> VINE_ID;                 // namespace:path
    public static ArgumentTypeRef<VoxelPath> VOXEL_PATH;           // path into a VoxelData tree (sub-03)
    public static ArgumentTypeRef<VinePlayer> PLAYER_IN_SESSION;   // session-scoped (sub-14; see §4)
    // + vanilla mirrors: INT, LONG, DOUBLE, BOOL, STRING, GREEDY, ENUM
}
public interface VineCommandContext {
    CommandSourceRef source();             // isPlayer()/player()/server() accessors
    <T> T argument(String name, Class<T> type);
    void feedback(String message);         // plain text at M1
    void error(String message);
}
public interface SuggestionSource { List<String> get(SuggestionContext ctx); } // partial ctx: typed prefix + source
```

### Internals (`vine-core`, `dev.vineengine.vine.internal.command`)

- Descriptors compile to Brigadier trees at dispatcher-build time (never cached across builds). Registration closes at `REGISTRIES_FROZEN`; dormant with zero consumers (Minimal Footprint).
- **Merge policy:** engine builds one merged tree per dispatcher. Duplicate literal at the same depth merges children; two consumers attaching *different argument shapes* to the same literal → deterministic conflict report (both IDs logged), first-registered wins, build continues.
- **Permission bridging:** `Level` maps to Brigadier `requires(source -> source.hasPermission(n))` on all cells. `Node` resolves via driver hook: Fabric cells consult fabric-permissions-api **if present** (`supports()` probe, never hard-dep), else fall back to `fallbackLevel`; NeoForge cells use the loader permission handler when registered, else fallback. Always evaluated server-side.
- **Reload policy:** descriptors are data, so JSON-shipped commands (sub-02 descriptor path) hot-reload with `/reload` — drivers re-run compilation on every dispatcher build; Java-registered descriptors are static.
- **Vanilla-client sync:** engine argument types are *server-parsed only*; in the synced command tree each maps to the nearest vanilla type (`VINE_ID` → resource-location, `VOXEL_PATH` → greedy string, `PLAYER_IN_SESSION` → player selector) so vanilla clients can tab-complete (server-computed suggestions) and never see an unknown argument type. Rationale in §4.

### Driver contract (`vine-spi`)

`CommandDriver`: `registerServerDispatcher(List<CommandDescriptor>)` (re-invoked per dispatcher build) · `registerClientDispatcher(...)` (client driver) · `PermissionBridge.hasPermission(source, node, fallbackLevel)` · engine supplies pre-built Brigadier nodes; drivers only attach them.

- **1.21.1-NeoForge:** `RegisterCommandsEvent`; client via `RegisterClientCommandsEvent`.
- **1.21.1-Fabric:** `CommandRegistrationCallback`; client via `ClientCommandRegistrationCallback`.
- **26.x (both):** same hooks, Mojmap names at runtime; Java 25 internals. Suggestion-provider wrapping (async suggestions) is absorbed per driver; consumers see only `SuggestionSource`.

## 3. Stages

### Stage A — M0 minimal: one command

- [x] **Do:** `literal`/`argument(STRING)`/`executes` builder → descriptor; `Level` permission; compile + attach in both 1.21.1 drivers; testmod `/vine_test echo <msg>` replies with the argument.
- **Acceptance:** TCK command-execution scenario (§7) green headless on both 1.21.1 drivers: op-level-2 source succeeds, level-0 source denied. *(Level-0 denial proven in the engine-side 17-check harness — a dedicated console is always op-4, so no live level-0 source exists headless; the gate itself is the same `VinePermission.level` check.)*
- **Touches:** vine-api `command`, vine-core `internal.command` (compiler), vine-spi `CommandDriver`, 1.21.1 drivers, testmod.
- **Bootstrap prompt:**
  > Implement commands stage A per `docs/subsystems/sub-06-commands.md` §2 (read plan §5.1/§5.7/§7 + `docs/README.md` conventions first). Build the descriptor DSL subset (literal, string argument, executes, op-level permission), the Brigadier compiler, the `CommandDriver` SPI, and 1.21.1 NF (`RegisterCommandsEvent`) + Fabric (`CommandRegistrationCallback`) bindings; testmod `/vine_test echo`. Acceptance: headless TCK command execution green on both 1.21.1 drivers; no Brigadier/loader types in vine-api signatures.

### Stage B — full descriptor DSL

- [x] **Do:** `requires` predicates, `redirect`, remaining vanilla-mirror argument types, `suggests`, JSON descriptor path (sub-02 machinery), merge/conflict policy.
- **Acceptance met:** TCK `command_tree` — mirrors/enum/greedy deliver their declared types, requirement denial and allow, server-computed suggestions, a merged child from a second consumer executes, both clash kinds report, `/vt` (Java) and `/vinequest vt` (JSON) aliases execute through the target tree, dead target and cycle degrade with reports, an unreachable greedy sibling is rejected at registration. 20/20 scenarios on both 1.21.1 cells.
- **Redirects (landed):** `Builder.redirect(targetRootLiteral)` aliases a tree — on a root literal (`/vt` *is* the alias for `/vine_test`) or on a child literal. An alias node must stay childless and executor-free (rejected at registration): the target tree parses the whole remainder, including its permissions and suggestions. Targets and cycles resolve over the merged snapshot at native-pass time, in registration order (deterministic reports): an unknown target or a cycle logs `command alias …` and the node degrades to a plain literal, so a dispatcher build can never fail because of consumer ordering. A merge that attaches children to an alias drops the redirect (children win) and reports it. Drivers register plain roots first and alias roots after, because Brigadier bakes the redirect node at build time.
- **Landed (partial):** vanilla mirrors `INT`/`LONG`/`DOUBLE`/`BOOL`/`GREEDY` + `VineArgumentTypes.enumeration(Class)` (parsed by constant name, case-insensitive) mapped per cell; `Builder.requires(Predicate<CommandSourceRef>)` evaluated server-side after the permission gate, before the executor (throw = denial, error feedback, executor never runs); `Builder.suggests(SuggestionSource)` wired to each cell's native provider, computed server-side so vanilla clients tab-complete unchanged; merge/conflict policy — same root literal merges children by name, a shape clash (literal vs argument, or differing argument types) reports both descriptor ids, keeps the first, and the build continues. Exemplar: `CommandTreeExemplar` (three descriptors on one root: mirrors/enum/requires/suggests + the merge and both clash kinds) driven by the `command_tree` scenario. **JSON descriptor path (landed):** `data/<ns>/vine/commands/<name>.json`, discovered through the same three views as the structural loader (classloader data roots, consumer code sources, JVM classpath), parsed by the engine's own codec into the same `CommandDescriptor` a Java builder produces — the compiler, merge policy and dispatcher walker never learn the difference. Node facets: `literal`/`argument` (+`type`: string/int/long/double/bool/greedy/`enum:<NAME>` with `values`), `permission` (op level), `executor` (an id registered through `VineCommands.registerExecutor`), `suggests` (literal candidate list), `redirect`, `then`. Behavior references resolve at load time, so a typo is a load failure, not a command that parses and does nothing; requirement predicates and custom suggestion sources stay Java-only by construction. The layer is applied at the end of consumer initializers — *before* the first native dispatcher build, which on Fabric happens during server construction (well before `REGISTRIES_FROZEN`); `VineCommands.applyJson` replaces the layer and invalidates the merged snapshot, which is the seam Stage E's `/reload` re-runs. A JSON descriptor clashes and merges like any other consumer: Java registrations are applied first, so they win a shape clash.
- **Acceptance:** TCK command-tree scenario — nested tree with requires/redirect/suggestions executes identically on both loaders; conflicting argument shape from a second consumer logs the conflict report and keeps the first.
- **Touches:** vine-api builder, vine-core compiler + merge, TCK.
- **Bootstrap prompt:**
  > Implement stage B per `docs/subsystems/sub-06-commands.md`: full builder surface, JSON descriptors, merged-tree build with the documented conflict policy. Acceptance: TCK command-tree + conflict scenarios green on both 1.21.1 drivers.

### Stage C — engine argument types & vanilla-client sync

- [x] **Do:** `VINE_ID`, `VOXEL_PATH`, `PLAYER_IN_SESSION` parsers + suggestion defaults; synced-tree mapping to nearest vanilla types; `VINE_ID` suggests from engine registries (sub-02).
- **Landed:** each engine type maps natively to its nearest vanilla argument type, so the *synced* tree carries nothing a vanilla client doesn't know: `VINE_ID` → resource-location (parsed back through `VineId.parse`), `VOXEL_PATH` → greedy string (validated by `VoxelPath.parse`), `PLAYER_IN_SESSION` → player selector (resolved to the engine `VinePlayer` facade). Suggestions are engine state, computed server-side: `VineSuggestions.REGISTERED_IDS` reads the runtime id map (keys are `"<registryId> <entryId>"`, the completion is the entry id — `vine_test:testblock,vine_test:testitem` live), `VineSuggestions.SESSION_PLAYERS` names the executing player's session participants through a cell-installed UUID→name resolver (`PlayerNames`: Fabric installs on `SERVER_STARTING`, NeoForge on `ServerStartedEvent`), and returns empty without sessions instead of failing. Because a vanilla argument type cannot express engine validation, a value that parses natively but fails engine rules (e.g. `Bad.Path`) is rejected with a clean `Invalid <argument>: …` error before the executor — never a dispatcher crash.
- **Acceptance met (engine half):** TCK `command_tree` — all three types parse and execute on both 1.21.1 cells, `VINE_ID` suggestions come from live registrations, a malformed path is rejected with a clean error. **Outstanding:** the client-smoke half (unmodded vanilla client sees the tree and tab-completes) needs a client runner — sub-21's client cell; recorded there as a checklist item rather than claimed here.
- **Touches:** vine-api `VineArgumentTypes`, vine-core parsers + sync mapping, drivers, TCK.
- **Bootstrap prompt:**
  > Implement stage C per `docs/subsystems/sub-06-commands.md`: the three engine argument types server-parsed only, mapped to vanilla types in the synced tree. `PLAYER_IN_SESSION` registers only when sub-14 is present (`supports()` probe) — degrade to player selector otherwise. Acceptance: TCK argument scenario green on both 1.21.1 drivers + vanilla-client smoke checklist item.

### Stage D — permission bridging

- [ ] **Do:** `Node` permission resolution: Fabric → fabric-permissions-api if present else fallback level; NeoForge → loader permission handler else fallback; all server-side.
- **Acceptance:** TCK permission scenario — node granted/denied with and without a permission provider present, on both loaders; fallback level honored.
- **Touches:** vine-spi `PermissionBridge`, both drivers, TCK.
- **Bootstrap prompt:**
  > Implement stage D per `docs/subsystems/sub-06-commands.md`: node-permission bridging per driver with probe-based optional integration (never a hard dependency). Acceptance: TCK permission scenario green on both 1.21.1 drivers.

### Stage E — client-side commands & reload refresh (may run parallel to D)

- [ ] **Do:** `vine-client-api` `VineClientCommands` (same DSL, local execution, never sent to server); `/reload`-time recompile of JSON-shipped descriptors.
- **Acceptance:** TCK client-command scenario — client command executes offline, invisible to the server; reload scenario — edited JSON command descriptor takes effect after `/reload` without restart.
- **Touches:** vine-client-api, client drivers, vine-core descriptor reload hook, TCK.
- **Bootstrap prompt:**
  > Implement stage E per `docs/subsystems/sub-06-commands.md`: client-side command registration (NF `RegisterClientCommandsEvent` / Fabric `ClientCommandRegistrationCallback`) and dispatcher-rebuild recompilation of JSON descriptors. Acceptance: TCK client-command + reload scenarios green on both 1.21.1 drivers.

## 4. Problems & blockers

- **Suggestion/context mapping per loader event:** loaders wrap Brigadier suggestion providers differently (async on Fabric client, event-gated on NF). Mitigation: drivers adapt once; consumers see only `SuggestionSource`; spike belongs to stage B.
- **Custom argument types on vanilla clients:** unknown argument types break the synced command tree for unmodded clients. Decision (owner: this file): engine argument types are server-parsed only and mapped to nearest vanilla types in the synced tree (§2) — no client mod ever required.
- **Merge conflicts between consumers:** two mods competing for the same literal. Decision: deterministic first-registered-wins with a conflict report naming both IDs (§2); namespaced root literals recommended in consumer docs. *Landed (Stage B):* same root literal merges children by name; a clash is either literal-vs-argument or two different argument types — each reports both descriptor ids with the path and both shapes, keeps the first, and the build continues.
- **Ambiguous argument siblings:** a `GREEDY` argument consumes the rest of the line, so a sibling declared after it is unreachable and a child under it can never parse. Decision: reject at registration (the compiler already rejects duplicate sibling names, which is the other half of the same hazard) — never accept a descriptor and let dispatch silently pick one branch. Consumers keep the greedy node last under its own literal.
- **Permission-plugin diversity:** no common permission API across the matrix. Mitigation: probe-based optional bridges with op-level fallback; never a hard dependency (stage D).
- **Stage A driver attach (in flight):** engine side landed — vine-api `dev.vineengine.vine.command` (descriptor DSL subset, `VineCommands`/`CommandBackend` via the EngineAccess seam), vine-core `dev.vineengine.vine.internal.command` (`CommandService` freeze-aware store + `CommandCompiler` + `CommandBridge` driver seam: `commandsForNativePass()` pull + `execute(...)` with error isolation). Remaining: the 1.21.1 drivers' native registration pass must pull the snapshot and attach nodes (`RegisterCommandsEvent` / `CommandRegistrationCallback`, eager-but-dormant listener) — exact spec handed to Main; `CommandQueue` stays sub-18's bare-literal hook mechanism and does NOT carry engine descriptors (no children/permission/feedback channel, and vine-core cannot push into a driver-side queue).
- **`PLAYER_IN_SESSION` ordering:** needs sub-14 session state. Mitigation: probe-gated registration, degrades to a plain player selector when sessions are absent (stage C).

## 5. Verification

Owns TCK scenarios (§7/§8 — each ships only passing on ≥2 drivers, one per loader family): **command execution** (op-level gate), **command tree** (requires/redirect/suggestions + conflict policy), **engine argument types**, **permission bridging**, **client command**, **`/reload` refresh**. Golden fixture: pinned descriptor JSON ↔ compiled-tree shape, identical on all four cells. Client-smoke: vanilla client tab-completes an engine-typed command without errors; denied source receives engine feedback, not silence.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.command` (public) / `dev.vineengine.vine.internal.command` (core) / `dev.vineengine.vine.internal.driver.<cell>.command` (drivers); client surface in `vine-client-api`. Shared modules Java 21 bytecode; 26.x drivers may use Java 25. Descriptors are data (Java + JSON paths).
- **Comment policy:** javadoc on public API stating *why* and invariants (e.g. "engine argument types never appear in the synced tree"); driver comments name the loader hook absorbed.
- **Forbidden:** Brigadier/loader types in vine-api signatures; version-string parsing (feature probes, §5.12); Mixins outside drivers; global re-registration of vanilla commands (Minimal Footprint); hard dependencies on permission plugins; tests pinning tree internals instead of execution/feedback behavior.
- **Done means:** all stage checkboxes ticked, all six TCK scenarios green on covered cells, status `done`, dashboard row updated in `docs/README.md`.
