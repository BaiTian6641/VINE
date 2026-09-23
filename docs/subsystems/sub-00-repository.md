# SUB-00 — Repository & bootstrap

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 · **Depends on:** — · **Blocks:** SUB-01, SUB-02, SUB-18, SUB-21, SUB-22
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.0, §5.10, §6, §7, §10 · **Module(s):** all (build skeleton)

## 1. Purpose

The Gradle skeleton the whole engine builds in: shared modules (`vine-api`,
`vine-client-api`, `vine-core`, `vine-spi`), one driver project per cell,
`vine-tck`, `vine-testmod`, version catalog, per-cell jar stamping, CI booting
a headless dedicated server per cell × JDK. Non-goals: engine code (sub-01+),
TCK scenarios (sub-21), testmod content (sub-22), remote publishing (§11.1).

## 2. Design

- **Layout** (§5.0/§6: plain multi-project, no preprocessor plugin):
  `settings.gradle` includes the 4 shared modules, `drivers:driver-<cell>` ×4,
  `vine-tck`, `vine-testmod`. Conventions in an included build `build-logic/`:
  `vine.java-shared` (toolchain 21, `release=21`, UTF-8, `-parameters`) for
  shared/tck/testmod; one `vine.driver-<cell>` convention per cell.
- **Toolchains** (§5.10): 1.21.1-NF = ModDevGradle (NeoGradle fallback),
  Mojmap+Parchment, Java 21 · 1.21.1-Fabric = loom remapping,
  Yarn/intermediary, Java 21 · 26.x-NF = MDG, no mappings step, Java 25 ·
  26.x-Fabric = loom non-remapping, Java 25. loom and MDG never share a
  project classpath; toolchain plugins apply only inside their driver
  subproject, never at root. On plugin bleed, promote `drivers/*` to included
  builds sharing the one catalog (no layout change).
- **Catalog** `gradle/libs.versions.toml`: single source for NeoForge,
  MDG/NeoGradle, Fabric Loader/API, loom, Mixin, JDKs, 26.x drop-window
  constants `mc26Latest`/`mc26Previous` (§3).
- **Coordinates**: group `dev.vineengine`; artifacts `vine-api`,
  `vine-client-api`, `vine-core`, `vine-spi`, `vine-driver-<cell>`, `vine-tck`,
  `vine-testmod`; driver jars stamp the cell
  (`vine-driver-1.21.1-neoforge-<version>.jar`); version = engine version
  (§8: vine-api SemVer, drivers per API major).
- **CI** (§7): matrix build (cells × JDK {21,25}: drivers on their required
  JDK, shared on both); per-cell headless server boot smoke (timeout, assert
  driver marker + `Done`, `stop`); Prime Invariant gate scanning API jars for
  `net.minecraft`/`net.neoforged`/`net.fabricmc` refs. Configuration cache on
  from day one.

## 3. Stages

### Stage A — shared-module skeleton

- [x] **Do:** Gradle wrapper (9.x); `settings.gradle` (root `vine`, all
  includes); catalog; `build-logic` + `vine.java-shared`; 4 shared modules +
  empty tck/testmod; group `dev.vineengine`, version `0.1.0-SNAPSHOT`;
  `--configuration-cache` in `gradle.properties`.
- **Acceptance:** `./gradlew build` green, cache stored+reused; each shared
  module emits a `dev.vineengine` jar.
- **Touches:** root build files, `build-logic/`, `vine-*/build.gradle`.
- **Bootstrap prompt:**
  > Create the VINE Gradle skeleton per docs/subsystems/sub-00-repository.md
  > Stage A: plain multi-project, the six modules, included build
  > `build-logic/` with `vine.java-shared` (Java 21 toolchain + release 21,
  > UTF-8, -parameters), configuration cache on. Acceptance: `./gradlew build`
  > green, cache reused. Conventions: docs/README.md, §5.0.

### Stage B — 1.21.1 driver scaffolds

- [x] **Do:** Both 1.21.1 driver subprojects per §2 (MDG/Mojmap+Parchment and
  remapping-loom/Yarn, Java 21); minimal entrypoints (`@Mod("vine")` /
  `ModInitializer`) logging `VINE driver <cell> alive`; project deps on
  vine-api/core/spi; dev server run configs.
- **Acceptance:** both build; each headless dev server reaches `Done` with the
  marker logged.
- **Touches:** `drivers/driver-1.21.1-*`, catalog, `build-logic`.
- **Bootstrap prompt:**
  > Per sub-00 Stage B, add both 1.21.1 driver subprojects with the §2
  > toolchains (NeoGradle only if MDG blocks; toolchain plugins only inside
  > their own subproject). Minimal entrypoints logging the cell marker.
  > Acceptance: both build and boot headless to `Done` + marker. §5.10; no
  > Mixins (§5.9). Parallel with Stage C.

### Stage C — 26.x driver scaffolds

