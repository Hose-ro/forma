package com.olvenio.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.shared.config.AppProperties;
import org.junit.jupiter.api.Test;

class TikTokEventsClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void payloadContainsOnlyTheAllowedContextAndAttributionFields() throws Exception {
        var client = new TikTokEventsClient(settings("https://staging.example.test", "StagingPixel123",
                TikTokSettings.ENDPOINT.toString(), "TEST_FIXTURE"), json);
        var event = new TikTokEventsClient.Event("shared-id", "Purchase", 1748000000L,
                "https://staging.example.test/checkout", json.readTree("""
                {"currency":"MXN","value":299.50,"contents":[{"content_id":"variant-1","price":125.00,"quantity":2}]}
                """), "FixtureCookie123", null);
        var payload = json.valueToTree(client.payload(event));
        assertThat(payload.path("event_source").asText()).isEqualTo("web");
        assertThat(payload.path("event_source_id").asText()).isEqualTo("StagingPixel123");
        assertThat(payload.path("test_event_code").asText()).isEqualTo("TEST_FIXTURE");
        var data = payload.path("data").get(0);
        assertThat(data.path("event_time").asLong()).isEqualTo(1748000000L);
        assertThat(data.path("event_id").asText()).isEqualTo("shared-id");
        assertThat(data.path("properties").path("value").isNumber()).isTrue();
        assertThat(data.path("user").size()).isEqualTo(1);
        assertThat(data.path("user").path("ttp").asText()).isEqualTo("FixtureCookie123");
        assertThat(payload.toString()).doesNotContain("access-token-fixture", "user_agent", "external_id", "email", "phone");
    }

    @Test
    void verifiesBusinessCodeAndDistinguishesRetryableFailures() {
        var client = new TikTokEventsClient(settings("https://staging.example.test", "StagingPixel123",
                TikTokSettings.ENDPOINT.toString(), "TEST_FIXTURE"), json);
        assertThat(client.result(200, "{\"code\":0}").sent()).isTrue();
        assertThat(client.result(200, "{\"code\":40002}").sent()).isFalse();
        assertThat(client.result(200, "{\"code\":40002}").retry()).isFalse();
        assertThat(client.result(401, "{\"code\":0}").sent()).isFalse();
        assertThat(client.result(200, "{\"code\":40100}").retry()).isTrue();
        assertThat(client.result(503, "").retry()).isTrue();
        assertThat(client.result(200, "not JSON").retry()).isTrue();
        assertThat(client.result(302, "{\"code\":0}").retry()).isFalse();
    }

    @Test
    void protectsTheProductionPixelAndTheAccessToken() {
        String endpoint = TikTokSettings.ENDPOINT.toString();
        assertThat(settings("http://localhost:3000", TikTokSettings.PRODUCTION_PIXEL, endpoint, "").enabled).isFalse();
        assertThat(settings(TikTokSettings.PRODUCTION_ORIGIN, TikTokSettings.PRODUCTION_PIXEL, endpoint, "TEST").enabled).isFalse();
        assertThat(settings(TikTokSettings.PRODUCTION_ORIGIN, TikTokSettings.PRODUCTION_PIXEL, endpoint, "").serverEnabled).isTrue();
        assertThat(settings("https://staging.example.test", "StagingPixel123", "https://other.example.test/", "TEST").serverEnabled).isFalse();
        assertThat(settings("https://staging.example.test", "StagingPixel123", endpoint, "").serverEnabled).isFalse();
    }

    private static TikTokSettings settings(String origin, String pixel, String endpoint, String code) {
        return new TikTokSettings(new AppProperties(origin, null, true, "MXN", null, null),
                true, pixel, "access-token-fixture", endpoint, code);
    }
}
