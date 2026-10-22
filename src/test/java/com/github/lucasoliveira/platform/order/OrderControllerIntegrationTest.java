package com.github.lucasoliveira.platform.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lucasoliveira.platform.common.exception.GlobalExceptionHandler;
import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.common.security.SecurityConfig;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.order.controller.OrderController;
import com.github.lucasoliveira.platform.order.dto.OrderItemRequest;
import com.github.lucasoliveira.platform.order.dto.OrderRequest;
import com.github.lucasoliveira.platform.order.dto.OrderResponse;
import com.github.lucasoliveira.platform.order.entity.OrderStatus;
import com.github.lucasoliveira.platform.order.service.OrderCancellationConflictException;
import com.github.lucasoliveira.platform.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.nullValue;

@WebMvcTest(OrderController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class
})
@TestPropertySource(properties = "security.metrics.password=test-metrics-password")
class OrderControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    OrderService service;

    @MockBean
    JwtService jwtService;

    @Test
    void shouldCreateOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        OrderRequest request = new OrderRequest(
                customerId,
                List.of(
                        new OrderItemRequest(productId, 2)
                )
        );

        OrderResponse response = new OrderResponse(
                orderId,
                customerId,
                OrderStatus.CREATED,
                new BigDecimal("5000.00"),
                null,
                List.of(
                        new OrderResponse.OrderItemResponse(
                                productId,
                                "NOTE-001",
                                "Notebook",
                                2,
                                new BigDecimal("2500.00"),
                                new BigDecimal("5000.00")
                        )
                )
        );

        when(service.create(any(OrderRequest.class), anyString()))
                .thenReturn(response);

        mockMvc.perform(
                        post("/orders")
                                .with(user("lucas@example.com"))
                                .header("Idempotency-Key", "test-key-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.customerId")
                        .value(customerId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(5000.00))
                .andExpect(jsonPath("$.items[0].productId")
                        .value(productId.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice")
                        .value(2500.00))
                .andExpect(jsonPath("$.items[0].total")
                        .value(5000.00));
    }

    @Test
    void shouldReturnBadRequestWhenIdempotencyKeyIsMissing() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        OrderRequest request = new OrderRequest(
                customerId,
                List.of(
                        new OrderItemRequest(productId, 1)
                )
        );

        mockMvc.perform(
                        post("/orders")
                                .with(user("lucas@example.com"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void shouldFindOrderById() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(service.findById(orderId))
                .thenReturn(
                        new OrderResponse(
                                orderId,
                                customerId,
                                OrderStatus.CREATED,
                                new BigDecimal("100.00"),
                                null,
                                List.of(
                                        new OrderResponse.OrderItemResponse(
                                                productId,
                                                "MOUSE-001",
                                                "Mouse",
                                                1,
                                                new BigDecimal("100.00"),
                                                new BigDecimal("100.00")
                                        )
                                )
                        )
                );

        mockMvc.perform(
                        get("/orders/{id}", orderId)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(100.00));
    }

    @Test
    void shouldFindOrdersByCustomer() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(service.findByCustomer(customerId))
                .thenReturn(
                        List.of(
                                new OrderResponse(
                                        orderId,
                                        customerId,
                                        OrderStatus.CREATED,
                                        new BigDecimal("100.00"),
                                        null,
                                        List.of()
                                )
                        )
                );

        mockMvc.perform(
                        get("/orders/customer/{customerId}", customerId)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id")
                        .value(orderId.toString()))
                .andExpect(jsonPath("$[0].customerId")
                        .value(customerId.toString()))
                .andExpect(jsonPath("$[0].status")
                        .value("CREATED"));
    }

    @Test
    void shouldCancelOrderAndReturnExistingOrderResponse() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderResponse response = new OrderResponse(
                orderId,
                customerId,
                OrderStatus.CANCELLED,
                new BigDecimal("200.00"),
                null,
                List.of(new OrderResponse.OrderItemResponse(
                        productId,
                        "MOUSE-001",
                        "Mouse",
                        2,
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00"))));
        when(service.cancel(orderId)).thenReturn(response);

        mockMvc.perform(post("/orders/{id}/cancel", orderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.totalAmount").value(200.00))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        verify(service).cancel(orderId);
    }

    @Test
    void shouldReturnConflictApiErrorForNonCancellableOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(service.cancel(orderId)).thenThrow(
                new OrderCancellationConflictException("internal state detail"));

        mockMvc.perform(post("/orders/{id}/cancel", orderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message")
                        .value("Order cannot be cancelled in its current state"))
                .andExpect(jsonPath("$.fields").value(nullValue()));
    }

    @Test
    void shouldReturnNotFoundForMissingOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(service.cancel(orderId)).thenThrow(new ResourceNotFoundException("Order not found"));

        mockMvc.perform(post("/orders/{id}/cancel", orderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Order not found"));
    }

    @Test
    void shouldReturnSameNotFoundApiErrorForUnauthorizedOrder() throws Exception {
        UUID missingOrderId = UUID.randomUUID();
        UUID unauthorizedOrderId = UUID.randomUUID();
        when(service.cancel(missingOrderId)).thenThrow(new ResourceNotFoundException("Order not found"));
        when(service.cancel(unauthorizedOrderId)).thenThrow(new ResourceNotFoundException("Order not found"));

        mockMvc.perform(post("/orders/{id}/cancel", missingOrderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Order not found"));

        mockMvc.perform(post("/orders/{id}/cancel", unauthorizedOrderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Order not found"));
    }

    @Test
    void shouldReturnOkWhenCancelRequestIsRepeatedForCancelledOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderResponse response = new OrderResponse(
                orderId,
                UUID.randomUUID(),
                OrderStatus.CANCELLED,
                BigDecimal.ZERO,
                null,
                List.of());
        when(service.cancel(orderId)).thenReturn(response);

        mockMvc.perform(post("/orders/{id}/cancel", orderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/orders/{id}/cancel", orderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(service, org.mockito.Mockito.times(2)).cancel(orderId);
    }

    @Test
    void shouldReturnBadRequestForMalformedOrderId() throws Exception {
        mockMvc.perform(post("/orders/not-a-uuid/cancel")
                        .with(user("lucas@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectUnauthenticatedCancellation() throws Exception {
        mockMvc.perform(post("/orders/{id}/cancel", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void shouldKeepUnrelatedIllegalStateExceptionAsInternalServerError() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(service.cancel(orderId)).thenThrow(new IllegalStateException("private detail"));

        mockMvc.perform(post("/orders/{id}/cancel", orderId)
                        .with(user("lucas@example.com")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }

    @Test
    void shouldReturnBadRequestWhenOrderRequestIsInvalid() throws Exception {
        OrderRequest request = new OrderRequest(
                null,
                List.of(
                        new OrderItemRequest(null, 0)
                )
        );

        mockMvc.perform(
                        post("/orders")
                                .with(user("lucas@example.com"))
                                .header("Idempotency-Key", "test-key-invalid-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error")
                        .value("Validation Error"))
                .andExpect(jsonPath("$.fields.customerId").exists())
                .andExpect(jsonPath("$.fields['items[0].productId']").exists());
    }

    @Test
    void shouldReturnNotFoundWhenOrderDoesNotExist() throws Exception {
        UUID orderId = UUID.randomUUID();

        when(service.findById(orderId))
                .thenThrow(new ResourceNotFoundException(
                        "Order not found: " + orderId
                ));

        mockMvc.perform(
                        get("/orders/{id}", orderId)
                                .with(user("lucas@example.com"))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message")
                        .value("Order not found: " + orderId));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/orders/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
