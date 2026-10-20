package com.github.lucasoliveira.platform.order.service;

import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.order.dto.*;
import com.github.lucasoliveira.platform.order.entity.*;
import com.github.lucasoliveira.platform.order.repository.OrderRepository;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class OrderService {

    private final OrderRepository orders;

    private final CustomerRepository customers;

    private final ProductRepository products;

    private final OrderAuthorizationService authorization;

    @PersistenceContext
    private EntityManager entityManager;

    public OrderService(OrderRepository orders, CustomerRepository customers, ProductRepository products,
                        OrderAuthorizationService authorization) {
        this.orders = orders;
        this.customers = customers;
        this.products = products;
        this.authorization = authorization;
    }

    @Transactional
    public OrderResponse create(OrderRequest r, String idempotencyKey) {

        OrderAuthorizationService.Scope scope = authorization.currentScope();
        if (!scope.allows(r.customerId())) {
            throw new AccessDeniedException("Order customer does not match the authenticated user");
        }

        Optional<Order> existingOrder =
                orders.findByIdempotencyKey(idempotencyKey);

        if (existingOrder.isPresent()) {
            Order order = existingOrder.get();
            if (!scope.allows(order.getCustomer().getId())) {
                throw new ResourceNotFoundException("Order not found");
            }
            return map(order);
        }

        Customer c = customers.findById(r.customerId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Customer not found: " + r.customerId()));

        Order o = new Order();

        o.setCustomer(c);
        o.setStatus(OrderStatus.CREATED);
        o.setIdempotencyKey(idempotencyKey);

        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest ir : r.items()) {

            Product p = products.findById(ir.productId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Product not found: " + ir.productId()));

            if (!Boolean.TRUE.equals(p.getActive())) {
                throw new IllegalArgumentException(
                        "Product is inactive: " + p.getSku());
            }

            if (p.getStock() < ir.quantity()) {
                throw new IllegalArgumentException(
                        "Insufficient stock for product: " + p.getSku());
            }

            p.setStock(p.getStock() - ir.quantity());

            OrderItem item = new OrderItem();

            item.setOrder(o);
            item.setProduct(p);
            item.setQuantity(ir.quantity());
            item.setUnitPrice(p.getPrice());

            o.getItems().add(item);

            total = total.add(
                    p.getPrice()
                            .multiply(BigDecimal.valueOf(ir.quantity()))
            );
        }

        o.setTotalAmount(total);

        Order savedOrder = orders.save(o);

        entityManager.flush();

        return map(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(UUID id) {
        OrderAuthorizationService.Scope scope = authorization.currentScope();
        Order order = orders.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        if (!scope.allows(order.getCustomer().getId())) {
            throw new ResourceNotFoundException("Order not found: " + id);
        }
        return map(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findByCustomer(UUID id) {
        OrderAuthorizationService.Scope scope = authorization.currentScope();
        if (!scope.allows(id)) {
            throw new ResourceNotFoundException("Customer not found: " + id);
        }
        return orders.findByCustomerId(id).stream()
                .map(this::map)
                .toList();
    }
    
    private OrderResponse map(Order o) {
        return new OrderResponse(o.getId(), o.getCustomer().getId(), o.getStatus(), o.getTotalAmount(), o.getCreatedAt(), o.getItems().stream().map(i -> new OrderResponse.OrderItemResponse(i.getProduct().getId(), i.getProduct().getSku(), i.getProduct().getName(), i.getQuantity(), i.getUnitPrice(), i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))).toList());
    }
}
