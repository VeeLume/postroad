# postroad

Companion mod for a NeoForge 1.21.1 modpack. It implements the pack's own
mechanics — the *civilization layer*: named places, courier depots, mail that
takes time, coins that only buy movement, and the roads that tie it together.

Written in Kotlin (Kotlin for Forge). The planner runs on its own thread inside
the mod; the Rust core the first design called for was dropped in favour of
Kotlin until play shows it is needed.

## What it does

- **Places.** Every village the world generates becomes a named place, its name
  drawn deterministically from the culture its style belongs to.
- **Depots.** Each village gets a **pit stop** where its road arrives: a small
  roofed stand with the depot, the town's sign and a mailbox's counterpart.
  A depot stores for the whole network, buys fresh loot, and keeps accounts.
- **Mail.** Parcels travel by distance, arriving after a number of in-game days;
  bulk goods take one lane, valuables another. Players have their own mailboxes.
- **Travel.** Signposts at junctions and towns are nodes on the road graph. Walk
  a road once to chart it, then travel it for a fare in coins, discounted by the
  quality of the road.
- **Roads.** Villages are predicted from the seed, roads planned between them on
  the real terrain and laid from a catalog of pieces, with lampposts, signposts
  and junctions. Roads join each village on its own street axis.

## Status

**v0.2.0**: increments 1–8 (`docs/`), and pre-generation that leaves the server
thread alone. Everything above works; every road is still the dirt tier. Road
tiers and the road-works quest that upgrades a route are the next release. See
`CHANGELOG.md`.

## Building

Requires JDK 21.

```
./gradlew build
```

The jar lands in `build/libs/`. `./gradlew runClient` / `runServer` start a
development instance with the mod loaded, and `./gradlew runGameTestServer` runs
the game tests headlessly.

`tools/*.py` are `uv run` scripts that regenerate checked-in assets: the courier
post and pit stop structure NBT, the road piece catalog, the game-test arenas
and placeholder textures. Edit the generator, not the file it writes.

## Configuration

`postroad-common.toml` holds the gameplay numbers (fares, mail days, buff
strengths, plan radius) and the ones worth tuning per server (how much terrain
is generated ahead of the planner, what the builder may spend per tick). Road
costs, palettes, piece shapes, cultures and buyback rates are data files under
`data/postroad/`, meant to be overridden from the modpack.

## Scope rule

The mod owns only what datapacks and scripts cannot do. Recipes, loot tables,
tags, buyback rates and pool weights are data files, meant to be overridden
from the modpack, not compiled in.

## License

MIT — see `LICENSE`.
