package com.github.lucasoliveira.platform.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lucasoliveira.platform.common.exception.GlobalExceptionHandler;
import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.common.security.SecurityConfig;
import com.github.lucasoliveira.platform.customer.controller.CustomerController;
import com.github.lucasoliveira.platform.customer.dto.CustomerRequest;
import com.github.lucasoliveira.platform.customer.dto.CustomerResponse;
import com.github.lucasoliveira.platform.customer.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class
})
class CustomerControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    CustomerService service;

    @MockBean
    JwtService jwtService;

    @Test
    void shouldCreateCustomer() throws Exception {
        CustomerRequest request = new CustomerRequest(
                "Lucas Oliveira",
                "lucas@example.com",
                "12345678900",
                "65999999999"
        );

        UUID id = UUID.randomUUID();

        CustomerResponse response = new CustomerResponse(
                id,
                "Lucas Oliveira",
                "lucas@example.com",
                "12345678900",
                "65999999999",
                null
        );

        when(service.create(any(CustomerRequest.class)))
                .thenReturn(response);

        mockMvc.perform(
                        post("/customers")
                                .with(user("lucas@example.com"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Lucas Oliveira"))
                .andExpect(jsonPath("$.email").value("lucas@example.com"))
                .andExpect(jsonPath("$.document").value("12345678900"));
    }

    @Test
    void shouldFindAllCustomers() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.findAll())
                .thenReturn(List.of(
                        new CustomerResponse(
                                id,
                                "Cliente Teste",
                                "cliente@example.com",
                                "12345678900",
                                "65999999999",
                                null
                        )
                ));

        mockMvc.perform(
                        get("/customers")
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].name").value("Cliente Teste"))
                .andExpect(jsonPath("$[0].email").value("cliente@example.com"));
    }

    @Test
    void shouldFindCustomerById() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.findById(id))
                .thenReturn(
                        new CustomerResponse(
                                id,
                                "Cliente Teste",
                                "cliente@example.com",
                                "12345678900",
                                "65999999999",
                                null
                        )
                );

        mockMvc.perform(
                        get("/customers/{id}", id)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Cliente Teste"));
    }

    @Test
    void shouldReturnBadRequestWhenCustomerRequestIsInvalid() throws Exception {
        CustomerRequest request = new CustomerRequest(
                "",
                "invalid-email",
                "",
                null
        );

        mockMvc.perform(
                        post("/customers")
                                .with(user("lucas@example.com"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Error"))
                .andExpect(jsonPath("$.message")
                        .value("Invalid request data"))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.document").exists());
    }

    @Test
    void shouldReturnNotFoundWhenCustomerDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.findById(id))
                .thenThrow(new ResourceNotFoundException(
                        "Customer not found: " + id
                ));

        mockMvc.perform(
                        get("/customers/{id}", id)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message")
                        .value("Customer not found: " + id));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/customers"))
                .andExpect(status().isUnauthorized());
    }
}