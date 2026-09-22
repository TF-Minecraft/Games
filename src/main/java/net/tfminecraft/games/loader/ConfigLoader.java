package net.tfminecraft.games.loader;

import java.io.File;
import java.io.IOException;

import java.util.Locale;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.wager.WagerItemOverride;

public final class ConfigLoader implements LoaderInterface {

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Games.plugin.getLogger().severe("[Games] Failed to load config.yml: " + ex.getMessage());
            return false;
        }

        Cache.debug = config.getBoolean("debug", Cache.debug);
        int stackMax = config.getInt("stack-visible-max", Cache.stackVisibleMax);
        Cache.stackVisibleMax = stackMax > 0 ? stackMax : Cache.stackVisibleMax;
        Cache.cardScale = (float) config.getDouble("card-scale", Cache.cardScale);
        int interp = config.getInt("interpolation-ticks", Cache.interpolationTicks);
        Cache.interpolationTicks = interp >= 0 ? interp : Cache.interpolationTicks;
        Cache.tableYOffset = config.getDouble("table-y-offset", Cache.tableYOffset);
        double range = config.getDouble("display-range", Cache.displayRange);
        Cache.displayRange = range > 0 ? range : Cache.displayRange;
        float gap = (float) config.getDouble("stack-layer-gap", Cache.stackLayerGap);
        Cache.stackLayerGap = gap > 0 ? gap : Cache.stackLayerGap;
        double discardOff = config.getDouble("table.discard-offset", Cache.tableDiscardOffset);
        Cache.tableDiscardOffset = discardOff;
        double boardOff = config.getDouble("table.board-offset", Cache.tableBoardOffset);
        Cache.tableBoardOffset = boardOff > 0 ? boardOff : Cache.tableBoardOffset;
        int recycleTicks = config.getInt("table.recycle-ticks", Cache.tableRecycleTicks);
        Cache.tableRecycleTicks = recycleTicks >= 0 ? recycleTicks : Cache.tableRecycleTicks;
        Cache.tableCardYawOffset = (float) config.getDouble("table-card.yaw-offset", Cache.tableCardYawOffset);
        Cache.tableCardPitch = (float) config.getDouble("table-card.pitch", Cache.tableCardPitch);
        Cache.tableCardRoll = (float) config.getDouble("table-card.roll", Cache.tableCardRoll);
        float handDist = (float) config.getDouble("hand.distance", Cache.handDistance);
        Cache.handDistance = handDist > 0 ? handDist : Cache.handDistance;
        Cache.handSpread = (float) config.getDouble("hand.spread", Cache.handSpread);
        Cache.handLift = (float) config.getDouble("hand.lift", Cache.handLift);
        Cache.handLayerGap = (float) config.getDouble("hand.layer-gap", Cache.handLayerGap);
        int follow = config.getInt("hand.follow-ticks", Cache.handFollowTicks);
        Cache.handFollowTicks = follow >= 0 ? follow : Cache.handFollowTicks;
        Cache.handYawLimit = (float) config.getDouble("hand.yaw-limit", Cache.handYawLimit);
        float stick = (float) config.getDouble("hand.yaw-stick", Cache.handYawStick);
        Cache.handYawStick = stick >= 0f ? stick : Cache.handYawStick;
        float selectedDist = (float) config.getDouble("hand.selected-distance", Cache.handSelectedDistance);
        Cache.handSelectedDistance = selectedDist > 0 ? selectedDist : Cache.handSelectedDistance;
        int selectTicks = config.getInt("hand.select-ticks", Cache.handSelectTicks);
        Cache.handSelectTicks = selectTicks >= 0 ? selectTicks : Cache.handSelectTicks;
        double selectRange = config.getDouble("hand.select-range", Cache.handSelectRange);
        Cache.handSelectRange = selectRange > 0 ? selectRange : Cache.handSelectRange;
        double selectRadius = config.getDouble("hand.select-radius", Cache.handSelectRadius);
        Cache.handSelectRadius = selectRadius > 0 ? selectRadius : Cache.handSelectRadius;
        double posStick = config.getDouble("hand.pos-stick", Cache.handPosStick);
        Cache.handPosStick = posStick >= 0 ? posStick : Cache.handPosStick;
        double sitRange = config.getDouble("hand.sit-range", Cache.handSitRange);
        Cache.handSitRange = sitRange > 0 ? sitRange : Cache.handSitRange;
        float sitEdge = (float) config.getDouble("hand.sit-edge", Cache.handSitEdge);
        Cache.handSitEdge = sitEdge > 0 ? sitEdge : Cache.handSitEdge;
        float sitInset = (float) config.getDouble("hand.sit-inset", Cache.handSitInset);
        Cache.handSitInset = sitInset >= 0 ? sitInset : Cache.handSitInset;
        float groupGap = (float) config.getDouble("hand.split-group-gap", Cache.handSplitGroupGap);
        Cache.handSplitGroupGap = groupGap >= 0 ? groupGap : Cache.handSplitGroupGap;
        int stagger = config.getInt("hand.reveal-stagger", Cache.handRevealStagger);
        Cache.handRevealStagger = stagger >= 0 ? stagger : Cache.handRevealStagger;
        int flip = config.getInt("hand.reveal-flip", Cache.handRevealFlip);
        Cache.handRevealFlip = flip >= 0 ? flip : Cache.handRevealFlip;
        int deal = config.getInt("hand.deal-ticks", Cache.handDealTicks);
        Cache.handDealTicks = deal >= 0 ? deal : Cache.handDealTicks;
        float dust = (float) config.getDouble("hand.reveal-dust", Cache.handRevealDust);
        Cache.handRevealDust = dust >= 0 ? dust : Cache.handRevealDust;
        Cache.cardSound = parseSound(config.getString("card-sound.name", "ITEM_BOOK_PAGE_TURN"),
                "card-sound.name", Sound.ITEM_BOOK_PAGE_TURN);
        Cache.cardSoundVolume = (float) config.getDouble("card-sound.volume", Cache.cardSoundVolume);
        Cache.cardSoundPitch = (float) config.getDouble("card-sound.pitch", Cache.cardSoundPitch);
        Cache.chipSound = parseSound(config.getString("chip-sound.name", "ENTITY_EXPERIENCE_ORB_PICKUP"),
                "chip-sound.name", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        Cache.chipSoundVolume = (float) config.getDouble("chip-sound.volume", Cache.chipSoundVolume);
        Cache.chipSoundPitch = (float) config.getDouble("chip-sound.pitch", Cache.chipSoundPitch);
        double wagerMin = config.getDouble("wager.min-range", Cache.wagerMinRange);
        Cache.wagerMinRange = wagerMin >= 0 ? wagerMin : Cache.wagerMinRange;
        double wagerMax = config.getDouble("wager.max-range", Cache.wagerMaxRange);
        Cache.wagerMaxRange = wagerMax > Cache.wagerMinRange ? wagerMax : Cache.wagerMaxRange;
        Cache.wagerIntegerDenars = config.getBoolean("wager.integer-denars", Cache.wagerIntegerDenars);
        int wagerStackMax = config.getInt("wager.stack-max", Cache.wagerStackMax);
        Cache.wagerStackMax = wagerStackMax > 0 ? wagerStackMax : Cache.wagerStackMax;
        int wagerUnit = config.getInt("wager.stack-unit", Cache.wagerStackUnit);
        Cache.wagerStackUnit = wagerUnit > 0 ? wagerUnit : Cache.wagerStackUnit;
        float wagerGap = (float) config.getDouble("wager.layer-gap", Cache.wagerLayerGap);
        Cache.wagerLayerGap = wagerGap > 0 ? wagerGap : Cache.wagerLayerGap;
        float itemScale = (float) config.getDouble("wager.item-scale", Cache.wagerItemScale);
        Cache.wagerItemScale = itemScale > 0 ? itemScale : Cache.wagerItemScale;
        Cache.wagerRandomYaw = config.getBoolean("wager.random-yaw", Cache.wagerRandomYaw);
        Cache.wagerYOffset = config.getDouble("wager.y-offset", Cache.wagerYOffset);
        double merge = config.getDouble("wager.merge-range", Cache.wagerMergeRange);
        Cache.wagerMergeRange = merge > 0 ? merge : Cache.wagerMergeRange;
        int voteSeconds = config.getInt("wager.vote-seconds", Cache.wagerVoteSeconds);
        Cache.wagerVoteSeconds = voteSeconds > 0 ? voteSeconds : Cache.wagerVoteSeconds;
        int placeSeconds = config.getInt("wager.place-seconds", Cache.wagerPlaceSeconds);
        Cache.wagerPlaceSeconds = placeSeconds > 0 ? placeSeconds : Cache.wagerPlaceSeconds;
        int payoutTicks = config.getInt("wager.payout-ticks", Cache.wagerPayoutTicks);
        Cache.wagerPayoutTicks = payoutTicks >= 0 ? payoutTicks : Cache.wagerPayoutTicks;
        int minPlayers = config.getInt("wager.min-players", Cache.wagerMinPlayers);
        Cache.wagerMinPlayers = minPlayers > 0 ? minPlayers : Cache.wagerMinPlayers;
        Cache.wagerAuditLog = config.getBoolean("wager.audit-log", Cache.wagerAuditLog);
        Cache.wagerShowChips = config.getBoolean("wager.show-chips", Cache.wagerShowChips);
        Cache.wagerGold = readOverride(config.getConfigurationSection("wager.gold"), "ia.tfmc:gold_coin");
        Cache.wagerSilver = readOverride(config.getConfigurationSection("wager.silver"), "m.currency.silver_coin");
        Cache.wagerItems.clear();
        ConfigurationSection items = config.getConfigurationSection("wager.items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                ConfigurationSection section = items.getConfigurationSection(key);
                WagerItemOverride override = readOverride(section, key.trim());
                if (override != null) {
                    Cache.wagerItems.add(override);
                }
            }
        }
        return true;
    }

    private static WagerItemOverride readOverride(ConfigurationSection section, String fallbackItem) {
        String item = fallbackItem;
        if (section != null) {
            String configured = section.getString("item");
            if (configured != null && !configured.isBlank()) {
                item = configured.trim();
            }
        }
        if (item == null || item.isBlank()) {
            return null;
        }
        Integer value = null;
        String model = null;
        Integer stackMax = null;
        Float layerGap = null;
        Float scale = null;
        Boolean randomYaw = null;
        Float pitch = null;
        boolean threeD = false;
        Double yOffset = null;
        if (section != null) {
            if (section.contains("value")) {
                int parsed = section.getInt("value");
                value = parsed > 0 ? parsed : null;
            }
            String configuredModel = section.getString("model");
            if (configuredModel != null && !configuredModel.isBlank()) {
                model = configuredModel.trim();
            }
            if (section.contains("stack-max")) {
                int parsed = section.getInt("stack-max");
                stackMax = parsed > 0 ? parsed : null;
            }
            if (section.contains("layer-gap")) {
                float parsed = (float) section.getDouble("layer-gap");
                layerGap = parsed > 0 ? parsed : null;
            }
            if (section.contains("scale")) {
                float parsed = (float) section.getDouble("scale");
                scale = parsed > 0 ? parsed : null;
            }
            if (section.contains("random-yaw")) {
                randomYaw = section.getBoolean("random-yaw");
            }
            if (section.contains("pitch")) {
                pitch = (float) section.getDouble("pitch");
            }
            threeD = section.getBoolean("3d", false);
            if (section.contains("y-offset")) {
                yOffset = section.getDouble("y-offset");
            }
        }
        return new WagerItemOverride(item, value, model, stackMax, layerGap, scale, randomYaw, pitch, threeD, yOffset);
    }

    // Existing configuration accepts legacy enum names and aliases; registry keys are not equivalent.
    @SuppressWarnings("deprecation")
    public static Sound parseSound(String raw, String path, Sound unknownFallback) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        key = key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Sound.valueOf(key);
        } catch (IllegalArgumentException ex) {
            Sound fallback = unknownFallback != null ? unknownFallback : Sound.ITEM_BOOK_PAGE_TURN;
            Games.plugin.getLogger().warning("[Games] Unknown " + path + " '" + raw + "', using " + fallback.name()
                    + ".");
            return fallback;
        }
    }
}
