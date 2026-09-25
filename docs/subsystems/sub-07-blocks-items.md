# SUB-07 — Blocks & items

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 minimal → M3 full · **Depends on:** SUB-02, SUB-03 · **Blocks:** SUB-11
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.3 (+ §5.2, §5.4, §5.8) · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

Turns sub-02's descriptor machinery into the two foundational content kinds:
blocks (with flattened property states and `VoxelData`-backed block entities)
and items (with durability as engine data, not identity). Content is
**behavior composition, not inheritance** — a VINE block/item is a descriptor
plus behavior interfaces; drivers construct the native singleton delegates, so
no class hierarchy crosses the API boundary (§5.3). Non-goals: client
rendering/model baking/BER wiring (deferred to sub-16/sub-17 — drivers ship
placeholder vanilla-style models only), recipes (sub-11), capabilities
(sub-04), worldgen placement (sub-13).

## 2. Design

**API surface** (`dev.vineengine.vine.content`, vine-api):

```java
public record Property<T extends Comparable<T>>(String name, List<T> values, Class<T> type) { }
// canonical factories: Property.bool(name), Property.intRange(name, min, max), Property.ofEnum(...)

public record BlockDescriptor(
    VineId id,
    List<Property<?>> properties,            // flattened state model; only model on all cells (post-1.13)
    BlockTuning tuning,                      // hardness, resistance, tool/tier hints, light, friction…
    Optional<BlockEntityDescriptor> blockEntity,
    List<BlockBehavior> behaviors,
    ModelHint model                          // cooked by sub-02 pipeline; consumed by sub-16/17 later
) { }

public record BlockEntityDescriptor(Codec<VoxelData> schema, boolean ticking, int tickInterval) { }

public sealed interface BlockBehavior { }
public non-sealed interface UseBehavior extends BlockBehavior {
    UseResult onUse(BlockUseContext ctx);            // ctx: world handle, pos, state, player, hand, hit
}
public non-sealed interface TickBehavior extends BlockBehavior {
    void tick(BlockTickContext ctx);                 // random + scheduled ticks normalized
}
public non-sealed interface LootBehavior extends BlockBehavior {
    void modifyLoot(LootContext ctx, LootSink out);  // engine-normalized loot modification
}

public record ItemDescriptor(
    VineId id,
    ItemTuning tuning,                         // stack size, rarity, fire-resistant…
    Optional<DurabilitySpec> durability,       // max durability; stored as VoxelData key vine:durability
    List<VineId> creativeTabs,
    List<ItemBehavior> behaviors,
    ModelHint model
) { }

public sealed interface ItemBehavior { }       // item hook family (§5.8 normalized hooks)
public non-sealed interface UseItemBehavior extends ItemBehavior { UseResult onUse(ItemUseContext ctx); }
public non-sealed interface TickItemBehavior extends ItemBehavior { void inventoryTick(ItemTickContext ctx); }
public non-sealed interface CraftBehavior extends ItemBehavior { void onCrafted(CraftContext ctx); }
public non-sealed interface RepairBehavior extends ItemBehavior { boolean canRepairWith(RepairContext ctx); }
public non-sealed interface DurabilityChangeBehavior extends ItemBehavior {
    void onDurabilityChange(ItemHandle item, int oldValue, int newValue, DamageCause cause);
}

public record CreativeTabDescriptor(VineId id, VineId icon, List<VineId> entries) { }
```

Both records are sub-02 **structural** `DescriptorType`s
(`vine:block`, `vine:item`); `LootBehavior`-driven modifiers are a **design**
descriptor riding dynamic registries so datapacks override them (§5.2).
Durability is a `VoxelData` (sub-03) data key, never an item identity axis —
one item type, damage as data (§5.3/§5.4).

**Internals** (`dev.vineengine.vine.internal.content`, vine-core):

- State table: cartesian product of `properties`; guarded by a hard budget
  (default ≤ 512 states/block, descriptor rejected with a diagnostic listing
  the product terms). Default state = first value of each property unless
  tuned.
