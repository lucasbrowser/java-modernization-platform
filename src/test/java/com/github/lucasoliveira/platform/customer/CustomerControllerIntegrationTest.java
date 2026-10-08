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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class CustomerControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CustomerService service;

    @MockBean
    private JwtService jwtService;

    @Test
    void shouldCreateCustomer() throws Exception {

        CustomerRequest request = new CustomerRequest(
                "Lucas",
                "lucas@example.com",
                "12345678900",
                "65999999999"
        );

        UUID id = UUID.randomUUID();

        CustomerResponse response = new CustomerResponse(
                id,
                "Lucas",
                "lucas@example.com",
                "12345678900",
                "65999999999",
                OffsetDateTime.now()
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
                .andExpect(jsonPath("$.name").value("Lucas"))
                .andExpect(jsonPath("$.email").value("lucas@example.com"));
    }

    @Test
    void shouldFindAllCustomers() throws Exception {

        UUID id = UUID.randomUUID();

        CustomerResponse response = new CustomerResponse(
                id,
                "Lucas",
                "lucas@example.com",
                "12345678900",
                "65999999999",
                OffsetDateTime.now()
        );

        Page<CustomerResponse> page =
                new PageImpl<>(List.of(response));

        when(service.findAll(any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(
                        get("/customers")
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id")
                        .value(id.toString()))
                .andExpect(jsonPath("$.content[0].name")
                        .value("Lucas"))
                .andExpect(jsonPath("$.totalElements")
                        .value(1))
                .andExpect(jsonPath("$.totalPages")
                        .value(1));
    }

    @Test
    void shouldFindCustomerById() throws Exception {

        UUID id = UUID.randomUUID();

        CustomerResponse response = new CustomerResponse(
                id,
                "Lucas",
                "lucas@example.com",
                "12345678900",
                "65999999999",
                OffsetDateTime.now()
        );

        when(service.findById(id))
                .thenReturn(response);

        mockMvc.perform(
                        get("/customers/{id}", id)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(id.toString()))
                .andExpect(jsonPath("$.name")
                        .value("Lucas"));
    }

    @Test
    void shouldReturnBadRequestWhenCustomerRequestIsInvalid()
            throws Exception {

        CustomerRequest request = new CustomerRequest(
                "",
                "invalid-email",
                "",
                ""
        );

        mockMvc.perform(
                        post("/customers")
                                .with(user("lucas@example.com"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("Validation Error"))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.document").exists());
    }

    @Test
    void shouldReturnNotFoundWhenCustomerDoesNotExist()
            throws Exception {

        UUID id = UUID.randomUUID();

        when(service.findById(id))
                .thenThrow(
                        new ResourceNotFoundException(
                                "Customer not found: " + id
                        )
                );

        mockMvc.perform(
                        get("/customers/{id}", id)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error")
                        .value("Not Found"));
    }

    @Test
    void shouldRejectUnauthenticatedRequest()
            throws Exception {

        mockMvc.perform(
                        get("/customers")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldApplyPaginationAndSortingForCustomers() throws Exception {
        CustomerResponse customer1 = new CustomerResponse(
                UUID.randomUUID(),
                "Ana",
                "ana@example.com",
                "11111111111",
                "65999999991",
                OffsetDateTime.now()
        );

        CustomerResponse customer2 = new CustomerResponse(
                UUID.randomUUID(),
                "Lucas",
                "lucas@example.com",
                "22222222222",
                "65999999992",
                OffsetDateTime.now()
        );

        Pageable pageable = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.ASC, "name")
        );

        Page<CustomerResponse> page = new PageImpl<>(
                List.of(customer1, customer2),
                pageable,
                5
        );

        when(service.findAll(any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(
                get("/customers")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "name,asc")
                        .with(user("lucas@example.com"))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].name").value("Ana"))
                .andExpect(jsonPath("$.content[1].name").value("Lucas"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(service).findAll(pageableCaptor.capture());

        Pageable capturedPageable = pageableCaptor.getValue();

        assertThat(capturedPageable.getPageNumber()).isEqualTo(1);
        assertThat(capturedPageable.getPageSize()).isEqualTo(2);

        assertThat(capturedPageable.getSort().getOrderFor("name"))
                .isNotNull();

        assertThat(capturedPageable.getSort().getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }
}