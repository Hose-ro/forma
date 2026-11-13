package com.velora.payments.application;

import com.velora.payments.infrastructure.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentReconciliationService {
    private final PaymentRepository payments;
    private final MercadoPagoClient client;
    private final PaymentVerificationService verification;
    private final PaymentProperties config;
    private final TransactionTemplate tx;
    public PaymentReconciliationService(PaymentRepository payments,MercadoPagoClient client,PaymentVerificationService verification,PaymentProperties config,TransactionTemplate tx) {
        this.payments=payments;this.client=client;this.verification=verification;this.config=config;this.tx=tx;
    }
    @Scheduled(fixedDelayString="${app.payments.reconciliation-delay:60000}")
    public void reconcile() {
        if(config.accessToken()==null || config.accessToken().isBlank()) return;
        // Claim one record at a time: queued records must not expire while an earlier HTTP call runs.
        for (int batch=0;batch<5;batch++) {
            List<UUID> ids=tx.execute(t->payments.db().queryForList("""
                with due as (select id from payments where next_reconciliation_at<=now()
                    and reconciliation_count<96 and (lease_until is null or lease_until<now())
                    and created_at<now()-interval '30 seconds' and status<>'REFUNDED'
                    order by next_reconciliation_at limit 1 for update skip locked)
                update payments p set lease_until=now()+interval '2 minutes',reconciliation_count=reconciliation_count+1,
                    next_reconciliation_at=now()+case when reconciliation_count<12 then interval '30 seconds' else interval '30 minutes' end
                    from due where p.id=due.id returning p.id
                """,UUID.class));
            if (ids.isEmpty()) break;
            UUID id=ids.getFirst();
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(60).toNanos();
            try {
                tx.executeWithoutResult(t->{
                    int changed=payments.db().update("update payments set status='UNKNOWN',failure_reason='PREFERENCE_RESULT_UNCERTAIN',updated_at=now() where id=? and status='CREATED' and provider_preference_id is null and created_at<now()-interval '2 minutes'",id);
                    if(changed>0) {
                        payments.db().update("update payment_attempts set status='UNKNOWN',updated_at=now() where payment_id=? and status='CREATING'",id);
                        payments.event(id,null,"INTERRUPTED_CREATION","{}","REVIEW");
                    }
                });
                // Search recovers lost webhooks, unknown preference responses, and additional card retries.
                for(int offset=0;offset<200;offset+=50) {
                    if(System.nanoTime()>deadline) throw new MercadoPagoClient.ProviderException();
                    var response=client.search(id,offset);
                    var results=response.path("results");
                    if(!results.isArray()) throw new MercadoPagoClient.ProviderException();
                    for(var resource:results) {
                        // Search results only identify candidates; GET is the authoritative full snapshot.
                        if(System.nanoTime()>deadline) throw new MercadoPagoClient.ProviderException();
                        verification.apply(client.payment(resource.path("id").asText()));
                    }
                    if(results.size()<50) break;
                }
            } catch(RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("Payment reconciliation deferred for {}",id);
                payments.event(id,null,"RECONCILIATION_FAILED","{}","RETRY");
            } finally { payments.db().update("update payments set lease_until=null where id=?",id); }
        }
    }
}