- Behavior dispatch: per-descriptor ordered lists; empty list = dormant
  (Minimal Footprint — no hook installed at all).
- BlockEntity host: stores a `VoxelData` root validated against the
  descriptor's `Codec` schema; ticking BEs batch into one driver-level ticker
  per chunk region; `ticking=false` BEs register no ticker.
- `ItemHandle`/`BlockUseContext` etc. are engine views — no native types leak.

**Driver contract** (vine-spi `ContentDriver` + per-cell notes):

- Construct native singleton delegates from descriptors at sub-02 Stage-B
  phase; wire behaviors into native dispatch only for behaviors present.
- **NF cells:** item extension points via NF's `IItemExtension` overrides on
  the delegate; block loot via Global Loot Modifiers for `LootBehavior`-as-
  modifier; creative tabs via `BuildCreativeModeTabContentsEvent`.
- **Fabric cells:** item hooks via delegate class + Fabric API events
  (`UseBlockCallback`/`UseItemCallback` bridging where the native delegate
  can't see the call); loot via `LootTableEvents.MODIFY`; tabs via
  `FabricItemGroup` + `ItemGroupEvents`.
- **26.x cells:** unobfuscated names, Java 25; item construction requires the
  registry id up front (settings carry the key) — drivers absorb; probe via
  `VineEngine.supports(...)` where drops differ.
- Datagen: per-cell blockstate/model JSON cooked by the sub-02 content
  pipeline from `ModelHint`; **no client render wiring here** — sub-16/17 own
  that phase; until then drivers emit plain cube-all/generated-item models.
- Sync/persistence: block entity `VoxelData` syncs via sub-05 packets on
  chunk watch; durability key round-trips through sub-03 components.

## 3. Stages

### Stage A — M0 minimal: one block, one item

- [x] **Do:** `BlockDescriptor`/`ItemDescriptor` skeletons (id + minimal
  tuning only, no behaviors/states/BEs), registered as sub-02 structural
  types; testmod registers one block + one item; materialization on both
  1.21.1 drivers; placeholder cube-all/generated models.
- **Acceptance:** M0 spike gate: identical testmod source boots, block
  places/breaks, item exists in inventory, on 1.21.1-NF **and** 1.21.1-Fabric.
- **Touches:** vine-api `content`, vine-core, vine-spi, both 1.21.1 drivers, vine-testmod.
- **Bootstrap prompt:**
  > Implement SUB-07 Stage A (plan §5.3; conventions docs/README.md). Add
  > `dev.vineengine.vine.content.BlockDescriptor`/`ItemDescriptor` records
  > (id, tuning, placeholder model hint — no behaviors yet) registered via
  > sub-02 `VineRegistries` as structural types `vine:block`/`vine:item`.
  > Drivers construct native singletons at the sub-02 structural phase.
  > Testmod: one block + one item. Acceptance: same testmod source boots on
  > driver-1.21.1-neoforge and driver-1.21.1-fabric; TCK place/break scenario
  > green on both. No loader types in vine-api; Mixins only in drivers.

### Stage B — flattened properties & state model

- [x] **Do:** `Property` factories, state-table flattening with the 512-state
  budget + diagnostic, default-state rules, state query/mutation API on the
  engine world view, blockstate datagen cooking per cell.
- **Landed (engine half, 2026-09-25):** the whole engine-side model exists and
  compiles, and both 1.21.1 cells are wired to it.
  `Property<T>(name, values, type)` carries `bool` / `intRange` / `ofEnum`
  factories and validates at authoring time: the native name grammar, no
  duplicates, integers strictly ascending inside the vanilla `0..15` carrier
  bound, enum values in declaration order (a subset is legal, a reorder is not —
  it would silently change state identity). Its codec names the value kind
  explicitly (`bool`/`int`/`enum` + `enumClass` for the third), so JSON never
  guesses a type. `BlockDescriptor.properties()` is the descriptor's second
  component, defaulted to empty in the codec, so every Stage-A descriptor keeps
  its exact meaning.
  `BlockStateTable` (vine-core `internal.content`) builds the cartesian product,
  indexes states row-major (first property most significant, last varying
  fastest) and is the single place the 512-state budget is enforced: an
  over-budget descriptor throws from `VineEngineImpl.register` with a diagnostic
  that names every product term and the total
  (`block X declares 3 properties with a state product of 4096 states, over the
  512-state budget (terms: a(16) × b(16) × c(16))`). The default state is the
  first declared value of each property — the same rule every cell materializes
  natively, so "freshly placed" means the same state on both sides.
  The state API is on an engine world view: `VineWorlds.overworld()` →
  `VineWorld` (`stateAt`, `setState`, `isLoaded`) → the cell's
  `WorldViewDriver`, bound once per process through `WorldViewBinding` exactly
  like the storage seam. Engine-side policy (only registered blocks are
  addressable, a state's schema must equal the descriptor's declared properties,
  a driver reporting an unregistered block is a bug and is thrown as one) sits in
  `EngineWorldView`, so every cell enforces identical rules; native mapping is
  the driver's.
  The testmod's exemplar is `vine_test:stateblock` — `lit` (bool), `level`
  (int 0..3), `mode` (an enum) = 24 states — with `/vine_test tck_state_get` and
  `/vine_test tck_state_set` driving the API, and a deliberately over-budget
  4096-state descriptor attempted at registration so the refusal (and its
  diagnostic) is observable in a boot log rather than only in a unit test.
- **Landed (cell half, 2026-09-25, both cells):** each cell turns
  the descriptor's axes into its own state carriers and proves the two models
  agree before a block is ever registered. Boolean → the native boolean property,
  bounded integer → the native bounded-int property (the declared values must be
  one contiguous ascending run — anything else has no native carrier and fails
  loudly naming property and values), enum → a small cell-owned
  `Property<E>` subclass, because vanilla's enum property is bound to
  `Enum<T> & StringRepresentable` and an engine enum is a plain enum. The native
  value spelling is the lowercased constant name while the engine's state fragment
  keeps the declared spelling (`mode=IDLE`), and the mapping back goes through the
  declared constant by value, never by name — so the two directions cannot drift.
  The invariant the whole cell rests on — the native default state *is* the
  engine's default state (`BlockState.defaultState`) — is checked in both
  directions at materialization (`requireDefaultState`), so a carrier whose
  default disagrees fails the boot naming the block instead of silently redefining
  what "freshly placed" means. Construction plumbing: a block's state definition
  is built inside its constructor, before any subclass field exists, so the
  descriptor's property list is published thread-locally around construction (a
  static-by-necessity, thread-scoped seam — never a field that cannot be set yet,
  and never a second source of truth).
