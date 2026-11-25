package com.olvenio.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.olvenio.shared.config.AppProperties.Checkout;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShippingRulesTest {
    private static Checkout rules(String zones, boolean onlyZones) {
        return new Checkout(null, zones, onlyZones, null);
    }

    @Test
    void deliversEverywhereUnlessRestricted() {
        assertTrue(rules(null, false).deliversTo("Yucatán"));
        assertTrue(rules("Jalisco", false).deliversTo("Yucatán"));
        assertFalse(rules("Jalisco", true).deliversTo("Yucatán"));
        assertFalse(rules(" ", true).deliversTo("Yucatán"));
    }

    @Test
    void coverageMatchesIgnoringAccentsAndCase() {
        Checkout c = rules("Ciudad de México; Nuevo León", true);
        assertTrue(c.deliversTo("ciudad de mexico"));
        assertTrue(c.deliversTo(" NUEVO LEÓN "));
    }

    @Test
    void ratesLeftFromPaidShippingOnlyCountAsCoverage() {
        Checkout c = rules("Ciudad de México=99; Nuevo León=149", true);
        assertEquals(List.of("Ciudad de México", "Nuevo León"), c.zones());
        assertTrue(c.deliversTo("Nuevo León"));
    }
}
