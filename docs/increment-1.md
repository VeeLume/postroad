# Increment 1 — Places and depots

The first shippable slice of the civilization layer. After this increment a
village has a courier post with a depot block inside; the depot names the town,
holds the town's shared storage, accepts coins into a ledger, and buys fresh
loot. Nothing here talks to other towns yet — that is increment 2.

## Scope

In:

- **Places registry** — every town that has a depot is a place with a stable
  id, a procedural name, a culture, a type and a position.
- **Courier post** — a small building injected into village house pools;
  contains one depot block.
- **Depot block** — opens the town storage; sneak-use pays coins in or sells
  fresh loot; shows the wallet balance.
- **Coins** — a mod item, obtained only through loot tables, bounties, quests and
  depot buyback. Never craftable. Never withdrawable.
- **Ledger** — every coin movement, network-wide, visible in game.
- **Fresh loot** — a data component stamped on items generated from container
  loot and mob drops; it expires ("settles") after a configurable number of
  in-game days.
- **Commands** — `/postroad places|balance|ledger|inspect|rates` for everyone,
  `/postroad grant` for ops (testing only).

Out (later increments): postal network, mailboxes, delivery lanes, signs,
teleport, roads, advancements, custom screens.

## Rules that shape the code

- **The mod owns only what data layers cannot do.** Loot tables, tags, recipes
  and configs live in the modpack repo. Everything in this increment that could
  be a datapack file *is* one: buyback rates are a NeoForge data map, the
  courier-post building is a structure NBT, fresh-loot origins are a global loot
  modifier JSON. The code is the mechanism, the data is the tuning.
- **One server-side network object.** All state below lives in a single
  `SavedData` on the overworld (`postroad_network`). It is the only mutable
  state; block entities hold only their place id.
- **Server thread only.** Nothing in this increment runs off-thread. That
  discipline starts to matter in increment 5.
- **No player depends on another.** Storage and mailboxes are shared and
  unlocked; there are no owners or permissions.

## Data model

```
Network (SavedData "postroad_network")
├── places: Map<PlaceId, Place>
│     Place { id, name, culture, type, dimension, pos, discoveredDay }
├── depots: Map<GlobalPos, PlaceId>          // depot block → its town
├── storage: Map<PlaceId, ItemContainer(54)> // town storage (same-town only)
├── accounts: Map<AccountId, Long>           // "player:<name>" and "fund:road"
└── ledger: List<LedgerEntry>                // capped, newest last
      LedgerEntry { day, actor, op, amount, account, note }
```

- `PlaceId` is a string derived from the structure start: `<dim>/<chunkX>/<chunkZ>`
  of the structure's origin chunk. Two depots in the same village resolve to
  the same place, so "one depot per town" degrades gracefully to "at least one".
- `type` is `village` or `tavern` (taverns are not injected in this increment,
  the field exists so increment 2 does not migrate data).
- `culture` is the key that selects a name generator. It is derived from the
  structure id the depot was generated inside, through a keyword table
  (`meadow_swiss` → `alpine`, `flower_forest_japanese` → `japanese`,
  `snowy_taiga_viking` → `norse`, vanilla `village_plains` → `plains`, …) with a
  `common` fallback. The table is a data file (`data/postroad/cultures.json`)
  so the modpack can extend it without a mod release.

## Naming

Names are deterministic: `hash(worldSeed, placeId)` seeds a small PRNG that
picks from the culture's syllable lists. Each culture is a JSON file
(`data/postroad/names/<culture>.json`) with `prefixes`, `middles`, `suffixes`
and a `pattern` list; a name is one pattern instance. Collisions within a
world are resolved by appending a distinguishing suffix from the culture's
`disambiguators` list. Names are never editable in game.

## Fresh loot

- Data component `postroad:fresh_loot { origin: "container" | "entity", day: int }`.
- Stamped by a global loot modifier. It classifies by the loot context, not
  the table id: a block state means a block drop (not stamped), a damage
  source means a mob drop (`entity`), everything else is container loot
  (`container`). Modded structure tables with arbitrary paths therefore count
  without configuration; the modifier's data file can still exclude or
  re-label table families by path prefix. Lootr generates per-player loot
  through the ordinary loot-table path, so instancing needs no special
  handling.
- `day` is the in-game day index (`gameTime / 24000`), not the tick, so loot
  taken on the same day stacks.
- An item is fresh while `currentDay - day < settleDays` (config, default 3).
  Nothing removes the component; it simply stops counting. Increment 2 reads
  the same predicate for the valuables lane.
