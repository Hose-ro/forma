package com.olvenio.shared;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpProfileTest {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void refusesAuthenticationWhenStarttlsIsUnavailable(String profile) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var worker = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var commands = worker.submit(() -> serveWithoutStarttls(server));

            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                    .withPropertyValues("spring.config.location=classpath:/application-" + profile + ".yml",
                            "spring.mail.host=127.0.0.1", "spring.mail.port=" + server.getLocalPort(),
                            "spring.mail.username=local-test", "spring.mail.password=local-test")
                    .run(context -> {
                        var sender = context.getBean(JavaMailSenderImpl.class);
                        var smtp = sender.getJavaMailProperties();
                        for (String key : List.of("auth", "starttls.enable", "starttls.required", "ssl.checkserveridentity")) {
                            assertThat(smtp.getProperty("mail.smtp." + key)).as(key).isEqualTo("true");
                        }
                        for (String key : List.of("connectiontimeout", "timeout", "writetimeout")) {
                            assertThat(smtp.getProperty("mail.smtp." + key)).as(key).isEqualTo("10000");
                        }
                        assertThatThrownBy(sender::testConnection)
                                .hasMessageContaining("STARTTLS is required");
                    });

            assertThat(commands.get(5, TimeUnit.SECONDS)).contains("EHLO").doesNotContain("AUTH", "MAIL");
        }
    }

    private static List<String> serveWithoutStarttls(ServerSocket server) throws Exception {
        try (var socket = server.accept();
             var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
             var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), US_ASCII))) {
            socket.setSoTimeout(5000);
            output.write("220 localhost test SMTP\r\n");
            output.flush();
            var commands = new ArrayList<String>();
            for (String line; (line = input.readLine()) != null; ) {
                String command = line.split(" ", 2)[0];
                commands.add(command);
                output.write(switch (command) {
                    case "EHLO" -> "250-localhost\r\n250 AUTH PLAIN LOGIN\r\n";
                    case "QUIT" -> "221 closing\r\n";
                    default -> "503 command unavailable\r\n";
                });
                output.flush();
                if ("QUIT".equals(command)) break;
            }
            return commands;
        }
    }
}
