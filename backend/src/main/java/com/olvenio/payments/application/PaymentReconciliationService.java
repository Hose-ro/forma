package com.olvenio.payments.application;

import com.olvenio.payments.application.port.PaymentGateway;
import com.olvenio.payments.domain.ProviderUnavailableException;
import com.olvenio.payments.infrastructure.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentReconciliationService {
    private final PaymentRepository payments;
    private final PaymentGateways gateways;
    private final PaymentVerificationService verification;
    private final TransactionTemplate tx;
    public PaymentReconciliationService(PaymentRepository payments,PaymentGateways gateways,PaymentVerificationService verification,TransactionTemplate tx) {
        this.payments=payments;this.gateways=gateways;this.verification=verification;this.tx=tx;
    }
    @Scheduled(fixedDelayString="${app.payments.reconciliation-delay:60000}")
    public void reconcile() {
        // Payments of a provider without credentials wait until it is configured again.
        List<PaymentGateway> enabled=gateways.enabled();
        if(enabled.isEmpty()) return;
        String providers=enabled.stream().map(g->"'"+g.provider().name()+"'").collect(Collectors.joining(","));
        // Claim one record at a time: queued records must not expire while an earlier HTTP call runs.
        for (int batch=0;batch<5;batch++) {
            List<UUID> ids=tx.execute(t->payments.db().queryForList("""
                with due as (select id from payments where next_reconciliation_at<=now() and provider in (%s)
                    and (reconciliation_count<96 or (status in ('CREATED','PENDING','IN_PROCESS','UNKNOWN')
                        and created_at>now()-interval '60 days')) and (lease_until is null or lease_until<now())
                    and created_at<now()-interval '30 seconds' and status<>'REFUNDED'
                    order by next_reconciliation_at limit 1 for update skip locked)
                update payments p set lease_until=now()+interval '2 minutes',reconciliation_count=reconciliation_count+1,
                    next_reconciliation_at=now()+case when reconciliation_count<12 then interval '30 seconds'
                        when reconciliation_count<96 then interval '30 minutes' else interval '6 hours' end
                    from due where p.id=due.id returning p.id
                """.formatted(providers),UUID.class));
            if (ids.isEmpty()) break;
            UUID id=ids.getFirst();
            Instant deadline=Instant.now().plus(Duration.ofSeconds(60));
            try {
                tx.executeWithoutResult(t->{
                    int changed=payments.db().update("update payments set status='UNKNOWN',failure_reason='PREFERENCE_RESULT_UNCERTAIN',updated_at=now() where id=? and status='CREATED' and provider_preference_id is null and created_at<now()-interval '2 minutes'",id);
                    if(changed>0) {
                        payments.db().update("update payment_attempts set status='UNKNOWN',updated_at=now() where payment_id=? and status='CREATING'",id);
                        payments.event(id,null,"INTERRUPTED_CREATION","{}","REVIEW");
                    }
                });
                var payment=payments.find(id).orElseThrow(ProviderUnavailableException::new);
                gateways.get(payment.provider()).forEachCharge(payment,deadline,verification::apply);
            } catch(RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("Payment reconciliation deferred for {}",id);
                payments.event(id,null,"RECONCILIATION_FAILED","{}","RETRY");
            } finally { payments.db().update("update payments set lease_until=null where id=?",id); }
        }
    }
}
