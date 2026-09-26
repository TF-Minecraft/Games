package net.tfminecraft.games;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.command.CommandManager;
import net.tfminecraft.games.command.WagerCommand;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.display.ProtocolLibBridge;
import net.tfminecraft.games.gui.GameSelectGui;
import net.tfminecraft.games.gui.TableOptionsGui;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.loader.ConfigLoader;
import net.tfminecraft.games.loader.GamesLoader;
import net.tfminecraft.games.loader.HelpLoader;
import net.tfminecraft.games.table.TableManager;

/**
 * Tabletop games. See docs/ for batch plan.
 */
public class Games extends JavaPlugin {

    public static Games plugin;

    private final ConfigLoader configLoader = new ConfigLoader();
    private final GamesLoader gamesLoader = new GamesLoader();
    private final CardLoader cardLoader = new CardLoader();
    private final HelpLoader helpLoader = new HelpLoader();
    private final CommandManager commandManager = new CommandManager();
    private final WagerCommand wagerCommand = new WagerCommand();

    @Override
    public void onEnable() {
        plugin = this;
        createFolders();
        createConfigs();
        if (!loadConfigs()) {
            getLogger().warning("Games loaded with config errors.");
        }

        ProtocolLibBridge.init(this);
        getServer().getPluginManager().registerEvents(DisplayManager.get(), this);
        getServer().getPluginManager().registerEvents(TableManager.get(), this);
        getServer().getPluginManager().registerEvents(GameSelectGui.INSTANCE, this);
        getServer().getPluginManager().registerEvents(TableOptionsGui.INSTANCE, this);
        TableManager.get().loadAll();
        TableManager.get().startClock();

        var gameCmd = getCommand("games");
        if (gameCmd != null) {
            gameCmd.setExecutor(commandManager);
            gameCmd.setTabCompleter(commandManager);
        } else {
            getLogger().severe("Command 'games' missing from plugin.yml");
        }
        var wagerCmd = getCommand("wager");
        if (wagerCmd != null) {
            wagerCmd.setExecutor(wagerCommand);
            wagerCmd.setTabCompleter(wagerCommand);
        } else {
            getLogger().severe("Command 'wager' missing from plugin.yml");
        }

        getLogger().info("Games enabled.");
    }

    @Override
    public void onDisable() {
        TableManager.get().stopClock();
        TableManager.get().despawnWorldAll();
        DisplayManager.get().shutdown();
        ProtocolLibBridge.shutdown();
        getLogger().info("Games disabled.");
    }

    public boolean reloadAll() {
        boolean ok = loadConfigs();
        if (ok) {
            TableManager.get().wipeHands();
            // wager.show-chips may have flipped, so draw every table again.
            TableManager.get().redrawAllChips();
        }
        return ok;
    }

