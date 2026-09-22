package net.tfminecraft.games.gui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableHouse;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.utils.Keys;

public final class TableOptionsGui implements Listener {

    private static final int SIZE = 27;
    private static final int SLOT_AUTO = 10;
    private static final int SLOT_MINT = 11;
    private static final int SLOT_MIN = 12;
    private static final int SLOT_MAX = 13;
    private static final int SLOT_BOXES = 14;
    private static final int SLOT_SHUFFLE = 15;
    private static final int SLOT_CONFIRM = 16;
    private static final int SLOT_CANCEL = 22;
    private static final long CHAT_TICKS = 20L * 60;

    private static final Map<UUID, ChatPrompt> prompts = new ConcurrentHashMap<>();

    private TableOptionsGui() {}

    public static final TableOptionsGui INSTANCE = new TableOptionsGui();

    public static void openPlace(Player player, boolean requireDeck, Location pending) {
        openPlace(player, requireDeck, pending, "blackjack");
    }

    public static void openPlace(Player player, boolean requireDeck, Location pending, String gameId) {
        String id = gameId != null ? gameId : "blackjack";
        TableLayout layout = Cache.layoutOf(id);
        TableHouse house = TableHouse.forPlace(player, layout);
        open(player, requireDeck, pending, null, id, house);
    }

    public static void openEdit(Player player, Table table) {
        if (player == null || table == null) {
            return;
        }
        open(player, false, null, table.getId(), table.getGameId(), TableHouse.from(table));
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static void open(Player player, boolean requireDeck, Location pending, UUID editId, String gameId,
            TableHouse house) {
        if (player == null || house == null) {
            return;
        }
        prompts.remove(player.getUniqueId());
        TableOptionsHolder holder = new TableOptionsHolder(requireDeck, pending, editId, gameId, house);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Messages.get("place.options_title"));
        holder.setInventory(inventory);
        fill(player, holder);
        player.openInventory(inventory);
    }

    private static void fill(Player player, TableOptionsHolder holder) {
        Inventory inventory = holder.getInventory();
        TableHouse house = holder.house();
        inventory.clear();
        if (isPoker(holder.gameId())) {
            inventory.setItem(SLOT_MIN, valueItem(Material.IRON_NUGGET, "small",
                    Messages.get("place.options_small", "n", String.valueOf(house.smallBlind()))));
            inventory.setItem(SLOT_MAX, valueItem(Material.GOLD_NUGGET, "big",
                    Messages.get("place.options_big", "n", String.valueOf(house.bigBlind()))));
            ShufflePolicy policy = house.shufflePolicy();
            inventory.setItem(SLOT_SHUFFLE, named(Material.BOOK, "shuffle",
                    Messages.get(policy == ShufflePolicy.ROUND ? "place.options_shuffle_round"
                            : "place.options_shuffle_shoe")));
            inventory.setItem(SLOT_CONFIRM, named(Material.LIME_WOOL, "confirm", Messages.get("place.options_confirm")));
            inventory.setItem(SLOT_CANCEL, named(Material.BARRIER, "cancel", Messages.get("place.options_cancel")));
            return;
        }
        boolean staff = player.hasPermission(TableHouse.STAFF_PERM);
        inventory.setItem(SLOT_AUTO, toggle(Material.LEVER, "auto", house.autoDealer(),
                Messages.get("place.options_auto"),
                Messages.get(house.autoDealer() ? "place.options_on" : "place.options_off")));
        if (staff) {
            inventory.setItem(SLOT_MINT, toggle(Material.GOLD_INGOT, "mint", house.staffMint(),
                    Messages.get("place.options_mint"),
                    Messages.get(house.staffMint() ? "place.options_on" : "place.options_off")));
        } else {
            inventory.setItem(SLOT_MINT, null);
        }
        inventory.setItem(SLOT_MIN, valueItem(Material.IRON_NUGGET, "min",
                Messages.get("place.options_min", "n", String.valueOf(house.minBet()))));
        inventory.setItem(SLOT_MAX, valueItem(Material.GOLD_NUGGET, "max",
                Messages.get("place.options_max", "n", String.valueOf(house.maxBet()))));
        inventory.setItem(SLOT_BOXES, valueItem(Material.PLAYER_HEAD, "boxes",
                Messages.get("place.options_boxes", "n", boxesLabel(house.maxBoxes()))));
        ShufflePolicy policy = house.shufflePolicy();
        inventory.setItem(SLOT_SHUFFLE, named(Material.BOOK, "shuffle",
                Messages.get(policy == ShufflePolicy.ROUND ? "place.options_shuffle_round"
                        : "place.options_shuffle_shoe")));
        inventory.setItem(SLOT_CONFIRM, named(Material.LIME_WOOL, "confirm", Messages.get("place.options_confirm")));
        inventory.setItem(SLOT_CANCEL, named(Material.BARRIER, "cancel", Messages.get("place.options_cancel")));
    }

