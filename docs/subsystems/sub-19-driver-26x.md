# SUB-19 — Driver 26.x (NeoForge + Fabric)

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M4 · **Depends on:** SUB-18 · **Blocks:** — (feeds M5 client-3D, sub-17, on these cells)
> **Cells:** 26.x · **Loaders:** both
> **Master plan:** §3 (drop policy), §5.9–§5.12, §10 (M4) · **Module(s):** drivers

## 1. Purpose

Materialize `vine-spi` on the 26.x era — `driver-26.x-neoforge` and
`driver-26.x-fabric` — over the locked **latest + previous drop** window (§3).
This is the thesis test (§1.2): a new Minecraft version means a new driver and
**zero consumer-mod changes**. Reuses every pattern sub-18 established; the
version axis is the only new variable. Non-goals: no new API surface (M4
consumes what M0–M3 built), no 3D client implementation (M5/sub-17 — here
only probe gating), no snapshot chasing, no per-drop forks of shared modules.

## 2. Design

**Unobfuscation dividend (§5.10):** Mojang ships unobfuscated binaries —
dev names = runtime names, so **no remap step, no refmaps**, reflection and
stack traces just work. Yarn is discontinued (26.1+): Mojmap only on both
loaders. Mixin need drops (not to zero): prefer **accessor/interface patterns**
and direct calls where Mojang's code allows; Mixins only for the same
no-native-hook SPI primitives as 1.21.1 (sub-18 §2 † rows), one config per
driver (§5.9).

**Per-cell differences:**

| Concern | 26.x-NeoForge | 26.x-Fabric |
|---|---|---|
| Toolchain | ModDevGradle, **Java 25** | fabric-loom **non-remapping**, **Java 25** |
| Mappings | Mojmap only; runtime = dev names | Mojmap only; runtime = dev names |
| Version pin | NeoForge `26.1.2.x` family format; one pin per supported drop | loader + Fabric API per drop (expect per-drop FAPI renames) |
| Entrypoint | sub-18 shapes (`@Mod` + mod bus, dist client init); API renames absorbed locally | sub-18 shapes (`ModInitializer`/`ClientModInitializer`); renames absorbed locally |
| Registration / caps / payloads | same systems as 1.21.1-NF (`DeferredRegister`, `RegisterCapabilitiesEvent`, `PayloadRegistrar`) — verify per-drop signatures | same systems as 1.21.1-Fabric (`Registry.register`/`DynamicRegistries`, lookups + fallback store, `PayloadTypeRegistry`) |
| Mixins | one config, **no refmap**; accessor/interface first | one config, **no refmap**; accessor/interface first |
| Render backend | GeckoLib **GL5**, hard dep (§5.14/§5.20) — verified: GL 5.5.x ships for 26.1.2/26.2 on NeoForge + Fabric | GeckoLib **GL5**, hard dep — same GL 5.5.x evidence |

**Drop-window mechanics (§3 + §5.12):** one maintenance branch per in-window
drop (`driver/26.x` pinned to that drop via the `mc26Latest`/`mc26Previous`
catalog constants); a new drop ⇒ cut the new branch, retire the oldest by
policy — cells retire by policy, never by decay. At boot each driver
self-checks: `SharedConstants` **data version** + the feature-probe matrix
(`unobfuscatedRuntime=true`, `vulkanRenderer=<probed>`, …) must match its
pinned expectations; mismatch ⇒ **explicit boot failure listing supported
cells** — never silent misbehavior, never version-string parsing.

**Vulkan guardrails (§5.11):** experimental Vulkan shipped in 26.2 with a
Graphics API selector. All 3D surfaces stay **probe-gated**: consumers get
`VineEngine.supports(ClientFeature.X)` and prototype behind `@VineUnsafe`
until M5. Driver code must not touch GL/RenderSystem outside the GeckoLib
backend seam; the `vulkanRenderer` probe answers from the runtime's selected
graphics API, not from version identity.

**Data-version bumps:** Mojang bumped the data version three times in seven
months of 2026. Drivers only *report* the data version; migration semantics
are engine-owned (sub-03 engine datafixers). TCK golden fixtures must
round-trip 1.21.1 ↔ 26.x (§7).

**Snapshot policy:** target **full releases only**; no snapshots in CI. Early
drop-window work may spike on snapshots locally, but committed CI pins
release artifacts only.

## 3. Stages

Each bootstrap assumes the agent has read this file, plan §5.9–§5.12 + §10
(M4), sub-18, and `docs/README.md` conventions.

