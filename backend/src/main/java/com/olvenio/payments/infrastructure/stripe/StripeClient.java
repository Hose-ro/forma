package com.olvenio.payments.infrastructure.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.payments.domain.ProviderUnavailableException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Minimal Stripe REST client (form-encoded requests, JSON responses) pinned to one API version. */
@Component
public class StripeClient {
    static final String API_VERSION = "2024-06-20";
    private final StripeProperties config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public StripeClient(StripeProperties config, ObjectMapper json) { this.config = config; this.json = json; }

    public record Response(int status, JsonNode body) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    /** Stripe replays the saved response for a repeated idempotency key, so a retried creation never duplicates. */
    public JsonNode createSession(Map<String, String> form, String idempotencyKey) {
        return require(send("POST", "/v1/checkout/sessions", form, idempotencyKey));
    }
    public JsonNode session(String id) {
        return require(send("GET", "/v1/checkout/sessions/" + id(id, "cs_") + "?expand[]=payment_intent", null, null));
    }
    /** Fails (4xx) when the session is no longer open; the caller re-reads it. */
    public Response expireSession(String id) {
        return send("POST", "/v1/checkout/sessions/" + id(id, "cs_") + "/expire", Map.of(), null);
    }
    public JsonNode paymentIntent(String id) {
        return require(send("GET", "/v1/payment_intents/" + id(id, "pi_") + "?expand[]=latest_charge", null, null));
    }
    /** Best effort: only some states can be canceled. The caller always re-reads the intent afterwards. */
    public Response cancelPaymentIntent(String id) {
        return send("POST", "/v1/payment_intents/" + id(id, "pi_") + "/cancel", Map.of(), null);
    }
    /** Search is eventually consistent (normally under a minute); it complements, never replaces, direct reads. */
    public JsonNode searchPaymentIntents(UUID reference, String page) {
        String query = "metadata['payment_id']:'" + reference + "'";
        String path = "/v1/payment_intents/search?limit=100&query=" + encode(query) + (page == null ? "" : "&page=" + encode(page));
        return require(send("GET", path, null, null));
    }

    private static String id(String value, String prefix) {
        if (value == null || !value.matches(prefix + "[A-Za-z0-9_]{1,250}")) throw new ProviderUnavailableException();
        return value;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static JsonNode require(Response r) {
        if (!r.ok()) throw new ProviderUnavailableException();
        return r.body();
    }

    private Response send(String method, String path, Map<String, String> form, String idempotencyKey) {
        config.requireConfigured();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.stripe.com" + path))
                    .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + config.secretKey())
                    .header("Stripe-Version", API_VERSION).header("Accept", "application/json");
            if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
            if (form == null) request.GET();
            else request.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, HttpRequest.BodyPublishers.ofString(form.entrySet().stream()
                            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"))));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500 || response.statusCode() == 429) throw new ProviderUnavailableException();
            return new Response(response.statusCode(), json.readTree(response.body()));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ProviderUnavailableException(); }
          catch (ProviderUnavailableException e) { throw e; }
          catch (Exception e) { throw new ProviderUnavailableException(); }
    }
}
