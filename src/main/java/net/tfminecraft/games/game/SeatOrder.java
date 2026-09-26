package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import net.tfminecraft.games.table.Table;

/**
 * Clockwise seat order for the button games, which seat players in the order they sat down.
 */
final class SeatOrder {

    private SeatOrder() {}

    /**
     * Every seat once, starting with the one after {@code from} and ending with {@code from}
     * itself. A {@code from} that is not seated, such as nobody or a player who has just left,
     * starts the order at the first seat.
     */
    static List<UUID> after(List<UUID> seats, UUID from) {
        int start = seats.indexOf(from) + 1;
        List<UUID> order = new ArrayList<>(seats.subList(start, seats.size()));
        order.addAll(seats.subList(0, start));
        return order;
    }

    /** The table's seats, starting with the one after {@code from}. */
    static List<UUID> after(Table table, UUID from) {
        return after(new ArrayList<>(table.actives()), from);
    }

    /**
     * The seats still at the table, starting after where {@code from} sat in an earlier
     * {@code seating}, so the turn passes on from a player who has just left.
     */
    static List<UUID> after(Table table, List<UUID> seating, UUID from) {
        List<UUID> order = after(seating, from);
        order.retainAll(table.actives());
        return order;
    }

    /** The seats after the button, in the order they act. */
    static List<UUID> leftOfButton(Table table) {
        return after(table, table.dealerId());
    }

    /** The seats of {@code include}, in the order they sit after the button. */
    static List<UUID> leftOfButton(Table table, List<UUID> include) {
        List<UUID> ordered = leftOfButton(table);
        ordered.retainAll(include);
        return ordered;
    }

    /** The seat after {@code from}, or null when nobody is seated. */
    static UUID next(Table table, UUID from) {
        return first(after(table, from), seat -> true);
    }

    /** The first seat in {@code order} that qualifies, or null when none does. */
    static UUID first(List<UUID> order, Predicate<UUID> eligible) {
        for (UUID id : order) {
            if (eligible.test(id)) {
                return id;
            }
        }
        return null;
    }
}