- **Acceptance met 2026-09-25:** the `state_roundtrip` scenario places
  `vine_test:stateblock`, changes `level` and `mode`, restarts the server and
  reads back `lit=true,level=3,mode=CHARGED`; its first step asserts the boot
  line for the deliberately over-budget 4096-state descriptor
  (`over the 512-state budget`); `:vine-tck:verifyDatagen` byte-matches each
  cell's cooked tree against its committed goldens (4 files per cell). Green on
  both 1.21.1 cells (`:vine-tck:runScenarios1211Fabric` /
  `:vine-tck:runScenarios1211Neoforge`, 33/33 per cell).
- **Touches:** vine-api, vine-core, drivers, vine-testmod, vine-tck.
- **Bootstrap prompt:**
  > Implement SUB-07 Stage B: flattened property/state model (§5.3 — all cells
  > are post-1.13, this is the only model). `Property<T>` with
  > bool/intRange/ofEnum factories; state table = cartesian product, hard
  > budget 512 states with a diagnostic naming the product terms; engine
  > state get/set on the world view; blockstate JSON cooked by the sub-02
  > pipeline. Acceptance: TCK state round-trip on both 1.21.1 drivers + budget
  > rejection test + golden datagen diff.

### Stage C — behavior composition + BlockEntity

- [ ] **Do:** `UseBehavior`/`TickBehavior`/`LootBehavior` dispatch (dormant
  when absent), engine contexts, `BlockEntityDescriptor` with `VoxelData`
  schema validation, batched BE ticking, BE sync via sub-05.
