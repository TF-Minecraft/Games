package net.tfminecraft.games.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.game.PokerGame;
import net.tfminecraft.games.game.PokerTournament;
import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.TxResult;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.voice.RpNames;

/** Host settings and player tournament actions at the nearby poker table. */
final class PokerCommands {
    private PokerCommands() {}

    static boolean execute(Player player, String[] args) {
        TableManager manager = TableManager.get();
        Table table = manager.tableNearby(player);
        if (table == null || !"poker".equalsIgnoreCase(table.getGameId())) {
            player.sendMessage(Messages.get("poker.no_table"));
            return true;
        }
        PokerTournament tournament = table.poker();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        try {
            switch (action) {
                case "status" -> player.sendMessage(Messages.get("poker.settings",
                        "buyin", String.valueOf(tournament.buyIn()),
                        "chips", String.valueOf(tournament.startingChips()),
                        "rebuys", String.valueOf(tournament.maxRebuys()),
                        "ante", String.valueOf(tournament.ante()),
                        "minutes", String.valueOf(tournament.blindMinutes())));
                case "buyin", "rebuy" -> {
                    if (table.live() || table.isPaying() || !tournament.canBuy(player.getUniqueId())
                            || (!tournament.registered(player.getUniqueId()) && table.actives().size() >= 100)) {
                        player.sendMessage(Messages.get("poker.buyin_refused"));
                        return true;
                    }
                    TxResult result = WagerEngine.get().begin(table, "tournament buy in")
                            .move(Accounts.coins(table, player), Accounts.bucket(table, player.getUniqueId()),
                                    tournament.buyIn()).commit();
                    if (!result.ok()) {
                        player.sendMessage(Messages.get(result.messageKey()));
                        return true;
                    }
                    tournament.buy(player.getUniqueId());
                    table.actives().add(player.getUniqueId());
                    GamesRegistry.of("poker").onTableReady(table);
                    player.sendMessage(Messages.get("poker.bought", "n", String.valueOf(tournament.startingChips())));
                }
                case "bet" -> {
                    if (args.length != 3 || !((PokerGame) GamesRegistry.of("poker"))
                            .betChips(table, player, Integer.parseInt(args[2]))) {
                        player.sendMessage(Messages.get("poker.chip_bet_refused"));
                        return true;
                    }
                }
                case "start" -> {
                    if (args.length != 2 || !manager.canEditHouse(player, table)
                            || !GamesRegistry.of("poker").tryClaimDealer(table, player) || !table.live()) {
                        player.sendMessage(Messages.get("poker.start_refused"));
                        return true;
                    }
                    player.sendMessage(Messages.get("session.started"));
                }
                case "configure" -> {
                    if (!manager.canEditHouse(player, table) || table.live() || table.isPaying()
                            || WagerEngine.get().total(table) > 0 || args.length != 7) {
                        player.sendMessage(Messages.get("poker.configure_refused"));
                        return true;
                    }
                    tournament.configure(Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                            Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
                    player.sendMessage(Messages.get("place.options_saved"));
                }
                case "kick" -> {
                    if (!manager.canEditHouse(player, table) || table.live() || table.isPaying()
                            || !tournament.enabled() || args.length != 3) {
                        player.sendMessage(Messages.get("poker.kick_refused"));
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null || !tournament.registered(target.getUniqueId())) {
                        player.sendMessage(Messages.get("admin.unknown_player", "name", args[2]));
                        return true;
                    }
                    GamesRegistry.of("poker").onLeave(table, target);
                    target.sendMessage(Messages.get("poker.kicked"));
                }
                case "finish" -> {
                    List<UUID> remaining = table.actives().stream()
                            .filter(id -> tournament.stack(id) > 0).toList();
                    if (!manager.canEditHouse(player, table) || table.live() || table.isPaying()
                            || !tournament.started() || remaining.size() != 1) {
                        player.sendMessage(Messages.get("poker.finish_refused"));
                        return true;
                    }
                    UUID winner = remaining.getFirst();
                    List<PayoutFlight> flights = new ArrayList<>();
                    WagerEngine.get().sweepPot(table, Bukkit.getPlayer(winner), winner, flights, "tournament prize");
                    WagerEngine.get().announceWins(table, "poker");
                    if (WagerEngine.get().felt(table) > 0) {
                        manager.flushPiles(table, flights, null);
                        player.sendMessage(Messages.get("poker.prize_pending"));
                        return true;
                    }
                    tournament.reset();
                    table.roundMoney().clear();
                    table.actives().clear();
                    manager.flushPiles(table, flights, () -> manager.refreshLabel(table));
                    player.sendMessage(Messages.get("poker.win", "name", RpNames.of(winner)));
                }
                default -> player.sendMessage(Messages.get("poker.usage"));
            }
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Messages.get("poker.usage"));
            return true;
        }
        manager.persistHouseChange(table);
        return true;
    }
}
