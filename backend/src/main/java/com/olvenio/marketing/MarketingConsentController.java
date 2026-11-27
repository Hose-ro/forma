package com.olvenio.marketing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/marketing")
class MarketingConsentController {
    private final TikTokConsent consent;

    MarketingConsentController(TikTokConsent consent) {
        this.consent = consent;
    }

    record Choice(@NotNull Boolean allowed, @Size(max = 512) String ttclid) {}

    @GetMapping("/consent")
    TikTokConsent.Preference status(HttpServletRequest request) {
        return consent.status(request);
    }

    @PostMapping("/consent")
    TikTokConsent.Preference update(@Valid @RequestBody Choice choice, HttpServletRequest request,
                                    HttpServletResponse response) {
        return consent.update(request, response, choice.allowed(), choice.ttclid());
    }

    record Attribution(@Size(max = 512) String ttp, @Size(max = 512) String ttclid) {}

    @PostMapping("/attribution")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void attribute(@Valid @RequestBody Attribution attribution, HttpServletRequest request) {
        consent.attribute(request, attribution.ttp(), attribution.ttclid());
    }
}
