# Games

> Cards on the table, coins in the pot: social games for TF-Minecraft.

Games brings playable card tables into the Minecraft world. Players gather around a deck, see cards and wagers on the table, and play through hands together. Private cards stay private until revealed, while shared cards and coin piles make the action visible to the people around them.

It supports both structured games with managed rounds and a free-play table for rules the players agree among themselves.

## Features

- **Four ways to play** — Tenceur Hold'em, Five-Draw, Blackjack, and unrestricted free play.
- **Physical table interaction** — draw, inspect, select, discard, and reveal cards through interactions with the deck and your hand.
- **Real stakes** — place in-game coins on the felt and play for a shared pot, with payouts tied into DenarEconomy.
- **Blackjack against the house** — play with an automatic dealer or a player taking the dealer's position, including double and split decisions.
- **Guild-owned tables** — connect house games to a guild's funds and let its members take over dealing between rounds.
- **Help at the table** — in-game books explain each game's rules and available actions.

## Poker tournaments

At an empty poker table, the host or staff can use
`/games poker configure <buy-in Denars> <starting chips> <max rebuys> <ante chips> <blind minutes>`.
A buy-in of `0` selects cash play. A blind interval of `0` keeps blinds fixed;
otherwise they double at each interval and are collected at the next hand.
Set the starting blinds through the table options menu.

Players use `/games poker buyin` before the first hand. On their turn,
`/games poker bet <chips>` puts extra chips into the pot, followed by a
`raise` or `check`. `call` collects the chips needed to match automatically.
`/games bet allin` (or `allin` / `all in` in chat) stakes the entire remaining stack.
Cash tables take physical stakes only on the current player's turn.

Busted players can `/games poker rebuy` between hands within the configured limit.
The host can `/games poker kick <player>` between hands and, when only one positive
stack remains, `/games poker finish` to pay the Denar prize. Leaving or removal
after tournament play starts forfeits the entry. Before play starts it is refunded.
Tournament chips never enter player inventories or Denar payouts. Shutdown or
reload ends tournaments and returns Denar stakes through the table's refund path;
settings persist, chip stacks do not.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/Games/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

After installing the pinned plugin dependencies used by the build workflow, run
`mvn clean verify` with Java 21. JUnit tests use MockBukkit for Paper registries,
worlds, players, and inventories, and Mockito for external plugin and packet
boundaries. Game scenarios exercise public callbacks and assert game rules,
money conservation, or player-visible effects.

JaCoCo measures all production classes, with no exclusions, and writes HTML, XML,
and CSV reports to `target/site/jacoco/`. Open `target/site/jacoco/index.html` to
inspect uncovered behaviour. The build workflow uploads the coverage report
alongside test results. Coverage data is replaced on each test run; use the full
suite when assessing repository-wide coverage.

The suite covers game rounds, money conservation, table interactions, and tournament settings. Tests should protect
supported behaviour, not create impossible internal states merely to execute a
branch. Where a branch cannot be reached through any real caller, remove it
rather than force it.

The suite includes complete Poker, Draw and Blackjack rounds across the real
table, deck, and money implementations. Packet tests verify ProtocolLib requests
through mocked external boundaries; packet encoding and client rendering still
require a real-server integration run.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
