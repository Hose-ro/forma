package com.olvenio.payments.application;

import com.olvenio.payments.application.port.PaymentGateway;
import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.shared.domain.ConflictException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PaymentGateways {
    private final Map<PaymentProvider, PaymentGateway> byProvider = new EnumMap<>(PaymentProvider.class);

    public PaymentGateways(List<PaymentGateway> gateways) {
        for (PaymentGateway g : gateways)
            if (byProvider.put(g.provider(), g) != null) throw new IllegalStateException("Duplicate gateway " + g.provider());
    }

    /** The gateway of an existing payment, enabled or not: closing and reconciling must still work. */
    public PaymentGateway get(String provider) { return get(PaymentProvider.valueOf(provider)); }

    public PaymentGateway get(PaymentProvider provider) {
        PaymentGateway g = byProvider.get(provider);
        if (g == null) throw new ConflictException("Payment provider is not available");
        return g;
    }

    /** A gateway a buyer may open a new checkout with. */
    public PaymentGateway forNewCheckout(PaymentProvider provider) {
        PaymentGateway g = byProvider.get(provider);
        if (g == null || !g.enabled()) throw new ConflictException("Payment method is not available");
        return g;
    }

    public List<PaymentGateway> enabled() {
        return byProvider.values().stream().filter(PaymentGateway::enabled).toList();
    }
}
