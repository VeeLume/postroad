# Increment 2 — Network and mail

Increment 1 gave every town a depot with its own storage. This increment
connects the towns: one postal network, storage reachable from any depot,
parcels between towns, and the time rule that makes treasure travel
slowly. It also replaces the vanilla chest menu with a real depot screen.

## Scope

In:

- **Postal network unlock** — a *Postal Charter* item, applied at any depot,
  turns same-town storage into network storage for the whole group.
- **Network storage** — every town's storage becomes a page of one network
  storage, browsable from any depot. Stackables move freely; unstackables
  stay where they were put.
- **Home town** — each player picks a home town at a depot; it is the default
  destination for their mail.
- **Parcels** — items sent from a depot to another town; they land in that
  town's storage. Stackables arrive at once; unstackables take days,
  proportional to distance.
- **Depot screen** — one custom screen, no tabs: the town's storage (paged
  through the network once chartered), select-and-send, dump and take.

- **Mailbox block** — an earned block (quest reward, no recipe) a player
  places at their base. It registers "<Player>'s mailbox" as a destination,
  so parcels can go to the base instead of a town. Receive-only: sending
  still happens at a depot.

> **Revised in playtest (2026-09-02):** the first cut had per-player
> mailboxes *inside depots* and a recipient selector. In a cooperative group
> that added a tab and a choice without adding anything: a parcel goes to a
> *place*, and whoever is there takes it out. The player-specific part
> survives as the mailbox block: towns are the base destination, a mailbox
> at home is the upgrade.
- **Advancements** — granted by the mod at each milestone so FTB Quests can
  use them as task triggers.

Out (later increments): signs, teleport, fares, roads, cargo box,
road-quality multipliers on delivery time.

## Rules

- **No player depends on another.** The charter is a group unlock, but any
  player can earn it; nothing here requires a second person.
- **Unstackables never move instantly.** Not through storage, not through
  mail. The only way an unstackable changes town is a parcel with the
  valuables delay, and sending it to yourself is a normal parcel.
- **Time is the only cost.** Sending is free. Coins buy movement of people
  (increment 3), never of goods.
- **Same state object.** Everything below lives on the increment 1 `Network`
  saved data; the block entity still holds only its place id.

## Unlock model

- Item `postroad:postal_charter`. No recipe. Intended as an FTB Quests reward;
  `/postroad unlock` exists for ops and tests.
- Sneak-use on any depot consumes the charter, sets `postalUnlocked` on the
  network, writes a ledger entry (`charter`, actor, town) and grants the
  `postal_network` advancement to the user.
- Before the unlock a depot shows only its own town's page, without page
  arrows, and the send row reads "Charter the postal network to send parcels". Nothing that exists before the
  unlock changes shape afterwards: a town's storage *is* its page.

## Data model changes

```
Network (existing)
├── postalUnlocked: Boolean
├── homes: Map<player, PlaceId>
├── storage: Map<PlaceId, ItemContainer(54)>      // unchanged; now the pages
├── parcels: List<Parcel>
│     Parcel { id, sender, from: PlaceId, to: PlaceId,
│              items: List<ItemStack>, sentDay, arrivalDay, lane }
└── mailboxes: Map<PlaceId, ItemContainer(27)>   // mailbox-block contents
```

- A mailbox is a `Place` of type `mailbox` with an `owner`; its id is the
  block position. Its contents live in the network, not the block entity, so
  a parcel can land while the chunk is unloaded; the block only opens them.
  Breaking the block drops the contents, removes the place and redirects
  parcels on the way to the owner's home town, else back to the sending town.
- Destinations = towns (places with a depot) followed by mailboxes. Network
  storage pages stay towns only.

- Player keys are lower-cased names, as accounts already are.
- `parcels` holds parcels in transit and parcels that arrived but did not
  fit into the destination's storage (`held`, retried daily).

## Storage semantics

- The storage tab pages by town, in the order towns were discovered; the
  current town is the default page. (Playtest: a separate "this town" tab
  duplicated the first page and was merged away.)
- **Remote unstackable rule.** A slot that holds an unstackable in a page
  other than the current town is shown but locked: it cannot be taken, and no
  unstackable can be placed into a remote page. Stackables have no
  restriction. The menu enforces this server-side; the screen greys the slot
  and explains on hover.
- Capacity is 54 slots per town, so the network grows with every town that
  has a depot. This is the only capacity rule; there is no separate pool.

## Mail flow

