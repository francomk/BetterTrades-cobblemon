# Changelog

## 0.1.0 — first release

- Cobblemon player trades replaced with a server-side screen (Polymer + sgui), no client mod needed.
  A refusal from BetterTrades never falls back to the native trade.
- Eight offer slots per side: items, Pokémon and money in the same trade.
- Items held in escrow in a dedicated local database, closed before anything is handed over, with
  recovery at the next login after a crash; Pokémon fingerprinted on offer and verified again at
  commit.
- Cobblemon's own behaviour preserved: trade evolutions, the friendship reset, and the
  `TRADE_EVENT_PRE` / `TRADE_EVENT_POST` events other mods rely on.
- Money through Impactor (optional): only the difference between the two offers is transferred, in
  one atomic operation, with a verified refund path when a trade is cancelled after payment.
- Completed trades stored in a normalised schema on MariaDB/MySQL, with SQLite fallback and replay.
- Blacklist for items and Pokémon (species, form, aspects), editable in game.
- Read-only API for other mods with per-mod keys and two access levels.
- All player-facing text in editable language files (English, Italian), with `&` colour codes.
- Sounds and action-bar feedback, end-of-trade summary, `/bt history` for players.

Before release the code went through an audit of 56 findings — duplication, item loss, race
conditions, economy, lifecycle — all closed. The full account, including what has not been
verified, is in `HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt`.
