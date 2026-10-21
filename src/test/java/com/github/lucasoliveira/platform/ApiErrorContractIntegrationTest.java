package com.github.lucasoliveira.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lucasoliveira.platform.auth.controller.AuthController;
import com.github.lucasoliveira.platform.auth.service.AuthService;
import com.github.lucasoliveira.platform.common.exception.GlobalExceptionHandler;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.common.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class
})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@TestPropertySource(properties = "security.metrics.password=test-metrics-password")
class ApiErrorContractIntegrationTest {

    private static final String INTERNAL_SENTINEL =
            "SENSITIVE_SENTINEL IllegalStateException select secret_value from private_table";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @Test
    void shouldReturnApiErrorForMalformedJson() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException());
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(400, body.path("status").asInt());
        assertEquals("Bad Request", body.path("error").asText());
        assertEquals("Malformed request body", body.path("message").asText());
        assertTrue(body.path("fields").isNull());
    }

    @Test
    void shouldReturnApiErrorForJsonWithIncompatibleType() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":{\"nested\":true}}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException());
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(400, body.path("status").asInt());
        assertEquals("Bad Request", body.path("error").asText());
        assertEquals("Malformed request body", body.path("message").asText());
        assertTrue(body.path("fields").isNull());
    }

    @Test
    void shouldPreserveFieldValidationDetails() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(400, body.path("status").asInt());
        assertEquals("Validation Error", body.path("error").asText());
        assertEquals("Invalid request data", body.path("message").asText());
        assertTrue(body.path("fields").has("email"));
        assertTrue(body.path("fields").has("password"));
    }

    @Test
    void shouldReturnGenericApiErrorForUnexpectedExceptionWithoutLeakingDetails() throws Exception {
        doThrow(new IllegalStateException(INTERNAL_SENTINEL))
                .when(authService)
                .login(any());

        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"valid-password\"}"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        assertEquals(500, body.path("status").asInt());
        assertEquals("Internal Server Error", body.path("error").asText());
        assertEquals("An unexpected error occurred.", body.path("message").asText());
        assertTrue(body.path("fields").isNull());
        assertFalse(response.contains(INTERNAL_SENTINEL));
        assertFalse(response.contains("IllegalStateException"));
        assertFalse(response.contains("select secret_value"));
        assertFalse(response.contains("at com.github"));
    }

}