- **Landed (storage half):** `BlockDescriptor` declares engine storage
  (`blockEntity`, now `Optional<BlockEntityDescriptor>` with a schema id, a ticking
  flag and an interval — Stage C above; it began as a plain flag, defaulted in the
  codec so every existing descriptor kept its meaning), and a declared block
  materializes with a block-entity type per cell — Fabric through
  `BlockEntityProvider`, NeoForge through `EntityBlock` plus a
  `RegisterEvent` pass for the block-entity registry (NeoForge forbids nesting one
  registry's registration inside another's, which the first attempt did and the
  placed-block probe caught) — and the entity's payload is the same
  `VoxelData` attach point items, entities and players use. So a placed block
  persists an engine tree across save/reload today, with no behavior language
  existing yet; proven live by the placed-block probe leg (`written=417` on the
  first boot, `survived=417` after a server restart) on both 1.21.1 cells.
  **Behavior composition** (the `UseBehavior`/`TickBehavior`/`LootBehavior`
  dispatch this stage is named for) landed with the behaviors half below, and
  Stage B's property flattening landed 2026-09-25; Stage D's item hooks remain
  open. What still holds this box open is named in the `Remaining` bullet.
- **Landed (engine half, 2026-09-25):** the behavior language and its dispatch exist
  engine-side (`BlockBehavior` + `BehaviorDispatch`). The cell halves — wiring the
  plan into each cell's materializer so a declared block's behaviors actually run —
  are the open work, so the acceptance below is not met.
  Not claimed as landed here: the working tree carries this wave uncommitted (the
  committed baseline this pass was briefed on, `4ed5376`, predates it).
  `BlockBehavior` is a sealed interface permitting exactly `UseBehavior`,
  `TickBehavior` and `LootBehavior` (the families are closed; their implementations
  are `non-sealed`, so a consumer implements them directly). The contexts are engine
  views and carry no loader type: `BlockUseContext` (world, pos, engine state,
  `VinePlayer`, `Hand`, `Direction`, hit `Vec3`), `BlockTickContext` (world, pos,
  state, `TickKind` — random, scheduled and block-entity normalized — and the
  holder's `VoxelData` tree when the block declares one), `LootContext` (with an
  empty miner when the cell cannot name one) and `LootSink` (add drops by engine item
  id, so a behavior never learns whether its cell expresses drops as a loot table, a
  modifier or a direct call). `UseResult` is PASS / HANDLED / DENIED, and dispatch
  stops at the first non-PASS.
  `BehaviorDispatch` (vine-core `internal.content`) is the one place a descriptor's
  ordered list becomes calls, and the one place a cell asks what to wire:
  `plan(blockId)` reports use/tick/loot/ticking/tickInterval, and a cell that follows
  it installs nothing for a block that declares nothing — the Minimal Footprint rule
  made mechanical rather than conventional. The engine also counts ticking blocks as
  they register and prints `[VINE] behaviors: ticking block entities=N` at the freeze,
  so a cell's own installed-ticker count has something to be equal to.
  `BlockDescriptor.blockEntity` is now `Optional<BlockEntityDescriptor>` — a schema id
  (the same registered-schema currency `VineData` speaks), a `ticking` flag and a
  `tickInterval` — and `behaviors` is the ordered list. The JSON codec intentionally
  does not carry behaviors: a behavior is code, and a data form for it needs a
  behavior registry no stage has built; pretending otherwise with a field that decodes
  to nothing would be a lie in the format.
  Consumer-side data access is now symmetric with behaviors: `VineWorld.dataAt(pos)`
  opens the holder's tree through the engine's storage path (the cell supplies the
  attach point via `WorldViewDriver.holderTarget`), so reading a placed block's tree is
  the same object its tick behavior writes, not a copy.
- **Acceptance:** TCK: testmod ticking counter-BE persists its count across
  save/load on both loaders; `onUse` fires with correct context; BE-less
  blocks install zero tickers (boot assertion).