- Tooltip line while fresh: "Fresh loot · settles in N days". Items the depot
  buys also show the rate ("Depot pays N coins each"), gold while fresh, grey
  otherwise — the buyback data map is synced to clients for this, so nobody
  has to test items at a depot.

## Coins and the ledger

- Item `postroad:coin`, stack size 64, no recipe. A creative-tab entry only.
- Pay-in: sneak-use the depot with coins in the main hand → the whole stack is
  consumed and credited to `player:<name>`; ledger entry `pay_in`.
- Buyback: sneak-use the depot with a fresh-loot item that has a data-map
  entry (`postroad:buyback`, item → coins per unit) → the stack is consumed and
  credited; ledger entry `buyback` with the item id in `note`. Items that are
  not fresh, or not in the data map, are refused with a message.
- Sneak-use with an empty hand → chat shows the town name, wallet balance,
  road fund balance and the last five ledger entries.
- Sneak-use with an item in hand is intercepted in the right-click event
  (vanilla would otherwise hand the click to the item, never the block).
- There is no withdrawal. Balances only ever decrease through fares and road
  works in later increments.
- **What the table covers.** The freshness stamp already restricts selling to
  loot, so the table's job is to say which loot is *treasure*. It lists the
  valuables that the pack's container tables actually roll (ores, gems,
  potions, tools, keys, discs, templates, horse armour, netherite) with a
  scale where a diamond beats a stack of iron. Food and farmables (bread,
  wheat, potatoes, apples) and junk (arrows, string, bones, rotten flesh) are
  deliberately absent: a village barrel is a snack, not an income.
  `/postroad rates` prints the live table.

## Courier post

- Structure NBTs `data/postroad/structure/courier_post_<style>.nbt`, one per
  palette style (oak, spruce, birch, acacia, jungle, cherry, dark_oak, desert,
  badlands, iberian), all generated from one shape by
  `tools/gen_courier_post.py` (no in-game structure-block workflow needed to
  reproduce them). Footprint 7×7, floor at y=0 like vanilla houses, one
  `building_entrance` jigsaw on the west edge at y=0 so village streets attach
  the same way they attach vanilla houses.
- The style for a pool comes from the culture whose keywords match the pool
  id (`post` field in the culture file) — the same lookup that picks a town's
  name style, so a Swiss meadow village gets a spruce post and a desert
  village a sandstone one.
- **One depot per town.** Jigsaw has no per-village cap, so the second and
  later worldgen depots in a town demote themselves to barrels on their first
  tick (the building stays, as an ordinary house). Player-placed depots are
  flagged at placement and never demoted; they simply join the town.
- Injection: on `ServerAboutToStartEvent`, for each target pool in the config
  list the mod appends a `single_pool_element` for the courier post with the
  configured weight. Default targets: the six vanilla `village/<biome>/houses`
  pools, the twenty Towns and Towers `kaisyn:village/*/houses` pools and the
  six `biomeswevegone:village/*/houses` pools. Adding a pool is a config edit.
  Pools that do not exist are skipped with a log line, so the list is safe
  across modpack changes.
- Placement uses an access transformer on `StructureTemplatePool`'s two
  element lists; no mixins.
- On first server tick after being placed by worldgen, the depot block entity
  looks up the structure start it sits in, derives place id and culture, and
  registers the place. A depot placed by a player outside any structure
  registers a `founded` place at its own position (this is the hook for
  player-founded places later; in this increment it exists only so the block is
  testable in a flat world).

## Storage UI

Town storage opens as a vanilla 6-row chest menu backed by the network's
container for that place. No custom screen in this increment; the 54-slot
limit is the one this increment ships with.

## Config (`postroad-common.toml`)

- `freshLoot.settleDays` (default 3)
- `courierPost.targetPools` (list of pool ids)
- `courierPost.weight` (default 6)
- `ledger.maxEntries` (default 500)

## Test plan

On a fresh world with the modpack's seed:

1. Two villages visited → two courier posts, two distinct names, `/postroad
   places` lists both with cultures matching their village style.
2. Deposit in village A's depot; the items are visible in A and absent in B.
3. Open a Lootr chest; the items show the fresh-loot tooltip; a crafted item
   does not.
4. Sneak-use with coins → balance increases; `/postroad ledger` shows the entry.
5. Sneak-use with a fresh emerald (data map entry) → coins credited; the same
   emerald after `/time add` past the settle window is refused.
6. Server restart preserves places, storage, balances and ledger.
