package com.github.lucasoliveira.platform.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lucasoliveira.platform.common.exception.GlobalExceptionHandler;
import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.common.security.SecurityConfig;
import com.github.lucasoliveira.platform.product.controller.ProductController;
import com.github.lucasoliveira.platform.product.dto.ProductRequest;
import com.github.lucasoliveira.platform.product.dto.ProductResponse;
import com.github.lucasoliveira.platform.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
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

@WebMvcTest(ProductController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        ProductControllerIntegrationTest.JwtTestConfiguration.class,
        SecurityContextProbeController.class
})
@TestPropertySource(properties = "security.metrics.password=test-metrics-password")
class ProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService service;

    @Autowired
    private JwtService jwtService;

    @Test
    void shouldCreateProduct() throws Exception {

        ProductRequest request = new ProductRequest(
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true
        );

        UUID id = UUID.randomUUID();

        ProductResponse response = new ProductResponse(
                id,
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true,
                OffsetDateTime.now()
        );

        when(service.create(any(ProductRequest.class)))
                .thenReturn(response);
        String token = jwtService.generateToken("admin@example.com", "ADMIN");

        mockMvc.perform(
                        post("/products")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(id.toString()))
                .andExpect(jsonPath("$.sku")
                        .value("SKU-001"))
                .andExpect(jsonPath("$.name")
                        .value("Product 1"));

        verify(service).create(request);
    }

    @Test
    void shouldFindAllProducts() throws Exception {

        UUID id = UUID.randomUUID();

        ProductResponse response = new ProductResponse(
                id,
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true,
                OffsetDateTime.now()
        );

        Page<ProductResponse> page =
                new PageImpl<>(List.of(response));

        when(service.findAll(any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(
                        get("/products")
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id")
                        .value(id.toString()))
                .andExpect(jsonPath("$.content[0].sku")
                        .value("SKU-001"))
                .andExpect(jsonPath("$.content[0].name")
                        .value("Product 1"))
                .andExpect(jsonPath("$.totalElements")
                        .value(1))
                .andExpect(jsonPath("$.totalPages")
                        .value(1));
    }

    @Test
    void shouldFindProductById() throws Exception {

        UUID id = UUID.randomUUID();

        ProductResponse response = new ProductResponse(
                id,
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true,
                OffsetDateTime.now()
        );

        when(service.findById(id))
                .thenReturn(response);

        mockMvc.perform(
                        get("/products/{id}", id)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(id.toString()))
                .andExpect(jsonPath("$.sku")
                        .value("SKU-001"));
    }

    @Test
    void shouldReturnBadRequestWhenProductRequestIsInvalid()
            throws Exception {

        ProductRequest request = new ProductRequest(
                "",
                "",
                "",
                null,
                null,
                null
        );

        mockMvc.perform(
                        post("/products")
                                .header("Authorization", "Bearer " + jwtService.generateToken(
                                        "admin@example.com", "ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("Validation Error"))
                .andExpect(jsonPath("$.fields.sku").exists())
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.price").exists())
                .andExpect(jsonPath("$.fields.stock").exists())
                .andExpect(jsonPath("$.fields.active").exists());
    }

    @Test
    void shouldReturnNotFoundWhenProductDoesNotExist()
            throws Exception {

        UUID id = UUID.randomUUID();

        when(service.findById(id))
                .thenThrow(
                        new ResourceNotFoundException(
                                "Product not found: " + id
                        )
                );

        mockMvc.perform(
                        get("/products/{id}", id)
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
                        get("/products")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectRequestWithInvalidBearerToken() throws Exception {
        mockMvc.perform(
                        get("/products")
                                .header("Authorization", "Bearer not-a-jwt")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectProductCreationForUserJwt() throws Exception {
        ProductRequest request = validProductRequest();
        String token = jwtService.generateToken("user@example.com", "USER");

        mockMvc.perform(
                        post("/products")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectAnonymousProductCreation() throws Exception {
        mockMvc.perform(
                        post("/products")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validProductRequest()))
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectProductCreationWithInvalidBearerToken() throws Exception {
        mockMvc.perform(
                        post("/products")
                                .header("Authorization", "Bearer not-a-jwt")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validProductRequest()))
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void shouldAllowAdminAndUserJwtsToReadProducts() throws Exception {
        UUID id = UUID.randomUUID();
        ProductResponse response = new ProductResponse(
                id,
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true,
                OffsetDateTime.now()
        );
        when(service.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(service.findById(id)).thenReturn(response);

        for (String role : List.of("ADMIN", "USER")) {
            String token = jwtService.generateToken(role.toLowerCase() + "@example.com", role);

            mockMvc.perform(
                            get("/products")
                                    .header("Authorization", "Bearer " + token)
                    )
                    .andExpect(status().isOk());

            mockMvc.perform(
                            get("/products/{id}", id)
                                    .header("Authorization", "Bearer " + token)
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()));
        }

        verify(service, times(2)).findAll(any(Pageable.class));
        verify(service, times(2)).findById(id);
    }

    @Test
    void shouldAuthenticateProtectedRequestWithValidJwt() throws Exception {
        String email = "jwt-user@example.com";
        String token = jwtService.generateToken(email, "USER");

        mockMvc.perform(
                        get("/test/security-context")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(email))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));

        when(service.findAll(any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(
                        get("/products")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk());

        verify(service).findAll(any(Pageable.class));
    }

    @Test
    void shouldApplyPaginationAndDescendingSortingForProducts() throws Exception {
        ProductResponse product1 = new ProductResponse(
                UUID.randomUUID(),
                "SKU-001",
                "Notebook",
                "Notebook profissional",
                new BigDecimal("5000.00"),
                10,
                true,
                OffsetDateTime.now()
        );

        ProductResponse product2 = new ProductResponse(
                UUID.randomUUID(),
                "SKU-002",
                "Mouse",
                "Mouse sem fio",
                new BigDecimal("100.00"),
                20,
                true,
                OffsetDateTime.now()
        );

        Pageable pageable = PageRequest.of(
                0,
                2,
                Sort.by(Sort.Direction.DESC, "price")
        );

        Page<ProductResponse> page = new PageImpl<>(
                List.of(product1, product2),
                pageable,
                5
        );

        when(service.findAll(any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(
                get("/products")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "price,desc")
                        .with(user("lucas@example.com"))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].price").value(5000.00))
                .andExpect(jsonPath("$.content[1].price").value(100.00))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(service).findAll(pageableCaptor.capture());

        Pageable capturedPageable = pageableCaptor.getValue();

        assertThat(capturedPageable.getPageNumber()).isEqualTo(0);
        assertThat(capturedPageable.getPageSize()).isEqualTo(2);

        assertThat(capturedPageable.getSort().getOrderFor("price"))
                .isNotNull();

        assertThat(capturedPageable.getSort().getOrderFor("price").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @TestConfiguration
    static class JwtTestConfiguration {
        @Bean
        JwtService jwtService() {
            return new JwtService(
                    "product-controller-test-secret-with-at-least-32-bytes",
                    3_600_000
            );
        }
    }

    private ProductRequest validProductRequest() {
        return new ProductRequest(
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true
        );
    }
}
