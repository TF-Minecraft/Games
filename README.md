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

The suite covers every production line and branch. Tests should protect
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
