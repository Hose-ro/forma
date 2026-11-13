package com.velora.payments;
import static org.assertj.core.api.Assertions.*;
import com.velora.payments.application.*;
import com.velora.payments.infrastructure.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class WebhookSignatureTest {
    private String sign(String id) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "ts=1742505638683,v1="+HexFormat.of().formatHex(mac.doFinal(("id:"+id+";request-id:req-123;ts:1742505638683;").getBytes(StandardCharsets.UTF_8)));
    }
    @Test void officialManifestAndConstantTimeComparison() throws Exception {
        assertThat(WebhookSignature.valid(sign("123456"),"req-123","123456","test-secret")).isTrue();
        assertThat(WebhookSignature.valid(sign("ABC123"),"req-123","ABC123","test-secret")).isTrue();
        assertThat(WebhookSignature.valid(sign("ABC123"),"req-123","abc123","test-secret")).isFalse();
        assertThat(WebhookSignature.valid(sign("123456"),"req-123","999999","test-secret")).isFalse();
        assertThat(WebhookSignature.valid(sign("123456"),"other","123456","test-secret")).isFalse();
        assertThat(WebhookSignature.valid(sign("123456"),"req-123","123456","wrong-secret")).isFalse();
    }
    @Test void malformedMissingOrAmbiguousSignatureRejected() throws Exception {
        for(String signature:new String[]{"", "ts=1,v1=oops", "ts=1,ts=2,v1="+"a".repeat(64)})
            assertThat(WebhookSignature.valid(signature,"req-123","123456","test-secret")).isFalse();
        assertThat(WebhookSignature.valid(null,"req-123","123456","test-secret")).isFalse();
        assertThat(WebhookSignature.valid(sign("123456"),null,"123456","test-secret")).isFalse();
    }
    @Test void invalidNotificationDoesNotCallProviderOrChangePayment() {
        var client=mock(MercadoPagoClient.class);var verification=mock(PaymentVerificationService.class);
        var service=new PaymentWebhookService(new PaymentProperties("token","test-secret","test","123","https://front.example","https://api.example"),client,verification);
        assertThatThrownBy(()->service.receive(null,"req","123","payment")).isInstanceOf(PaymentWebhookService.InvalidSignatureException.class);
        verifyNoInteractions(client,verification);
    }
}
