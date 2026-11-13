package com.velora.payments;
import static org.assertj.core.api.Assertions.*;
import com.velora.payments.domain.*;
import com.velora.payments.application.PaymentVerificationService;
import com.velora.orders.domain.*;
import com.velora.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {
    private final Instant now=Instant.parse("2026-11-11T12:00:00Z");
    @Test void mapsOfficialStatesExplicitlyAndBlocksUnsupportedStates() {
        var amount=new BigDecimal("100");
        Map<String,PaymentStatus> expected=Map.of("pending",PaymentStatus.PENDING,"authorized",PaymentStatus.PENDING,
            "in_process",PaymentStatus.IN_PROCESS,"approved",PaymentStatus.APPROVED,"rejected",PaymentStatus.REJECTED,
            "cancelled",PaymentStatus.CANCELLED,"refunded",PaymentStatus.REFUNDED,"in_mediation",PaymentStatus.UNKNOWN,
            "charged_back",PaymentStatus.UNKNOWN,"new_state",PaymentStatus.UNKNOWN);
        expected.forEach((status,value)->assertThat(PaymentStatus.fromProvider(status,BigDecimal.ZERO,amount)).isEqualTo(value));
        assertThat(PaymentStatus.fromProvider("approved",BigDecimal.TEN,amount)).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(PaymentStatus.fromProvider("approved",amount,amount)).isEqualTo(PaymentStatus.REFUNDED);
    }
    @Test void rejectsOlderDuplicateAndRegressiveProviderSnapshots() {
        assertThat(PaymentStatus.accepts(PaymentStatus.PENDING,now,PaymentStatus.APPROVED,now)).isFalse();
        assertThat(PaymentStatus.accepts(PaymentStatus.PENDING,now,PaymentStatus.APPROVED,now.minusSeconds(1))).isFalse();
        assertThat(PaymentStatus.accepts(PaymentStatus.APPROVED,now,PaymentStatus.PENDING,now.plusSeconds(1))).isFalse();
        assertThat(PaymentStatus.accepts(PaymentStatus.REFUNDED,now,PaymentStatus.APPROVED,now.plusSeconds(1))).isFalse();
        assertThat(PaymentStatus.accepts(PaymentStatus.APPROVED,now,PaymentStatus.REFUNDED,now.plusSeconds(1))).isTrue();
    }
    @Test void cardRetryDoesNotOverwriteApprovedPaymentAndDisputeBlocksFulfillment() {
        assertThat(PaymentVerificationService.aggregate(List.of("APPROVED","REJECTED"),PaymentStatus.REJECTED)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(PaymentVerificationService.aggregate(List.of("APPROVED","UNKNOWN"),PaymentStatus.APPROVED)).isEqualTo(PaymentStatus.UNKNOWN);
    }
    @Test void duplicateApprovalsRequireFinancialReview() {
        assertThat(PaymentVerificationService.aggregate(List.of("APPROVED","APPROVED"),PaymentStatus.APPROVED)).isEqualTo(PaymentStatus.UNKNOWN);
    }
    @Test void financialOrderTransitionsAreIdempotentAndRefundIsReflected() {
        var o=Order.quote(null,null,"a@example.com","Ana","Perez",null,"MXN","hash",now.plusSeconds(600));
        var price=Money.of(new BigDecimal("100"),"MXN");
        o.addItem(UUID.randomUUID(),UUID.randomUUID(),"SKU","Product","product","one",null,price,1,price);
        o.place("VEL-1","checkout-key-12345",now,null);
        o.recordVerifiedPayment("REJECTED",now);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        o.recordVerifiedPayment("APPROVED",now);int events=o.getEvents().size();
        o.recordVerifiedPayment("APPROVED",now);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);assertThat(o.getEvents()).hasSize(events);
        assertThatThrownBy(()->o.cancel("manual",null,now)).isInstanceOf(RuntimeException.class);
        o.recordVerifiedPayment("PARTIALLY_REFUNDED",now);assertThat(o.getStatus()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        o.recordVerifiedPayment("REFUNDED",now);assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }
    @Test void verifiesMoneyReferenceRecipientAndTestEnvironment() throws Exception {
        var id=UUID.randomUUID();
        var p=new Payment(id,UUID.randomUUID(),"MERCADO_PAGO",null,null,PaymentStatus.PENDING,new BigDecimal("100"),"MXN",null,now,null,null);
        var json=new ObjectMapper().createObjectNode();json.put("transaction_amount",100);json.put("currency_id","MXN");
        json.put("external_reference",id.toString());json.put("collector_id",123);json.put("live_mode",false);json.put("transaction_amount_refunded",0);
        assertThat(PaymentVerificationService.verified(json,p,"123")).isTrue();
        json.put("live_mode",true);
        assertThat(PaymentVerificationService.verified(json,p,"123")).isFalse();
        assertThat(PaymentVerificationService.verified(json,p,"123",true)).isTrue();
        assertThat(PaymentVerificationService.verified(json,p,"999",true)).isFalse();
        json.put("transaction_amount",99);assertThat(PaymentVerificationService.verified(json,p,"123",true)).isFalse();
        json.put("transaction_amount",100);json.put("live_mode",false);
        json.put("transaction_amount",99);assertThat(PaymentVerificationService.verified(json,p,"123")).isFalse();json.put("transaction_amount",100);
        assertThat(PaymentVerificationService.verified(json,p,"999")).isFalse();json.put("currency_id","USD");assertThat(PaymentVerificationService.verified(json,p,"123")).isFalse();
        json.put("currency_id","MXN");json.put("external_reference",UUID.randomUUID().toString());assertThat(PaymentVerificationService.verified(json,p,"123")).isFalse();
    }
}
