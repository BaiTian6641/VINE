# SUB-XX — <Subsystem name>

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** Mx · **Depends on:** SUB-YY · **Blocks:** SUB-ZZ
> **Cells:** all | 1.21.1 | 26.x · **Loaders:** both
> **Master plan:** §x.y · **Module(s):** vine-api | vine-client-api | vine-core | vine-spi | drivers

<!--
This template is BINDING. Executing agents read exactly one subsystem file and
must find everything they need inside it + the master plan §-refs. Dense, no
filler; the observed cohort band is ≈9–14 KB — content completeness (stages,
prompts, driver contracts) always wins over size.
-->

## 1. Purpose

What this subsystem is for; the consumer-visible capability it creates (2–6
sentences). State explicit non-goals — what this subsystem does NOT do.

## 2. Design

How it works:

- **API surface** (`vine-api` / `vine-client-api`): Java-ish signatures in
  fenced blocks. Precise types. No `net.minecraft.*`, no loader types, no
  Mixins in signatures (Prime Invariant).
- **Internals** (`vine-core`): version-free mechanics, data structures,
  ownership and lifecycle.
- **Driver contract** (`vine-spi` + per-cell notes): what each driver must
  implement, and the known per-cell differences (1.21.1-NF / 1.21.1-Fabric /
  26.x-NF / 26.x-Fabric). Version/loader differences live here, never in API
  shape.
- **Data flow / sync / persistence** story where relevant.

## 3. Stages

Tiny, ordered, independently verifiable increments (each ≤ one focused agent
session). Stages that may run in parallel say so.

### Stage A — <name>

- [ ] **Do:** concrete work items.
- **Acceptance:** observable check (TCK scenario / testmod behavior /
  compile+boot log line).
- **Touches:** modules/packages.
- **Bootstrap prompt:**
  > Self-contained instruction block the executing agent can be started with
  > verbatim: context, files to create/modify, the acceptance check, and the
  > binding conventions pointer.

### Stage B — …

## 4. Problems & blockers

Known risks, version/loader differences, spike items, honest unknowns — each
with a mitigation or a stated decision owner.

## 5. Verification

TCK scenarios this subsystem owns; golden fixtures; performance budgets;
client-smoke checklist items. Every API surface ships only with a TCK scenario
passing on ≥2 drivers (one per loader family) — master plan §8.

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.*` (public) /
  `dev.vineengine.vine.internal.*` (SPI, drivers); shared modules compile to
  Java 21 bytecode; 26.x drivers may use Java 25. Behavior composition over
  inheritance. Descriptors are data (Java + JSON paths).
- **Comment policy:** javadoc on every public API element stating *why* and
  invariants, not *what*; driver code comments explain the specific
  version/loader difference being absorbed; no narration of the obvious.
- **Forbidden:** version-string parsing (feature probes only, §5.12); loader
  classes in API signatures; Mixins outside drivers; global vanilla behavior
  changes (Minimal Footprint, §5.1); tests that pin implementation instead of
  observable contract.
- **Done means:** every stage checkbox ticked, acceptance checks green on all
  cells this file covers, status header set to `done`, dashboard row updated in
  `docs/README.md`.
