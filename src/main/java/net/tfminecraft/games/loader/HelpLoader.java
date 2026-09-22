package net.tfminecraft.games.loader;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.help.HelpBook;

/**
 * Reads help.yml, where every top level key is a book somebody can ask for by name.
 */
public final class HelpLoader implements LoaderInterface {

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Games.plugin.getLogger().severe("[Games] Failed to load help.yml: " + ex.getMessage());
            return false;
        }

        Cache.helpBooks.clear();
        for (String id : config.getKeys(false)) {
            ConfigurationSection section = config.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            List<String> pages = section.getStringList("pages");
            if (pages.isEmpty()) {
                Games.plugin.getLogger().warning("[Games] Help book '" + id + "' has no pages.");
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            Cache.helpBooks.put(key, new HelpBook(section.getString("title", id),
                    section.getString("author", "The house"), pages));
        }
        if (Cache.helpBooks.isEmpty()) {
            Games.plugin.getLogger().warning("[Games] help.yml had no usable books.");
        }
        return true;
    }
}
