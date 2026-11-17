package com.olvenio.payments.application;

import com.olvenio.payments.application.port.ProviderCharge;
import com.olvenio.payments.domain.*;
import com.olvenio.payments.infrastructure.*;
import com.olvenio.orders.infrastructure.OrderRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies authoritative provider snapshots, from any gateway, to the payment and its order. */
@Service
public class PaymentVerificationService {
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final TransactionTemplate tx;
    public PaymentVerificationService(PaymentRepository payments,OrderRepository orders,TransactionTemplate tx) {
        this.payments=payments;this.orders=orders;this.tx=tx;
    }
    public void apply(ProviderCharge charge) {
        UUID reference=charge.reference();
        if (reference==null) return; // Resource belongs to another integration.
        var found=payments.find(reference);
        if (found.isEmpty()) return;
        tx.executeWithoutResult(t->{
            var order=orders.lockById(found.get().orderId()).orElseThrow();
            Payment payment=payments.lock(reference);
            String providerId=charge.chargeId();
            String providerStatus=charge.providerStatus();
            // All persisted payloads are explicit allowlists with no customer/card data.
            if (!matches(charge,payment) || providerId==null || !providerId.matches("[A-Za-z0-9_-]{1,100}")
                    || providerStatus==null || providerStatus.length()>50) {
                payments.event(reference,null,"RESOURCE_MISMATCH","{}","REVIEW");return;
            }
            Instant updated=charge.updatedAt();
            if (updated==null) { payments.event(reference,null,"INVALID_RESOURCE_VERSION","{}","REVIEW");return; }
            BigDecimal refunded=charge.refunded();
            PaymentStatus next=charge.status();
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
            // Preserve a successful retry when another resource from the same checkout is declined.
            PaymentStatus aggregate=aggregate(statuses,next);
            String effectiveId=payments.db().queryForObject("select provider_payment_id from payment_resources where payment_id=? order by case when status=? then 0 else 1 end,provider_updated_at desc,provider_payment_id limit 1",String.class,reference,aggregate.name());
            payments.db().update("update payments set status=?,provider_payment_id=?,approved_at=case when ?='APPROVED' then coalesce(approved_at,now()) else approved_at end,failure_reason=?,updated_at=now() where id=?",aggregate.name(),effectiveId,aggregate.name(),aggregate==PaymentStatus.UNKNOWN?"PROVIDER_STATUS_REQUIRES_REVIEW":null,reference);
            // Across attempts: a method switch must never hide a second captured payment for the same order.
            var attemptStates=payments.db().queryForList("select status from payments where order_id=?",String.class,order.getId());
            PaymentStatus orderStatus=orderAggregate(attemptStates,aggregate);
            if (captured(attemptStates)>1) payments.event(reference,"duplicate:"+reference+":"+providerId,"DUPLICATE_CHARGE","{}","REVIEW");
            order.recordVerifiedPayment(orderStatus.name(),Instant.now());
            // Paid (or in review): any other checkout still open must stop taking money.
            if (order.getStatus()!=com.olvenio.orders.domain.OrderStatus.PENDING_PAYMENT)
                payments.db().update("update payments set checkout_closed_at=now(),close_reason='ORDER' where order_id=? and id<>? and checkout_closed_at is null",order.getId(),reference);
            payments.event(reference,eventKey,"PAYMENT_VERIFIED","{\"providerStatus\":\""+providerStatus.replaceAll("[^A-Za-z_]","")+"\",\"status\":\""+next+"\",\"testAccountsVerified\":"+charge.testAccountsVerified()+",\"providerLiveMode\":"+charge.liveMode()+"}","PROCESSED");
        });
    }
    public static PaymentStatus aggregate(List<String> states,PaymentStatus fallback) {
        if(states.stream().filter("APPROVED"::equals).count()>1) return PaymentStatus.UNKNOWN;
        // An unresolved dispute or multiple approvals require review before fulfillment.
        for (PaymentStatus s:List.of(PaymentStatus.UNKNOWN,PaymentStatus.APPROVED,PaymentStatus.PARTIALLY_REFUNDED,PaymentStatus.REFUNDED,PaymentStatus.IN_PROCESS,PaymentStatus.PENDING))
            if(states.contains(s.name())) return s;
        return fallback;
    }
    private static long captured(List<String> states) {
        return states.stream().filter(s->s.equals("APPROVED") || s.equals("PARTIALLY_REFUNDED")).count();
    }
    /** Financial status of an order from the status of each of its checkout attempts. */
    public static PaymentStatus orderAggregate(List<String> attemptStates,PaymentStatus fallback) {
        if (captured(attemptStates)>1) return PaymentStatus.UNKNOWN;
        return aggregate(attemptStates,fallback);
    }
    /** Money, currency, reference, recipient account and environment must all match the local payment. */
    public static boolean matches(ProviderCharge c,Payment p) {
        return p.provider().equals(c.provider().name()) && c.accountVerified()
            && c.amount()!=null && c.amount().compareTo(p.amount())==0
            && p.currency().equals(c.currency()) && p.id().equals(c.reference())
            && c.refunded()!=null && c.refunded().signum()>=0 && c.refunded().compareTo(p.amount())<=0;
    }
}
