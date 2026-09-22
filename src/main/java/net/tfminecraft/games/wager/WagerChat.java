package net.tfminecraft.games.wager;

import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.tfminecraft.games.Messages;

/** Chat lines for loot votes. Hover is title plus lore text only. */
public final class WagerChat {

    private WagerChat() {}

    public static void sendProposed(Player viewer, String playerName, ItemStack item, int denars) {
        String raw = Messages.get("wager.proposed",
                "player", playerName,
                "amount", String.valueOf(item.getAmount()),
                "denars", String.valueOf(denars));
        String title = itemTitle(item);
        int idx = raw.indexOf("{item}");
        if (idx < 0) {
            viewer.sendMessage(raw);
            return;
        }
        JsonArray root = new JsonArray();
        root.add(textNode(raw.substring(0, idx)));
        JsonObject name = textNode(title);
        JsonObject hover = new JsonObject();
        hover.addProperty("action", "show_text");
        hover.addProperty("contents", hoverBody(item, title));
        name.add("hoverEvent", hover);
        root.add(name);
        root.add(textNode(raw.substring(idx + "{item}".length())));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tellraw " + viewer.getName() + " " + root);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static String itemTitle(ItemStack item) {
        if (item == null) {
            return "item";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        String raw = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (raw.isEmpty()) {
            return item.getType().name();
        }
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static String hoverBody(ItemStack item, String title) {
        StringBuilder body = new StringBuilder(title);
        ItemMeta meta = item != null ? item.getItemMeta() : null;
        if (meta != null && meta.hasLore()) {
            List<String> lore = meta.getLore();
            if (lore != null) {
                for (String line : lore) {
                    if (line == null) {
                        continue;
                    }
                    body.append('\n').append(line);
                }
            }
        }
        return body.toString();
    }

    private static JsonObject textNode(String raw) {
        JsonObject node = new JsonObject();
        node.addProperty("text", raw == null ? "" : raw);
        return node;
    }
}
