# Increment 8 — pit stops

Decided with Valerie on 2026-09-17. A courier post in the village house pools
is a weighted draw: a village may get none, and village mods weigh their pools
so differently (a total of 20 in one Towns & Towers pool, 535 in CTOV's
savanna pool) that no single weight is right. Instead, every village's depot
is a **pit stop** at the end of its road: less an office in town, more a
coaching stop where the road arrives.

## What gets built

- **One pit stop per village**, beside the end of the first of its roads the
  chunk-load builder reaches, a few blocks outside the village. A 5×5 roofed
  stand (`structure/pit_stop_<style>.nbt`, from `tools/gen_pit_stop.py`, the
  courier post's palettes): open front toward the road, depot against the back
  wall facing it, barrel, hay bale, lantern, and a hanging sign the depot
  labels with the town name.
- **A signpost at every other road end** of the town: one arm into the
  village, one along the road to the town at its other end. It is a sign
  node on the road like the junction signs. None goes up where the way is
  signed already: a fork or another signpost within 24 blocks of the road's
  end (roads that leave through one exit and fork right after it got a post
  each beside the fork's own sign).
- **A village no road reaches** gets its stop at a street exit, on a line
  straight out along the exit's facing, once nothing is left to plan for it:
  no provisional road to it, every road end handled, no dropped pair waiting
  for a replan.

## Site (detail)

Along the road from its town end, up to 8 plan points (about 24 blocks), both
sides, 4–7 blocks off the centre line. A site fits when its chunks are loaded,
it is clear of every village piece (1 block) and other stop (2 blocks), every
road's centre line keeps 2 blocks from it, the ground is dry and solid, its
highest column is at most 2 above the lowest and within 1 of the road block,
and nothing but plants and leaves stands in its height. The floor goes at the
highest column; a foundation of the road's fill block goes under the rest.

If no site fits at a road end, that end gets a signpost and the stop is
sought at the next end, and finally at the street exits. A town where nothing
fits gets no stop (logged).

## Binding

The stop's depot stands outside the village's pieces, where `PlaceResolver`
would found a place of its own. The builder binds it to the predicted town
(`Network.bindDepot`) before the depot's first tick, and attaches the town to
the road's path as a travel node. A town whose depot registered first (a
courier post, when a pack turns `courierPost.share` back on) keeps it and gets
no stop.

## Planner

A stop that stands blocks the cells wholly inside it (`PassRequest.stops`), so
later roads do not run through it but still reach the street exit beside it.

## Stored

`PlannedTown.stop` / `stopDone` (`Stop`, `StopDone`), `PlannedRoad.endsDone`
(`EndsDone`: which town ends have their stop or signpost).

## Config

`build.pitStops` (default true). `courierPost.share` now defaults to 0.

The numbers worth tuning on a real server are settings, not constants:
`plan.corridorChunks` (3: the corridor generated per provisional road, and
the biggest single cost), `plan.pregenSortSeconds` (1: how often the queue is
re-sorted player-first, 0 to keep pass order), `plan.maxReplans` (2),
`build.sweepSeconds` (10), `build.junctionMerge` (9), `build.endSignClear`
(24), and `corridorHalf` (24) in `roads/planner.json` beside `detour`.

## Site

The site search goes outward from the road's end, nearest first, 4–7 blocks
off the centre line, and runs twice: once over even ground, then over rough
ground (a deeper foundation, a step of 2 to the road). A large village whose
own pieces crowd the road end used to push its stop the full 24 blocks out.

## Also in this increment

- **No redundant roads.** Pairs are planned shortest first, and a pair the
  roads so far already connect within `detour` × its straight distance
  (`planner.json`, 1.4; a hop through a town's streets counts) is not linked.
  Four villages 100–300 blocks apart used to get all six roads and a loop.
- `mowziesmobs:wrought_chamber` is passable, and a passable structure alone in
  its set is no longer built by the town finder: that replay was most of the
  planner thread's time in the profile.
- A signpost clears leaves, and prefers the side where its whole stack of way
  signs fits.
- Signs and stops no longer wait for their chunk to load again: a junction or
  stop that becomes due while its chunk is loaded was only built on the next
  load (a sign "appearing" after flying away and back). The builder offers
  such chunks every 10 s, and a road going final queues its built chunks too.
- **One post per place.** Fork junctions within 9 blocks of each other share
  one signpost with the arms of all their roads (three forks had stood 6
  blocks apart), and no post goes up where fewer than three directions
  remain: two roads that split for a cell and meet again were two "forks".
  Roads that leave a town through the same exit share one road-end post with
  an arm to each destination (two posts had stacked on each other, both
  saying the town's own name), and a post never stands on another post.
- **Road-end posts wait and give way.** A road-end post goes up only once no
  road to its town is provisional any more, and a junction sign that goes up
  later within 24 blocks takes the road-end post down (`PlannedRoad.endPosts`
  records where each stands): the fork at Fair Gate was added by a later
  replan, after the end post of the first final road.
- **No gaps.** A chunk job fixes its road list when it starts (a road swapped
  in mid-job shifted the resume index past one still to build, and the chunk
  was never offered again while it stayed loaded), and the 10 s sweep also
  offers loaded chunks a final road still owes.
- **Corridors near players first.** The pre-generation queue is re-sorted
  every second: the corridor nearest a player, then its chunks nearest the
  player. A road is only laid once its whole corridor is known, which took up
  to 50 minutes around spawn in first-come order.
- `nova_structures:conduit_ruin` (underwater) is passable: two layouts had cost
  33 s of planner time.

- `#postroad:towns`: the structures that are towns, `#minecraft:village` plus
  villages mods leave out of it (Dungeons and Taverns' birch, jungle and swamp
  villages). Stored obstacles of such structures block nothing.
