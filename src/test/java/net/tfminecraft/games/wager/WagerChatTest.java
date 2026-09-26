package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.google.gson.JsonParser;
import net.tfminecraft.games.Messages;

class WagerChatTest {
    @Test
    void itemTitlesPreferCustomNameOtherwiseReadableMaterial() {
        assertEquals("item", WagerChat.itemTitle(null));
        assertEquals("Iron sword", WagerChat.itemTitle(new ItemStack(Material.IRON_SWORD)));
        ItemStack item = new ItemStack(Material.PAPER);
        var meta = item.getItemMeta();
        meta.setDisplayName("§6Rare map");
        item.setItemMeta(meta);
        assertEquals("§6Rare map", WagerChat.itemTitle(item));
    }

    @Test
    void proposalEmbedsEscapedItemTitleAndLoreAsHoverText() {
        Player viewer = mock(Player.class);
        when(viewer.getName()).thenReturn("Alice");
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        ItemStack item = new ItemStack(Material.PAPER, 3);
        var meta = item.getItemMeta();
        String title = "Map \"North\" \\ roads";
        meta.setDisplayName(title);
        meta.setLore(List.of("First line", "Second \"line\""));
        item.setItemMeta(meta);
        try (MockedStatic<Messages> messages = mockStatic(Messages.class);
                MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            messages.when(() -> Messages.get("wager.proposed", "player", "Bob", "amount", "3", "denars", "25"))
                    .thenReturn("Bob offers {item} for 25.");
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            bukkit.when(() -> Bukkit.dispatchCommand(eq(console), anyString())).thenAnswer(call -> {
                String command = call.getArgument(1);
                assertTrue(command.startsWith("tellraw Alice "));
                var json = JsonParser.parseString(command.substring("tellraw Alice ".length())).getAsJsonArray();
                assertEquals(3, json.size());
                assertEquals("Bob offers ", json.get(0).getAsJsonObject().get("text").getAsString());
                var label = json.get(1).getAsJsonObject();
                assertEquals(title, label.get("text").getAsString());
                assertEquals("show_text", label.getAsJsonObject("hoverEvent").get("action").getAsString());
                assertEquals(title + "\nFirst line\nSecond \"line\"", label.getAsJsonObject("hoverEvent").get("contents").getAsString());
                assertEquals(" for 25.", json.get(2).getAsJsonObject().get("text").getAsString());
                return true;
            });
            WagerChat.sendProposed(viewer, "Bob", item, 25);
            bukkit.verify(() -> Bukkit.dispatchCommand(eq(console), anyString()));
            verify(viewer, never()).sendMessage(anyString());
        }
    }

    @Test
    void proposalOfPlainItemHoversWithItsNameAlone() {
        Player viewer = mock(Player.class);
        when(viewer.getName()).thenReturn("Alice");
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        try (MockedStatic<Messages> messages = mockStatic(Messages.class);
                MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            messages.when(() -> Messages.get("wager.proposed", "player", "Bob", "amount", "1", "denars", "8"))
                    .thenReturn("{item} from Bob");
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            bukkit.when(() -> Bukkit.dispatchCommand(eq(console), anyString())).thenReturn(true);
            WagerChat.sendProposed(viewer, "Bob", item, 8);
            bukkit.verify(() -> Bukkit.dispatchCommand(eq(console), argThat(command -> {
                var json = JsonParser.parseString(command.substring("tellraw Alice ".length())).getAsJsonArray();
                var label = json.get(1).getAsJsonObject();
                return json.get(0).getAsJsonObject().get("text").getAsString().isEmpty()
                        && "Iron sword".equals(label.get("text").getAsString())
                        && "Iron sword".equals(label.getAsJsonObject("hoverEvent").get("contents").getAsString())
                        && " from Bob".equals(json.get(2).getAsJsonObject().get("text").getAsString());
            })));
        }
    }

    @Test
    void templateWithoutItemPlaceholderUsesOrdinaryChat() {
        Player viewer = mock(Player.class);
        ItemStack item = new ItemStack(Material.PAPER);
        try (MockedStatic<Messages> messages = mockStatic(Messages.class);
                MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            messages.when(() -> Messages.get("wager.proposed", "player", "Bob", "amount", "1", "denars", "5"))
                    .thenReturn("Bob offers an item for 5.");
            WagerChat.sendProposed(viewer, "Bob", item, 5);
            verify(viewer).sendMessage("Bob offers an item for 5.");
            bukkit.verifyNoInteractions();
        }
    }
}
