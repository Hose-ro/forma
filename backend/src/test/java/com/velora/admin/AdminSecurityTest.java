package com.velora.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.velora.admin.api.AdminController;
import com.velora.admin.application.AdminService;
import com.velora.catalog.application.CatalogAdminService;
import com.velora.orders.application.OrderQueryService;
import com.velora.shared.config.AppProperties;
import com.velora.shared.config.SecurityConfig;
import com.velora.shared.security.AuthUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, AdminSecurityTest.Props.class})
class AdminSecurityTest {

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class Props {
    }

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AdminService adminService;
    @MockitoBean
    CatalogAdminService catalogAdminService;
    @MockitoBean
    OrderQueryService orderQueryService;
    @MockitoBean
    UserDetailsService userDetailsService;

    private static AuthUser principal(String role) {
        return new AuthUser(UUID.randomUUID(), role.toLowerCase() + "@example.com", null, role, true);
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerGets403() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard").with(user(principal("CUSTOMER")))).andExpect(status().isForbidden());
    }

    @Test
    void adminIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard").with(user(principal("ADMIN")))).andExpect(status().isOk());
    }

    @Test
    void unsafeAdminRequestWithoutCsrfIsRejected() throws Exception {
        String path = "/api/v1/admin/products/" + UUID.randomUUID();
        mvc.perform(delete(path).with(user(principal("ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(user(principal("ADMIN"))).with(csrf())).andExpect(status().isOk());
    }
}
