package com.velora.payments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.velora.payments.application.*;
import com.velora.payments.domain.*;
import com.velora.payments.infrastructure.*;
import com.velora.orders.domain.*;
import com.velora.orders.infrastructure.*;
import com.velora.shared.security.*;
import com.velora.shared.config.AppProperties;
import com.velora.shared.domain.*;
import org.springframework.transaction.support.*;
import org.springframework.transaction.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;

class PaymentIdempotencyTest {
    private final PaymentRepository payments=mock(PaymentRepository.class);
    private final OrderRepository orders=mock(OrderRepository.class);
    private final MercadoPagoClient client=mock(MercadoPagoClient.class);
    private final SecretTokens tokens=new SecretTokens(new AppProperties(null,"test-secret-test-secret-test-secret-42",false,"MXN",null,null));
    private final TransactionTemplate tx=new TransactionTemplate(new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object t,TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}
        protected void doRollback(DefaultTransactionStatus s){}
    });
    private PaymentService service(){return new PaymentService(orders,payments,client,new PaymentProperties("token","secret","test","123","https://front.example","https://back.example"),tokens,tx);}
    private UUID setup(String token,PaymentStatus status,String url){
        UUID id=UUID.randomUUID();var now=Instant.now();
        Order o=Order.quote(null,null,"a@example.com","Ana","Perez",null,"MXN",tokens.hash(token),now.plusSeconds(600));
        var price=Money.of(BigDecimal.TEN,"MXN");o.addItem(id,id,"SKU","P","p","v",null,price,1,price);o.place("VEL-1","checkout-key-123",now,null);
        when(orders.lockById(id)).thenReturn(Optional.of(o));
        when(payments.byOrder(id)).thenReturn(Optional.of(new Payment(UUID.randomUUID(),id,"MERCADO_PAGO",null,"preference-1",status,BigDecimal.TEN,"MXN",url,now,null,null)));
        return id;
    }
    @Test void existingPreferenceIsReusedWithoutSecondProviderRequest(){
        String token=tokens.newToken();UUID id=setup(token,PaymentStatus.PENDING,"https://www.mercadopago.com.mx/checkout/v1/redirect");
        assertThat(service().preference(id,"payment-key-12345678",token).providerPreferenceId()).isEqualTo("preference-1");
        assertThat(service().preference(id,"another-key-12345678",token).providerPreferenceId()).isEqualTo("preference-1");
        verify(client,times(2)).requireTestSeller();
        verify(client,never()).createPreference(any());
        verify(client,never()).preference(any());
    }
    @Test void oldCheckoutLinkIsRecoveredFromSamePreferenceWithoutCreatingAnother() {
        String token=tokens.newToken();UUID id=setup(token,PaymentStatus.PENDING,"https://sandbox.mercadopago.com.mx/checkout/v1/redirect");
        Payment previous=payments.byOrder(id).orElseThrow();
        String url="https://www.mercadopago.com.mx/checkout/v1/redirect?pref_id=preference-1";
        Payment refreshed=new Payment(previous.id(),id,previous.provider(),null,"preference-1",previous.status(),previous.amount(),previous.currency(),url,previous.createdAt(),null,null);
        var official=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        official.put("id","preference-1");official.put("external_reference",previous.id().toString());official.put("collector_id",123);
        when(client.preference("preference-1")).thenReturn(official);
        when(client.checkoutUrl(official)).thenReturn(url);
        when(payments.lock(previous.id())).thenReturn(previous);
        when(payments.find(previous.id())).thenReturn(Optional.of(refreshed));
        when(payments.db()).thenReturn(mock(org.springframework.jdbc.core.JdbcTemplate.class));
        assertThat(service().preference(id,"payment-key-12345678",token).checkoutUrl()).isEqualTo(url);
        verify(client).preference("preference-1");verify(client,never()).createPreference(any());
        verify(payments).event(previous.id(),null,"CHECKOUT_LINK_REFRESHED","{}","PROCESSED");
    }
    @Test void uncertainCreationCannotBeResubmitted(){
        String token=tokens.newToken();UUID id=setup(token,PaymentStatus.UNKNOWN,null);
        assertThatThrownBy(()->service().preference(id,"payment-key-12345678",token)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(client);
    }
    @Test void keyCannotBeReusedForAnotherOrder(){
        String token=tokens.newToken();UUID id=setup(token,PaymentStatus.PENDING,"https://www.mercadopago.com.mx/checkout/v1/redirect");
        when(payments.keyBelongsToAnotherOrder("payment-key-12345678",id)).thenReturn(true);
        assertThatThrownBy(()->service().preference(id,"payment-key-12345678",token)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(client);
    }
    @Test void knownUuidDoesNotAuthorizePayment(){
        UUID id=setup(tokens.newToken(),PaymentStatus.PENDING,"https://www.mercadopago.com.mx/checkout/v1/redirect");
        assertThatThrownBy(()->service().preference(id,"payment-key-12345678","wrong")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(client);verify(payments,never()).byOrder(any());
    }
}