    private boolean loadConfigs() {
        boolean ok = true;
        ok &= configLoader.loadSafe(new File(getDataFolder(), "config.yml"));
        Messages.load(new File(getDataFolder(), "messages.yml"));
        ok &= gamesLoader.loadSafe(new File(getDataFolder(), "games.yml"));
        ok &= cardLoader.loadSafe(new File(getDataFolder(), "cards.yml"));
        ok &= helpLoader.loadSafe(new File(getDataFolder(), "help.yml"));
        if (ok) {
            getLogger().info("[Games] Configs loaded.");
        }
        if (Cache.debug) {
            getLogger().info("[Games] Debug: stack-visible-max=" + Cache.stackVisibleMax
                    + " card-scale=" + Cache.cardScale
                    + " interpolation-ticks=" + Cache.interpolationTicks
                    + " table-y-offset=" + Cache.tableYOffset
                    + " poker.leave-distance=" + Cache.pokerLeaveDistance
                    + " poker.icon=" + Cache.pokerIcon
                    + " display-range=" + Cache.displayRange
                    + " table-card.yaw-offset=" + Cache.tableCardYawOffset
                    + " table-card.pitch=" + Cache.tableCardPitch
                    + " table-card.roll=" + Cache.tableCardRoll
                    + " hand.distance=" + Cache.handDistance
                    + " hand.spread=" + Cache.handSpread
                    + " hand.lift=" + Cache.handLift
                    + " hand.layer-gap=" + Cache.handLayerGap
                    + " hand.follow-ticks=" + Cache.handFollowTicks
                    + " hand.yaw-limit=" + Cache.handYawLimit
                    + " hand.yaw-stick=" + Cache.handYawStick
                    + " hand.selected-distance=" + Cache.handSelectedDistance
                    + " hand.select-ticks=" + Cache.handSelectTicks
                    + " hand.select-range=" + Cache.handSelectRange
                    + " hand.select-radius=" + Cache.handSelectRadius
                    + " hand.pos-stick=" + Cache.handPosStick
                    + " hand.sit-range=" + Cache.handSitRange
                    + " hand.sit-edge=" + Cache.handSitEdge
                    + " hand.sit-inset=" + Cache.handSitInset
                    + " hand.split-group-gap=" + Cache.handSplitGroupGap
                    + " hand.reveal-stagger=" + Cache.handRevealStagger
                    + " hand.reveal-flip=" + Cache.handRevealFlip
                    + " hand.deal-ticks=" + Cache.handDealTicks
                    + " hand.reveal-dust=" + Cache.handRevealDust
                    + " card-sound=" + Cache.cardSound
                    + " chip-sound=" + Cache.chipSound
                    + " wager.min-range=" + Cache.wagerMinRange
                    + " wager.max-range=" + Cache.wagerMaxRange
                    + " wager.integer-denars=" + Cache.wagerIntegerDenars
                    + " wager.stack-max=" + Cache.wagerStackMax
                    + " wager.stack-unit=" + Cache.wagerStackUnit
                    + " wager.layer-gap=" + Cache.wagerLayerGap
                    + " wager.item-scale=" + Cache.wagerItemScale
                    + " wager.y-offset=" + Cache.wagerYOffset
                    + " wager.random-yaw=" + Cache.wagerRandomYaw
                    + " wager.merge-range=" + Cache.wagerMergeRange
                    + " wager.vote-seconds=" + Cache.wagerVoteSeconds
                    + " wager.place-seconds=" + Cache.wagerPlaceSeconds
                    + " wager.payout-ticks=" + Cache.wagerPayoutTicks
                    + " wager.audit-log=" + Cache.wagerAuditLog
                    + " wager.show-chips=" + Cache.wagerShowChips
                    + " table.discard-offset=" + Cache.tableDiscardOffset
                    + " table.board-offset=" + Cache.tableBoardOffset
                    + " table.recycle-ticks=" + Cache.tableRecycleTicks
                    + " wager.min-players=" + Cache.wagerMinPlayers
                    + " wager.gold=" + Cache.wagerGold.item()
                    + " wager.silver=" + Cache.wagerSilver.item()
                    + " wager.items=" + Cache.wagerItems.size()
                    + " poker.rank-values=" + Cache.gameRankValues
                    + " catalog=" + CardLoader.cardCount());
        }
        return ok;
    }

    private void createFolders() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
        mkdir("Data/tables");
    }

    private void mkdir(String relativePath) {
        File folder = new File(getDataFolder(), relativePath);
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    private void createConfigs() {
        String[] defaultFiles = {
            "config.yml",
            "messages.yml",
            "cards.yml",
            "games.yml",
            "help.yml"
        };
        for (String path : defaultFiles) {
            copyResourceIfMissing(path);
        }
    }

    private void copyResourceIfMissing(String relativePath) {
        File target = new File(getDataFolder(), relativePath);
        if (target.exists()) {
            return;
        }
        target.getParentFile().mkdirs();
        // Every default file is bundled in the plugin jar.
        try (InputStream in = getResource(relativePath)) {
            Files.copy(in, target.toPath());
        } catch (IOException ex) {
            getLogger().severe("Failed to copy default resource " + relativePath + ": " + ex.getMessage());
        }
    }
}
