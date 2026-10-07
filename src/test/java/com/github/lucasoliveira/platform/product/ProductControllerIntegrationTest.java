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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class
})
class ProductControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ProductService service;

    @MockBean
    JwtService jwtService;

    @Test
    void shouldCreateProduct() throws Exception {
        ProductRequest request = new ProductRequest(
                "NOTE-001",
                "Notebook",
                "Notebook Enterprise",
                new BigDecimal("2500.00"),
                10,
                true
        );

        UUID id = UUID.randomUUID();

        when(service.create(any(ProductRequest.class)))
                .thenReturn(
                        new ProductResponse(
                                id,
                                "NOTE-001",
                                "Notebook",
                                "Notebook Enterprise",
                                new BigDecimal("2500.00"),
                                10,
                                true,
                                null
                        )
                );

        mockMvc.perform(
                        post("/products")
                                .with(user("lucas@example.com"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.sku").value("NOTE-001"))
                .andExpect(jsonPath("$.name").value("Notebook"))
                .andExpect(jsonPath("$.price").value(2500.00))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void shouldFindAllProducts() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.findAll())
                .thenReturn(
                        List.of(
                                new ProductResponse(
                                        id,
                                        "NOTE-001",
                                        "Notebook",
                                        "Notebook Enterprise",
                                        new BigDecimal("2500.00"),
                                        10,
                                        true,
                                        null
                                )
                        )
                );

        mockMvc.perform(
                        get("/products")
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].sku").value("NOTE-001"))
                .andExpect(jsonPath("$[0].stock").value(10));
    }

    @Test
    void shouldFindProductById() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.findById(id))
                .thenReturn(
                        new ProductResponse(
                                id,
                                "NOTE-001",
                                "Notebook",
                                "Notebook Enterprise",
                                new BigDecimal("2500.00"),
                                10,
                                true,
                                null
                        )
                );

        mockMvc.perform(
                        get("/products/{id}", id)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.sku").value("NOTE-001"));
    }

    @Test
    void shouldReturnBadRequestWhenProductRequestIsInvalid() throws Exception {
        ProductRequest request = new ProductRequest(
                "",
                "",
                null,
                null,
                -1,
                null
        );

        mockMvc.perform(
                        post("/products")
                                .with(user("lucas@example.com"))
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
    void shouldReturnNotFoundWhenProductDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();

        when(service.findById(id))
                .thenThrow(new ResourceNotFoundException(
                        "Product not found: " + id
                ));

        mockMvc.perform(
                        get("/products/{id}", id)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value("Product not found: " + id));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isUnauthorized());
    }
}