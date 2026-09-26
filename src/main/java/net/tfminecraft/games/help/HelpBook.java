package net.tfminecraft.games.help;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/**
 * One book from help.yml. Pages are written by hand in the config rather than wrapped here,
 * because a book page only holds about thirteen short lines and where the breaks fall is a
 * writing decision.
 */
public record HelpBook(String title, String author, List<String> pages) {

    /** A book title longer than this is refused by the client. */
    private static final int TITLE_MAX = 32;

    /**
     * Show the book without giving one away. The player is left holding whatever they had.
     * The loader only keeps books with pages and always supplies a title and author, though
     * either may be blank.
     */
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public void openFor(Player player) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle(trim(title.isBlank() ? "Help" : title));
        meta.setAuthor(StringFormatter.formatHex(author.isBlank() ? "The house" : author));
        List<String> out = new ArrayList<>();
        for (String page : pages) {
            out.add(StringFormatter.formatHex(page));
        }
        meta.setPages(out);
        item.setItemMeta(meta);
        player.openBook(item);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    private static String trim(String raw) {
        String plain = StringFormatter.formatHex(raw);
        return plain.length() > TITLE_MAX ? plain.substring(0, TITLE_MAX) : plain;
    }
}