    private static boolean isPoker(String gameId) {
        return gameId != null && "poker".equalsIgnoreCase(gameId);
    }

    private static String boxesLabel(int n) {
        return n < 1 ? Messages.get("place.options_boxes_open") : String.valueOf(n);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static ItemStack toggle(Material material, String action, boolean on, String name, String lore) {
        ItemStack item = named(material, action, name);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static ItemStack valueItem(Material material, String action, String name) {
        ItemStack item = named(material, action, name);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(Messages.get("place.options_chat")));
            item.setItemMeta(meta);
        }
        return item;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static ItemStack named(Material material, String action, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.getPersistentDataContainer().set(Keys.guiAction(), PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TableOptionsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(Keys.guiAction(), PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        TableHouse house = holder.house();
        boolean staff = player.hasPermission(TableHouse.STAFF_PERM);
        switch (action) {
            case "auto" -> house.setAutoDealer(!house.autoDealer());
            case "mint" -> {
                if (staff) {
                    house.setStaffMint(!house.staffMint());
                }
            }
            case "min" -> {
                startChat(player, holder, ChatField.MIN);
                return;
            }
            case "max" -> {
                startChat(player, holder, ChatField.MAX);
                return;
            }
            case "small" -> {
                startChat(player, holder, ChatField.SMALL);
                return;
            }
            case "big" -> {
                startChat(player, holder, ChatField.BIG);
                return;
            }
            case "boxes" -> {
                startChat(player, holder, ChatField.BOXES);
                return;
            }
            case "shuffle" -> house.setShufflePolicy(house.shufflePolicy().next());
            case "confirm" -> {
                GuiSounds.click(player);
                confirm(player, holder);
                return;
            }
            case "cancel" -> {
                GuiSounds.click(player);
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        GuiSounds.click(player);
        fill(player, holder);
    }

    private static void startChat(Player player, TableOptionsHolder holder, ChatField field) {
        GuiSounds.click(player);
        UUID id = player.getUniqueId();
        ChatPrompt prompt = new ChatPrompt(holder.requireDeck(), holder.pendingHit(), holder.editTableId(),
                holder.gameId(), holder.house(), field);
        prompts.put(id, prompt);
        player.closeInventory();
        player.sendMessage(Messages.get("place.options_chat_" + field.key));
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            ChatPrompt still = prompts.get(id);
            if (still != prompt) {
                return;
            }
            prompts.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline()) {
                online.sendMessage(Messages.get("place.options_chat_timeout"));
            }
        }, CHAT_TICKS);
    }

    // Retain Bukkit chat-event ordering and String message semantics for existing integrations.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        ChatPrompt prompt = prompts.get(event.getPlayer().getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String raw = event.getMessage() == null ? "" : event.getMessage().trim();
        Bukkit.getScheduler().runTask(Games.plugin, () -> applyChat(player, prompt, raw));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }

    private static void applyChat(Player player, ChatPrompt prompt, String raw) {
        ChatPrompt current = prompts.get(player.getUniqueId());
        if (current != prompt) {
            return;
        }
        prompts.remove(player.getUniqueId());
        if (raw.equalsIgnoreCase("cancel")) {
            player.sendMessage(Messages.get("place.options_chat_cancelled"));
            reopen(player, prompt);
            return;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            player.sendMessage(Messages.get("place.options_chat_invalid"));
            reopen(player, prompt);
            return;
        }
        TableLayout layout = Cache.layoutOf(prompt.gameId);
        boolean staff = player.hasPermission(TableHouse.STAFF_PERM);
        TableHouse house = prompt.house;
        switch (prompt.field) {
            case MIN -> house.setMinBet(clampMin(value, staff, layout, house.maxBet()));
            case MAX -> house.setMaxBet(clampMax(value, staff, layout, house.minBet()));
            case BOXES -> house.setMaxBoxes(clampBoxes(value, staff, layout));
            case SMALL -> {
                if (value < 0 || !blindsOk(value, house.bigBlind())) {
                    player.sendMessage(Messages.get(value < 0 ? "place.options_chat_invalid"
                            : "place.options_chat_blinds"));
                    reopen(player, prompt);
                    return;
                }
                house.setSmallBlind(value);
            }
            case BIG -> {
                if (value < 0 || !blindsOk(house.smallBlind(), value)) {
                    player.sendMessage(Messages.get(value < 0 ? "place.options_chat_invalid"
                            : "place.options_chat_blinds"));
                    reopen(player, prompt);
                    return;
                }
                house.setBigBlind(value);
            }
        }
        GuiSounds.click(player);
        reopen(player, prompt);
    }

    private static void reopen(Player player, ChatPrompt prompt) {
        if (player == null || !player.isOnline()) {
            return;
        }
        open(player, prompt.requireDeck, prompt.pending, prompt.editId, prompt.gameId, prompt.house);
    }

    private static void confirm(Player player, TableOptionsHolder holder) {
        TableHouse house = holder.house();
        boolean poker = isPoker(holder.gameId());
        if (!poker) {
            if (!player.hasPermission(TableHouse.STAFF_PERM)) {
                house.setStaffMint(false);
            }
            GuildTables.stampGuild(player, house);
            Table existing = holder.editTableId() != null ? TableManager.get().table(holder.editTableId()) : null;
            String refuse = GuildTables.refuseKey(player, house, existing);
            if (refuse != null) {
                player.closeInventory();
                GuiSounds.deny(player);
                GuildTables.tellRefuse(player, refuse, house);
                return;
            }
        }
        player.closeInventory();
        if (holder.editTableId() != null) {
            Table table = TableManager.get().table(holder.editTableId());
            if (table == null || table.live()) {
                player.sendMessage(Messages.get("place.options_live"));
                return;
            }
            if (!TableManager.get().canEditHouse(player, table)) {
                player.sendMessage(Messages.get("place.options_denied"));
                return;
            }
            TableManager.get().applyHouse(table, house);
            player.sendMessage(Messages.get("place.options_saved"));
            return;
        }
        house.setOwnerPlayer(player.getUniqueId());
        TableManager.get().armPlace(player, holder.gameId(), holder.requireDeck(), house);
        Location at = holder.pendingHit();
        if (at != null) {
            TableManager.get().tryPlace(player, at);
            return;
        }
        player.sendMessage(Messages.get(holder.requireDeck() ? "place.armed" : "place.armed_admin"));
    }

    private static boolean blindsOk(int small, int big) {
        if (small < 1 || big < 1) {
            return true;
        }
        return big >= small;
    }

    private static int clampMin(int value, boolean staff, TableLayout layout, int maxBet) {
        int floor = 1;
        if (!staff && layout != null) {
            floor = Math.max(1, layout.minBet());
        }
        int ceil = maxBet > 0 ? maxBet : Integer.MAX_VALUE;
        if (!staff && layout != null && layout.maxBet() > 0) {
            ceil = Math.min(ceil, layout.maxBet());
        }
        return Math.max(floor, Math.min(ceil, value));
    }

    private static int clampMax(int value, boolean staff, TableLayout layout, int minBet) {
        int floor = Math.max(1, minBet);
        int ceil = Integer.MAX_VALUE;
        if (!staff && layout != null && layout.maxBet() > 0) {
            ceil = layout.maxBet();
        }
        return Math.max(floor, Math.min(ceil, value));
    }

    private static int clampBoxes(int value, boolean staff, TableLayout layout) {
        int yaml = layout != null ? layout.maxBoxes() : 0;
        if (value < 0) {
            value = 0;
        }
        if (!staff && yaml > 0) {
            return Math.min(yaml, value);
        }
        return value;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TableOptionsHolder) {
            event.setCancelled(true);
        }
    }

    private enum ChatField {
        MIN("min"),
        MAX("max"),
        BOXES("boxes"),
        SMALL("small"),
        BIG("big");

        private final String key;

        ChatField(String key) {
            this.key = key;
        }
    }

    private static final class ChatPrompt {
        private final boolean requireDeck;
        private final Location pending;
        private final UUID editId;
        private final String gameId;
        private final TableHouse house;
        private final ChatField field;

        private ChatPrompt(boolean requireDeck, Location pending, UUID editId, String gameId, TableHouse house,
                ChatField field) {
            this.requireDeck = requireDeck;
            this.pending = pending;
            this.editId = editId;
            this.gameId = gameId;
            this.house = house;
            this.field = field;
        }
    }
}
