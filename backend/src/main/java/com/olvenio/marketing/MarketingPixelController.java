package com.olvenio.marketing;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
class MarketingPixelController {
    private final TikTokSettings settings;

    MarketingPixelController(TikTokSettings settings) {
        this.settings = settings;
    }

    @GetMapping(value = "/marketing/pixel", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> pixel() {
        if (!settings.enabled) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        String endpoints = "https://analytics.tiktok.com https://anlytics-ipv6.tiktokw.us";
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self' " + endpoints
                        + "; connect-src " + endpoints + "; img-src " + endpoints
                        + "; frame-ancestors " + settings.origin + "; base-uri 'none'; form-action 'none'")
                .body("""
                        <!doctype html><html lang="es"><head><meta charset="utf-8"><title>Píxel de TikTok</title>
                        <script src="/marketing/pixel.js" defer data-parent="%s" data-pixel="%s"></script></head><body></body></html>
                        """.formatted(HtmlUtils.htmlEscape(settings.origin), HtmlUtils.htmlEscape(settings.pixel)));
    }
}
