package com.github.lucasoliveira.platform;

import com.github.lucasoliveira.platform.auth.controller.AuthController;
import com.github.lucasoliveira.platform.auth.service.AuthService;
import com.github.lucasoliveira.platform.common.exception.GlobalExceptionHandler;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.common.security.SecurityConfig;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @Test
    void shouldCharacterizeMalformedJsonInMvcRequestPipeline() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException());
        assertTrue(result.getResponse().getContentAsString().isEmpty(),
                "O pipeline MVC atual não serializa um ApiError para erro de leitura");
    }

    @Test
    void shouldCharacterizeJsonWithIncompatibleTypeInMvcRequestPipeline() throws Exception {
        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":{\"nested\":true}}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException());
        assertTrue(result.getResponse().getContentAsString().isEmpty(),
                "O pipeline MVC atual não serializa um ApiError para erro de leitura");
    }

    @Test
    void shouldCharacterizeUnexpectedExceptionEscapingMvcWithoutAnErrorBody() {
        org.mockito.Mockito.doThrow(new IllegalStateException(INTERNAL_SENTINEL))
                .when(authService)
                .login(org.mockito.ArgumentMatchers.any());

        ServletException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ServletException.class,
                () -> mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"valid-password\"}"))
        );

        assertTrue(thrown.getMessage().contains(INTERNAL_SENTINEL));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

}
