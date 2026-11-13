package com.velora.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.velora.checkout.domain.OrderPricing;
import com.velora.shared.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPricingTest {

    private static Money mxn(String v) {
        return Money.of(new BigDecimal(v), "MXN");
    }

    @Test
    void computesLineTotalsSubtotalShippingAndTotalExactly() {
        var result = OrderPricing.compute(List.of(
                new OrderPricing.Line(mxn("2490.00"), 2),
                new OrderPricing.Line(mxn("0.10"), 3)), mxn("99.90"));

        assertThat(result.lineTotals().get(0).amount()).isEqualByComparingTo("4980.00");
        assertThat(result.lineTotals().get(1).amount()).isEqualByComparingTo("0.30"); // no float drift
        assertThat(result.subtotal().amount()).isEqualTo(new BigDecimal("4980.30"));
        assertThat(result.total().amount()).isEqualTo(new BigDecimal("5080.20"));
    }

    @Test
    void roundsHalfEvenToTwoDecimals() {
        assertThat(mxn("10.125").rounded()).isEqualTo(new BigDecimal("10.12"));
        assertThat(mxn("10.135").rounded()).isEqualTo(new BigDecimal("10.14"));
    }

    @Test
    void rejectsNegativeAmountsMixedCurrenciesAndInvalidQuantities() {
        assertThatThrownBy(() -> mxn("-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "mxn")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mxn("1").plus(Money.of(BigDecimal.ONE, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPricing.Line(mxn("1"), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderPricing.compute(List.of(), mxn("0"))).isInstanceOf(IllegalArgumentException.class);
    }
}
