package net.tfminecraft.games.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.help.HelpBook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class HelpLoaderTest {
    @TempDir Path temp;
    private Games previousPlugin;
    private Map<String, HelpBook> previousBooks;
    private Logger logger;
    private final HelpLoader loader = new HelpLoader();

    @BeforeEach
    void setUp() {
        previousPlugin = Games.plugin;
        previousBooks = new LinkedHashMap<>(Cache.helpBooks);
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
    }

    @AfterEach
    void tearDown() {
        Cache.helpBooks.clear();
        Cache.helpBooks.putAll(previousBooks);
        Games.plugin = previousPlugin;
    }

    @Test
    void loadsNamedBooksWithDefaultMetadataAndPreservesPageBreaks() throws Exception {
        loader.load(yaml("""
                ignored: scalar
                PoKeR:
                  title: House Poker
                  author: Dealer
                  pages:
                    - |-
                      First line
                      Second line
                    - Next page
                Blackjack:
                  pages: [Basics]
                NoPages:
                  pages: []
                """));
        assertEquals(List.of("poker", "blackjack"), List.copyOf(Cache.helpBooks.keySet()));
        HelpBook poker = Cache.helpBook("POKER");
        assertEquals("House Poker", poker.title());
        assertEquals("Dealer", poker.author());
        assertEquals(List.of("First line\nSecond line", "Next page"), poker.pages());
        assertEquals(new HelpBook("Blackjack", "The house", List.of("Basics")), Cache.helpBook("blackjack"));
        assertNull(Cache.helpBook("NoPages"));
        assertNull(Cache.helpBook(null));
        verify(logger).warning("[Games] Help book 'NoPages' has no pages.");
    }

    @Test
    void successfulReloadReplacesBooksAndWarnsWhenNoneAreUsable() throws Exception {
        assertTrue(loader.loadSafe(yaml("old: {pages: [Old]}\n")));
        assertTrue(loader.loadSafe(yaml("new: {pages: [New]}\n")));
        assertNull(Cache.helpBook("old"));
        assertEquals(List.of("New"), Cache.helpBook("new").pages());
        assertTrue(loader.loadSafe(yaml("empty: {}\nignored: scalar\n")));
        assertTrue(Cache.helpBooks.isEmpty());
        verify(logger).warning("[Games] Help book 'empty' has no pages.");
        verify(logger).warning("[Games] help.yml had no usable books.");
    }

    @Test
    void malformedAndMissingFilesRetainLastUsableBooks() throws Exception {
        assertTrue(loader.loadSafe(yaml("poker: {pages: [Saved]}\n")));
        HelpBook saved = Cache.helpBook("poker");
        assertFalse(loader.loadSafe(temp.resolve("missing.yml").toFile()));
        assertSame(saved, Cache.helpBook("poker"));
        assertFalse(loader.loadSafe(yaml("poker: [broken")));
        assertSame(saved, Cache.helpBook("poker"));
        verify(logger, times(2)).severe(startsWith("[Games] Failed to load help.yml: "));
    }

    @Test
    @SuppressWarnings("deprecation")
    void opensFormattedBookAndPlaysSoundWithoutGivingAnItem() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                poker:
                  title: '&aPoker'
                  author: '&bDealer'
                  pages: ['&cFirst page', 'Second page']
                """)));
        Player player = mock(Player.class);
        Location at = new Location(null, 1, 2, 3);
        when(player.getLocation()).thenReturn(at);
        Cache.helpBook("poker").openFor(player);
        ArgumentCaptor<ItemStack> opened = ArgumentCaptor.forClass(ItemStack.class);
        verify(player).openBook(opened.capture());
        ItemStack book = opened.getValue();
        assertEquals(Material.WRITTEN_BOOK, book.getType());
        BookMeta meta = (BookMeta) book.getItemMeta();
        assertEquals("§aPoker", meta.getTitle());
        assertEquals("§bDealer", meta.getAuthor());
        assertEquals(List.of("§cFirst page", "Second page"), meta.getPages());
        verify(player).playSound(at, Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        verify(player, never()).getInventory();
    }

    @Test
    @SuppressWarnings("deprecation")
    void blankMetadataUsesFriendlyDefaultsAndLongTitlesFitClientLimit() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                blank: {title: ' ', author: ' ', pages: ['']}
                long: {title: '1234567890123456789012345678901234567890', pages: [Rules]}
                """)));
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        Cache.helpBook("blank").openFor(player);
        Cache.helpBook("long").openFor(player);
        ArgumentCaptor<ItemStack> opened = ArgumentCaptor.forClass(ItemStack.class);
        verify(player, times(2)).openBook(opened.capture());
        BookMeta blank = (BookMeta) opened.getAllValues().getFirst().getItemMeta();
        assertEquals("Help", blank.getTitle());
        assertEquals("The house", blank.getAuthor());
        assertEquals(List.of(""), blank.getPages());
        BookMeta longTitle = (BookMeta) opened.getAllValues().getLast().getItemMeta();
        assertEquals("12345678901234567890123456789012", longTitle.getTitle());
    }

    private File yaml(String contents) throws Exception {
        return Files.writeString(Files.createTempFile(temp, "help-", ".yml"), contents).toFile();
    }
}
