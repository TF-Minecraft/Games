package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.games.Messages;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.wager.WagerEngine;

/**
 * The pot as the hologram shows it: who put in what, then the total.
 */
final class PotLabel {

    private PotLabel() {}

    /**
     * One line per owner with money on the felt, then the total. The house tray is left out, and
     * an empty felt gives back nothing so the hologram does not carry a dead line.
     */
    static String lines(Table table) {
        // The ledger only reports owners who have money on the felt.
        Map<UUID, Integer> totals = WagerEngine.get().totalsExcept(table, table.getId());
        if (totals.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        int sum = 0;
        for (Map.Entry<UUID, Integer> entry : totals.entrySet()) {
            int denars = entry.getValue();
            sum += denars;
            lines.add(Messages.get("label.pot_seat",
                    "name", RpNames.of(entry.getKey()),
                    "n", String.valueOf(denars)));
        }
        lines.add(Messages.get("label.pot", "n", String.valueOf(sum)));
        return String.join("\n", lines);
    }
}
