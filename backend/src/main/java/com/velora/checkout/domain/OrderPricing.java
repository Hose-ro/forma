package com.velora.checkout.domain;

import com.velora.shared.domain.Money;
import java.util.ArrayList;
import java.util.List;

/** Pure server-side price calculation. Inputs come from the catalog, never from the client. */
public final class OrderPricing {
    private OrderPricing() {
    }

    public record Line(Money unitPrice, int quantity) {
        public Line {
            if (quantity < 1) throw new IllegalArgumentException("quantity must be >= 1");
        }
    }

    public record Breakdown(List<Money> lineTotals, Money subtotal, Money shipping, Money total) {
    }

    public static Breakdown compute(List<Line> lines, Money shipping) {
        if (lines.isEmpty()) throw new IllegalArgumentException("No lines to price");
        Money subtotal = Money.zero(shipping.currency());
        List<Money> totals = new ArrayList<>(lines.size());
        for (Line line : lines) {
            Money lineTotal = line.unitPrice().times(line.quantity());
            totals.add(lineTotal);
            subtotal = subtotal.plus(lineTotal);
        }
        Money roundedSubtotal = Money.of(subtotal.rounded(), subtotal.currency());
        Money roundedShipping = Money.of(shipping.rounded(), shipping.currency());
        return new Breakdown(List.copyOf(totals), roundedSubtotal, roundedShipping, roundedSubtotal.plus(roundedShipping));
    }
}
