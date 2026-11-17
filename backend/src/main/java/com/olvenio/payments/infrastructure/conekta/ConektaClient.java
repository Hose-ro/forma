package com.olvenio.payments.infrastructure.conekta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.payments.domain.ProviderUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Minimal Conekta REST client pinned to API 2.3.0. */
@Component
public class ConektaClient {
    static final String ACCEPT = "application/vnd.conekta-v2.3.0+json";
    private final ConektaProperties config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ConektaClient(ConektaProperties config, ObjectMapper json) { this.config = config; this.json = json; }

    public record Response(int status, JsonNode body) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    /** Idempotency-Key: a retried creation returns the original order. */
    public JsonNode createOrder(Object body, String idempotencyKey) {
        return require(send("POST", "/orders", body, idempotencyKey));
    }
    public JsonNode order(String id) { return require(send("GET", "/orders/" + id(id), null, null)); }
    /** Refused when a charge is paid (or, possibly, pending): the caller re-reads the order. */
    public Response cancelOrder(String id) { return send("POST", "/orders/" + id(id) + "/cancel", json.createObjectNode(), null); }
    /** Orders created in a time window, newest first; used to find an order whose creation result was lost. */
    public JsonNode ordersCreatedBetween(Instant from, Instant to) {
        return require(send("GET", "/orders?limit=250&created_at.gte=" + from.getEpochSecond() + "&created_at.lte=" + to.getEpochSecond(), null, null));
    }

    private static String id(String value) {
        if (value == null || !value.matches("ord_[A-Za-z0-9]{1,100}")) throw new ProviderUnavailableException();
        return value;
    }
    private static JsonNode require(Response r) {
        if (!r.ok()) throw new ProviderUnavailableException();
        return r.body();
    }

    private Response send(String method, String path, Object body, String idempotencyKey) {
        config.requireConfigured();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.conekta.io" + path))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + config.privateKey())
                    .header("Accept", ACCEPT).header("Accept-Language", "es");
            if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500 || response.statusCode() == 429 || response.statusCode() == 401)
                throw new ProviderUnavailableException();
            return new Response(response.statusCode(), response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body()));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ProviderUnavailableException(); }
          catch (ProviderUnavailableException e) { throw e; }
          catch (Exception e) { throw new ProviderUnavailableException(); }
    }
}