### Stage A — Scaffold adoption & core-runtime binding

- [ ] **Do:** sub-00 Stage C already scaffolded both 26.x subprojects (MDG /
  non-remapping loom, Java 25, `mc26Latest`/`mc26Previous` catalog constants,
  marker entrypoints, JDK 25 CI legs). Turn scaffolds into drivers: package
  roots `dev.vineengine.vine.internal.driver26x.{common,neoforge,fabric}`;
  complete metadata (GeckoLib hard dep, §5.14); port sub-18 Stage B's
  `VineDriver` binding — full five-phase `advancePhase`, `CellInfo` with
  `unobfuscatedRuntime=true`; confirm zero remap/refmap plumbing exists.
- **Acceptance:** both 26.x cells boot with all five phases logged in order;
  Fabric build log shows no remapping work; probe matrix answers match §2
  expectations.
- **Touches:** `drivers/driver-26.x-*/` sources + metadata.
- **Bootstrap prompt:**
  > Execute Stage A of `docs/subsystems/sub-19-driver-26x.md`: on sub-00 Stage
  > C's 26.x scaffolds, establish `driver26x.{common,neoforge,fabric}`
  > packages, complete metadata (GeckoLib hard dep), and port sub-18's
  > `VineDriver` boot binding (five phases, `CellInfo` from data version +
  > probes, `unobfuscatedRuntime=true`). Acceptance = phase log lines in
  > order on both cells, no remap work anywhere in the build.

### Stage B — SPI port & unobfuscation dividend

- [ ] **Do:** port every remaining SPI implementation from the 1.21.1 drivers
  (events, registration, caps, payloads) to 26.x; delete remap/refmap
  plumbing; replace 1.21.1 Mixins with accessor/interface patterns or direct
  calls wherever the unobfuscated code allows; absorb loader API renames
  locally.
- **Acceptance:** all TCK scenarios that pass on 1.21.1 cells pass on both
  26.x cells; Mixin count ≤ 1.21.1's, each surviving Mixin justified by a
  comment naming the SPI method served.
- **Touches:** `dev.vineengine.vine.internal.driver26x.{neoforge,fabric}`.
- **Bootstrap prompt:**
  > Execute Stage B of `docs/subsystems/sub-19-driver-26x.md`: port the full
  > SPI implementation set from the 1.21.1 drivers to both 26.x cells per §2
  > (no remap/refmap, accessor/interface over Mixins, renames absorbed in
  > drivers). Acceptance = every TCK scenario green on both 26.x cells;
  > surviving Mixins each justified.

### Stage C — Drop-window mechanics

- [ ] **Do:** implement sub-01's boot self-check contract for the drop window:
  pinned drop-coordinate set (from the `mc26Latest`/`mc26Previous` catalog
  constants), expected data-version window + probe matrix per drop, mismatch ⇒
  sub-01's clean boot failure listing supported cells.
- **Acceptance:** booting against an out-of-window drop fails fast with a log
  message naming the supported cells; both in-window drops pass the
  self-check; no version-string parsing anywhere (grep check).
- **Touches:** `driver26x.*.boot`, version catalog.
- **Bootstrap prompt:**
  > Execute Stage C of `docs/subsystems/sub-19-driver-26x.md`: implement the
  > drop-window self-check per §2 — pinned latest+previous drop coordinates
  > (catalog constants), expected data-version window + probe matrix per drop,
  > sub-01's explicit boot failure on mismatch. Acceptance = out-of-window
  > boot fails fast listing supported cells; both in-window drops pass.

### Stage D — Vulkan guardrails & GL5 readiness

- [ ] **Do:** driver-side GL5 readiness: GeckoLib **GL5** dependency +
  boot-time presence check (hard dep per §5.14/§5.20) behind the backend seam
  sub-18 established — the `VineAnimationBackend` GL5 implementation itself is
  sub-09 Stage E, not this file; implement the `vulkanRenderer` probe from the
  runtime's selected graphics API; probe-gate every 3D surface so
  unimplemented client features answer `supports(...) == false` instead of
  crashing (§5.11).
- **Acceptance:** client boots on both 26.x cells with experimental Vulkan
  enabled and with GeckoLib present/absent (absence = explicit hard-dep
  failure, not a crash); all 3D probes report honestly; no GL/RenderSystem
  references outside the backend seam (grep check).