The loop the screen is built around: **arrive with full pockets, mark what
leaves, one click, collect at home later.** "Home" is the home town in stage
1 and the mailbox in stage 2; the screen does not care which.

1. **One view, no tabs.** The grid is the town's storage (paged through the
   network once chartered); the player's inventory is below as in any chest.
2. **Select** turns clicks into marks: a click on any stack, inventory or
   grid, highlights it green instead of picking it up. **Loot** marks every
   stack still stamped as fresh loot in one click. Nothing leaves that was not
   marked — with this many mods, "everything but the hotbar" was not a safe
   rule.
3. **Send N** ships the marked stacks to the destination shown next to it
   (arrow buttons; mailbox first, then home town, then nearest-first; the
   current town is not offered). Marked stacks in the grid ship too, so items
   can be forwarded from a town's storage.
4. On send the items are split into two parcels if needed: stackables (bulk
   lane, arrive at once), unstackables (valuables lane, days). Ledger gets no
   entry; the log gets one line.
5. Delivery runs once per in-game day change and on server start into the
   destination's storage (town or mailbox); what does not fit stays `held`.
6. **Dump** (main inventory into the shown page) and **Take** (shown page
   into the inventory) cover the plain chest uses; collecting at home is one
   click.
7. The Send button's tooltip lists the sender's parcels on the way; the
   sender gets a chat line when a valuables parcel lands, if online.

> Earlier cuts had a send tab with a 9-slot outbox, then a "send everything
> but the hotbar" button. Both were wrong for this pack: the first was
> ceremony, the second unsafe with a modded inventory. Marking in place is
> the version that survived play.

## Lanes and timing

- `bulk` — stackables only: `arrivalDay = sentDay` (delivered on the next
  daily pass or immediately if the day is unchanged; "near-instant").
- `valuables` — any unstackable: `arrivalDay = sentDay + baseDays +
  ceil(distance / blocksPerDay)`, distance measured between the two towns'
  anchor positions. Defaults `baseDays = 1`, `blocksPerDay = 1000`, both
  config. Road quality divides the distance term in increment 3.
- Days are day-time days (sleeping advances them), consistent with the
  fresh-loot stamp.

## Depot screen

- One `MenuType` opened by the depot with the place id as extra data. Tabs
  are server-side state on the menu so the same menu backs every view.
- Widgets: tab bar with a *Dump* button (main inventory into the shown
  page); page arrows plus the town name in the title of the storage tab
  ("» Fair Gate (1/4)", the marker meaning *here*; the title trims itself
  rather than run under the buttons); the destination as an arrow selector
  on the send tab, no free-text entry; a *Home* button on the storage tab
  while it shows the current town.
- Pages are ordered here-first, then by distance; destinations own mailbox,
  home town, then by distance. Arrow selectors will not scale past a dozen
  towns — a pick-list is the next step if the network grows that far.
- Actions that are not slot clicks (change page, pick destination, select
  mode, mark loot, send, dump, take, set home) are payloads registered with NeoForge's
  payload registrar; every one is validated server-side against the menu's
  place and the network state.
- The vanilla 6-row chest menu from increment 1 goes away.

## Advancements

Impossible-trigger advancements, hidden, granted through
`player.getAdvancements().award(...)` from the mod:

- `postroad:depot_used` — first depot interaction.
- `postroad:home_set` — home town chosen.
- `postroad:postal_network` — charter applied.
- `postroad:parcel_sent` — first parcel to another town.
- `postroad:mailbox_placed` — a mailbox set up at the base.

FTB Quests uses these as advancement tasks; the questbook text and rewards
stay in the modpack.

## Commands

`/postroad home [town]`, `/postroad mail` (my parcels, all towns),
`/postroad unlock` (op), plus the increment 1 set.

## Config additions

`mail.valuablesBaseDays` (1), `mail.valuablesBlocksPerDay` (1000).

## Test plan

Game tests:

1. Charter use flips `postalUnlocked`, second use is refused.
2. Before unlock, remote page access is refused; after unlock a stackable
   placed in town A is visible and takeable from town B.
3. An unstackable in town A's page cannot be taken from town B.
4. Sending a mixed outbox creates two parcels; the bulk one is in the
   destination's storage at once, the valuables one after the computed days.
5. A full destination storage holds the parcel and delivers it once space is
   freed.
6. Home default: a sender with a home town gets it pre-selected.

In play: the screen itself (layout, selectors, hover text), the chat
notification, and whether the default timing feels right over a real
session.
