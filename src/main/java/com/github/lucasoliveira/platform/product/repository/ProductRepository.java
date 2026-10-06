package com.github.lucasoliveira.platform.product.repository;

import com.github.lucasoliveira.platform.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findBySku(String sku);
    boolean existsBySku(String sku);
}
