# Changelog

## 0.3.0

- **Charting pays.** Road nobody has charted yet earns a coin per 150 blocks
  (`chart.surveyBlocksPerCoin`), plus 2 for every town a walk brings onto the travel network.
  A stretch is paid once, even after its path is removed or the road is replanned; a walk that
  doubles back is paid once; a teleport during a walk abandons it. Everything charted before
  this version counts as paid.
- **Free journeys.** Beyond the free distance, a journey is free whenever your free journey
  is ready, and starts a cooldown of an in-game day per 1000 blocks (`travel.cooldownBlocksPerDay`,
  0 switches it off); sleeping shortens it. During the cooldown journeys cost their fare.
- **Travel on a map.** The travel screen is a map: your atlas, the charted roads by tier, the
  destinations (green free, gold a fare, grey out of reach) and the towns you have seen.
- **The atlas.** Your client keeps a map of everything it has loaded, in vanilla map colours,
  saved per server under `postroad/atlas/` in the game folder. Unexplored land stays dark.
- **Loot travels with you.** Fresh loot is no longer mailed when you travel.
- **Salvage Satchel** (leather, string, paper): sells looted gear and treasure on the spot,
  straight into your wallet, and pays in coins you put in. Gear from loot chests carries a
  permanent *Looted* mark and sells by its material; treasure nobody can craft sells marked
  or not. The satchel shows the price on every slot and takes only what it buys. The depot
  buys the same way. The fresh-loot stamp is gone: old stamps turn into the mark, and loot
  stacks again. Data maps `salvage` and `salvage_material` replace `buyback`.
- **Road works.** A depot's *Road works* shows the town's roads on the map; anyone chips in
  coins and the next tier's material, and the road becomes gravel, then paved, where it
  stands: faster to walk, cheaper to travel. Prices are `roadWorks.*`.
- **Trees come off the roads.** Trees on or right beside a road are felled and leaves over it
  cleared, once per road chunk as it loads. Log buildings, villages and pit stops are left alone.
- **Mail from your mailbox.** A mailbox sends too: mark stacks and send them to another
  player's mailbox or a town, as at a depot.
- **Charting guide.** The charting map's *Guide* shows every block that counts as road, by
  tier, and what each tier gives.
- Operators: `/postroad roads relay` re-lays upgraded roads, `/postroad roads trees` clears
  the roads round you and reports what it saw.

## 0.2.6

- Towns and roads on Xaero's maps. Towns you have explored on your World Map get a gold
  waypoint (in the world and on the minimap within 768 blocks, further out on the world map
  only); roads are drawn on the world map, amber where charted, slate where not, with their
  name on hover. Roads show over explored map only; `map.roadsBeyondExplored` shows them
  everywhere. The towns can be switched off in Xaero's waypoint options. A client without
  this version still joins, it just has no overlay.
- Inside a town every road counts as charted. A walk or a sign that met an uncharted road at
  a street exit (several roads often end on the same block) used to be cut off from the town;
  now everything inside a town's area reaches its depot. Existing worlds need no re-charting.
- The travel list offers towns and signs players named. The road builder's junction and
  road-end signs no longer fill it, and a sign labelled with its town's name is not listed
  twice. Every sign can still be travelled from.

## 0.2.5

- The road speed buff only applies on roads the network knows: generated roads, and roads
  a player has charted. Plank floors, logs, gravel beaches and village streets no longer
  make you faster. A road you build yourself gets the buff once you chart it. How far from
  the road's line it still counts is `roads.buffReach` (6 blocks).

## 0.2.4

- Travel reaches every road into a town. A depot used to join only the one road its node
  sat on (usually the pit stop's, often uncharted), so charting the road to a neighbour
  could still leave "no destinations". Now a town's depot connects to every charted road
  that ends in the town, a charted walk that meets an uncharted road still connects where
  it meets it, and a depot that stands away from the roads gets its travel node the first
  time *Go* is pressed. Existing worlds need no re-charting.
- Right-click a linked sign to travel; sneak + right-click with an empty hand edits it.
  Dye, ink sacs and honeycomb still apply to the sign. Left-click no longer travels, so a
  linked sign breaks like any other, and breaking it removes its destination.
- The travel list no longer shows destinations right next to you (the sign beside a depot).

## 0.2.3

- The road builder assembles each road once instead of for every chunk and every tick it
  resumes; it took about 9 ms a tick on the server with three players exploring.

## 0.2.2

- Fresh loot stacks again. Only unstackables and items the depot buys get the stamp, so
  rotten flesh, string and the like from chests and mobs stack with everything else, and a
  stamp is removed once it has settled, so a looted diamond joins mined ones after three
  days. Existing worlds heal as they are played: stamps on items that should not carry one
  come off in player inventories, in any container a player opens, on dropped items, and in
  depot storage, mailboxes and parcels.

## 0.2.1

- The depot screen is a normal chest again. It was 176×285 — taller than the window at
  Minecraft's own auto GUI scale on a 1080p display, which left its lower half fighting
  EMI's panels. The body is now the vanilla six-row chest to the pixel (176×222) and the
  depot's controls moved into a panel attached to its right, which EMI is told to keep
  clear of.

## 0.2.0

A performance release for the dedicated server.

- Pre-generation no longer hogs the server thread. A finished corridor chunk keeps
  its ticket until nothing queued needs its neighbourhood, requests are queued in
  spatial order, and tickets are released a few per tick. Before, every chunk's
  neighbours were written out and read back on the server thread as its ticket
  dropped, and a corridor's end dropped thousands at once (a 5–7 s stall).
  `/postroad roads measure` now reports the tail after a run on its own line.

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
  upgrade a route are a later release.
- Trees can grow on a laid road: features that run after `surface_structures`
  see the road as ground.
- Village pairs separated by water or by ground too steep for the piece catalog
  are left unlinked.