- **Evidence (2026-09-25, both 1.21.1 cells):** `behavior_tick_counter` green on
  both cells — 40 server ticks at the declared interval 20 produce exactly `count=2`
  (per-holder interval arithmetic, driven through the `AdvanceTicks` barrier), the
  count survives a `SaveReloadWorld` as `count=2`, and 20 further ticks reach
  `count=3` — with the engine printing `[VINE] behaviors: ticking block entities=1`
  and each cell printing its own `installed tickers=1` from the counts it actually
  installed, so "a block with no ticking block entity installs no ticker" is a
  compared pair of numbers rather than a claim. `behavior_loot_drops` green on both
  cells: a `/setblock … destroy` removal (no player) reaches the loot behavior,
  which prints the full context (`miner=none`, `added=vine_test:testitem`) and each
  cell reports `loot applied=1`. `onUse` is proven per cell through the loader's own
  GameTest helper driving a real interaction on a placed counter block, asserting
  the engine's printed context (position, block, player, hand, face, hit) — Fabric
  3/3 tests, NeoForge 2/2.
- **Cell-seam notes (deliberate, not gaps):** Fabric hangs loot off the block's own
  removal callback, so it fires on *every* removal; NeoForge uses `BlockDropsEvent`,
  which fires where drops are actually resolved (`dropBlock=true`), so a removal
  that drops nothing does not reach a loot behavior there — the no-breaker case is
  covered on both. Global Loot Modifiers were considered on NeoForge and rejected
  as the *less* close seam: they fire inside every loot roll in the game and are
  enabled by datapack content rather than by a block's plan, while `BlockDropsEvent`
  fires exactly where the engine's `LootContext` says it should with the drop list
  in hand. For use, NeoForge hooks `Block#useItemOn` rather than
  `PlayerInteractEvent.RightClickBlock`: the event is posted by exactly one vanilla
  caller and never by the GameTest helper the acceptance drives, and installing both
  layers would run a real player's behavior twice, since the event does not stop
  vanilla's own `useItemOn` call from following it.
- **Remaining (named, not implied):** block-entity **sync to clients** — the
  `Do` line's "BE sync via sub-05" — is not landed: the payload rides the storage
  path (persist/save/reload), and pushing it to clients needs the sub-03 client
  filtering and the sub-05 delta transport that sub-03's Stage E still lists as
  remaining. Nothing else in this stage is open once the cell halves land.
- **Touches:** vine-api, vine-core, vine-spi, drivers, vine-testmod.
- **Bootstrap prompt:**
  > Implement SUB-07 Stage C: behavior composition (§5.3 composition over
  > inheritance). Sealed `BlockBehavior` family with ordered per-descriptor
  > dispatch; absent behaviors = no hooks (Minimal Footprint). BlockEntity
  > backed by a `VoxelData` root validated against the descriptor's `Codec`;
  > ticking BEs batched per chunk region. Acceptance: TCK counter-BE
  > save/load round-trip + use-context scenario green on both 1.21.1 drivers.

### Stage D — item hook family + durability-as-data

- [ ] **Do:** the five `ItemBehavior` interfaces end-to-end; durability as
  `VoxelData` key `vine:durability` (max from `DurabilitySpec`); NF
  `IItemExtension` vs Fabric event/delegate bridging; damage/repair paths
  routed through `DurabilityChangeBehavior`.
- **Acceptance:** TCK: durability decrements on use, `onDurabilityChange`
  observes old/new/cause, repair behavior gates anvil/crafting repair, all
  persisted across save/load identically on both 1.21.1 loaders.
- **Touches:** vine-api, vine-core, drivers, vine-testmod.
- **Bootstrap prompt:**
  > Implement SUB-07 Stage D (§5.3 items + §5.8 item hook family): use /
  > inventoryTick / onCrafted / canRepairWith / onDurabilityChange behaviors;
  > durability is a sub-03 `VoxelData` key, never item identity. Per-loader:
  > NF item extension overrides, Fabric delegate + API events. Acceptance:
  > TCK durability + hook scenarios on both 1.21.1 drivers.

