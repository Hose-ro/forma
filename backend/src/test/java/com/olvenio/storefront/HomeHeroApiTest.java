package com.olvenio.storefront;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.olvenio.admin.api.AdminController;
import com.olvenio.admin.application.AdminService;
import com.olvenio.catalog.application.CatalogAdminService;
import com.olvenio.orders.application.OrderQueryService;
import com.olvenio.shared.config.AppProperties;
import com.olvenio.shared.config.SecurityConfig;
import com.olvenio.shared.security.AuthUser;
import com.olvenio.storefront.api.HomeController;
import com.olvenio.storefront.application.HomeHeroDtos.HeroC;
import com.olvenio.storefront.application.HomeHeroDtos.HeroView;
import com.olvenio.storefront.application.HomeHeroService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AdminController.class, HomeController.class})
@Import({SecurityConfig.class, HomeHeroApiTest.Props.class})
class HomeHeroApiTest {

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class Props {
    }

    @Autowired MockMvc mvc;
    @MockitoBean AdminService admin;
    @MockitoBean HomeHeroService heroes;
    @MockitoBean CatalogAdminService catalogAdminService;
    @MockitoBean OrderQueryService orderQueryService;
    @MockitoBean UserDetailsService userDetailsService;

    private static AuthUser principal(String role) {
        return new AuthUser(UUID.randomUUID(), role.toLowerCase() + "@example.com", null, role, true);
    }

    private static final String CAMPAIGN = """
            {"enabled": true, "content": {"campaigns": [{"world": "Audio", "eyebrow": "Nuevo",
             "title": "El ritual", "italic": "de escuchar.", "body": "Texto", "productSlug": "tempo"}]}}""";

    @Test
    void storefrontReadsPublishedHeroesWithoutSigningIn() throws Exception {
        when(heroes.published()).thenReturn(List.of(new HeroView("b", true, JsonNodeFactory.instance.objectNode(), Instant.now())));
        mvc.perform(get("/api/v1/home/heroes").servletPath("/api/v1/home/heroes")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].variant").value("b"));
    }

    @Test
    void onlyAdministratorsEditHeroes() throws Exception {
        mvc.perform(get("/api/v1/admin/home/heroes").servletPath("/api/v1/admin/home/heroes")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/admin/home/heroes/c").servletPath("/api/v1/admin/home/heroes/c").with(user(principal("CUSTOMER"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CAMPAIGN)).andExpect(status().isForbidden());
        verify(admin, never()).replaceHero(any(), any(Boolean.class), any());
    }

    @Test
    void administratorReplacesAValidHero() throws Exception {
        mvc.perform(put("/api/v1/admin/home/heroes/c").servletPath("/api/v1/admin/home/heroes/c").with(user(principal("ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CAMPAIGN)).andExpect(status().isOk());
        verify(admin).replaceHero(eq("c"), eq(true), any(HeroC.class));
    }

    @Test
    void rejectsUnsafeOrIncompleteContent() throws Exception {
        String[] invalid = {
                // javascript: and protocol-relative images are not catalog URLs
                """
                {"enabled": true, "content": {"title": "Tu día", "moments": [{"id": "noche", "label": "Noche", "line": "x",
                 "imageUrl": "javascript:alert(1)", "productSlugs": ["luna"]}]}}""",
                """
                {"enabled": true, "content": {"title": "Tu día", "moments": [{"id": "noche", "label": "Noche", "line": "x",
                 "imageUrl": "//evil.example/a.jpg", "productSlugs": ["luna"]}]}}""",
                // unknown moment and too many products
                """
                {"enabled": true, "content": {"title": "Tu día", "moments": [{"id": "madrugada", "label": "x", "line": "x",
                 "imageUrl": "/products/a.jpg", "productSlugs": ["a", "b", "c", "d"]}]}}""",
                // no content at all
                """
                {"enabled": true}""",
        };
        for (String body : invalid) {
            mvc.perform(put("/api/v1/admin/home/heroes/d").servletPath("/api/v1/admin/home/heroes/d").with(user(principal("ADMIN"))).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/admin/home/heroes/c").servletPath("/api/v1/admin/home/heroes/c").with(user(principal("ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"enabled": true, "content": {"campaigns": []}}""")).andExpect(status().isBadRequest());
        verify(admin, never()).replaceHero(any(), any(Boolean.class), any());
    }
}
