# Changelog

## 0.1.0

The first release: the civilization layer as increments 1–8 built it.

### Places and depots

- Every village becomes a named place, named deterministically from the culture
  its village style belongs to, so a Swiss meadow village never draws a Japanese
  name.
- Each village gets one depot, as a **pit stop** beside the end of its first road
  (at a street exit when no road reaches it): a roofed stand with the depot, a
  barrel, a hay bale and a sign carrying the town's name.
- A depot stores for the whole network, buys fresh loot for coins by a buyback
  table, and keeps personal and shared accounts.

### Mail and travel

- Parcels take in-game days by distance, in two lanes: stackables move fast,
  valuables slowly, and express pays coins to skip days. Players get their own
  mailboxes, delivered to by name.
- Charting a road by walking it once opens it for travel. Signposts and town
  signs are the nodes; a fare in coins buys the journey, discounted by the road.

### Roads

- Villages are predicted from the seed before their chunks exist, roads planned
  between them on real terrain, and laid from a catalog of pieces (straights,
  diagonals, corners, bends per rise) with lampposts and junction signposts.
- Roads join a village on its street axis, at the street's own stub.
- Roads generate with the chunk as a placed feature; a chunk-load builder is the
  fallback for chunks that were finished before the plan reached them.
- A pair of villages the existing roads already connect within 1.4× its straight
  distance gets no road of its own.
- Terrain the planner has not seen is generated ahead of it, corridor by
  corridor, nearest player first.

### For pack authors

- Costs, palettes, piece shapes, cultures, road rules and the buyback table are
  data files under `data/postroad/`. Structures that are towns
  (`#postroad:towns`) or that a road may cross (`#postroad:passable`) are tags.
- `postroad-common.toml` carries the gameplay numbers and the per-server tuning:
  `plan.*` for how much is planned and generated ahead, `build.*` for what the
  builder spends and puts up, `travel.*`, `mail.*`, `roads.*` for the economy.

### Known limits

- Every road is the dirt tier. Road tiers and the road-works quest that pays to
  upgrade a route are 0.2.0.
- Trees can grow on a laid road: features that run after `surface_structures`
  see the road as ground.
- Village pairs separated by water or by ground too steep for the piece catalog
  are left unlinked.
