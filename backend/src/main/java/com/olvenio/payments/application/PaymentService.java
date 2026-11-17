package com.olvenio.payments.application;

import com.olvenio.payments.application.port.*;
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
    private final PaymentGateways gateways;
    private final CheckoutCloser closer;
    private final PaymentVerificationService verification;
    private final SecretTokens tokens;
    private final TransactionTemplate tx;
    /** Only used for orders placed before reservations had an explicit expiry. */
    @Value("${app.checkout.reservation-ttl:24h}")
    private Duration reservationTtl = Duration.ofHours(24);
    public PaymentService(OrderRepository orders,PaymentRepository payments,PaymentGateways gateways,
            CheckoutCloser closer,PaymentVerificationService verification,SecretTokens tokens,TransactionTemplate tx) {
        this.orders=orders;this.payments=payments;this.gateways=gateways;this.closer=closer;this.verification=verification;this.tokens=tokens;this.tx=tx;
    }
    private Order authorize(UUID id,String token) {
        Order o=orders.lockById(id).orElseThrow(()->new NotFoundException("Order not found"));
        if (!CurrentUser.isAdmin() && !ResourceAccess.allowed(o.getUserId(),o.getAccessTokenHash(),CurrentUser.id(),token,tokens))
            throw new NotFoundException("Order not found");
        return o;
    }
    public Payment get(UUID order,String token) {
        return tx.execute(t->{authorize(order,token);return payments.latestByOrder(order).orElseThrow(()->new NotFoundException("Payment not found"));});
    }
    /** The buyer came back from the provider: finish the checkout if the provider needs it, then read its charges. */
    public Payment confirm(UUID paymentId,String token) { return confirm(paymentId,token,false); }
    /** The provider reported the buyer's approval (e.g. the buyer closed the tab before returning). */
    public void confirmFromProvider(UUID paymentId) { confirm(paymentId,null,true); }
    private Payment confirm(UUID paymentId,String token,boolean system) {
        Payment found=payments.find(paymentId).orElseThrow(()->new NotFoundException("Payment not found"));
        PaymentGateway gateway=gateways.get(found.provider());
        tx.executeWithoutResult(t->{
            Order o=system?orders.lockById(found.orderId()).orElseThrow():authorize(found.orderId(),token);
            // The order lock is held through the provider call. Closing takes the same lock first, so it either
            // prevents the completion or sees its result when it re-reads the charges.
            if (o.getStatus()!=OrderStatus.PENDING_PAYMENT) return;
            if (o.getReservationExpiresAt()!=null && !o.getReservationExpiresAt().isAfter(Instant.now())) return;
            payments.lock(paymentId);
            if (payments.checkoutClosed(paymentId)) return;
            gateway.complete(payments.find(paymentId).orElseThrow());
        });
        gateway.forEachCharge(payments.find(paymentId).orElseThrow(),null,verification::apply);
        return payments.find(paymentId).orElseThrow();
    }
    /** Legacy entry point of the Mercado Pago-only checkout. */
    public Payment preference(UUID order,String key,String token) {
        return checkout(order,PaymentProvider.MERCADO_PAGO,key,token);
    }
    private record Start(Payment payment,boolean create,Instant expires,List<Payment> toClose) {}
    public Payment checkout(UUID order,PaymentProvider provider,String key,String token) {
        PaymentGateway gateway=gateways.forNewCheckout(provider);
        gateway.requireConfigured();
        if (key==null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw new InvalidRequestException("A valid Idempotency-Key is required");
        Start start=begin(order,provider,key,token);
        if (!start.toClose().isEmpty()) {
            // Switching method: the previous checkout is closed at its provider, and every charge started from it
            // re-read, before another provider may take money for this order.
            for (Payment previous:start.toClose()) closer.closeAtProvider(previous);
            start=begin(order,provider,key,token);
            if (!start.toClose().isEmpty()) throw new ConflictException("The previous payment is still closing; retry in a moment");
        }
        if (start.create()) return create(gateway,start.payment(),order,start.expires());
        if (start.payment().checkoutUrl()==null) return recover(gateway,start.payment(),order,start.expires());
        gateway.requireReady();
        if (gateway.allowedCheckoutUrl(start.payment().checkoutUrl())) return start.payment();
        // Repair a previously stored checkout link using a read, never a second checkout creation.
        Payment existing=start.payment();
        return store(existing,order,gateway.refresh(existing),"CHECKOUT_LINK_REFRESHED");
    }

    /** Commits CREATING before any network call. A crash or timeout can never trigger a blind second POST. */
    private Start begin(UUID order,PaymentProvider provider,String key,String token) {
        return tx.execute(t->{
            Order o=authorize(order,token);
            if (o.getStatus()!=OrderStatus.PENDING_PAYMENT) throw new ConflictException("Order cannot be paid in its current state");
            Instant expires=o.getReservationExpiresAt()==null?Instant.now().plus(reservationTtl):o.getReservationExpiresAt();
            // The checkout link must die together with the stock reservation.
            if (!expires.isAfter(Instant.now().plus(IN_FLIGHT))) throw new ConflictException("The reservation for this order expired; place a new order");
            if (payments.keyBelongsToAnotherOrder(key,order)) throw new ConflictException("Idempotency-Key belongs to another order");
            if (payments.orderClosing(order)) throw new ConflictException("The payment link is closing or closed; this order cannot start another payment");
            var open=payments.openByOrder(order);
            if (open.isPresent()) {
                if (provider.name().equals(open.get().provider())) return new Start(open.get(),false,expires,List.of());
                payments.lock(open.get().id());
                payments.closeCheckout(open.get().id(),PaymentRepository.CloseReason.SWITCH);
                payments.event(open.get().id(),null,"CHECKOUT_SWITCH_REQUESTED","{\"to\":\""+provider+"\"}","PROCESSED");
            }
            // A previous checkout closed only locally may still be payable at its provider.
            var pending=payments.pendingProviderClose(order);
            if (!pending.isEmpty()) return new Start(null,false,expires,pending);
            // A started charge (e.g. an unpaid cash ticket) can still be completed: never open a second way to pay.
            if (payments.liveChargeForOrder(order))
                throw new ConflictException("A payment for this order is still in process; wait until it resolves before choosing another method");
            if (payments.keyUsed(key)) throw new ConflictException("Idempotency-Key was already used; send a new key for a new payment method");
            if (o.getTotal().signum()<=0 || !"MXN".equals(o.getCurrency())) throw new ConflictException("Invalid payable amount or currency");
            UUID id=UUID.randomUUID();
            payments.db().update("insert into payments(id,order_id,provider,status,amount,currency) values (?,?,?,'CREATED',?,?)",id,order,provider.name(),o.getTotal(),o.getCurrency());
            payments.db().update("insert into payment_attempts(id,payment_id,idempotency_key,status) values (?,?,?,'CREATING')",UUID.randomUUID(),id,key);
            payments.event(id,null,"PREFERENCE_REQUESTED","{\"provider\":\""+provider+"\"}","PROCESSED");
            return new Start(payments.find(id).orElseThrow(),true,expires,List.of());
        });
    }

    /**
     * The first creation never reported back (timeout, crash, provider error). Ask the provider what actually
     * exists: adopt the preference if it was created, and only when the provider confirms there is none, claim
     * the attempt and create it again.
     */
    private Payment recover(PaymentGateway gateway,Payment existing,UUID order,Instant expires) {
        if (existing.createdAt().isAfter(Instant.now().minus(IN_FLIGHT)))
            throw new ConflictException("Payment initialization is still in progress; retry in a couple of minutes");
        gateway.requireReady();
        var found=gateway.findCreated(existing);
        if (found.isPresent()) return store(existing,order,found.get(),"PREFERENCE_RECOVERED");
        Integer claimed=tx.execute(t->{
            Order current=orders.lockById(order).orElseThrow();
            requirePayable(current);
            payments.lock(existing.id());
            requireCheckoutOpen(existing.id());
            return payments.db().update("update payment_attempts set status='CREATING',updated_at=now() where payment_id=? and status in ('UNKNOWN','CREATING') and updated_at<now()-interval '2 minutes'",existing.id());
        });
        if (claimed==null || claimed!=1) throw new ConflictException("Payment initialization is still in progress; retry in a couple of minutes");
        payments.event(existing.id(),null,"PREFERENCE_RETRY","{}","PROCESSED");
        return create(gateway,existing,order,expires);
    }

    private Payment store(Payment p,UUID order,CheckoutSession session,String event) {
        return tx.execute(t->{
            Order current=orders.lockById(order).orElseThrow();
            if (current.getStatus()!=OrderStatus.PENDING_PAYMENT) throw new ConflictException("Order cannot be paid in its current state");
            payments.lock(p.id());
            requireCheckoutOpen(p.id());
            return storeLocked(p,session,event);
        });
    }

    private Payment storeLocked(Payment p,CheckoutSession session,String event) {
        String url=session.url();
        String preferenceId=session.id();
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

    private Payment create(PaymentGateway gateway,Payment p,UUID order,Instant expires) {
        try {
            // Keep the order lock through the bounded provider call and persistence. Cancellation first
            // takes this same lock to close checkout: it either prevents the POST or sees its persisted result.
            // The earlier CREATING commit still protects an uncertain result if this transaction rolls back.
            return tx.execute(t->{
                Order current=orders.lockById(order).orElseThrow();
                requirePayable(current);
                payments.lock(p.id());
                requireCheckoutOpen(p.id());
                var buyer=new CheckoutBuyer((current.getCustomerFirstName()+" "+current.getCustomerLastName()).trim(),current.getEmail(),current.getCustomerPhone());
                return storeLocked(p,gateway.create(p,expires,buyer),"PREFERENCE_READY");
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
            throw e instanceof ProviderUnavailableException u?u:new ProviderUnavailableException();
        }
    }
}
