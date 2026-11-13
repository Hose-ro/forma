package com.velora.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velora.shared.config.AppProperties.Checkout;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShippingRulesTest {
    private static Checkout rules(String freeFrom, boolean onlyZones) {
        return new Checkout(null, new BigDecimal("180"), freeFrom == null ? null : new BigDecimal(freeFrom),
                "Ciudad de México=99; Nuevo León=149", onlyZones, null);
    }

    private static BigDecimal price(Checkout c, String state, String subtotal) {
        return c.shippingFor(state, new BigDecimal(subtotal)).orElseThrow();
    }

    @Test
    void zoneRateMatchesIgnoringAccentsAndCase() {
        assertEquals(new BigDecimal("99"), price(rules(null, false), "ciudad de mexico", "500"));
        assertEquals(new BigDecimal("149"), price(rules(null, false), " NUEVO LEÓN ", "500"));
    }

    @Test
    void otherStatesPayFlatRateUnlessRestricted() {
        assertEquals(new BigDecimal("180"), price(rules(null, false), "Yucatán", "500"));
        assertTrue(rules(null, true).shippingFor("Yucatán", new BigDecimal("500")).isEmpty());
    }

    @Test
    void freeShippingStartsAtThreshold() {
        assertEquals(new BigDecimal("99"), price(rules("1500", false), "Ciudad de México", "1499.99"));
        assertEquals(BigDecimal.ZERO, price(rules("1500", false), "Ciudad de México", "1500"));
        assertTrue(rules("1500", true).shippingFor("Yucatán", new BigDecimal("9999")).isEmpty());
    }

    @Test
    void malformedZonesFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new Checkout(null, null, null, "Jalisco:149", false, null));
        assertThrows(IllegalStateException.class, () -> new Checkout(null, new BigDecimal("-1"), null, null, false, null));
    }
}
