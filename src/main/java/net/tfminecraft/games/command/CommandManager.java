package net.tfminecraft.games.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.deck.Deck;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.ProtocolLibBridge;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.gui.GameSelectGui;
import net.tfminecraft.games.gui.TableOptionsGui;
import net.tfminecraft.games.help.HelpBook;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.game.BlackjackGame;
import net.tfminecraft.games.game.Game;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;

public class CommandManager implements CommandExecutor, TabCompleter {

    public String cmd1 = "games";

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase(cmd1)) {
            return false;
        }
        if (args.length == 0) {
            if (sender.hasPermission("games.admin")) {
                sender.sendMessage(Messages.get("admin.usage"));
                return true;
            }
            if (sender.hasPermission("games.bet")) {
                sender.sendMessage(Messages.get("bet.usage"));
                return true;
            }
            if (sender.hasPermission("games.help")) {
                sender.sendMessage(Messages.get("help.hint"));
                return true;
            }
            sender.sendMessage(Messages.get("admin.no_permission"));
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            if (!sender.hasPermission("games.help")) {
                sender.sendMessage(Messages.get("admin.no_permission"));
                return true;
            }
            return handleHelp(sender, args);
        }
        if (args[0].equalsIgnoreCase("bet")) {
            if (!sender.hasPermission("games.bet")) {
                sender.sendMessage(Messages.get("admin.no_permission"));
                return true;
            }
            return handleBet(sender, args);
        }
        if (!sender.hasPermission("games.admin")) {
            sender.sendMessage(Messages.get("admin.no_permission"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                if (!sender.hasPermission("games.admin.reload")) {
                    sender.sendMessage(Messages.get("admin.no_permission"));
                    return true;
                }
                boolean ok = Games.plugin.reloadAll();
                sender.sendMessage(Messages.get(ok ? "reload.success" : "reload.failed"));
                return true;
            case "deck":
                return handleDeck(sender, args);
            case "display":
                return handleDisplay(sender, args);
            case "place":
                return handlePlace(sender, args);
            case "payout":
                return handlePayout(sender, args);
            case "session":
                return handleSession(sender, args);
            case "deal":
                return handleDeal(sender, args);
            default:
                sender.sendMessage(Messages.get("admin.usage"));
                return true;
        }
    }

    private boolean handleHelp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("help.player_only"));
            return true;
        }
        String id = args.length > 1 ? args[1] : "index";
        HelpBook book = Cache.helpBook(id);
        if (book == null || book.isEmpty()) {
            player.sendMessage(Messages.get("help.unknown", "games", helpIds()));
            return true;
        }
        book.openFor(player);
        return true;
    }

    /** The books somebody can actually ask for, minus the index they get by default. */
    private static List<String> helpBookIds() {
        List<String> out = new ArrayList<>();
        for (String id : Cache.helpBooks.keySet()) {
            if (!"index".equals(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static String helpIds() {
        return String.join(", ", helpBookIds());
    }

    private boolean handleDeck(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("test")) {
            sender.sendMessage(Messages.get("deck.usage"));
            return true;
        }
        String setName = args.length >= 3 ? args[2] : Cache.pokerCardSet;
        Optional<Deck> created = Deck.create(setName);
        if (created.isEmpty()) {
            sender.sendMessage(Messages.get("deck.unknown_set", "set", setName));
            return true;
        }
        Deck deck = created.get();
        deck.shuffle();
        sender.sendMessage(Messages.get("deck.shuffled",
                "set", deck.getSetName(),
                "count", String.valueOf(deck.size())));

        int drawn = 0;
        int jokers = 0;
        Card last = null;
        Optional<Card> next;
        while ((next = deck.draw()).isPresent()) {
            Card card = next.get();
            drawn++;
            if (card.isJoker()) {
                jokers++;
            }
            last = card;
        }
        sender.sendMessage(Messages.get("deck.drawn",
                "drawn", String.valueOf(drawn),
                "jokers", String.valueOf(jokers),
                "remaining", String.valueOf(deck.remaining())));

        boolean emptyOk = deck.draw().isEmpty();
        sender.sendMessage(Messages.get(emptyOk ? "deck.empty_ok" : "deck.empty_failed"));

        if (last != null) {
            deck.discard(last);
        }
        sender.sendMessage(Messages.get("deck.discarded",
                "discarded", String.valueOf(deck.discarded()),
                "remaining", String.valueOf(deck.remaining())));
        deck.recycle();
        sender.sendMessage(Messages.get("deck.recycled",
                "remaining", String.valueOf(deck.remaining()),
                "discarded", String.valueOf(deck.discarded())));
        return true;
    }

    private boolean handleDisplay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("display.players_only"));
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("test")) {
            sender.sendMessage(Messages.get("display.usage"));
            return true;
        }
        if (!ProtocolLibBridge.isReady()) {
            sender.sendMessage(Messages.get("display.not_ready"));
            return true;
        }
        Location origin = lookLocation(player);
        ItemStack back = itemFromPath(CardLoader.getBackItem());
        if (back == null) {
            sender.sendMessage(Messages.get("display.missing_item"));
            return true;
        }
        Card faceCard = CardLoader.get("cerrith_1");
        ItemStack face = faceCard != null ? itemFromPath(faceCard.getItem()) : back;
        UUID tokenId = UUID.randomUUID();
        DisplayPose pose = DisplayPose.identity(Cache.cardScale);
        if (!DisplayManager.get().spawn(tokenId, origin, back, pose)) {
            sender.sendMessage(Messages.get("display.spawn_failed"));
            return true;
        }
        DisplayManager.get().setItemFor(tokenId, player, face);

        var interaction = WorldAnchors.spawnInteraction(origin.clone().add(0, 0.05, 0), 0.4f, 0.2f, tokenId.toString());
        var label = WorldAnchors.spawnLabel(origin.clone(), "Display test");
        UUID interactionId = interaction != null ? interaction.getUniqueId() : null;
        UUID labelId = label != null ? label.getUniqueId() : null;

        sender.sendMessage(Messages.get("display.spawned"));
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            DisplayManager.get().setTransform(tokenId, pose.withTranslation(0.8f, 0f, 0f), Cache.interpolationTicks);
        }, 5L);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            DisplayManager.get().despawn(tokenId);
            WorldAnchors.remove(interactionId);
            WorldAnchors.remove(labelId);
        }, 160L);
        return true;
    }

    private boolean handlePlace(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("place.players_only"));
            return true;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("poker") || args[1].equalsIgnoreCase("draw")
                || args[1].equalsIgnoreCase("blackjack") || args[1].equalsIgnoreCase("freeplay"))) {
            if (args[1].equalsIgnoreCase("blackjack")) {
                TableOptionsGui.openPlace(player, false, null);
                return true;
            }
            TableManager.get().armPlace(player, args[1].toLowerCase(Locale.ROOT), false);
            player.sendMessage(Messages.get("place.armed_admin"));
            return true;
        }
        if (args.length >= 2) {
            sender.sendMessage(Messages.get("place.usage"));
            return true;
        }
        GameSelectGui.open(player, false, null);
        return true;
    }

    private boolean handlePayout(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("place.players_only"));
            return true;
        }
        Table table = TableManager.get().tableNear(player);
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return true;
        }
        Player winner = player;
        if (args.length >= 2) {
            winner = Bukkit.getPlayerExact(args[1]);
            if (winner == null || !winner.isOnline()) {
                player.sendMessage(Messages.get("admin.unknown_player", "name", args[1]));
                return true;
            }
        }
        TableManager.get().payout(table, winner);
        return true;
    }

    private boolean handleSession(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("place.players_only"));
            return true;
        }
        if (args.length < 2 || (!args[1].equalsIgnoreCase("start") && !args[1].equalsIgnoreCase("stop"))) {
            sender.sendMessage(Messages.get("session.usage"));
            return true;
        }
        Table table = TableManager.get().tableNear(player);
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return true;
        }
        if (args[1].equalsIgnoreCase("start")) {
            if (table.live()) {
                player.sendMessage(Messages.get("session.already"));
                return true;
            }
            TableManager.get().beginSession(table);
            player.sendMessage(Messages.get("session.started"));
            return true;
        }
        if (!table.live()) {
            player.sendMessage(Messages.get("session.idle"));
            return true;
        }
        TableManager.get().endSession(table);
        player.sendMessage(Messages.get("session.stopped"));
        return true;
    }

    private boolean handleBet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("place.players_only"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Messages.get("bet.usage"));
            return true;
        }
        Table table = TableManager.get().tableNearby(player);
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("hit") || action.equals("stand") || action.equals("double")
                || action.equals("split") || action.equals("check") || action.equals("call")
                || action.equals("fold") || action.equals("raise")) {
            return handleBetPlay(player, table, action);
        }
        if (!player.getUniqueId().equals(table.dealerId())) {
            player.sendMessage(Messages.get("bet.not_dealer"));
            return true;
        }
        if (table.live()) {
            player.sendMessage(Messages.get("session.already"));
            return true;
        }
        switch (action) {
            case "min":
            case "max":
                if (args.length < 3) {
                    player.sendMessage(Messages.get("bet.usage"));
                    return true;
                }
                int n;
                try {
                    n = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    player.sendMessage(Messages.get("bet.usage"));
                    return true;
                }
                if (n < 1) {
                    player.sendMessage(Messages.get("bet.usage"));
                    return true;
                }
                if (action.equals("min")) {
                    if (table.maxBet() > 0 && n > table.maxBet()) {
                        player.sendMessage(Messages.get("bet.usage"));
                        return true;
                    }
                    table.setMinBet(n);
                    player.sendMessage(Messages.get("bet.min_set", "min", String.valueOf(n)));
                } else {
                    if (table.minBet() > 0 && n < table.minBet()) {
                        player.sendMessage(Messages.get("bet.usage"));
                        return true;
                    }
                    table.setMaxBet(n);
                    player.sendMessage(Messages.get("bet.max_set", "max", String.valueOf(n)));
                }
                TableManager.get().refreshLabel(table);
                return true;
            case "open":
                if (table.minBet() < 1 || table.maxBet() < 1) {
                    player.sendMessage(Messages.get("bet.need_limits"));
                    return true;
                }
                table.setBetOpen(true);
                TableManager.get().refreshLabel(table);
                player.sendMessage(Messages.get("bet.opened"));
                return true;
            case "close":
                table.setBetOpen(false);
                Game game = GamesRegistry.of(table.getGameId());
                if (game instanceof BlackjackGame blackjack) {
                    blackjack.closeBets(table);
                } else if (TableManager.get().hasNonDealerOwnedPile(table)) {
                    TableManager.get().beginSession(table);
                }
                TableManager.get().refreshLabel(table);
                player.sendMessage(Messages.get("bet.closed_cmd"));
                return true;
            default:
                player.sendMessage(Messages.get("bet.usage"));
                return true;
        }
    }

    private boolean handleBetPlay(Player player, Table table, String action) {
        if (!table.live()) {
            player.sendMessage(Messages.get("bet.need_live"));
            return true;
        }
        Game game = GamesRegistry.of(table.getGameId());
        if (game == null) {
            player.sendMessage(Messages.get("bet.need_live"));
            return true;
        }
        if (game.allowPlayChat(table, player)) {
            TableManager.get().applyPlayCall(player, action);
            return true;
        }
        player.sendMessage(Messages.get("bet.not_actor"));
        return true;
    }

    private boolean handleDeal(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.get("place.players_only"));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("table")) {
            return handleDealTable(player, args);
        }
        int n = 1;
        if (args.length >= 2) {
            try {
                n = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(Messages.get("deal.usage"));
                return true;
            }
            if (n < 1) {
                sender.sendMessage(Messages.get("deal.usage"));
                return true;
            }
        }
        Table table = TableManager.get().tableNear(player);
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return true;
        }
        TableManager.get().dealToPlayer(table, player, n);
        player.sendMessage(Messages.get("deal.done", "count", String.valueOf(n)));
        return true;
    }

    private boolean handleDealTable(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Messages.get("deal.table_usage"));
            return true;
        }
        String pile = args[2].toLowerCase(Locale.ROOT);
        if (!pile.equals("board") && !pile.equals("dealer")) {
            player.sendMessage(Messages.get("deal.table_usage"));
            return true;
        }
        int n = 1;
        boolean faceUp = true;
        int i = 3;
        if (args.length > i && args[i].equalsIgnoreCase("back")) {
            faceUp = false;
            i++;
        } else if (args.length > i) {
            try {
                n = Integer.parseInt(args[i]);
            } catch (NumberFormatException ex) {
                player.sendMessage(Messages.get("deal.table_usage"));
                return true;
            }
            if (n < 1) {
                player.sendMessage(Messages.get("deal.table_usage"));
                return true;
            }
            i++;
            if (args.length > i && args[i].equalsIgnoreCase("back")) {
                faceUp = false;
            }
        }
        Table table = TableManager.get().tableNear(player);
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return true;
        }
        TableManager.get().dealToTable(table, pile, n, faceUp);
        player.sendMessage(Messages.get("deal.table_done", "pile", pile, "count", String.valueOf(n)));
        return true;
    }

    private static Location lookLocation(Player player) {
        Block target = player.getTargetBlockExact(8);
        if (target != null && target.getType() != Material.AIR) {
            return target.getLocation().add(0.5, 1.05 + Cache.tableYOffset, 0.5);
        }
        Location loc = player.getEyeLocation().add(player.getLocation().getDirection().multiply(2.0));
        loc.setY(player.getLocation().getY() + 1.0 + Cache.tableYOffset);
        return loc;
    }

    private static ItemStack itemFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return TLibs.getItemAPI().getCreator().getItemFromPath(path);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase(cmd1)) {
            return Collections.emptyList();
        }
        boolean admin = sender.hasPermission("games.admin");
        boolean bet = sender.hasPermission("games.bet");
        boolean help = sender.hasPermission("games.help");
        if (!admin && !bet && !help) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> first = new ArrayList<>();
            if (help) {
                first.add("help");
            }
            if (bet) {
                first.add("bet");
            }
            if (admin) {
                first.addAll(List.of("reload", "deck", "display", "place", "payout", "session", "deal"));
            }
            return prefix(first, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help") && help) {
            return prefix(helpBookIds(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bet") && bet) {
            return prefix(List.of("min", "max", "open", "close", "hit", "stand", "double", "split",
                    "check", "call", "fold", "raise"), args[1]);
        }
        if (!admin) {
            return Collections.emptyList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deal")) {
            return prefix(List.of("table"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("deal") && args[1].equalsIgnoreCase("table")) {
            return prefix(List.of("board", "dealer"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("session")) {
            return prefix(List.of("start", "stop"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("payout")) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return prefix(names, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            return prefix(List.of("poker", "draw", "blackjack", "freeplay"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deck")) {
            return prefix(List.of("test"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("display")) {
            return prefix(List.of("test"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("deck") && args[1].equalsIgnoreCase("test")) {
            return prefix(new ArrayList<>(CardLoader.setNames()), args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> prefix(List<String> options, String token) {
        String lower = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
