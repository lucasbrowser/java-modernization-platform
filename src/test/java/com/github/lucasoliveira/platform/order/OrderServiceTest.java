package com.github.lucasoliveira.platform.order;

import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.order.dto.OrderItemRequest;
import com.github.lucasoliveira.platform.order.dto.OrderRequest;
import com.github.lucasoliveira.platform.order.dto.OrderResponse;
import com.github.lucasoliveira.platform.order.entity.Order;
import com.github.lucasoliveira.platform.order.entity.OrderStatus;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    OrderRepository orders;

    @Mock
    CustomerRepository customers;

    @Mock
    ProductRepository products;

    @InjectMocks
    OrderService service;

    @Test
    void shouldCreateOrderAndDecreaseStock() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Customer customer = new Customer();

        Product product = new Product();
        product.setSku("NOTE-001");
        product.setName("Notebook");
        product.setPrice(new BigDecimal("2500.00"));
        product.setStock(10);
        product.setActive(true);

        when(customers.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(products.findById(productId))
                .thenReturn(Optional.of(product));

        when(orders.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.create(
                new OrderRequest(
                        customerId,
                        List.of(new OrderItemRequest(productId, 2))
                )
        );

        assertEquals(
                new BigDecimal("5000.00"),
                response.totalAmount()
        );

        assertEquals(8, product.getStock());
        assertEquals(1, response.items().size());
        assertEquals(2, response.items().get(0).quantity());
        assertEquals(
                new BigDecimal("2500.00"),
                response.items().get(0).unitPrice()
        );

        verify(orders).save(any(Order.class));
    }

    @Test
    void shouldRejectInsufficientStock() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Customer customer = new Customer();

        Product product = new Product();
        product.setSku("MOUSE-001");
        product.setPrice(new BigDecimal("100.00"));
        product.setStock(1);
        product.setActive(true);

        when(customers.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(products.findById(productId))
                .thenReturn(Optional.of(product));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        new OrderRequest(
                                customerId,
                                List.of(new OrderItemRequest(productId, 2))
                        )
                )
        );

        assertEquals(
                "Insufficient stock for product: MOUSE-001",
                exception.getMessage()
        );

        assertEquals(1, product.getStock());

        verify(orders, never()).save(any());
    }

    @Test
    void shouldRejectInactiveProduct() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Customer customer = new Customer();

        Product product = new Product();
        product.setSku("OLD-001");
        product.setName("Produto Inativo");
        product.setPrice(new BigDecimal("100.00"));
        product.setStock(10);
        product.setActive(false);

        when(customers.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(products.findById(productId))
                .thenReturn(Optional.of(product));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        new OrderRequest(
                                customerId,
                                List.of(new OrderItemRequest(productId, 1))
                        )
                )
        );

        assertEquals(
                "Product is inactive: OLD-001",
                exception.getMessage()
        );

        assertEquals(10, product.getStock());

        verify(orders, never()).save(any());
    }

    @Test
    void shouldThrowExceptionWhenCustomerDoesNotExist() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(customers.findById(customerId))
                .thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> service.create(
                        new OrderRequest(
                                customerId,
                                List.of(new OrderItemRequest(productId, 1))
                        )
                )
        );

        assertEquals(
                "Customer not found: " + customerId,
                exception.getMessage()
        );

        verify(products, never()).findById(any());
        verify(orders, never()).save(any());
    }

    @Test
    void shouldThrowExceptionWhenProductDoesNotExist() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Customer customer = new Customer();

        when(customers.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(products.findById(productId))
                .thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> service.create(
                        new OrderRequest(
                                customerId,
                                List.of(new OrderItemRequest(productId, 1))
                        )
                )
        );

        assertEquals(
                "Product not found: " + productId,
                exception.getMessage()
        );

        verify(orders, never()).save(any());
    }

    @Test
    void shouldCalculateTotalForMultipleItems() {
        UUID customerId = UUID.randomUUID();
        UUID notebookId = UUID.randomUUID();
        UUID mouseId = UUID.randomUUID();

        Customer customer = new Customer();

        Product notebook = new Product();
        notebook.setSku("NOTE-001");
        notebook.setName("Notebook");
        notebook.setPrice(new BigDecimal("2500.00"));
        notebook.setStock(10);
        notebook.setActive(true);

        Product mouse = new Product();
        mouse.setSku("MOUSE-001");
        mouse.setName("Mouse");
        mouse.setPrice(new BigDecimal("100.00"));
        mouse.setStock(20);
        mouse.setActive(true);

        when(customers.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(products.findById(notebookId))
                .thenReturn(Optional.of(notebook));

        when(products.findById(mouseId))
                .thenReturn(Optional.of(mouse));

        when(orders.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.create(
                new OrderRequest(
                        customerId,
                        List.of(
                                new OrderItemRequest(notebookId, 2),
                                new OrderItemRequest(mouseId, 3)
                        )
                )
        );

        assertEquals(
                new BigDecimal("5300.00"),
                response.totalAmount()
        );

        assertEquals(8, notebook.getStock());
        assertEquals(17, mouse.getStock());
        assertEquals(2, response.items().size());

        verify(orders).save(any(Order.class));
    }

    @Test
    void shouldFindOrderById() {
        UUID orderId = UUID.randomUUID();

        Customer customer = new Customer();

        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CREATED);
        order.setTotalAmount(new BigDecimal("500.00"));

        when(orders.findById(orderId))
                .thenReturn(Optional.of(order));

        OrderResponse response = service.findById(orderId);

        assertEquals(
                OrderStatus.CREATED,
                response.status()
        );

        assertEquals(
                new BigDecimal("500.00"),
                response.totalAmount()
        );

        verify(orders).findById(orderId);
    }

    @Test
    void shouldThrowExceptionWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();

        when(orders.findById(orderId))
                .thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> service.findById(orderId)
        );

        assertEquals(
                "Order not found: " + orderId,
                exception.getMessage()
        );
    }

    @Test
    void shouldFindOrdersByCustomer() {
        UUID customerId = UUID.randomUUID();

        Customer customer = new Customer();

        Order firstOrder = new Order();
        firstOrder.setCustomer(customer);
        firstOrder.setStatus(OrderStatus.CREATED);
        firstOrder.setTotalAmount(new BigDecimal("100.00"));

        Order secondOrder = new Order();
        secondOrder.setCustomer(customer);
        secondOrder.setStatus(OrderStatus.CONFIRMED);
        secondOrder.setTotalAmount(new BigDecimal("200.00"));

        when(orders.findByCustomerId(customerId))
                .thenReturn(List.of(firstOrder, secondOrder));

        List<OrderResponse> response =
                service.findByCustomer(customerId);

        assertEquals(2, response.size());

        assertEquals(
                OrderStatus.CREATED,
                response.get(0).status()
        );

        assertEquals(
                OrderStatus.CONFIRMED,
                response.get(1).status()
        );

        assertEquals(
                new BigDecimal("100.00"),
                response.get(0).totalAmount()
        );

        assertEquals(
                new BigDecimal("200.00"),
                response.get(1).totalAmount()
        );

        verify(orders).findByCustomerId(customerId);
    }
}