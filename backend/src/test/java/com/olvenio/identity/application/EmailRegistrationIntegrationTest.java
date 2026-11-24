package com.olvenio.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "app.mail.from=olvenio@example.com", "app.payments.frontend-url=http://localhost:3000",
        "app.mail.initial-delay=3600000", "app.admin.email=", "app.admin.password="
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "OLVENIO_REGISTRATION_REGRESSION", matches = "true")
class EmailRegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRecoveryService mailWorker;
    @Autowired JdbcTemplate db;
    @Autowired com.olvenio.shared.security.SecretTokens tokens;
    @MockitoBean JavaMailSender mail;

    @Test void deliversAndResendsCodeThenCreatesExactlyOneVerifiedAccount() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        MockHttpSession session = new MockHttpSession();
        String oldSessionId = session.getId();
        UUID id = begin(email, session);
        mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isNoContent());
        mvc.perform(write("/login", "{\"email\":\"%s\",\"password\":\"registration-password\"}".formatted(email), session))
                .andExpect(status().isUnauthorized());
        mailWorker.deliverEmails();
        db.update("update account_emails set sent_at=now()-interval '2 minutes' where id=?", id);
        mvc.perform(write("/register/resend", "{\"challengeId\":\"%s\"}".formatted(id), session))
                .andExpect(status().isAccepted());
        mailWorker.deliverEmails();

        var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(2)).send(messages.capture());
        String code = messages.getValue().getText().split("\n\n")[1];
        assertThat(code).matches("[0-9]{6}");
        assertThat(messages.getAllValues()).allSatisfy(message -> {
            assertThat(message.getTo()).containsExactly(email);
            assertThat(message.getText()).contains("\n\n" + code + "\n\n");
        });
        String confirmation = confirmation(id, code);
        mvc.perform(post("/api/v1/auth/register/verify").session(session).contentType("application/json").content(confirmation))
                .andExpect(status().isForbidden());
        mvc.perform(write("/register/verify", confirmation, session))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.emailVerified").value(true));
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        mvc.perform(get("/api/v1/auth/session").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(write("/register/verify", confirmation, new MockHttpSession()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void rejectsExpiredAndExhaustedChallenges() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UUID exhausted = begin(UUID.randomUUID() + "@example.com", session);
        String code = tokens.registrationCode(exhausted);
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            mvc.perform(write("/register/verify", confirmation(exhausted, wrong), session))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(write("/register/verify", confirmation(exhausted, code), session))
                .andExpect(status().isUnprocessableEntity());
        UUID expired = begin(UUID.randomUUID() + "@example.com", session);
        db.update("update account_emails set expires_at=now()-interval '1 second' where id=?", expired);
        mvc.perform(write("/register/verify", confirmation(expired, tokens.registrationCode(expired)), session))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isNoContent());
    }

    private UUID begin(String email, MockHttpSession session) throws Exception {
        String body = """
                {"email":"%s","firstName":"Test","lastName":"User","termsAccepted":true,
                 "password":"registration-password","passwordConfirmation":"registration-password"}
                """.formatted(email);
        String response = mvc.perform(write("/register", body, session))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("challengeId").asText());
    }

    private static MockHttpServletRequestBuilder write(String path, String body, MockHttpSession session) {
        return post("/api/v1/auth" + path).session(session).with(csrf()).contentType("application/json").content(body);
    }

    private static String confirmation(UUID id, String code) {
        return "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(id, code);
    }
}