- **Touches:** `driver26x.*.client`, backend seam.
- **Bootstrap prompt:**
  > Execute Stage D of `docs/subsystems/sub-19-driver-26x.md`: wire the
  > GeckoLib GL5 dependency + presence check on both 26.x cells behind
  > sub-18's backend seam (backend implementation belongs to sub-09 Stage E),
  > implement the `vulkanRenderer` probe from the runtime graphics API
  > selector, probe-gate all unimplemented 3D surfaces per §5.11. Acceptance
  > = client boots with Vulkan enabled, GL5 absence fails explicitly, probes
  > answer honestly.

### Stage E — M4 acceptance gate

- [ ] **Do:** driver side of the joint M4 gate (with sub-22 Stage F, which
  owns the `versions/26.x-*` testmod subprojects): full TCK matrix run (all
  four cells — the 1.21.1 cells on JDK 21, the 26.x cells on JDK 25) with the
  new drivers, golden-fixture round-trip 1.21.1 ↔ 26.x through engine
  datafixers (sub-03).
- **Acceptance:** **M4 pass — consumer testmod rebuilds for 26.x with ZERO
  source changes** (§10); full matrix green; fixtures round-trip both
  directions.
- **Touches:** `vine-tck` CI matrix, driver fixes only.
- **Bootstrap prompt:**
  > Execute Stage E of `docs/subsystems/sub-19-driver-26x.md`: run the M4 gate
  > jointly with sub-22 Stage F (it adds the `versions/26.x-*` builds linking
  > `vine-api` + your drivers — no testmod source edits) and the full TCK
  > matrix (all four cells — 1.21.1 on JDK 21, 26.x on JDK 25) plus
  > golden-fixture round-trips. Any required consumer source change is an API
  > leak: report it to the owning subsystem, never patch the testmod.

## 4. Problems & blockers

- **Drop cadence CI churn (~3 drops/year):** each drop re-pins toolchains,
  loader, and FAPI. Mitigation: drop coordinates are data in the build (one
  edit per drop); latest+previous bounds the matrix; cells retire by policy.
- **Early-window NeoForge beta instability:** new drops ship against beta NF.
  Mitigation: the previous-drop cell stays the stable target; the latest-drop
  cell may pin a beta NF but must pass the same TCK before the window advances.
- **Fabric API per-drop renames:** FAPI breaks per drop. Mitigation: all FAPI
  usage concentrated in the driver's translation layer (sub-18 pattern);
  renames are one-driver diffs, never consumer-visible.
- **Data-version bumps vs engine datafixers:** each drop bumps Mojang's data
  version; VINE data migration must not ride DFU versions (§5.4). Mitigation:
  driver reports the data version only; sub-03 owns migration; golden fixtures
  (§7) catch divergence. Owner of semantics: sub-03.
- **Vulkan mid-flight (§9 risk):** probe gating is the whole mitigation; if
  Mojang flips the default renderer before M5, `vulkanRenderer=true` cells
  must still boot with 3D surfaces dormant.

## 5. Verification

- **M4 gate (owned):** testmod zero-source-change rebuild on both 26.x cells.
- TCK scenarios owned: boot self-check pass (in-window) and explicit failure
  (out-of-window drop); probe-matrix correctness incl. `vulkanRenderer`; GL5
  backend resolution; full-matrix regression (all four cells — 1.21.1 on JDK
  21, 26.x on JDK 25); golden fixture round-trip 1.21.1 ↔ 26.x (with sub-03).
- §8 rule: no surface ships without its scenario on ≥2 drivers, one per
  loader family — the 26.x pair must pass every existing scenario, proving
  parity rather than adding surface.

## 6. Agent guidance

- **Conventions:** packages
  `dev.vineengine.vine.internal.driver26x.{neoforge,fabric}`; **Java 25 inside
  these drivers** (shared modules stay Java 21 bytecode). Copy sub-18
  patterns; diverge only where 26.x forces it, and comment the divergence.
- **Comment policy:** each surviving Mixin names the SPI method it backs and
  why accessor/interface was insufficient; each drop pin records the drop it
  targets.
- **Forbidden:** version-string parsing (data version + probes only, §5.12);
  snapshots in CI; GL/RenderSystem calls outside the backend seam (§5.11);
  Mixins where an accessor/interface works; consumer-visible per-drop
  conditionals.
- **Done means:** stages ticked, M4 gate green (zero-source-change rebuild,
  full matrix), status → `done`, dashboard row updated in `docs/README.md`.
