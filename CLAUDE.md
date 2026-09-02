# postroad — working notes for Claude

NeoForge 1.21.1 companion mod for a modpack; Kotlin via Kotlin for Forge. Design docs
live in `docs/` (one per increment). This file is about the code.

## Build

- JDK 21. `./gradlew build` → `build/libs/postroad-<version>.jar`.
- ModDevGradle 2.x (`net.neoforged.moddev`), Kotlin 2.4 (matches Kotlin for Forge 5.12).
- `./gradlew runGameTestServer` runs the game tests under `gametest/` headlessly.
- `./gradlew runServer` / `runClient` start dev instances in `run/`.
- `tools/*.py` are `uv run` scripts (inline dependencies) that generate checked-in assets:
  `gen_courier_post.py` → the courier-post structure NBT, `gen_textures.py` → placeholder textures.
  Regenerate rather than hand-edit.

## Layout (`io.github.veelume.postroad`)

- `Postroad` — entry point; the only place that touches event buses.
- `PostroadConfig` — common config; gameplay numbers the modpack overrides.
- `registry/Registries.kt` — every DeferredRegister (blocks, items, block entities, data
  components, creative tab, data maps, loot-modifier codecs).
- `network/` — `Network` (the single SavedData: places, depots, storage, accounts, ledger),
  `Place`/`LedgerEntry`, `PlaceResolver` (depot position → place).
- `names/` — culture data (`data/postroad/cultures/*.json`) and the deterministic name generator.
- `depot/` — depot block, block entity (holds only its place id), and `DepotInteraction`
  (all click behaviour, server side).
- `loot/` — fresh-loot data component, the global loot modifier that stamps it, client tooltip.
- `worldgen/CourierPostInjector` — appends the courier post to village house pools at server start.
- `command/PostroadCommands` — `/postroad …`.

## Conventions

- **Data over code.** If the modpack could express it as a datapack file (rates, pool weights,
  culture keywords, loot rules), it is a data file with a codec, not a constant.
- **One state object.** All mutable server state goes through `Network`; block entities and
  items only carry ids or stamps. Call `setDirty()` on every mutation (the `TownContainer`
  wrapper does it for storage).
- **Server thread only** in increments 1–4. Anything that will run off-thread later (the road
  planner) must be a pure function over copied data.
- **Event listeners** use the explicit `addListener(Event::class.java, Consumer)` overload —
  the reflective one cannot resolve Kotlin lambdas' generic type.
- **Sneak + item never reaches a block's `useItemOn`** — vanilla treats it as a secondary use
  and goes straight to the item. Interactions that need "sneak with X in hand" are handled in
  `PlayerInteractEvent.RightClickBlock` (see `depot/DepotEvents.kt`) and the event is cancelled.
  `GameTestHelper.useBlock` calls the block directly, so a passing game test does not prove the
  in-game path; keep both.
- **Access transformers** over mixins. Current ATs: `StructureTemplatePool.rawTemplates/templates`.
- Lang keys: `message.postroad.*` for chat feedback, `command.postroad.*` for command output.
- No personal or machine-specific details in this repo (paths, hosts, who plays); those live in
  the owner's notes. Repo docs stay generic.
