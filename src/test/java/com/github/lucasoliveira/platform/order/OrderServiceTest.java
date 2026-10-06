package com.github.lucasoliveira.platform.order;

import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.order.dto.*;
import com.github.lucasoliveira.platform.order.entity.Order;
import com.github.lucasoliveira.platform.order.repository.OrderRepository;
import com.github.lucasoliveira.platform.order.service.OrderService;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock OrderRepository orders;
    @Mock CustomerRepository customers;
    @Mock ProductRepository products;
    @InjectMocks OrderService service;

    @Test
    void shouldCreateOrderAndDecreaseStock() {
        UUID customerId = UUID.randomUUID(), productId = UUID.randomUUID();
        Customer customer = new Customer();
        Product product = new Product();
        product.setSku("NOTE-001"); product.setName("Notebook"); product.setPrice(new BigDecimal("2500.00")); product.setStock(10); product.setActive(true);
        when(customers.findById(customerId)).thenReturn(Optional.of(customer));
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = service.create(new OrderRequest(customerId, List.of(new OrderItemRequest(productId, 2))));

        assertEquals(new BigDecimal("5000.00"), response.totalAmount());
        assertEquals(8, product.getStock());
        assertEquals(1, response.items().size());
        verify(orders).save(any(Order.class));
    }

    @Test
    void shouldRejectInsufficientStock() {
        UUID customerId = UUID.randomUUID(), productId = UUID.randomUUID();
        Customer customer = new Customer(); Product product = new Product();
        product.setSku("MOUSE-001"); product.setPrice(new BigDecimal("100.00")); product.setStock(1); product.setActive(true);
        when(customers.findById(customerId)).thenReturn(Optional.of(customer));
        when(products.findById(productId)).thenReturn(Optional.of(product));

        assertThrows(IllegalArgumentException.class, () -> service.create(new OrderRequest(customerId, List.of(new OrderItemRequest(productId, 2)))));
        verify(orders, never()).save(any());
    }
}
