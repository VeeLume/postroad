# packcore

Companion mod for a NeoForge 1.21.1 modpack. It implements the pack's own
mechanics — the *civilization layer*: named places, courier depots, mail that
takes time, coins that only buy movement, and the roads that tie it together.

Written in Kotlin (Kotlin for Forge). A Rust core for road planning arrives in a
later increment.

## Status

Increment 1 (places + depots) in progress. See `docs/increment-1.md` for the
design and `docs/` for later increments as they land.

## Building

Requires JDK 21.

```
./gradlew build
```

The jar lands in `build/libs/`. `./gradlew runClient` / `runServer` start a
development instance with the mod loaded.

`tools/gen_courier_post.py` regenerates the courier-post structure NBT
(`uv run tools/gen_courier_post.py`).

## Scope rule

The mod owns only what datapacks and scripts cannot do. Recipes, loot tables,
tags, buyback rates and pool weights are data files, meant to be overridden
from the modpack, not compiled in.

## License

MIT — see `LICENSE`.
