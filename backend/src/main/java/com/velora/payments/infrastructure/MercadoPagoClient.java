package com.velora.payments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.payments.domain.Payment;
import com.velora.shared.domain.DomainException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MercadoPagoClient {
    private final PaymentProperties config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public MercadoPagoClient(PaymentProperties config, ObjectMapper json) { this.config=config; this.json=json; }

    public JsonNode createPreference(Payment p) {
        requireTestSeller();
        String base = config.frontendUrl().replaceAll("/$", "");
        Map<String,Object> body = Map.of(
            "items", List.of(Map.of("id",p.orderId().toString(),"title","Pedido FORMA", "quantity",1,
                "currency_id",p.currency(),"unit_price",p.amount())),
            "external_reference", p.id().toString(),
            "back_urls", Map.of("success",base+"/checkout/success?orderId="+p.orderId(),
                "pending",base+"/checkout/pending?orderId="+p.orderId(),"failure",base+"/checkout/failure?orderId="+p.orderId()),
            "notification_url",config.backendUrl().replaceAll("/$", "")+"/api/v1/webhooks/mercadopago",
            "auto_return","approved");
        return request("POST", "/checkout/preferences", body);
    }
    public JsonNode payment(String id) {
        if (id == null || !id.matches("[0-9]{1,100}")) throw new ProviderException();
        return request("GET", "/v1/payments/"+id, null);
    }
    public JsonNode search(UUID reference, int offset) {
        return request("GET", "/v1/payments/search?external_reference="+reference+"&limit=50&offset="+offset, null);
    }
    private volatile java.time.Instant sellerVerifiedUntil = java.time.Instant.EPOCH;

    /** Test credentials share the public checkout host; verify the account instead of guessing from its token prefix. */
    public synchronized void requireTestSeller() {
        config.requireConfigured();
        if (java.time.Instant.now().isBefore(sellerVerifiedUntil)) return;
        JsonNode seller = request("GET", URI.create("https://api.mercadolibre.com/users/me"), null);
        if (!testSellerMatches(seller, config.collectorId())) throw new ProviderException();
        sellerVerifiedUntil = java.time.Instant.now().plusSeconds(300);
    }
    public static boolean testSellerMatches(JsonNode seller, String collectorId) {
        if (collectorId == null || !seller.path("tags").isArray()) return false;
        boolean test = false;
        for (JsonNode tag : seller.path("tags")) if ("test_user".equals(tag.asText())) test = true;
        return test && collectorId.equals(seller.path("id").asText()) && "MLM".equals(seller.path("site_id").asText());
    }
    /** Test-user Checkout Pro payments may use the public processing environment (live_mode=true).
     * Verify both parties through the official account API instead of treating that flag as sufficient evidence. */
    public boolean testAccountsVerified(JsonNode payment) {
        requireTestSeller();
        if (!config.collectorId().equals(payment.path("collector_id").asText())) return false;
        JsonNode payer=payment.path("payer");
        String buyerId=payer.path("id").asText();
        if (!buyerId.matches("[0-9]{1,30}") || buyerId.equals(config.collectorId())) return false;
        JsonNode buyer=request("GET",URI.create("https://api.mercadolibre.com/users/"+buyerId),null);
        return testBuyerMatches(buyer,payer);
    }
    public static boolean testBuyerMatches(JsonNode buyer, JsonNode payer) {
        String id=payer.path("id").asText();
        if (!id.matches("[0-9]{1,30}") || !id.equals(buyer.path("id").asText()) || !"MLM".equals(buyer.path("site_id").asText())) return false;
        if (testSellerMatches(buyer,id)) return true;
        // Public buyer profiles can omit tags. Require both provider-issued test identity indicators.
        return buyer.path("nickname").asText().matches("TESTUSER[0-9]{1,40}")
            && payer.path("email").asText().toLowerCase(java.util.Locale.ROOT).endsWith("@testuser.com");
    }
    public JsonNode preference(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,200}")) throw new ProviderException();
        return request("GET", "/checkout/preferences/"+id, null);
    }
    public static boolean allowedCheckoutUrl(String value) {
        try {
            URI u = URI.create(value);
            return "https".equals(u.getScheme()) && "www.mercadopago.com.mx".equals(u.getHost())
                && u.getUserInfo()==null && u.getPort()==-1 && u.getFragment()==null
                && "/checkout/v1/redirect".equals(u.getPath());
        } catch (RuntimeException e) { return false; }
    }
    public String checkoutUrl(JsonNode preference) {
        String value = preference.path("init_point").asText();
        if (!allowedCheckoutUrl(value)) throw new ProviderException();
        return value;
    }
    private JsonNode request(String method, String path, Object body) {
        return request(method, URI.create("https://api.mercadopago.com"+path), body);
    }
    private JsonNode request(String method, URI uri, Object body) {
        config.requireConfigured();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(6)).header("Authorization", "Bearer "+config.accessToken())
                .header("Accept","application/json");
            if (body == null) request.GET();
            else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            // Preference creation has no assumed provider-side idempotency: the durable local attempt prevents resubmission.
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()<200 || response.statusCode()>=300) throw new ProviderException();
            return json.readTree(response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ProviderException(); }
          catch (Exception e) { throw new ProviderException(); }
    }
    public static class ProviderException extends DomainException {
        public ProviderException() { super("Mercado Pago is temporarily unavailable; payment verification is pending"); }
        public int status() { return 503; }
        public String title() { return "Payment provider unavailable"; }
    }
}
