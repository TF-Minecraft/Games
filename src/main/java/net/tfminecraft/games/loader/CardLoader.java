package net.tfminecraft.games.loader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.card.Card;

public final class CardLoader implements LoaderInterface {

    private static final Map<String, Card> cards = new LinkedHashMap<>();
    private static final Map<String, List<String>> setIds = new LinkedHashMap<>();
    private static String backItem = "ia.tfmc_games:card_back";
    private static String deckItem = "ia.tfmc_games:deck";

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Games.plugin.getLogger().severe("[Games] Failed to load cards.yml: " + ex.getMessage());
            return false;
        }

        cards.clear();
        setIds.clear();

        ConfigurationSection cardsSection = config.getConfigurationSection("cards");
        if (cardsSection != null) {
            for (String id : cardsSection.getKeys(false)) {
                ConfigurationSection section = cardsSection.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                Card card = new Card(
                        id,
                        section.getString("suit", "none"),
                        section.getInt("rank", 0),
                        section.getBoolean("joker", false),
                        section.getString("item", "ia.tfmc_games:" + id));
                cards.put(id.toLowerCase(Locale.ROOT), card);
            }
        }

        backItem = config.getString("back", backItem);
        deckItem = config.getString("deck-item", deckItem);

        ConfigurationSection setsSection = config.getConfigurationSection("sets");
        if (setsSection != null) {
            for (String setName : setsSection.getKeys(false)) {
                List<String> ids = new ArrayList<>();
                for (String rawId : setsSection.getStringList(setName)) {
                    if (rawId == null || rawId.isBlank()) {
                        continue;
                    }
                    Card card = get(rawId);
                    if (card == null) {
                        Games.plugin.getLogger().warning(
                                "[Games] Set '" + setName + "' skips unknown card id: " + rawId);
                        continue;
                    }
                    ids.add(card.getId());
                }
                setIds.put(setName.toLowerCase(Locale.ROOT), Collections.unmodifiableList(ids));
            }
        }

        if (Games.plugin != null && net.tfminecraft.games.cache.Cache.debug) {
            Games.plugin.getLogger().info("[Games] Debug: cards=" + cards.size()
                    + " sets=" + setIds.keySet());
        }
        return true;
    }

    public static Card get(String id) {
        if (id == null) {
            return null;
        }
        return cards.get(id.toLowerCase(Locale.ROOT));
    }

    public static List<Card> getSet(String name) {
        List<String> ids = setIds.get(normalizeSet(name));
        if (ids == null) {
            return List.of();
        }
        List<Card> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Card card = get(id);
            if (card != null) {
                result.add(card);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean hasSet(String name) {
        return setIds.containsKey(normalizeSet(name));
    }

    public static Set<String> setNames() {
        return Collections.unmodifiableSet(setIds.keySet());
    }

    public static String getBackItem() {
        return backItem;
    }

    public static String getDeckItem() {
        return deckItem;
    }

    public static int cardCount() {
        return cards.size();
    }

    private static String normalizeSet(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
