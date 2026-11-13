package com.velora.payments.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.velora.payments.domain.*;
import com.velora.payments.infrastructure.*;
import com.velora.orders.infrastructure.OrderRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentVerificationService {
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final PaymentProperties config;
    private final TransactionTemplate tx;
    private final MercadoPagoClient client;
    public PaymentVerificationService(PaymentRepository payments,OrderRepository orders,PaymentProperties config,TransactionTemplate tx,MercadoPagoClient client) {
        this.payments=payments;this.orders=orders;this.config=config;this.tx=tx;this.client=client;
    }
    public void apply(JsonNode resource) {
        UUID reference;
        try { reference=UUID.fromString(resource.path("external_reference").asText()); }
        catch (RuntimeException e) { return; } // Resource belongs to another integration.
        var found=payments.find(reference);
        if (found.isEmpty()) return;
        // Network account checks stay outside the order/payment row-lock transaction.
        boolean testAccounts=client.testAccountsVerified(resource);
        tx.executeWithoutResult(t->{
            var order=orders.lockById(found.get().orderId()).orElseThrow();
            Payment payment=payments.lock(reference);
            String providerId=resource.path("id").asText();
            String providerStatus=resource.path("status").asText();
            String version=resource.path("date_last_updated").asText();
            // All persisted payloads are explicit allowlists with no customer/card data.
            if (!verified(resource,payment,config.collectorId(),testAccounts) || !providerId.matches("[0-9]{1,100}") || providerStatus.length()>50) {
                payments.event(reference,null,"RESOURCE_MISMATCH","{}","REVIEW");return;
            }
            Instant updated;
            try { updated=OffsetDateTime.parse(version).toInstant(); }
            catch (RuntimeException e) { payments.event(reference,null,"INVALID_RESOURCE_VERSION","{}","REVIEW");return; }
            BigDecimal refunded=resource.path("transaction_amount_refunded").decimalValue();
            PaymentStatus next=PaymentStatus.fromProvider(providerStatus,refunded,payment.amount());
            String eventKey="resource:"+providerId+":"+updated+":"+next;
            var old=payments.db().queryForList("select * from payment_resources where provider_payment_id=?",providerId);
            if (!old.isEmpty()) {
                var row=old.getFirst();
                if (!reference.equals(row.get("payment_id"))) {payments.event(reference,null,"RESOURCE_MISMATCH","{}","REVIEW");return;}
                var previous=((java.sql.Timestamp)row.get("provider_updated_at")).toInstant();
                if (refunded.compareTo((BigDecimal)row.get("refunded_amount"))<0) {
                    payments.event(reference,null,"REFUND_AMOUNT_REGRESSION","{}","REVIEW");return;
                }
                if (!PaymentStatus.accepts(PaymentStatus.valueOf((String)row.get("status")),previous,next,updated)) return;
            }
            payments.db().update("insert into payment_resources(provider_payment_id,payment_id,status,provider_status,provider_updated_at,refunded_amount) values (?,?,?,?,?,?) on conflict(provider_payment_id) do update set status=excluded.status,provider_status=excluded.provider_status,provider_updated_at=excluded.provider_updated_at,refunded_amount=excluded.refunded_amount",providerId,reference,next.name(),providerStatus,java.sql.Timestamp.from(updated),refunded);
            var statuses=payments.db().queryForList("select status from payment_resources where payment_id=?",String.class,reference);
            // Preserve a successful retry when another resource from the same preference is declined.
            PaymentStatus aggregate=aggregate(statuses,next);
            String effectiveId=payments.db().queryForObject("select provider_payment_id from payment_resources where payment_id=? order by case when status=? then 0 else 1 end,provider_updated_at desc,provider_payment_id limit 1",String.class,reference,aggregate.name());
            payments.db().update("update payments set status=?,provider_payment_id=?,approved_at=case when ?='APPROVED' then coalesce(approved_at,now()) else approved_at end,failure_reason=?,updated_at=now() where id=?",aggregate.name(),effectiveId,aggregate.name(),aggregate==PaymentStatus.UNKNOWN?"PROVIDER_STATUS_REQUIRES_REVIEW":null,reference);
            order.recordVerifiedPayment(aggregate.name(),Instant.now());
            payments.event(reference,eventKey,"PAYMENT_VERIFIED","{\"providerStatus\":\""+providerStatus.replaceAll("[^a-z_]","")+"\",\"status\":\""+next+"\",\"testAccountsVerified\":"+testAccounts+",\"providerLiveMode\":"+resource.path("live_mode").asBoolean()+"}","PROCESSED");
        });
    }
    public static PaymentStatus aggregate(List<String> states,PaymentStatus fallback) {
        if(states.stream().filter("APPROVED"::equals).count()>1) return PaymentStatus.UNKNOWN;
        // An unresolved dispute or multiple approvals require review before fulfillment.
        for (PaymentStatus s:List.of(PaymentStatus.UNKNOWN,PaymentStatus.APPROVED,PaymentStatus.PARTIALLY_REFUNDED,PaymentStatus.REFUNDED,PaymentStatus.IN_PROCESS,PaymentStatus.PENDING))
            if(states.contains(s.name())) return s;
        return fallback;
    }
    public static boolean verified(JsonNode r,Payment p,String collector) {
        return verified(r,p,collector,false);
    }
    public static boolean verified(JsonNode r,Payment p,String collector,boolean testAccountsVerified) {
        return r.path("transaction_amount").isNumber() && r.path("transaction_amount").decimalValue().compareTo(p.amount())==0
            && p.currency().equals(r.path("currency_id").asText()) && p.id().toString().equals(r.path("external_reference").asText())
            && collector.equals(r.path("collector_id").asText()) && r.path("live_mode").isBoolean() && (!r.path("live_mode").asBoolean() || testAccountsVerified)
            && r.path("transaction_amount_refunded").isNumber() && r.path("transaction_amount_refunded").decimalValue().signum()>=0
            && r.path("transaction_amount_refunded").decimalValue().compareTo(p.amount())<=0;
    }
}