- [x] **Do:** Both 26.x driver subprojects per §2 (MDG and non-remapping loom,
  Java 25, no mappings); game versions via catalog `mc26Latest`/
  `mc26Previous`; entrypoints mirroring B.
- **Acceptance:** both build on Java 25; headless boot; 26.x Fabric log shows
  no remapping work.
- **Touches:** `drivers/driver-26.x-*`, catalog, `build-logic`.
- **Bootstrap prompt:**
  > Per sub-00 Stage C, add both 26.x driver subprojects with the §2
  > toolchains (game ships unobfuscated — no mappings step), versions via
  > catalog drop-window constants, entrypoints as in 1.21.1. Acceptance: both
  > build and boot headless; zero remapping in the Fabric log. Parallel with
  > Stage B.

### Stage D — CI matrix + boot smoke

- [x] **Do:** `.github/workflows/ci.yml` per §2: matrix compile, per-cell
  headless server boot smoke (timeout + log assertion), Prime Invariant jar
  scan; Gradle/loom/MDG caching.
- **Acceptance:** workflow green on push; smoke proven to fail with the marker
  removed (temporary break, revert).
- **Touches:** `.github/workflows/`, driver run configs.
- **Bootstrap prompt:**
  > Per sub-00 Stage D, write GH Actions CI: matrix build over 4 cells × JDK
  > {21,25} (drivers only on their required JDK, shared modules on both);
  > per-cell boot smoke under a timeout, passing only on marker + `Done` then
  > `stop`; jar-scan job failing on any loader/MC package reference in the API
  > jars. Acceptance: green workflow + one demonstrated red run.

### Stage E — artifact stamping & local publishing

- [x] **Do:** `maven-publish` to `<root>/build/repo` on all modules; driver
  `archivesName` cell stamping; `Implementation-Version` + `Vine-Api-Version`
  manifest entries on shared jars; root `publishAllToLocal` task.
- **Acceptance:** all 10 artifacts + POMs under `build/repo/dev/vineengine/`;
  scratch consumer resolves `dev.vineengine:vine-api`. Consumer note: the
  vine-api POM references DFU (§5.1 carve-out), so consumers must also declare
  Mojang's maven (`https://libraries.minecraft.net/`) for the transitive.
- **Touches:** all module build files.
- **Bootstrap prompt:**
  > Per sub-00 Stage E, add maven-publish (local repo at build/repo) to every
  > module: group dev.vineengine, vine-driver-<cell> artifacts with
  > cell-stamped jars, manifest entries on shared jars, aggregate
  > publishAllToLocal. Acceptance: 10 artifacts + POMs local; scratch consumer
  > resolves vine-api. No remote publishing (§11.1).

## 4. Problems & blockers

- **loom + MDG coexistence** — clashing plugin classpaths/lifecycles.
  Mitigation: toolchain plugins strictly per-driver, root clean; escape hatch =
  `drivers/*` as included builds sharing the catalog. Owner: sub-00.
- **Per-subproject toolchains** — one daemon juggling JDK 21+25. Mitigation:
  toolchain specs only (never `sourceCompatibility`); CI installs both JDKs.
- **Configuration-cache compatibility** — loom/MDG have lagged. Mitigation:
  cache mandatory from Stage A; a non-compliant driver is isolated into its
  own included build, never a global disable.
- **Gradle 9.1.0 too old for 26.x loom (landed deviation, Stage C)** — every
  fabric-loom supporting the 26.x non-remapping mode (loom ≥ 1.15) declares a
  Gradle plugin api-version above 9.1.0 (1.15.x → 9.2, 1.17.x → 9.5). Wrapper
  bumped 9.1.0 → 9.6.0 (Fabric's official pairing with loom 1.17 for 26.3,
  https://www.fabricmc.net/2026/09/15/263.html); loom pinned 1.17.21.
  Owner: sub-00.
- **26.x drop-window churn** (~3 drops/year, §9) — contained: a drop bump is a
  two-line catalog edit + CI run; unsupported-runtime boot failure is sub-01's
  driver self-check.

## 5. Verification

Owns no TCK scenario (precedes the harness); its verification *is* the CI
workflow every later scenario runs under: green matrix build, per-cell boot
smoke, Prime Invariant jar scan, local-publish smoke with a scratch consumer.

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.*` (public) /
  `dev.vineengine.vine.internal.*` (SPI, drivers); shared modules Java 21
  bytecode, 26.x drivers Java 25; composition over inheritance; descriptors
  are data.
- **Comment policy:** javadoc on public API stating *why* + invariants;
  build/driver comments explain the version/loader difference absorbed; no
  narration of the obvious.
- **Forbidden:** version-string parsing (§5.12); loader classes in API
  signatures; Mixins outside drivers; global vanilla behavior changes (§5.1);
  implementation-pinning tests; toolchain plugins at root; preprocessor
  plugins (§6); remote publishing.
- **Done means:** all checkboxes ticked, CI green on all four cells, header
  `done`, dashboard row updated in `docs/README.md`.
