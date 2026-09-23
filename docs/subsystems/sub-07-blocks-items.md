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

- [ ] **Do:** `Property` factories, state-table flattening with the 512-state
  budget + diagnostic, default-state rules, state query/mutation API on the
  engine world view, blockstate datagen cooking per cell.
- **Acceptance:** TCK: multi-property testmod block round-trips place →
  state-change → save/load on both loaders; over-budget descriptor rejected
  with readable error; datagen golden fixtures match.
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
- **Acceptance:** TCK: testmod ticking counter-BE persists its count across
  save/load on both loaders; `onUse` fires with correct context; BE-less
  blocks install zero tickers (boot assertion).
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
