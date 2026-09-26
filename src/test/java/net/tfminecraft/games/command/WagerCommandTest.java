package net.tfminecraft.games.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.table.TableManager;

class WagerCommandTest {
    private final WagerCommand command = new WagerCommand();

    @Test
    void playerCommandsCommitVoteOrProposePositiveWholeAmounts() {
        TableManager manager = mock(TableManager.class);
        Player player = mock(Player.class);
        try (MockedStatic<TableManager> managers = mockStatic(TableManager.class)) {
            managers.when(TableManager::get).thenReturn(manager);
            assertTrue(command.onCommand(player, null, "wager", new String[0]));
            verify(manager).commitStreet(player);
            assertTrue(command.onCommand(player, null, "wager", new String[] {"ACCEPT"}));
            verify(manager).voteWager(player, true);
            assertTrue(command.onCommand(player, null, "wager", new String[] {"Decline"}));
            verify(manager).voteWager(player, false);
            assertTrue(command.onCommand(player, null, "wager", new String[] {"25"}));
            verify(manager).proposeLoot(player, 25);
            verifyNoMoreInteractions(manager);
        }
    }

    @Test
    void invalidAmountsAndConsoleNeverAlterTheTable() {
        TableManager manager = mock(TableManager.class);
        Player player = mock(Player.class);
        CommandSender console = mock(CommandSender.class);
        try (MockedStatic<TableManager> managers = mockStatic(TableManager.class);
                MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            managers.when(TableManager::get).thenReturn(manager);
            messages.when(() -> Messages.get("wager.players_only")).thenReturn("Players only");
            messages.when(() -> Messages.get("wager.usage")).thenReturn("Usage");
            messages.when(() -> Messages.get("wager.need_amount")).thenReturn("Positive amount required");
            assertTrue(command.onCommand(console, null, "wager", new String[] {"25"}));
            verify(console).sendMessage("Players only");
            for (String invalid : List.of("abc", "1.5", "2147483648")) {
                command.onCommand(player, null, "wager", new String[] {invalid});
            }
            verify(player, times(3)).sendMessage("Usage");
            for (String invalid : List.of("0", "-1")) {
                command.onCommand(player, null, "wager", new String[] {invalid});
            }
            verify(player, times(2)).sendMessage("Positive amount required");
            verifyNoInteractions(manager);
        }
    }

    @Test
    void completionFiltersVoteWordsCaseInsensitivelyAtFirstArgumentOnly() {
        assertEquals(List.of("accept", "decline"), command.onTabComplete(null, null, "wager", new String[] {""}));
        assertEquals(List.of("accept"), command.onTabComplete(null, null, "wager", new String[] {"AC"}));
        assertEquals(List.of("decline"), command.onTabComplete(null, null, "wager", new String[] {"d"}));
        assertTrue(command.onTabComplete(null, null, "wager", new String[] {"x"}).isEmpty());
        assertTrue(command.onTabComplete(null, null, "wager", new String[0]).isEmpty());
        assertTrue(command.onTabComplete(null, null, "wager", new String[] {"accept", ""}).isEmpty());
    }
}
