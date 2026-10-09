package com.github.lucasoliveira.platform.order.repository;

import com.github.lucasoliveira.platform.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByCustomerId(UUID customerId);
    
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Query("""
    SELECT COUNT(DISTINCT o.id)
    FROM Order o
    JOIN o.items i
    WHERE i.product.id = :productId
    """)
    long countByProductId(@Param("productId") UUID productId);
}


