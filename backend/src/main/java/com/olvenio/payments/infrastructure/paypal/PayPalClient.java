package com.olvenio.payments.infrastructure.paypal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.payments.domain.ProviderUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Minimal PayPal REST client: OAuth client-credentials token (cached) and JSON calls. */
@Component
public class PayPalClient {
    private final PayPalProperties config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private String token;
    private Instant tokenUntil = Instant.EPOCH;

    public PayPalClient(PayPalProperties config, ObjectMapper json) { this.config = config; this.json = json; }

    public record Response(int status, JsonNode body) {
        public boolean ok() { return status >= 200 && status < 300; }
        public String issue() { return body.path("details").path(0).path("issue").asText(); }
    }

    /** PayPal-Request-Id makes a retried creation return the original order. */
    public JsonNode createOrder(Object body, String requestId) {
        return require(send("POST", "/v2/checkout/orders", body, requestId));
    }
    public JsonNode order(String id) { return require(send("GET", "/v2/checkout/orders/" + id(id), null, null)); }
    /** 422 ORDER_ALREADY_CAPTURED / ORDER_NOT_APPROVED are answers, not failures: the caller decides. */
    public Response capture(String id, String requestId) {
        return send("POST", "/v2/checkout/orders/" + id(id) + "/capture", json.createObjectNode(), requestId);
    }
    public JsonNode verifyWebhook(Object body) {
        return require(send("POST", "/v1/notifications/verify-webhook-signature", body, null));
    }

    private static String id(String value) {
        if (value == null || !value.matches("[A-Z0-9]{1,36}")) throw new ProviderUnavailableException();
        return value;
    }
    private static JsonNode require(Response r) {
        if (!r.ok()) throw new ProviderUnavailableException();
        return r.body();
    }

    private synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenUntil)) return token;
        String basic = Base64.getEncoder().encodeToString((config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(config.apiBase() + "/v1/oauth2/token"))
                .timeout(Duration.ofSeconds(6)).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new ProviderUnavailableException();
        JsonNode body = json.readTree(r.body());
        String value = body.path("access_token").asText();
        if (value.isBlank()) throw new ProviderUnavailableException();
        token = value;
        tokenUntil = Instant.now().plusSeconds(Math.max(60, body.path("expires_in").asLong(300) - 120));
        return token;
    }

    private Response send(String method, String path, Object body, String requestId) {
        config.requireConfigured();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.apiBase() + path))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token())
                    .header("Accept", "application/json").header("Prefer", "return=representation");
            if (requestId != null) request.header("PayPal-Request-Id", requestId);
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) synchronized (this) { token = null; }
            if (response.statusCode() >= 500 || response.statusCode() == 429 || response.statusCode() == 401)
                throw new ProviderUnavailableException();
            return new Response(response.statusCode(), response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body()));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ProviderUnavailableException(); }
          catch (ProviderUnavailableException e) { throw e; }
          catch (Exception e) { throw new ProviderUnavailableException(); }
    }
}
