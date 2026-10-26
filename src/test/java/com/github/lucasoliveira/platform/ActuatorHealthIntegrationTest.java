package com.github.lucasoliveira.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0"
)
@ActiveProfiles("test")
class ActuatorHealthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("provision-test-roles.sql")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldExposeHealthEndpointWithoutAuthentication() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertEquals(200, response.statusCode());
        assertHealthStatusUp(response.body());
        assertDoesNotExposeDetails(response.body());
    }

    @Test
    void shouldExposeLivenessProbeWithoutAuthentication() throws Exception {
        HttpResponse<String> response =
            get("/actuator/health/liveness");

        assertEquals(200, response.statusCode());
        assertHealthStatusUp(response.body());
        assertDoesNotExposeDetails(response.body());
    }

    @Test
    void shouldExposeReadinessProbeWithoutAuthentication() throws Exception {
        HttpResponse<String> response =
            get("/actuator/health/readiness");

        assertEquals(200, response.statusCode());
        assertHealthStatusUp(response.body());
        assertDoesNotExposeDetails(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        URI uri = URI.create(
            "http://localhost:" + managementPort + path
        );

        HttpRequest request = HttpRequest.newBuilder(uri)
            .GET()
            .build();

        return HTTP_CLIENT.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private void assertHealthStatusUp(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);

        assertNotNull(json);
        assertTrue(json.has("status"), "A resposta deve conter o status");
        assertEquals("UP", json.get("status").asText());
    }

    private void assertDoesNotExposeDetails(String body) {
        assertFalse(
            body.contains("\"details\""),
            "A resposta não deve expor detalhes de saúde"
        );
        assertFalse(
            body.contains("\"components\""),
            "A resposta não deve expor componentes internos"
        );
        assertFalse(
            body.contains("jdbc:postgresql"),
            "A resposta não deve expor a URL do banco"
        );
        assertFalse(
            body.contains("test-metrics-password"),
            "A resposta não deve expor credenciais"
        );
    }
}

