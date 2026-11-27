package com.olvenio.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
class TikTokEventsClient {
    record Event(String id, String name, long time, String url, JsonNode properties, String ttp, String ttclid) {}
    record Result(boolean sent, boolean retry, int httpStatus, int apiCode) {}

    private static final int TIMEOUT_SECONDS = 4;
    private final TikTokSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();

    TikTokEventsClient(TikTokSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    Result send(Event event) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        HttpRequest request = HttpRequest.newBuilder(settings.endpoint).timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Access-Token", settings.token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(event)))).build();
        var operation = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = operation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result(response.statusCode(), response.body());
        } finally {
            if (!operation.isDone()) operation.cancel(true);
        }
    }

    Map<String, Object> payload(Event event) {
        Map<String, String> user = new LinkedHashMap<>();
        if (event.ttp() != null) user.put("ttp", event.ttp());
        if (event.ttclid() != null) user.put("ttclid", event.ttclid());
        Map<String, Object> item = new LinkedHashMap<>(Map.of(
                "event", event.name(), "event_id", event.id(), "event_time", event.time(),
                "page", Map.of("url", event.url()), "user", user));
        if (!event.properties().isEmpty()) item.put("properties", event.properties());
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "event_source", "web", "event_source_id", settings.pixel, "data", List.of(item)));
        if (!settings.testCode.isEmpty()) payload.put("test_event_code", settings.testCode);
        return payload;
    }

    Result result(int status, String body) {
        int code = -1;
        try {
            var response = json.readTree(body);
            if (response != null) {
                var value = response.path("code");
                if (value.isIntegralNumber() && value.canConvertToInt()) code = value.intValue();
            }
        } catch (IOException ignored) {
            // Never log the provider's response body.
        }
        return new Result(status == 200 && code == 0,
                status == 408 || status == 429 || status >= 500
                        || (status == 200 && (code == -1 || code == 40100 || code >= 50000 && code < 60000)),
                status, code);
    }
}
