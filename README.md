# BetterTrades

Server-side player trading for [Cobblemon](https://modrinth.com/mod/cobblemon) on Fabric 1.21.1,
by [Studio Deriva](https://discord.gg/pQPWr7vfHu).

BetterTrades replaces the built-in Cobblemon trade with its own trade screen: Pokémon, items and
money in the same offer, a full history in a database, and a read-only API other mods can query.

**No client mod required.** The screen is built with [Polymer](https://modrinth.com/mod/polymer)
and [sgui](https://modrinth.com/mod/sgui), so vanilla clients see it as a normal container.

**Free and open source**, like everything we make: MIT, source included, no premium version.

---

## Features

- **Same entry point as vanilla Cobblemon.** Players open a trade the way they always did, from
  the interaction wheel. BetterTrades takes over from there — and only BetterTrades: a refusal
  never falls back to the native trade, so nothing slips past the blacklist or the history.
- **Items, Pokémon and money in one offer.** Eight slots per side.
- **Items are held in escrow.** An offered item leaves the inventory immediately and is recorded in
  a local database, so it cannot be dropped, used or duplicated while the trade is open. If the
  server crashes mid-trade, the items come back at the next login.
- **Pokémon are never moved before the trade completes.** They are fingerprinted when offered and
  re-checked at commit: if anything changed, the trade is cancelled instead of going through.
- **Cobblemon's own behaviour is preserved.** Trade evolutions still happen, friendship is reset
  the way `performTrade` resets it, and `TRADE_EVENT_PRE` / `TRADE_EVENT_POST` are still fired, so
  another mod's veto and Cobblemon's trade advancement keep working.
- **Money through [Impactor](https://modrinth.com/plugin/impactor).** Only the difference between
  the two offers is transferred, in a single atomic operation. Impactor is optional: without it the
  money button is simply greyed out.
- **Every completed trade is stored** in a normalised schema (3NF/BCNF), on MariaDB/MySQL with an
  automatic SQLite fallback and replay when the remote database comes back.
- **Blacklist** for items and Pokémon, down to species + form + aspects, editable in game.
- **Read-only API** for other mods, with per-mod keys and two levels (queries, or queries plus
  cancellable pre-trade events).
- **Every message is a file you can edit.** English and Italian ship with the mod; a server owner
  can rewrite any line, colour codes included, without recompiling.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | Fabric 0.16.5+ |
| Java | 21 |
| Required | Fabric API, Cobblemon 1.7.3–1.7.x, Polymer, sgui |
| Optional | LuckPerms (permissions), Impactor (money) |

Server-side only. Installing it on a client is harmless but pointless, except in singleplayer.
Polymer, sgui, sqlite-jdbc and mariadb-java-client travel inside the jar — do not install them
separately. Cobblemon does not: it is already on your server.

## Installing

1. Drop `bettertrades-<version>.jar` into `mods/`.
2. Start the server once. `config/bettertrades/config.json` is created with working defaults:
   SQLite, no external database, nothing to fill in.
3. Serve the resource pack (below). That is the only step that is not automatic.

### The resource pack

The trade screen is drawn with models and fonts that live in Polymer's resource pack, so players
have to receive it. Polymer can host it for you, but its auto-host is **off by default** on a
production server. Turn it on in `config/polymer/auto-host.json`:

```jsonc
{
  "enabled": true,
  "required": true
}
```

Then restart. BetterTrades logs a warning at startup when the pack is not going to reach anyone, so
you find out before your players do.

If you serve the pack some other way — a `resource-pack=` URL in `server.properties`, a merged pack
of your own — leave auto-host alone and keep `general.requireResourcePack` at `true`; the mod
checks whether each player actually has the pack, not who sent it.

If you would rather open trades even without the pack, set `general.requireResourcePack` to
`false`. The screen still works, but the buttons are blank paper for anyone without the pack.

## Commands

`/bt` is an alias of `/bettertrades`.

| Command | Who | What |
|---|---|---|
| `/bt help` | everyone | what you can use |
| `/bt history [page]` | everyone | your last trades, with the contents in the tooltip |
| `/bt history <player> [page]` | admin | someone else's trades |
| `/bt active` | admin | trades in progress, contents in the tooltip |
| `/bt cancel <player>` | admin | cancel a trade in progress |
| `/bt blacklist add\|remove\|list` | admin | blocked items and Pokémon |
| `/bt api add\|revoke\|list` | admin | keys for other mods |
| `/bt db status\|sync\|reload\|lang` | admin | backend state, manual replay, reloads |

Admin commands need permission level 4, or the LuckPerms node `bettertrades.admin`.

## Configuration

`config/bettertrades/config.json`, created on first start. These are the defaults, and on a fresh
server they are meant to be left exactly as they are:

```jsonc
{
  "general": {
    "language": "en_us",         // a file name in config/bettertrades/lang/
    "requireResourcePack": true, // do not open the screen for players without the pack
    "sounds": true,
    "actionBar": true,           // short feedback on the action bar instead of chat
    "tradeSummary": true         // "you gave / you got" recap after each trade
  },
  "database": {
    "useRemote": false,          // false = SQLite only, nothing else to set up
    "host": "127.0.0.1",
    "port": 3306,
    "name": "bettertrades",
    "user": "bettertrades",
    "password": "",
    "readUser": "",              // SELECT-only user used by the mod API
    "readPassword": "",
    "retryIntervalSeconds": 60,
    "failureThreshold": 3
  },
  "trade": {
    "maxDistance": 10.0,         // keep it in step with Cobblemon's tradeMaxDistance
    "cancelOnDimensionChange": true,
    "cancelOnBattle": true,
    "respectTradeableFlag": true
  },
  "economy": {
    "enabled": true,
    "currency": ""               // a label only: the provider's primary currency is always used
  }
}
```

With `useRemote: false` the mod writes to `config/bettertrades/bettertrades.db` (SQLite) and works
on its own. Escrow always lives in its own file, `config/bettertrades/escrow.db`, whatever the main
backend is.

`trade.maxDistance` and Cobblemon's own `tradeMaxDistance` are two separate numbers in two separate
files. BetterTrades compares them at startup and says so in the log when they disagree, because the
smaller of the two is the one that decides.

### Messages

`config/bettertrades/lang/en_us.json` and `it_it.json` hold every line players see. Edit them and
run `/bt db lang` to reload — no restart. Colour codes use `&` (`&a`, `&l`, `&r`). Placeholders
`{0}`, `{1}` are substituted in order; keep them.

The untouched originals are rewritten into `config/bettertrades/lang/default/` on every start, so
after an update you can diff them and copy over any new key.

Set `general.language` to the name of any file in that folder: adding `fr_fr.json` and setting
`"language": "fr_fr"` is all it takes for a new language.

## API for other mods

```java
BetterTradesApi.Queries api = BetterTradesApi.connect("mymod", "<key from /bt api add>");
api.byPlayer(uuid, 20, 0).thenAccept(trades -> ...);
```

Level 1 covers history queries and blacklist writes; level 2 adds a cancellable pre-trade event and
a post-trade notification. Reads run off the server thread on a SELECT-only database connection.

Listeners are registered **per server start**, not once at mod initialisation: BetterTrades clears
them at `SERVER_STOPPED`, so a second world in the same JVM does not fire them twice.

The key is a barrier against accidental integration, not against a hostile mod in the same JVM.

## Database

MariaDB/MySQL is the primary backend, SQLite is the safety net. If the remote database is down at
start, or falls over during play, the mod keeps working on SQLite and marks what it wrote; when the
remote comes back, the replay copies it over. Primary keys are generated by the mod (UUID/ULID), so
the replay is idempotent.

Only completed trades are stored. Escrow rows are written while a trade is open, and resolved when
it closes either way — always closed before anything is handed over, so a crash in between can cost
an item but never duplicate one.

## Building from source

```
./gradlew build          # jar in build/libs/
./gradlew test           # 15 unit tests over the schema, escrow states and column widths
```

Java 21, and a first run with network access to fetch the dependencies.

## How this mod was checked

[`HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt`](HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt) is the full
account of the audit this code went through: 56 findings — duplication, item loss, race conditions,
economy, lifecycle — what each one was, and what was changed to close it. It also says plainly what
has **not** been verified yet. Worth reading before trusting any trading mod with your economy,
this one included.

## Contributing

Ideas, bug reports and pull requests are all welcome — [our Discord](https://discord.gg/pQPWr7vfHu)
has a channel for each. If an idea of yours ships, your name goes in the credits and in the release
notes. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT. See [LICENSE](LICENSE). © 2026 Studio Deriva.
