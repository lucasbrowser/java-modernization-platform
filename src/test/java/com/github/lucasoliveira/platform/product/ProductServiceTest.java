package com.github.lucasoliveira.platform.product;

import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.product.dto.ProductRequest;
import com.github.lucasoliveira.platform.product.dto.ProductResponse;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import com.github.lucasoliveira.platform.product.service.ProductService;
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
class ProductServiceTest {

    @Mock
    ProductRepository repository;

    @InjectMocks
    ProductService service;

    @Test
    void shouldCreateProduct() {
        ProductRequest request = new ProductRequest(
                "NOTE-001",
                "Notebook",
                "Notebook Enterprise",
                new BigDecimal("2500.00"),
                10,
                true
        );

        when(repository.existsBySku(request.sku()))
                .thenReturn(false);

        when(repository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = service.create(request);

        assertEquals("NOTE-001", response.sku());
        assertEquals("Notebook", response.name());
        assertEquals("Notebook Enterprise", response.description());
        assertEquals(
                new BigDecimal("2500.00"),
                response.price()
        );
        assertEquals(10, response.stock());
        assertTrue(response.active());

        verify(repository).save(any(Product.class));
    }

    @Test
    void shouldRejectDuplicatedSku() {
        ProductRequest request = new ProductRequest(
                "NOTE-001",
                "Notebook",
                "Notebook Enterprise",
                new BigDecimal("2500.00"),
                10,
                true
        );

        when(repository.existsBySku(request.sku()))
                .thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(request)
        );

        assertEquals(
                "SKU already registered",
                exception.getMessage()
        );

        verify(repository, never()).save(any(Product.class));
    }

    @Test
    void shouldFindAllProducts() {
        Product first = new Product();
        first.setSku("NOTE-001");
        first.setName("Notebook");
        first.setPrice(new BigDecimal("2500.00"));
        first.setStock(10);
        first.setActive(true);

        Product second = new Product();
        second.setSku("MOUSE-001");
        second.setName("Mouse");
        second.setPrice(new BigDecimal("100.00"));
        second.setStock(20);
        second.setActive(true);

        when(repository.findAll())
                .thenReturn(List.of(first, second));

        List<ProductResponse> response = service.findAll();

        assertEquals(2, response.size());
        assertEquals("NOTE-001", response.get(0).sku());
        assertEquals("MOUSE-001", response.get(1).sku());

        verify(repository).findAll();
    }

    @Test
    void shouldFindProductById() {
        UUID id = UUID.randomUUID();

        Product product = new Product();
        product.setSku("NOTE-001");
        product.setName("Notebook");
        product.setPrice(new BigDecimal("2500.00"));
        product.setStock(10);
        product.setActive(true);

        when(repository.findById(id))
                .thenReturn(Optional.of(product));

        ProductResponse response = service.findById(id);

        assertEquals("NOTE-001", response.sku());
        assertEquals("Notebook", response.name());
        assertEquals(10, response.stock());

        verify(repository).findById(id);
    }

    @Test
    void shouldThrowExceptionWhenProductDoesNotExist() {
        UUID id = UUID.randomUUID();

        when(repository.findById(id))
                .thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> service.findById(id)
        );

        assertEquals(
                "Product not found: " + id,
                exception.getMessage()
        );
    }
}