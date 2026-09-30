package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import net.tfminecraft.games.Messages;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.card.CardNames;
import net.tfminecraft.games.voice.RpNames;

/**
 * Showdown chat shared by Hold'em and Five-Draw.
 */
final class HandTalk {

    private HandTalk() {}

    /**
     * Names the best hand on the table and spells out the five cards under it. Meant to be sent
     * once for the whole showdown, not per side pot. A tie names every player who tied, because
     * two hands can score the same on different cards.
     */
    static List<String> bestHand(String gameId, List<UUID> seats, Function<UUID, List<Card>> handOf) {
        List<UUID> tied = new ArrayList<>();
        List<HoldemRank.Score> scores = new ArrayList<>();
        HoldemRank.Score best = HoldemRank.Score.none();
        for (UUID id : seats) {
            HoldemRank.Score score = HoldemRank.best(gameId, handOf.apply(id));
            if (score.cards().isEmpty()) {
                continue;
            }
            int cmp = tied.isEmpty() ? 1 : score.compareTo(best);
            if (cmp > 0) {
                best = score;
                tied.clear();
                scores.clear();
                tied.add(id);
                scores.add(score);
            } else if (cmp == 0) {
                tied.add(id);
                scores.add(score);
            }
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < tied.size(); i++) {
            lines.add(Messages.get("hand.best", "name", RpNames.of(tied.get(i))));
            lines.add(Messages.get("hand.best_cards", "cards", cardList(scores.get(i).cards())));
        }
        return lines;
    }

    static List<String> allHands(String gameId, List<UUID> seats, Function<UUID, List<Card>> handOf) {
        List<String> lines = new ArrayList<>();
        for (UUID id : seats) {
            List<Card> cards = handOf.apply(id);
            if (!cards.isEmpty()) {
                lines.add(Messages.get("hand.shown", "name", RpNames.of(id), "cards", cardList(cards)));
            }
        }
        return lines;
    }

    /** Raw, so the caller's message formats the whole line in one pass. */
    private static String cardList(List<Card> cards) {
        String template = Messages.getRaw("hand.card");
        List<String> parts = new ArrayList<>();
        for (Card card : cards) {
            parts.add(template
                    .replace("{rank}", CardNames.rankLabel(card))
                    .replace("{suit}", CardNames.suitLabel(card.getSuit())));
        }
        return String.join(Messages.getRaw("hand.card_join"), parts);
    }
}
