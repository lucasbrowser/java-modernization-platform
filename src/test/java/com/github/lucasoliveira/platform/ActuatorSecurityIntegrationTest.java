package com.github.lucasoliveira.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "management.prometheus.metrics.export.enabled=true"
    }
)
@ActiveProfiles("test")
class ActuatorSecurityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final String METRICS_USERNAME = "prometheus";
    private static final String METRICS_PASSWORD = "test-metrics-password";

    @LocalManagementPort
    private int managementPort;

    @Test
    void shouldRejectPrometheusScrapeWithoutCredentials() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertEquals(401, response.statusCode());
    }

    @Test
    void shouldRejectPrometheusScrapeWithInvalidCredentials() throws Exception {
        HttpResponse<String> response = getWithBasicAuth(
            "/actuator/prometheus",
            METRICS_USERNAME,
            "wrong-password"
        );

        assertEquals(401, response.statusCode());
    }

    @Test
    void shouldAllowPrometheusScrapeWithValidCredentials() throws Exception {
        HttpResponse<String> response = getWithBasicAuth(
            "/actuator/prometheus",
            METRICS_USERNAME,
            METRICS_PASSWORD
        );

        assertEquals(
            200,
            response.statusCode(),
            () -> "Status recebido: " + response.statusCode()
                + "; Content-Type: "
                + response.headers()
                    .firstValue("Content-Type")
                    .orElse("<ausente>")
                + "; resposta: " + response.body()
        );
        assertTrue(
            response.body().contains("# HELP")
                || response.body().contains("# TYPE"),
            "A resposta deve conter métricas no formato Prometheus"
        );
    }

    @Test
    void shouldRejectInfoEndpointWithoutAuthentication() throws Exception {
        HttpResponse<String> response = get("/actuator/info");

        assertEquals(401, response.statusCode());
    }

    @Test
    void shouldRejectMetricsEndpointWithoutAuthentication() throws Exception {
        HttpResponse<String> response = get("/actuator/metrics");

        assertEquals(401, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .GET()
            .build();

        return HTTP_CLIENT.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> getWithBasicAuth(
        String path,
        String username,
        String password
    ) throws Exception {
        String credentials = username + ":" + password;
        String encodedCredentials = Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8)
        );

        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Basic " + encodedCredentials)
            .GET()
            .build();

        return HTTP_CLIENT.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + managementPort + path);
    }
}
