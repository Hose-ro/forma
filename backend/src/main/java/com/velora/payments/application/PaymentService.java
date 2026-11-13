package com.velora.payments.application;

import com.velora.payments.domain.*;
import com.velora.payments.infrastructure.*;
import com.velora.orders.domain.*;
import com.velora.orders.infrastructure.OrderRepository;
import com.velora.shared.domain.*;
import com.velora.shared.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final MercadoPagoClient client;
    private final PaymentProperties config;
    private final SecretTokens tokens;
    private final TransactionTemplate tx;
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
        // Commit CREATING before any network call. A crash or timeout can never trigger a second POST.
        record Start(Payment payment,boolean create) {}
        Start start=tx.execute(t->{
            Order o=authorize(order,token);
            if (o.getStatus()!=OrderStatus.PENDING_PAYMENT) throw new ConflictException("Order cannot be paid in its current state");
            if (payments.keyBelongsToAnotherOrder(key,order)) throw new ConflictException("Idempotency-Key belongs to another order");
            var existing=payments.byOrder(order);
            if (existing.isPresent()) return new Start(existing.get(),false);
            if (o.getTotal().signum()<=0 || !"MXN".equals(o.getCurrency())) throw new ConflictException("Invalid payable amount or currency");
            UUID id=UUID.randomUUID();
            payments.db().update("insert into payments(id,order_id,status,amount,currency) values (?,?,'CREATED',?,?)",id,order,o.getTotal(),o.getCurrency());
            payments.db().update("insert into payment_attempts(id,payment_id,idempotency_key,status) values (?,?,?,'CREATING')",UUID.randomUUID(),id,key);
            payments.event(id,null,"PREFERENCE_REQUESTED","{}","PROCESSED");
            return new Start(payments.find(id).orElseThrow(),true);
        });
        if (!start.create()) {
            if (start.payment().checkoutUrl()==null) throw new ConflictException("Payment initialization is uncertain and requires reconciliation; do not create another order");
            client.requireTestSeller();
            if (MercadoPagoClient.allowedCheckoutUrl(start.payment().checkoutUrl())) return start.payment();
            // Repair a previously stored checkout link using a read, never a second preference creation.
            Payment existing=start.payment();
            var official=client.preference(existing.providerPreferenceId());
            if (!config.collectorId().equals(official.path("collector_id").asText())
                    || !existing.id().toString().equals(official.path("external_reference").asText())
                    || !existing.providerPreferenceId().equals(official.path("id").asText())) throw new MercadoPagoClient.ProviderException();
            String url=client.checkoutUrl(official);
            return tx.execute(t->{
                Order current=orders.lockById(order).orElseThrow();
                if (current.getStatus()!=OrderStatus.PENDING_PAYMENT) throw new ConflictException("Order cannot be paid in its current state");
                payments.lock(existing.id());
                payments.db().update("update payments set checkout_url=?,updated_at=now() where id=?",url,existing.id());
                payments.event(existing.id(),null,"CHECKOUT_LINK_REFRESHED","{}","PROCESSED");
                return payments.find(existing.id()).orElseThrow();
            });
        }
        Payment p=start.payment();
        try {
            var result=client.createPreference(p);
            String preferenceId=result.path("id").asText();
            if (preferenceId.isBlank() || !config.collectorId().equals(result.path("collector_id").asText())) throw new MercadoPagoClient.ProviderException();
            String url=client.checkoutUrl(result);
            return tx.execute(t->{
                orders.lockById(order).orElseThrow();payments.lock(p.id());
                payments.db().update("update payments set provider_preference_id=?,checkout_url=?,status=case when status='CREATED' then 'PENDING' else status end,updated_at=now() where id=?",preferenceId,url,p.id());
                payments.db().update("update payment_attempts set status='READY',provider_request_id=?,updated_at=now() where payment_id=?",preferenceId,p.id());
                payments.event(p.id(),null,"PREFERENCE_READY","{}","PROCESSED");
                return payments.find(p.id()).orElseThrow();
            });
        } catch (RuntimeException e) {
            tx.executeWithoutResult(t->{
                orders.lockById(order).orElseThrow();payments.lock(p.id());
                payments.db().update("update payment_attempts set status='UNKNOWN',updated_at=now() where payment_id=? and status='CREATING'",p.id());
                payments.db().update("update payments set status=case when status='CREATED' then 'UNKNOWN' else status end,failure_reason='PREFERENCE_RESULT_UNCERTAIN',updated_at=now() where id=?",p.id());
                payments.event(p.id(),null,"PREFERENCE_UNCERTAIN","{}","REVIEW");
            });
            throw new MercadoPagoClient.ProviderException();
        }
    }
}
