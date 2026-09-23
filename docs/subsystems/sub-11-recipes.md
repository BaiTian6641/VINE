# SUB-11 — Recipes & crafting

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M3 · **Depends on:** SUB-02, SUB-07 · **Blocks:** SUB-20 (implements this file's `RecipeViewerAdapter` SPI)
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.16, §5.2 · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

Version-free recipe surface: consumers register custom recipe *types*
(machines, rituals, upgrade paths — not just grids) and author recipe
*instances* as datapack JSON with engine extensions — VoxelData predicates in
ingredients, VoxelData on results. Non-goals: replacing vanilla matching for
vanilla types; shipping a recipe GUI (viewers bridge via sub-20).

## 2. Design

**Structural vs dynamic split** (§5.2 locked rule): recipe **types +
serializers** are *structural* (static startup registration via the sub-02
descriptor pipeline). Recipe **instances** are *dynamic* (datapack JSON,
`data/<ns>/recipe/<path>.json`, loaded by the host `RecipeManager`); consumer
instances are cooked per cell by the sub-02 content pipeline — datapacks may
override them, `/reload` re-reads them.

**API surface** (`vine-api`, `dev.vineengine.vine.recipe`):

```java
public sealed interface VineIngredient permits VineIngredient.Item, VineIngredient.Tag, VineIngredient.Data {
  static VineIngredient item(VineId item);
  static VineIngredient tag(VineId itemTag);
  static VineIngredient data(VineId item, DataPredicate match);  // VoxelData predicate (sub-03)
}
public record DataPredicate(Map<VineId, Term> terms) {
  public sealed interface Term permits Eq, Range, Present {}
}
public record RecipeResult(VineId item, int count, VoxelData data) {}  // data -> components (§5.4)
public record RecipeTypeDescriptor(VineId id,
    Codec<? extends RecipeInstance> instanceCodec,               // sub-02 codec rule
    MatchContext.Kind contextKind,                               // GRID_3X3 | SINGLE_SLOT | SLOT_MAP | CUSTOM
    RecipeBookMapping book) {}
public record RecipeBookMapping(BookPolicy policy, @Nullable VineId tabOrCategory) {}
public enum BookPolicy { NONE, VANILLA_CATEGORY, CONSUMER_TAB }
public record RecipeInstance(VineId type, List<VineIngredient> inputs,
    RecipeResult result, @Nullable VineId unlock) {}             // decoded datapack JSON
public interface VineRecipes {
  <C extends MatchContext> Optional<RecipeInstance> find(VineId type, C context);
  void unlock(VinePlayer player, VineId recipeId);               // non-crafting grant
}
```

Instance JSON example (decoded by `instanceCodec`):

```json
{ "type": "mymod:infusion",
  "inputs": [
    { "item": "mymod:void_crystal" },
    { "tag": "mymod:catalysts", "count": 2 },
    { "item": "mymod:rune_blank", "data": { "mymod:tier": { "min": 2 } } } ],
  "result": { "item": "mymod:rune_infused", "count": 1, "data": { "mymod:tier": 3 } },
  "unlock": "mymod:recipes/infusion_tier3" }
```

**Internals** (`vine-core`, `internal.recipe`): ingredient/instance codecs
(JSON + network form), engine registry view mirroring the host
`RecipeManager`, `RecipeMatcher` loop for custom types. Never cache decoded
instances across reloads; re-decode per reload and emit `RecipeReloadedEvent`
(sub-01 bus).

**Driver contract** (`vine-spi`, `internal.spi.RecipeDriver`):

```java
void registerRecipeType(RecipeTypeDescriptor d);              // structural phase
void registerBookCategory(RecipeBookMapping m);               // client
RecipeQueryHandle bindQuery(VineId type, RecipeMatcher<?> m); // RecipeManager bridge
void applyResultData(Object nativeStack, VoxelData data);     // components transcode
```

Per cell: **1.21.1-NF** — `DeferredRegister` on `RECIPE_TYPE`/
`RECIPE_SERIALIZER`, book categories via `RegisterRecipeBookCategoriesEvent`,
`data()` via NeoForge `ICustomIngredient`, unlock via `RecipeUnlockedTrigger`.
**1.21.1-Fabric** — `Registry.register(Registries.RECIPE_*)`, `data()` via
Fabric `CustomIngredientSerializer`, book categories have no first-party event
(spike, §4). **26.x** — same shape, unobfuscated; probe
`VineEngine.supports("recipes.dynamic-registry")` for instance-loading drift,
never version strings.

## 3. Stages

### Stage A — Ingredient DSL & codecs

- [ ] **Do:** records/sealed types above; JSON + network codecs; golden
  fixture = §2 example; round-trip tests.
- **Acceptance:** fixture round-trips identically on both 1.21.1 cells pre-M4; deferred 26.x harness re-run on M4 landing (acceptance-matrix rule).
- **Touches:** vine-api `recipe`, vine-core `internal.recipe.codec`.
- **Bootstrap prompt:**
  > Implement SUB-11 Stage A per `docs/subsystems/sub-11-recipes.md` §2 (read
  > `vine-engine-plan.md` §5.16/§5.2 + `docs/TEMPLATE.md` first; Prime
  > Invariant, Java 21 bytecode). Use sub-02 descriptor codecs + sub-03
  > `VoxelData`. Acceptance: §2 JSON fixture round-trips through the codecs.

### Stage B — Structural type/serializer registration

- [ ] **Do:** `RecipeTypeDescriptor` through the sub-02 structural pipeline;
  `registerRecipeType` in all four drivers; testmod declares `mymod:infusion`.
- **Acceptance:** type + serializer present in native registries on the two
  1.21.1 cells pre-M4 (boot log line per cell); deferred 26.x re-run on M4
  landing (acceptance-matrix rule).
- **Touches:** vine-spi, vine-core, drivers/*, vine-testmod.
- **Bootstrap prompt:**
  > Wire `RecipeTypeDescriptor` into sub-02 structural registration; implement
  > `registerRecipeType` per cell per sub-11 §2 driver notes (NeoForge
  > DeferredRegister; Fabric `Registry.register(Registries…)`; 26.x feature
  > probes; Mixins only in drivers §5.9). Acceptance: testmod recipe type in
  > native registries on both 1.21.1 cells at boot (26.x re-run deferred to M4).

### Stage C — Instance pipeline, matching, reload

- [ ] **Do:** datapack instance loading via content pipeline;
  `RecipeMatcher`/`MatchContext`; `RecipeQueryHandle` per driver; `data()`
  native ingredient subclasses; `RecipeReloadedEvent`; result VoxelData→
  component transcode; `VineRecipes.find`.
- **Acceptance:** TCK `recipe-materialization` green on ≥2 drivers (one per
  loader family): `infusion` matches a `data()` input, result stack carries
  the VoxelData, datapack override after `/reload` takes effect.
- **Touches:** vine-core, vine-spi, drivers/*, vine-testmod, vine-tck.
- **Bootstrap prompt:**
  > Implement SUB-11 Stage C per `docs/subsystems/sub-11-recipes.md`: instances
  > ride the host RecipeManager, no cross-reload caching, `RecipeReloadedEvent`
  > on the sub-01 bus, custom-ingredient subclasses per loader
  > (`ICustomIngredient` / `CustomIngredientSerializer`). Acceptance: TCK
  > `recipe-materialization` incl. reload-override round, one NF + one Fabric cell.

### Stage D — Recipe book & unlock hooks (parallel with C)

- [ ] **Do:** `RecipeBookMapping` registration per cell; `unlock` advancement
  wiring; `VineRecipes.unlock`.
- **Acceptance:** testmod type under its mapped book tab on both loaders;
  unlock fires on craft and via API.
- **Touches:** vine-api, vine-spi, drivers/* (client), vine-testmod.
- **Bootstrap prompt:**
  > Implement SUB-11 Stage D per `docs/subsystems/sub-11-recipes.md`: book
  > category mapping (`NONE`/`VANILLA_CATEGORY`/`CONSUMER_TAB`) per loader +
  > unlock hooks; client code only in drivers/vine-client-api. Acceptance:
  > mapped tab + both unlock paths on 1.21.1-NF and 1.21.1-Fabric.

### Stage E — Viewer-bridge SPI (handoff to sub-20)

- [ ] **Do:** `RecipeViewerAdapter` SPI in vine-spi: `void expose(VineId
  recipeType, RecipeViewLayout layout)`; `RecipeViewLayout(List<ViewSlot>
  slots, @Nullable VineId background, int width, int height)`. JEI/REI/EMI
  adapters live in sub-20 — here only the SPI + a TCK probe adapter.
- **Acceptance:** TCK probe receives every testmod custom type's layout on one
  cell per loader family.
- **Touches:** vine-spi, vine-tck.
- **Bootstrap prompt:**
  > Define the SUB-11 viewer-bridge SPI per `docs/subsystems/sub-11-recipes.md`
  > Stage E (engine owns layout data; viewer mods are sub-20 bridges). Ship SPI
  > + TCK probe adapter. Acceptance: probe observes all testmod recipe-type
  > layouts on one NeoForge and one Fabric cell.

## 4. Problems & blockers

- **Predicate expressiveness vs vanilla JSON.** `data()` exceeds vanilla
  ingredient JSON; compiles to per-loader custom-ingredient APIs (both loader
  families ship one). Vanilla-only consumers ignore the extension. Owner: here.
- **Grid-less types in viewers.** JEI/REI/EMI assume slot grids;
  `RecipeViewLayout` carries explicit slot geometry, non-slot systems fall
  back to generic list rendering. Owner: sub-20, against Stage E contract.
- **Reload vs `REGISTRIES_FROZEN`.** Types freeze at bootstrap, instances
  reload freely. Decode lazily against the frozen type table; unknown type →
  log + skip (vanilla behavior), never crash. Owner: here.
- **Fabric book-category drift** (no NeoForge-event equivalent on 1.21.1) —
  Stage D spike; fallback `NONE` on that cell behind a feature probe.

## 5. Verification

- TCK `recipe-materialization` (owned here, plan §7): structural type +
  datapack instance + `data()` match + VoxelData on result. Ship gate: ≥2
  drivers, one per loader family (§8).
- Golden fixture: §2 JSON round-trips through codec and each driver's native
  serializer. Reload fixture: datapack override visible after `/reload`.
- Client smoke: book tab present; unlock toast on grant.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.recipe` (public) /
  `dev.vineengine.vine.internal.recipe` + `internal.spi`; shared modules Java
  21. Descriptors are data (Java + JSON paths).
- **Comment policy:** javadoc states why/invariants (`data()` evaluation is
  server-authoritative; no cross-reload caching); driver comments name the
  per-loader difference absorbed.
- **Forbidden:** version-string parsing (probes §5.12); `net.minecraft.*`/
  loader types in API signatures; Mixins outside drivers; touching vanilla
  recipes without consumer opt-in (Minimal Footprint §5.1).
- **Done means:** all boxes ticked, acceptance green on all cells, status
  `done`, dashboard row in `docs/README.md` updated.