### Stage E — creative tabs + loot hooks *(parallelizable with D)*

- [ ] **Do:** `CreativeTabDescriptor` (structural type `vine:creative_tab`,
  entries referencing content `VineId`s, vanilla-tab insertion by id);
  `LootBehavior`-as-design-descriptor mapped to NF GLM / Fabric
  `LootTableEvents.MODIFY`; datapack override path.
- **Acceptance:** TCK: testmod tab appears with declared entries on both
  loaders; loot modifier fires on block break and is overridden by a datapack
  fixture.
- **Touches:** vine-api, vine-core, drivers, vine-testmod, vine-tck.
- **Bootstrap prompt:**
  > Implement SUB-07 Stage E: creative-tab descriptor + per-loader tab
  > registration (NF `BuildCreativeModeTabContentsEvent`, Fabric
  > `FabricItemGroup`/`ItemGroupEvents`), and engine loot hooks normalized
  > over NF Global Loot Modifiers / Fabric `LootTableEvents.MODIFY`, exposed
  > as a sub-02 design descriptor so datapacks override them. Acceptance: TCK
  > tab + loot-override scenarios on both 1.21.1 loaders.

## 4. Problems & blockers

- **State-count / datagen explosion** — properties multiply states and
  blockstate JSON combinatorially. Mitigation: 512-state budget at
  registration, datagen emits only declared `ModelHint` variants; consumers
  with genuinely huge state spaces split blocks (javadoc guidance).
- **BE tick perf** — naive per-BE ticking scales poorly. Mitigation: batched
  region ticker + `ticking=false` default; perf assertion rides the §7
  performance scenario budget.
- **Item frame / armor stand edge behaviors** — these entities interact with
  items outside normal use paths and differ per loader. Mitigation: route
  frame/stand interactions through the engine use pipeline where the loader
  exposes an event; document uncovered edges as known limits until concrete
  demand (decision owner: this file).
- **Loot-table vs loot-modifier split** — vanilla datapack tables vs NF GLM
  vs Fabric `LootTableEvents` have different granularity and ordering.
  Mitigation: `LootBehavior`/`LootSink` is the normalized floor; pack makers
  keep the vanilla table override path via sub-02 design descriptors.
- **26.x item-construction churn** (id-in-settings, unobfuscated renames).
  Mitigation: drivers absorb; `supports()` probes; spike with sub-19.

## 5. Verification

Owned TCK scenarios (1.21.1-NF + 1.21.1-Fabric; 26.x after sub-19):

1. M0 gate: identical testmod source — block place/break + item presence, both loaders.
2. Property state round-trip: place → mutate → save/load; over-budget rejection.
3. BlockEntity `VoxelData` persistence + batched ticking + zero-hook dormancy assertion.
4. Durability-as-data: use/repair/change-hook sequence persisted via golden fixture.
5. Creative tab contents + datapack-overridden loot hook.
6. Datagen golden fixtures (blockstates/item models) byte-identical per cell.
Client-smoke checklist: block renders with placeholder model; item icon in tab.

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.*` (public) /
  `dev.vineengine.vine.internal.*` (SPI, drivers); shared modules Java 21
  bytecode; 26.x drivers may use Java 25. Behavior composition over
  inheritance — consumer-visible content classes are forbidden. Descriptors
  are data (Java + JSON paths, sub-02 machinery).
- **Comment policy:** javadoc states *why* and invariants (e.g. "durability
  is data, not identity — never branch on damage in equality"); driver
  comments name the absorbed loader/version difference; no narration.
- **Forbidden:** version-string parsing (feature probes only, §5.12); loader
  classes in API signatures; Mixins outside drivers; global vanilla behavior
  changes (absent behavior = absent hook, Minimal Footprint §5.1); client
  render wiring (belongs to sub-16/17).
- **Done means:** every stage checkbox ticked, acceptance checks green on all
  cells this file covers, status header set to `done`, dashboard row updated
  in `docs/README.md`.
