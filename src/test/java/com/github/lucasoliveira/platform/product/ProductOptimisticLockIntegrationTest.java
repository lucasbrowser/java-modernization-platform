package com.github.lucasoliveira.platform.product;

import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ProductOptimisticLockIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shouldIncrementVersionWhenProductIsUpdated() {
        Product product = new Product();
        product.setSku("LOCK-VERSION-001");
        product.setName("Produto Version");
        product.setPrice(new BigDecimal("100.00"));
        product.setStock(10);
        product.setActive(true);

        Product saved = productRepository.saveAndFlush(product);

        assertEquals(0L, saved.getVersion());

        saved.setStock(8);

        Product updated = productRepository.saveAndFlush(saved);

        assertEquals(1L, updated.getVersion());
    }
}