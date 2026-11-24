package com.olvenio.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.olvenio.shared.config.AppProperties;
import com.olvenio.shared.config.SecurityConfig;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.FormContentFilter;

@SpringBootTest(classes = ApiRequestBodyIntegrationTest.LocalWebApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.context-path=/shop"})
class ApiRequestBodyIntegrationTest {
    private static final String PATH = "/api/v1/carts/body-check";
    private static final int MAX_BODY = 128 * 1024;

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @MockitoBean UserDetailsService users;

    @Test void formContentFilterIsAbsent() {
        assertThat(context.getBeansOfType(FormContentFilter.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void patchFormBodyIsBoundedOnTheLocalServer(boolean knownLength) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/shop" + PATH);
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            var csrf = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(csrf.statusCode()).isEqualTo(200);
            String cookie = csrf.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

            var accepted = client.send(patch(uri, cookie, csrf.body(), MAX_BODY, knownLength),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(accepted.body()).isEqualTo(Integer.toString(MAX_BODY));

            var rejected = client.send(patch(uri, cookie, csrf.body(), MAX_BODY + 1, knownLength),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(413);
            assertThat(rejected.body()).contains("Request body too large");
        }
    }

    private static HttpRequest patch(URI uri, String cookie, String csrf, int size, boolean knownLength) {
        byte[] body = ("value=" + "x".repeat(size - "value=".length())).getBytes(StandardCharsets.US_ASCII);
        var publisher = knownLength ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header("Cookie", cookie).header("X-XSRF-TOKEN", csrf)
                .method("PATCH", publisher).build();
    }

    @EnableConfigurationProperties(AppProperties.class)
    @Import({SecurityConfig.class, BodyController.class})
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    static class LocalWebApp {}

    @TestComponent
    @RestController
    static class BodyController {
        @GetMapping(PATH)
        String csrf(CsrfToken token) {
            return token.getToken();
        }

        @PatchMapping(PATH)
        int receivedSize(@RequestBody String body) {
            return body.length();
        }
    }
}
