package net.tfminecraft.games.loader;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.layout.TableLayout.BetZone;
import net.tfminecraft.games.layout.TableLayout.FeltBox;
import net.tfminecraft.games.layout.TableLayout.FeltRing;
import net.tfminecraft.games.layout.TableLayout.PileSlot;
import net.tfminecraft.games.layout.TableLayout.SoundFx;
import net.tfminecraft.games.layout.TableLayout.VoiceLines;

public final class GamesLoader implements LoaderInterface {

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Games.plugin.getLogger().severe("[Games] Failed to load games.yml: " + ex.getMessage());
            return false;
        }

        Cache.gameRankValues.clear();
        Cache.tableLayouts.clear();
        for (String gameId : config.getKeys(false)) {
            ConfigurationSection section = config.getConfigurationSection(gameId);
            if (section == null) {
                continue;
            }
            loadRankValues(gameId, section);
            Cache.tableLayouts.put(gameId.toLowerCase(Locale.ROOT), readLayout(section));
        }

        TableLayout poker = Cache.layoutOf("poker");
        if (poker != null) {
            if (poker.leaveDistance() > 0) {
                Cache.pokerLeaveDistance = poker.leaveDistance();
            }
            if (!poker.cardSet().isBlank()) {
                Cache.pokerCardSet = poker.cardSet();
            }
            if (!poker.label().isBlank()) {
                Cache.pokerLabel = poker.label();
            }
            if (!poker.icon().isBlank()) {
                Cache.pokerIcon = poker.icon();
            }
        }
        Cache.gameRankValues.putIfAbsent("poker", Map.of(1, 14));
        return true;
    }

    private static TableLayout readLayout(ConfigurationSection section) {
        String cardSet = section.getString("card-set", "");
        String label = section.getString("label", "");
        String icon = section.getString("icon", "");
        double leave = section.getDouble("leave-distance", 0);
        Map<String, PileSlot> piles = new HashMap<>();
        ConfigurationSection pileSec = section.getConfigurationSection("piles");
        if (pileSec != null) {
            for (String name : pileSec.getKeys(false)) {
                ConfigurationSection one = pileSec.getConfigurationSection(name);
                if (one == null) {
                    continue;
                }
                piles.put(name.toLowerCase(Locale.ROOT),
                        new PileSlot(one.getDouble("forward", 0), one.getDouble("right", 0)));
            }
        }
        FeltRing ring = null;
        FeltBox box = null;
        ConfigurationSection felt = section.getConfigurationSection("felt");
        if (felt != null) {
            String type = felt.getString("type", "ring");
            if (type.equalsIgnoreCase("box")) {
                box = new FeltBox(
                        felt.getDouble("forward", 0),
                        felt.getDouble("right", 0),
                        felt.getDouble("width", 0),
                        felt.getDouble("depth", 0));
            } else {
                ring = new FeltRing(
                        felt.getDouble("min-range", Cache.wagerMinRange),
                        felt.getDouble("max-range", Cache.wagerMaxRange));
            }
        }
        PileSlot stand = null;
        ConfigurationSection standSec = section.getConfigurationSection("stand");
        if (standSec != null) {
            stand = new PileSlot(standSec.getDouble("forward", 0), standSec.getDouble("right", 0));
        }
        double noBet = section.getDouble("no-bet-radius", 0);
        boolean hitsSoft17 = section.getBoolean("dealer-hits-soft-17", false);
        boolean autoDealer = section.getBoolean("auto-dealer", false);
        int minBet = Math.max(1, section.getInt("min-bet", 1));
        int maxBet = Math.max(0, section.getInt("max-bet", 0));
        int betSeconds = Math.max(1, section.getInt("bet-seconds", 10));
        VoiceLines voice = VoiceLines.defaults();
        ConfigurationSection voiceSec = section.getConfigurationSection("voice");
        if (voiceSec != null) {
            voice = new VoiceLines(
                    voiceSec.getString("channel", "rp"),
                    voiceSec.getString("hit", "Hit."),
                    voiceSec.getString("stand", "Stand."),
                    voiceSec.getString("double", "Double."),
                    voiceSec.getString("split", "Split."));
        }
        BetZone betZone = null;
        ConfigurationSection zoneSec = section.getConfigurationSection("bet-zone");
        if (zoneSec != null) {
            betZone = new BetZone(zoneSec.getDouble("radius", zoneSec.getDouble("half-width", 0.5)));
        }
        int resultDelay = Math.max(0, section.getInt("result-delay-ticks", 8));
        int roundEnd = Math.max(1, section.getInt("round-end-seconds", 10));
        int maxBoxes = Math.max(0, section.getInt("max-boxes", 0));
        int maxHandsPerBox = Math.max(1, section.getInt("max-hands-per-box", 4));
        boolean resplitAces = section.getBoolean("resplit-aces", false);
        int smallBlind = 0;
        int bigBlind = 0;
        ConfigurationSection blinds = section.getConfigurationSection("blinds");
        if (blinds != null) {
            int small = blinds.getInt("small", 0);
            int big = blinds.getInt("big", 0);
            if (small > 0) {
                smallBlind = Math.max(1, small);
            }
            if (big > 0) {
                bigBlind = Math.max(1, big);
            }
        }
        SoundFx chipFx = null;
        ConfigurationSection chipSec = section.getConfigurationSection("chip-sound");
        if (chipSec != null) {
            chipFx = new SoundFx(
                    ConfigLoader.parseSound(chipSec.getString("name"), "chip-sound.name",
                            Cache.chipSound != null ? Cache.chipSound : org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP),
                    (float) chipSec.getDouble("volume", Cache.chipSoundVolume),
                    (float) chipSec.getDouble("pitch", Cache.chipSoundPitch));
        }
        return new TableLayout(cardSet, label, icon, leave, piles, ring, box, stand, noBet, hitsSoft17,
                autoDealer, minBet, maxBet, betSeconds, voice, betZone, resultDelay, roundEnd, chipFx, maxBoxes,
                smallBlind, bigBlind, maxHandsPerBox, resplitAces);
    }

    private static void loadRankValues(String gameId, ConfigurationSection section) {
        ConfigurationSection values = section.getConfigurationSection("rank-values");
        if (values == null) {
            return;
        }
        Map<Integer, Integer> map = new HashMap<>();
        for (String key : values.getKeys(false)) {
            try {
                int rank = Integer.parseInt(key);
                map.put(rank, values.getInt(key));
            } catch (NumberFormatException ignored) {
                Games.plugin.getLogger().warning("[Games] Ignored non-numeric rank-values key '" + key
                        + "' under " + gameId);
            }
        }
        if (!map.isEmpty()) {
            Cache.gameRankValues.put(gameId.toLowerCase(Locale.ROOT), map);
        }
    }
}
