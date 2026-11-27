package com.olvenio.marketing;

import com.olvenio.shared.config.AppProperties;
import java.net.URI;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class TikTokSettings {
    static final String PRODUCTION_PIXEL = "DC395ARC77UFT0UGGH30";
    static final String PRODUCTION_ORIGIN = "https://olvenio.com";
    static final URI ENDPOINT = URI.create("https://business-api.tiktok.com/open_api/v1.3/event/track/");
    private static final Set<String> PRODUCTION_HOSTS = Set.of("olvenio.com", "www.olvenio.com");

    final boolean enabled;
    final boolean serverEnabled;
    final String pixel;
    final String token;
    final String testCode;
    final URI endpoint;
    final String origin;
    final boolean secureCookie;

    TikTokSettings(AppProperties app,
                   @Value("${app.marketing.tiktok.enabled}") boolean enabled,
                   @Value("${app.marketing.tiktok.pixel-id}") String pixel,
                   @Value("${app.marketing.tiktok.access-token}") String token,
                   @Value("${app.marketing.tiktok.url}") String url,
                   @Value("${app.marketing.tiktok.test-code}") String testCode) {
        this.origin = app.allowedOrigins().stream().findFirst().orElse("");
        this.secureCookie = app.cookieSecure();
        this.pixel = pixel.trim();
        this.token = token.trim();
        this.testCode = testCode.trim();
        // Pin the host/path and forbid redirects so a configuration error cannot forward the token.
        this.endpoint = ENDPOINT.toString().equals(url.trim()) ? ENDPOINT : null;
        boolean productionPixel = PRODUCTION_PIXEL.equalsIgnoreCase(this.pixel);
        boolean productionSite = !origin.isEmpty()
                && PRODUCTION_HOSTS.contains(URI.create(origin).getHost().toLowerCase(java.util.Locale.ROOT));
        this.enabled = enabled && !origin.isEmpty() && this.pixel.matches("[A-Za-z0-9]{10,32}")
                && (!productionPixel || (PRODUCTION_ORIGIN.equals(origin) && this.testCode.isEmpty()));
        this.serverEnabled = this.enabled && endpoint != null && !this.token.isEmpty()
                && (productionSite || !this.testCode.isEmpty());
        if (enabled && !this.enabled) LoggerFactory.getLogger(getClass()).warn("TikTok configuration disabled");
        else if (enabled && !this.serverEnabled) LoggerFactory.getLogger(getClass()).warn("TikTok Events API configuration disabled");
    }
}
