package com.github.lucasoliveira.platform.order;

import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.order.dto.OrderItemRequest;
import com.github.lucasoliveira.platform.order.dto.OrderRequest;
import com.github.lucasoliveira.platform.order.dto.OrderResponse;
import com.github.lucasoliveira.platform.order.entity.Order;
import com.github.lucasoliveira.platform.order.entity.OrderItem;
import com.github.lucasoliveira.platform.order.entity.OrderStatus;
import com.github.lucasoliveira.platform.order.repository.OrderRepository;
import com.github.lucasoliveira.platform.order.service.OrderCancellationConflictException;
import com.github.lucasoliveira.platform.order.service.OrderService;
import com.github.lucasoliveira.platform.order.service.OrderAuthorizationService;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

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

    @Mock
    private EntityManager entityManager;

    @Mock
    OrderAuthorizationService authorization;

    @InjectMocks
    OrderService service;

    @BeforeEach
    void setUp() {
        when(authorization.currentScope())
                .thenReturn(new OrderAuthorizationService.Scope(true, null));
        ReflectionTestUtils.setField(
                service,
                "entityManager",
                entityManager
        );
    }

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
                ), "test-key-001"
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
    void shouldCancelCreatedOrderAndRestoreStockForEveryItem() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Customer customer = customerWithId(customerId);
        Product notebook = productWithId(UUID.randomUUID(), "NOTE-001", 8);
        Product mouse = productWithId(UUID.randomUUID(), "MOUSE-001", 17);
        Order order = orderWithItems(customer, OrderStatus.CREATED,
                orderItem(notebook, 2),
                orderItem(notebook, 3),
                orderItem(mouse, 4));

        when(authorization.currentScope())
                .thenReturn(new OrderAuthorizationService.Scope(false, customerId));
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = service.cancel(orderId);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(OrderStatus.CANCELLED, response.status());
        assertEquals(13, notebook.getStock());
        assertEquals(21, mouse.getStock());
        verify(orders).findByIdForUpdate(orderId);
        verify(entityManager).flush();
    }

    @Test
    void shouldAllowAdminToCancelOrderForAnyCustomer() {
        UUID orderId = UUID.randomUUID();
        Product product = productWithId(UUID.randomUUID(), "NOTE-001", 8);
        Order order = orderWithItems(customerWithId(UUID.randomUUID()), OrderStatus.CREATED,
                orderItem(product, 2));
        when(authorization.currentScope())
                .thenReturn(new OrderAuthorizationService.Scope(true, null));
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = service.cancel(orderId);

        assertEquals(OrderStatus.CANCELLED, response.status());
        assertEquals(10, product.getStock());
    }

    @Test
    void shouldCancelCreatedOrderWithoutItemsWithoutChangingStock() {
        UUID orderId = UUID.randomUUID();
        Customer customer = customerWithId(UUID.randomUUID());
        Order order = orderWithItems(customer, OrderStatus.CREATED);

        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = service.cancel(orderId);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(OrderStatus.CANCELLED, response.status());
        assertTrue(response.items().isEmpty());
        verify(entityManager).flush();
        verifyNoInteractions(products);
    }

    @Test
    void shouldNotRestoreStockAgainWhenOrderIsAlreadyCancelled() {
        UUID orderId = UUID.randomUUID();
        Product product = productWithId(UUID.randomUUID(), "NOTE-001", 10);
        Order order = orderWithItems(customerWithId(UUID.randomUUID()), OrderStatus.CANCELLED,
                orderItem(product, 3));

        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = service.cancel(orderId);

        assertEquals(OrderStatus.CANCELLED, response.status());
        assertEquals(10, product.getStock());
        verify(orders).findByIdForUpdate(orderId);
        verify(entityManager, never()).flush();
    }

    @Test
    void shouldRejectNonCreatedOrderWithoutChangingStateOrStock() {
        UUID orderId = UUID.randomUUID();
        Product product = productWithId(UUID.randomUUID(), "NOTE-001", 10);
        Order order = orderWithItems(customerWithId(UUID.randomUUID()), OrderStatus.CONFIRMED,
                orderItem(product, 3));

        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        OrderCancellationConflictException exception = assertThrows(
                OrderCancellationConflictException.class,
                () -> service.cancel(orderId));

        assertEquals("Order cannot be cancelled in its current state", exception.getMessage());
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        assertEquals(10, product.getStock());
        verify(entityManager, never()).flush();
    }

    @Test
    void shouldHideAnotherCustomersOrderWhenUserCancelsIt() {
        UUID orderId = UUID.randomUUID();
        UUID associatedCustomerId = UUID.randomUUID();
        Order order = orderWithItems(customerWithId(UUID.randomUUID()), OrderStatus.CREATED);
        when(authorization.currentScope()).thenReturn(
                new OrderAuthorizationService.Scope(false, associatedCustomerId));
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> service.cancel(orderId));

        assertEquals("Order not found", exception.getMessage());
        assertEquals(OrderStatus.CREATED, order.getStatus());
        verify(entityManager, never()).flush();
    }

    @Test
    void shouldNotReadOrderWhenAuthorizationScopeIsDenied() {
        UUID orderId = UUID.randomUUID();
        when(authorization.currentScope()).thenThrow(
                new org.springframework.security.access.AccessDeniedException("Customer association required"));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.cancel(orderId));

        verify(orders, never()).findByIdForUpdate(any(UUID.class));
    }

    @Test
    void shouldThrowNotFoundWhenCancellingMissingOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> service.cancel(orderId));

        assertEquals("Order not found", exception.getMessage());
        verify(entityManager, never()).flush();
    }

    private Customer customerWithId(UUID customerId) {
        Customer customer = new Customer();
        ReflectionTestUtils.setField(customer, "id", customerId);
        return customer;
    }

    private Product productWithId(UUID productId, String sku, int stock) {
        Product product = new Product();
        ReflectionTestUtils.setField(product, "id", productId);
        product.setSku(sku);
        product.setName(sku);
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(stock);
        product.setActive(true);
        return product;
    }

    private Order orderWithItems(Customer customer, OrderStatus status, OrderItem... items) {
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(status);
        order.setTotalAmount(BigDecimal.ZERO);
        for (OrderItem item : items) {
            item.setOrder(order);
            order.getItems().add(item);
        }
        return order;
    }

    private OrderItem orderItem(Product product, int quantity) {
        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal("10.00"));
        return item;
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
                        ), "test-key-002"
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
                        ), "test-key-003"
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
                        ), "test-key-004"
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
                        ), "test-key-005"
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
                ), "test-key-006"
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
    void shouldReturnExistingOrderWhenIdempotencyKeyAlreadyExists() {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Customer customer = new Customer();

        Order existingOrder = new Order();
        existingOrder.setCustomer(customer);
        existingOrder.setStatus(OrderStatus.CREATED);
        existingOrder.setTotalAmount(new BigDecimal("500.00"));

        when(orders.findByIdempotencyKey("test-key-007"))
                .thenReturn(Optional.of(existingOrder));

        OrderResponse response = service.create(
                new OrderRequest(
                        customerId,
                        List.of()
                ),
                "test-key-007"
        );

        assertEquals(
                OrderStatus.CREATED,
                response.status()
        );

        assertEquals(
                new BigDecimal("500.00"),
                response.totalAmount()
        );

        verify(orders).findByIdempotencyKey("test-key-007");

        verify(customers, never()).findById(any(UUID.class));
        verify(products, never()).findById(any(UUID.class));
        verify(orders, never()).save(any(Order.class));
    }

    @Test
    void shouldRejectUserWhenCreatingForAnotherCustomerBeforeCheckingIdempotency() {
        UUID associatedCustomerId = UUID.randomUUID();
        UUID requestedCustomerId = UUID.randomUUID();
        when(authorization.currentScope()).thenReturn(
                new OrderAuthorizationService.Scope(false, associatedCustomerId));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.create(new OrderRequest(requestedCustomerId, List.of()), "foreign-key"));

        verify(orders, never()).findByIdempotencyKey(anyString());
        verifyNoInteractions(customers, products);
    }

    @Test
    void shouldNotReplayAnotherCustomersOrderForUser() {
        UUID associatedCustomerId = UUID.randomUUID();
        Customer otherCustomer = new Customer();
        ReflectionTestUtils.setField(otherCustomer, "id", UUID.randomUUID());
        Order foreignOrder = new Order();
        foreignOrder.setCustomer(otherCustomer);
        foreignOrder.setStatus(OrderStatus.CREATED);
        foreignOrder.setTotalAmount(new BigDecimal("500.00"));
        when(authorization.currentScope()).thenReturn(
                new OrderAuthorizationService.Scope(false, associatedCustomerId));
        when(orders.findByIdempotencyKey("foreign-key")).thenReturn(Optional.of(foreignOrder));

        assertThrows(ResourceNotFoundException.class,
                () -> service.create(new OrderRequest(associatedCustomerId, List.of()), "foreign-key"));

        verify(orders, never()).save(any(Order.class));
        verifyNoInteractions(products);
    }

    @Test
    void shouldHideAnotherCustomersOrderWhenUserLooksItUpById() {
        UUID orderId = UUID.randomUUID();
        UUID associatedCustomerId = UUID.randomUUID();
        Customer otherCustomer = new Customer();
        ReflectionTestUtils.setField(otherCustomer, "id", UUID.randomUUID());
        Order foreignOrder = new Order();
        foreignOrder.setCustomer(otherCustomer);
        when(authorization.currentScope()).thenReturn(
                new OrderAuthorizationService.Scope(false, associatedCustomerId));
        when(orders.findById(orderId)).thenReturn(Optional.of(foreignOrder));

        assertThrows(ResourceNotFoundException.class, () -> service.findById(orderId));
    }

    @Test
     void shouldCreateOrderAndStoreIdempotencyKey() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Customer customer = new Customer();

        Product product = new Product();
        product.setSku("KEYBOARD-001");
        product.setName("Keyboard");
        product.setPrice(new BigDecimal("150.00"));
        product.setStock(10);
        product.setActive(true);

        when(orders.findByIdempotencyKey("test-key-008"))
                .thenReturn(Optional.empty());

        when(customers.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(products.findById(productId))
                .thenReturn(Optional.of(product));

        when(orders.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = service.create(
                new OrderRequest(
                        customerId,
                        List.of(
                                new OrderItemRequest(productId, 2)
                        )
                ),
                "test-key-008"
        );

        assertEquals(
                new BigDecimal("300.00"),
                response.totalAmount()
        );

        assertEquals(8, product.getStock());

        ArgumentCaptor<Order> captor =
                ArgumentCaptor.forClass(Order.class);

        verify(orders).save(captor.capture());

        assertEquals(
                "test-key-008",
                captor.getValue().getIdempotencyKey()
        );
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
