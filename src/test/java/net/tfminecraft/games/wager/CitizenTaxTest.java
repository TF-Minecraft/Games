package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CitizenTaxTest {

    @Test
    void chipsDueMatchesRoundedTaxCappedAtProfit() {
        assertEquals(10, CitizenTax.chipsDue(100, 10.0));
        assertEquals(3, CitizenTax.chipsDue(50, 2.5));
        assertEquals(5, CitizenTax.chipsDue(5, 10.0));
    }

    @Test
    void chipsDueIsZeroForNoProfitOrNoTax() {
        assertEquals(0, CitizenTax.chipsDue(0, 10.0));
        assertEquals(0, CitizenTax.chipsDue(100, 0.0));
        assertEquals(0, CitizenTax.chipsDue(100, -1.0));
        assertEquals(0, CitizenTax.chipsDue(100, Double.NaN));
        assertEquals(0, CitizenTax.chipsDue(100, Double.POSITIVE_INFINITY));
    }

    @Test
    void taxBelowHalfADenarWithholdsNoChips() {
        assertEquals(0, CitizenTax.chipsDue(100, 0.4));
    }

    @Test
    void chipsDueRoundsHalfUp() {
        assertEquals(1, CitizenTax.chipsDue(10, 0.5));
        assertEquals(2, CitizenTax.chipsDue(10, 1.5));
    }

    @Test
    void missingCharacterDefersAProfitPayout() {
        org.junit.jupiter.api.Assertions.assertNull(CitizenTax.quote(null, 100));
    }
}
