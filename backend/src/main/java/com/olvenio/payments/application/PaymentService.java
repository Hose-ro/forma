package com.olvenio.payments.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.olvenio.payments.domain.*;
import com.olvenio.payments.infrastructure.*;
import com.olvenio.orders.domain.*;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.shared.domain.*;
import com.olvenio.shared.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    /** An attempt younger than this may still be talking to the provider; nobody else may touch it. */
    private static final Duration IN_FLIGHT = Duration.ofMinutes(2);
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final MercadoPagoClient client;
    private final PaymentProperties config;
    private final SecretTokens tokens;
    private final TransactionTemplate tx;
    /** Only used for orders placed before reservations had an explicit expiry. */
    @Value("${app.checkout.reservation-ttl:24h}")
    private Duration reservationTtl = Duration.ofHours(24);
    public PaymentService(OrderRepository orders,PaymentRepository payments,MercadoPagoClient client,
            PaymentProperties config,SecretTokens tokens,TransactionTemplate tx) {
        this.orders=orders;this.payments=payments;this.client=client;this.config=config;this.tokens=tokens;this.tx=tx;
    }
    private Order authorize(UUID id,String token) {
        Order o=orders.lockById(id).orElseThrow(()->new NotFoundException("Order not found"));
        if (!CurrentUser.isAdmin() && !ResourceAccess.allowed(o.getUserId(),o.getAccessTokenHash(),CurrentUser.id(),token,tokens))
            throw new NotFoundException("Order not found");
        return o;
    }
    public Payment get(UUID order,String token) {
        return tx.execute(t->{authorize(order,token);return payments.byOrder(order).orElseThrow(()->new NotFoundException("Payment not found"));});
    }
    public Payment preference(UUID order,String key,String token) {
        config.requireConfigured();
        if (key==null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw new InvalidRequestException("A valid Idempotency-Key is required");
        // Commit CREATING before any network call. A crash or timeout can never trigger a blind second POST.
        record Start(Payment payment,boolean create,Instant expires) {}
        Start start=tx.execute(t->{
            Order o=authorize(order,token);
            if (o.getStatus()!=OrderStatus.PENDING_PAYMENT) throw new ConflictException("Order cannot be paid in its current state");
            Instant expires=o.getReservationExpiresAt()==null?Instant.now().plus(reservationTtl):o.getReservationExpiresAt();
            // The checkout link must die together with the stock reservation.
            if (!expires.isAfter(Instant.now().plus(IN_FLIGHT))) throw new ConflictException("The reservation for this order expired; place a new order");
            if (payments.keyBelongsToAnotherOrder(key,order)) throw new ConflictException("Idempotency-Key belongs to another order");
            var existing=payments.byOrder(order);
            if (existing.isPresent()) {
                requireCheckoutOpen(existing.get().id());
                return new Start(existing.get(),false,expires);
            }
            if (o.getTotal().signum()<=0 || !"MXN".equals(o.getCurrency())) throw new ConflictException("Invalid payable amount or currency");
            UUID id=UUID.randomUUID();
            payments.db().update("insert into payments(id,order_id,status,amount,currency) values (?,?,'CREATED',?,?)",id,order,o.getTotal(),o.getCurrency());
            payments.db().update("insert into payment_attempts(id,payment_id,idempotency_key,status) values (?,?,?,'CREATING')",UUID.randomUUID(),id,key);
            payments.event(id,null,"PREFERENCE_REQUESTED","{}","PROCESSED");
            return new Start(payments.find(id).orElseThrow(),true,expires);
        });
        if (start.create()) return create(start.payment(),order,start.expires());
        if (start.payment().checkoutUrl()==null) return recover(start.payment(),order,start.expires());
        client.requireTestSeller();
        if (MercadoPagoClient.allowedCheckoutUrl(start.payment().checkoutUrl())) return start.payment();
        // Repair a previously stored checkout link using a read, never a second preference creation.
        Payment existing=start.payment();
        return store(existing,order,official(existing,existing.providerPreferenceId()),"CHECKOUT_LINK_REFRESHED");
    }

    /**
     * The first creation never reported back (timeout, crash, provider error). Ask the provider what actually
     * exists: adopt the preference if it was created, and only when the provider confirms there is none, claim
     * the attempt and create it again.
     */
    private Payment recover(Payment existing,UUID order,Instant expires) {
        if (existing.createdAt().isAfter(Instant.now().minus(IN_FLIGHT)))
            throw new ConflictException("Payment initialization is still in progress; retry in a couple of minutes");
        client.requireTestSeller();
        JsonNode found=client.preferencesByReference(existing.id()).path("elements");
        if (!found.isArray()) throw new MercadoPagoClient.ProviderException();
        if (!found.isEmpty()) return store(existing,order,official(existing,found.get(0).path("id").asText()),"PREFERENCE_RECOVERED");
        Integer claimed=tx.execute(t->{
            Order current=orders.lockById(order).orElseThrow();
            requirePayable(current);
            payments.lock(existing.id());
            requireCheckoutOpen(existing.id());
            return payments.db().update("update payment_attempts set status='CREATING',updated_at=now() where payment_id=? and status in ('UNKNOWN','CREATING') and updated_at<now()-interval '2 minutes'",existing.id());
        });
        if (claimed==null || claimed!=1) throw new ConflictException("Payment initialization is still in progress; retry in a couple of minutes");
        payments.event(existing.id(),null,"PREFERENCE_RETRY","{}","PROCESSED");
        return create(existing,order,expires);
    }

    /** Authoritative read of a preference, checked against the local payment record. */
    private JsonNode official(Payment p,String preferenceId) {
        JsonNode official=client.preference(preferenceId);
        if (!config.collectorId().equals(official.path("collector_id").asText())
                || !p.id().toString().equals(official.path("external_reference").asText())
                || !preferenceId.equals(official.path("id").asText())) throw new MercadoPagoClient.ProviderException();
        return official;
    }

    private Payment store(Payment p,UUID order,JsonNode preference,String event) {
        return tx.execute(t->{
            Order current=orders.lockById(order).orElseThrow();
            if (current.getStatus()!=OrderStatus.PENDING_PAYMENT) throw new ConflictException("Order cannot be paid in its current state");
            payments.lock(p.id());
            requireCheckoutOpen(p.id());
            return storeLocked(p,preference,event);
        });
    }

    private Payment storeLocked(Payment p,JsonNode preference,String event) {
        String url=client.checkoutUrl(preference);
        String preferenceId=preference.path("id").asText();
        payments.db().update("update payments set provider_preference_id=?,checkout_url=?,status=case when status in ('CREATED','UNKNOWN') and provider_payment_id is null then 'PENDING' else status end,failure_reason=case when failure_reason='PREFERENCE_RESULT_UNCERTAIN' then null else failure_reason end,updated_at=now() where id=?",preferenceId,url,p.id());
        payments.db().update("update payment_attempts set status='READY',provider_request_id=?,updated_at=now() where payment_id=?",preferenceId,p.id());
        payments.event(p.id(),null,event,"{}","PROCESSED");
        return payments.find(p.id()).orElseThrow();
    }

    private void requirePayable(Order order) {
        if (order.getStatus()!=OrderStatus.PENDING_PAYMENT)
            throw new ConflictException("Order cannot be paid in its current state");
        if (order.getReservationExpiresAt()!=null && !order.getReservationExpiresAt().isAfter(Instant.now().plus(IN_FLIGHT)))
            throw new ConflictException("The reservation for this order expired; place a new order");
    }

    private void requireCheckoutOpen(UUID payment) {
        if (payments.checkoutClosed(payment))
            throw new ConflictException("The payment link is closing or closed; this order cannot start another payment");
    }

    private Payment create(Payment p,UUID order,Instant expires) {
        try {
            // Keep the order lock through the bounded provider call and persistence. Cancellation first
            // takes this same lock to close checkout: it either prevents the POST or sees its persisted result.
            // The earlier CREATING commit still protects an uncertain result if this transaction rolls back.
            return tx.execute(t->{
                Order current=orders.lockById(order).orElseThrow();
                requirePayable(current);
                payments.lock(p.id());
                requireCheckoutOpen(p.id());
                var result=client.createPreference(p,expires);
                if (result.path("id").asText().isBlank() || !config.collectorId().equals(result.path("collector_id").asText())) throw new MercadoPagoClient.ProviderException();
                return storeLocked(p,result,"PREFERENCE_READY");
            });
        } catch (ConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            tx.executeWithoutResult(t->{
                Order current=orders.lockById(order).orElseThrow();payments.lock(p.id());
                if (current.getStatus()!=OrderStatus.PENDING_PAYMENT || payments.checkoutClosed(p.id())) return;
                payments.db().update("update payment_attempts set status='UNKNOWN',updated_at=now() where payment_id=? and status='CREATING'",p.id());
                payments.db().update("update payments set status=case when status='CREATED' then 'UNKNOWN' else status end,failure_reason='PREFERENCE_RESULT_UNCERTAIN',updated_at=now() where id=? and checkout_url is null",p.id());
                payments.event(p.id(),null,"PREFERENCE_UNCERTAIN","{}","REVIEW");
            });
            throw new MercadoPagoClient.ProviderException();
        }
    }
}
