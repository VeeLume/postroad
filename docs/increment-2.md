# Increment 2 — Network and mail

Increment 1 gave every town a depot with its own storage. This increment
connects the towns: one postal network, storage reachable from any depot,
parcels addressed to players, and the time rule that makes treasure travel
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
- **Parcels** — items sent from a depot to a player at a town. Stackables
  arrive at once; unstackables take days, proportional to distance.
- **Mailboxes** — per player and town, where parcels land. Anyone can open
  any mailbox, as decided in increment 1.
- **Depot screen** — one custom screen with tabs: this town, network storage,
  mailbox, send.
- **Advancements** — granted by the mod at each milestone so FTB Quests can
  use them as task triggers.

Out (later increments): signs, teleport, fares, roads, cargo box, home
mailbox blocks, road-quality multipliers on delivery time.

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
- Before the unlock a depot shows only its own town's page and the mailbox
  and send tabs are disabled with a hint. Nothing that exists before the
  unlock changes shape afterwards: a town's storage *is* its page.

## Data model changes

```
Network (existing)
├── postalUnlocked: Boolean
├── homes: Map<player, PlaceId>
├── storage: Map<PlaceId, ItemContainer(54)>      // unchanged; now the pages
├── mailboxes: Map<(player, PlaceId), ItemContainer(27)>
└── packages: List<Package>
      Package { id, sender, recipient, from: PlaceId, to: PlaceId,
                items: List<ItemStack>, sentDay, arrivalDay, lane }
```

- Player keys are lower-cased names, as accounts already are.
- A mailbox is created on first delivery; empty mailboxes are dropped on save.
- `packages` holds parcels in transit and parcels that arrived but did not
  fit into the mailbox (`held`, retried daily).

## Storage semantics

- The network storage tab pages by town, in the order towns were discovered;
  the current town is the default page.
- **Remote unstackable rule.** A slot that holds an unstackable in a page
  other than the current town is shown but locked: it cannot be taken, and no
  unstackable can be placed into a remote page. Stackables have no
  restriction. The menu enforces this server-side; the screen greys the slot
  and explains on hover.
- Capacity is 54 slots per town, so the network grows with every town that
  has a depot. This is the only capacity rule; there is no separate pool.

## Mail flow

1. **Send tab** at any depot. Recipient is chosen from a list (players online
   plus every player the network has seen), destination from the list of towns
   with a depot. Destination defaults to the recipient's home town, else the
   current town. A 9-slot outbox and a Send button.
2. On Send the outbox is split into two parcels if needed: one with all
   stackables (bulk lane), one with all unstackables (valuables lane). Each
   gets `arrivalDay` per the timing rule and joins `packages`. Ledger gets no
   entry (no coins moved); the log gets one line.
3. Delivery runs once per in-game day change and on server start: every
   parcel with `arrivalDay <= today` is moved into the recipient's mailbox at
   the destination town; what does not fit stays `held`.
4. **Mailbox tab** shows the mailbox for the current town and, below it, a
   line per town where the player has parcels waiting or in transit
   ("2 parcels at Oakstead", "1 parcel arriving in 2 days"). The mailbox is
   a plain container: take what you want, leave the rest.
5. The recipient gets a chat line when a parcel is delivered, if online.

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
- Widgets: tab bar; page selector (previous/next plus the town name) on the
  network tab; recipient and destination as cycle/dropdown selectors on the
  send tab, no free-text entry; a *Set as home* button on the this-town tab
  showing the current home.
- Actions that are not slot clicks (change tab, change page, pick recipient
  or destination, send, set home) are payloads registered with NeoForge's
  payload registrar; every one is validated server-side against the menu's
  place and the network state.
- The vanilla 6-row chest menu from increment 1 goes away.

## Advancements

Impossible-trigger advancements, hidden, granted through
`player.getAdvancements().award(...)` from the mod:

- `postroad:depot_used` — first depot interaction.
- `postroad:home_set` — home town chosen.
- `postroad:postal_network` — charter applied.
- `postroad:parcel_sent`, `postroad:parcel_received`.

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
4. Sending a mixed outbox creates two parcels; the bulk one is in the mailbox
   after one daily pass, the valuables one after the computed days (advance
   `dayTime` in the test).
5. A full mailbox holds the parcel and delivers it once space is freed.
6. Home default: sending to a player with a home pre-selects that town.

In play: the screen itself (layout, selectors, hover text), the chat
notification, and whether the default timing feels right over a real
session.
