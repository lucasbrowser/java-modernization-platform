package com.github.lucasoliveira.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lucasoliveira.platform.auth.controller.AuthController;
import com.github.lucasoliveira.platform.auth.dto.LoginRequest;
import com.github.lucasoliveira.platform.auth.dto.LoginResponse;
import com.github.lucasoliveira.platform.auth.dto.RegisterRequest;
import com.github.lucasoliveira.platform.auth.service.AuthService;
import com.github.lucasoliveira.platform.common.exception.GlobalExceptionHandler;
import com.github.lucasoliveira.platform.common.exception.EmailAlreadyRegisteredException;
import com.github.lucasoliveira.platform.common.exception.InvalidCredentialsException;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.common.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;


import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class
})
class AuthControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AuthService service;

    @MockBean
    JwtService jwtService;

    @Test
    void shouldRegisterUser() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "Lucas Oliveira",
                "lucas@example.com",
                "password123"
        );

        mockMvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated());

        org.mockito.Mockito.verify(service)
                .register(any(RegisterRequest.class));
    }

    @Test
    void shouldLoginUser() throws Exception {
        LoginRequest request = new LoginRequest(
                "lucas@example.com",
                "password123"
        );

        when(service.login(any(LoginRequest.class)))
                .thenReturn(
                        new LoginResponse(
                                "jwt-token",
                                "Bearer",
                                3600
                        )
                );

        mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken")
                        .value("jwt-token"))
                .andExpect(jsonPath("$.tokenType")
                        .value("Bearer"))
                .andExpect(jsonPath("$.expiresIn")
                        .value(3600));
    }

    @Test
    void shouldRejectInvalidRegisterRequest() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "",
                "invalid-email",
                "123"
        );

        mockMvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("Validation Error"));
    }

    @Test
    void shouldRejectInvalidLoginRequest() throws Exception {
        LoginRequest request = new LoginRequest(
                "invalid-email",
                ""
        );

        mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("Validation Error"));
    }


    @Test
    void shouldReturnUnauthorizedForInvalidCredentials() throws Exception {
        LoginRequest request = new LoginRequest(
                "lucas@example.com",
                "wrong-password"
        );

        when(service.login(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException("Invalid credentials"));

        mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void shouldReturnConflictWhenEmailAlreadyRegistered() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "Lucas Oliveira",
                "lucas@example.com",
                "password123"
        );

        doThrow(new EmailAlreadyRegisteredException("Email already registered"))
                .when(service)
                .register(any(RegisterRequest.class));

        mockMvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

}