package com.github.lucasoliveira.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lucasoliveira.platform.auth.controller.AdminUserCustomerLinkController;
import com.github.lucasoliveira.platform.auth.service.UserCustomerLinkResult;
import com.github.lucasoliveira.platform.auth.service.UserCustomerLinkService;
import com.github.lucasoliveira.platform.common.exception.GlobalExceptionHandler;
import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.common.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserCustomerLinkController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, AdminUserCustomerLinkControllerIntegrationTest.JwtTestConfiguration.class})
@TestPropertySource(properties = "security.metrics.password=test-metrics-password")
class AdminUserCustomerLinkControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtService jwtService;
    @MockBean UserCustomerLinkService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @Test
    void shouldAssignCustomerLink() throws Exception {
        when(service.assignCustomer(eq(userId), eq(customerId), eq("Approved")))
                .thenReturn(new UserCustomerLinkResult(userId, customerId, true));

        performAssign("{\"customerId\":\"" + customerId + "\",\"justification\":\" Approved \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.changed").value(true));

        verify(service).assignCustomer(userId, customerId, "Approved");
    }

    @Test
    void shouldChangeCustomerLink() throws Exception {
        UUID nextCustomerId = UUID.randomUUID();
        when(service.assignCustomer(userId, nextCustomerId, "Reallocation"))
                .thenReturn(new UserCustomerLinkResult(userId, nextCustomerId, true));

        performAssign("{\"customerId\":\"" + nextCustomerId + "\",\"justification\":\"Reallocation\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(nextCustomerId.toString()))
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void shouldReturnNoOpWhenAssignmentIsRepeated() throws Exception {
        when(service.assignCustomer(userId, customerId, "Same"))
                .thenReturn(new UserCustomerLinkResult(userId, customerId, false));

        performAssign("{\"customerId\":\"" + customerId + "\",\"justification\":\"Same\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));
    }

    @Test
    void shouldRemoveCustomerAndSerializeNullCustomerId() throws Exception {
        when(service.removeCustomer(userId, "Approved removal"))
                .thenReturn(new UserCustomerLinkResult(userId, null, true));

        performRemoval("{\"justification\":\"Approved removal\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").hasJsonPath())
                .andExpect(jsonPath("$.customerId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void shouldReturnNoOpWhenAlreadyUnlinked() throws Exception {
        when(service.removeCustomer(userId, "Repeated"))
                .thenReturn(new UserCustomerLinkResult(userId, null, false));

        performRemoval("{\"justification\":\"Repeated\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").hasJsonPath())
                .andExpect(jsonPath("$.changed").value(false));
    }

    @Test
    void shouldRejectInvalidRequestBodies() throws Exception {
        performAssign("{\"customerId\":\"" + customerId + "\"}").andExpect(status().isBadRequest());
        performAssign("{\"customerId\":\"" + customerId + "\",\"justification\":\"  \"}").andExpect(status().isBadRequest());
        performAssign("{\"customerId\":\"" + customerId + "\",\"justification\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest());
        performAssign("{\"justification\":\"Reason\"}").andExpect(status().isBadRequest());
        performAssign("{\"customerId\":\"invalid\",\"justification\":\"Reason\"}").andExpect(status().isBadRequest());
        performRemoval("{}").andExpect(status().isBadRequest());
        performRemoval("{\"justification\":\" \"}").andExpect(status().isBadRequest());
        performRemoval("{\"justification\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void shouldAcceptJustificationOverRawLimitWhenStrippedValueFits() throws Exception {
        String justification = " ".repeat(5) + "x".repeat(500) + " ".repeat(5);
        when(service.removeCustomer(userId, "x".repeat(500)))
                .thenReturn(new UserCustomerLinkResult(userId, null, false));

        performRemoval("{\"justification\":\"" + justification + "\"}")
                .andExpect(status().isOk());
        verify(service).removeCustomer(userId, "x".repeat(500));
    }

    @Test
    void shouldMapNotFoundAndInvalidTargetToApiError() throws Exception {
        when(service.assignCustomer(userId, customerId, "Reason"))
                .thenThrow(new ResourceNotFoundException("Customer not found"));
        performAssign("{\"customerId\":\"" + customerId + "\",\"justification\":\"Reason\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));

        when(service.assignCustomer(userId, customerId, "Missing user"))
                .thenThrow(new ResourceNotFoundException("User not found"));
        performAssign("{\"customerId\":\"" + customerId + "\",\"justification\":\"Missing user\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));

        when(service.removeCustomer(userId, "Reason"))
                .thenThrow(new IllegalArgumentException("Target user must have USER role"));
        performRemoval("{\"justification\":\"Reason\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void shouldMapUnexpectedExceptionsThroughExistingGlobalHandler() throws Exception {
        when(service.removeCustomer(userId, "Reason")).thenThrow(new IllegalStateException("boom"));
        performRemoval("{\"justification\":\"Reason\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }

    @Test
    void shouldRejectAnonymousAndNonAdminRequests() throws Exception {
        mockMvc.perform(put("/admin/users/{userId}/customer-link", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/admin/users/{userId}/customer-link", userId)
                        .header("Authorization", "Bearer " + jwtService.generateToken("user@example.com", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"justification\":\"Reason\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private org.springframework.test.web.servlet.ResultActions performAssign(String body) throws Exception {
        return mockMvc.perform(put("/admin/users/{userId}/customer-link", userId)
                .header("Authorization", "Bearer " + jwtService.generateToken("admin@example.com", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions performRemoval(String body) throws Exception {
        return mockMvc.perform(post("/admin/users/{userId}/customer-link/removal", userId)
                .header("Authorization", "Bearer " + jwtService.generateToken("admin@example.com", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @TestConfiguration
    static class JwtTestConfiguration {
        @Bean JwtService jwtService() {
            return new JwtService("customer-link-controller-test-secret-at-least-32-bytes", 3_600_000);
        }
    }
}
