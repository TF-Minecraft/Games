package net.tfminecraft.games.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.bukkit.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CardNamesTest {
    @ParameterizedTest
    @CsvSource({
            "CeRrItH, #55ff55Cerrith, 5635925",
            "SEITHR, #ffffffSeithr, 16777215",
            "Oseni, #ffaa00Oseni, 16755200",
            "MITLAN, #5555ffMitlan, 5592575",
            "unknown, #aaaaaaUnknown, 11184810",
            "x, #aaaaaaX, 11184810"
    })
    void mapsSuitsToNamesAndMatchingDustColors(String suit, String label, int rgb) {
        assertEquals(label, CardNames.suitLabel(suit));
        assertEquals(Color.fromRGB(rgb), CardNames.suitDust(card(suit, 2, false)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingSuitsHaveUnknownLabelsAndGrayDust(String suit) {
        assertEquals("#aaaaaaUnknown", CardNames.suitLabel(suit));
        assertEquals(Color.fromRGB(0xaaaaaa), CardNames.suitDust(card(suit, 2, false)));
    }

    @ParameterizedTest
    @CsvSource({"1, Ace", "11, Jack", "12, Queen", "13, King", "2, 2", "10, 10", "0, 0", "-1, -1"})
    void labelsFaceAndNumericRanks(int rank, String label) {
        assertEquals("#ffffff" + label, CardNames.rankLabel(card("cerrith", rank, false)));
    }

    @Test
    void missingCardsAndJokersHaveSpecialLabelsAndNeutralDust() {
        assertEquals("#ffffff?", CardNames.rankLabel(null));
        assertEquals(Color.fromRGB(0xaaaaaa), CardNames.suitDust(null));
        Card joker = card("cerrith", 13, true);
        assertEquals("#ffffffJoker", CardNames.rankLabel(joker));
        assertEquals(Color.fromRGB(0xaaaaaa), CardNames.suitDust(joker));
    }

    @Test
    void labelsAreIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("#5555ffMitlan", CardNames.suitLabel("MITLAN"));
            assertEquals("#aaaaaaWinter", CardNames.suitLabel("WINTER"));
            assertEquals(Color.fromRGB(0x5555ff), CardNames.suitDust(card("MITLAN", 1, false)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void cardRetainsEveryCatalogField() {
        Card card = new Card("catalog-id", "seithr", 7, true, "ia:custom-item");
        assertEquals("catalog-id", card.getId());
        assertEquals("seithr", card.getSuit());
        assertEquals(7, card.getRank());
        assertTrue(card.isJoker());
        assertEquals("ia:custom-item", card.getItem());
    }

    private static Card card(String suit, int rank, boolean joker) {
        return new Card("id", suit, rank, joker, "item");
    }
}
