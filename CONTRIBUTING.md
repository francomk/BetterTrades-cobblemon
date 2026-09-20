# Contributing

BetterTrades is made by [Studio Deriva](https://discord.gg/pQPWr7vfHu), a Minecraft modding team of
developers and artists. Everything we make is free and open source — source included, no paywall,
no premium version. Read it, learn from it, fork it, build on it.

## Ideas

Tell us what you would want to play. We read everything. If your idea turns into a mod or a
feature, you get credited for it in the release notes; plenty of what we have shipped started as
somebody's throwaway message.

A good suggestion answers five things:

```
Idea:             one line, the whole thing
What it does:     blocks, items, mobs, mechanics
Why it's worth building: what is missing without it
Version / loader: if it only makes sense on one
References:       links, screenshots, mods that come close
```

An idea with the *why* filled in gets built far more often than a one-liner. One idea per message,
and check whether somebody already suggested it before posting it again.

## Bugs

Open an issue, or a ticket on Discord, with:

- the mod version and the Minecraft/Fabric/Cobblemon versions,
- what happened and what you expected,
- the relevant part of `logs/latest.log` (the lines from `BetterTrades`, `BetterTrades DB`,
  `BetterTrades escrow` or `BetterTrades economy`),
- whether the trade involved items, Pokémon, money, or a mix.

For anything that looks like item duplication or item loss, please also say what the item counts
were before and after. That single number is what makes a report reproducible.

## Pull requests

```
./gradlew build     # compiles and runs the tests
./gradlew test      # tests only
```

House style, such as it is:

- Java 21, four spaces, no wildcard imports.
- Comments say **why**, not what. If a line exists because of a bug that already happened, the
  comment says which one — most of the comments in this codebase are of that kind, and they are
  the reason the next person does not undo the fix.
- Everything player-facing goes through `Lang` and lives in the language files. No hardcoded
  strings in the screens or the commands.
- Anything touching escrow, money or the commit path needs a test or a written argument for why it
  cannot have one. Those three are where item duplication comes from; see
  [`HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt`](HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt) for the
  catalogue of ways they have already gone wrong.
- Nothing blocking on the server thread. Database and economy calls are asynchronous, and the
  server thread never waits on a disk or on Impactor.

## Licence

By contributing you agree your work ships under the MIT licence, like the rest of the project.
Do not post work that is not yours to give away: somebody else's paid commission, leaked content,
or anything you are under an agreement to keep quiet.
