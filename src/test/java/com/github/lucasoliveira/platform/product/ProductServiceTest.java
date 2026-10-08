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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository repo;

    @InjectMocks
    private ProductService service;

    @Test
    void shouldCreateProduct() {

        ProductRequest request = new ProductRequest(
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true
        );

        Product product = new Product();

        product.setSku(request.sku());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setActive(request.active());

        when(repo.existsBySku(request.sku()))
                .thenReturn(false);

        when(repo.save(any(Product.class)))
                .thenReturn(product);

        ProductResponse response =
                service.create(request);

        assertThat(response).isNotNull();
        assertThat(response.sku())
                .isEqualTo("SKU-001");

        assertThat(response.name())
                .isEqualTo("Product 1");

        assertThat(response.price())
                .isEqualByComparingTo("100.00");

        assertThat(response.stock())
                .isEqualTo(10);

        verify(repo).save(any(Product.class));
    }

    @Test
    void shouldRejectDuplicatedSku() {

        ProductRequest request = new ProductRequest(
                "SKU-001",
                "Product 1",
                "Product description",
                new BigDecimal("100.00"),
                10,
                true
        );

        when(repo.existsBySku(request.sku()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SKU already registered");

        verify(repo, never()).save(any(Product.class));
    }

    @Test
    void shouldFindAllProductsWithPagination() {

        Product product = new Product();

        product.setSku("SKU-001");
        product.setName("Product 1");
        product.setDescription("Product description");
        product.setPrice(new BigDecimal("100.00"));
        product.setStock(10);
        product.setActive(true);

        Pageable pageable = PageRequest.of(0, 10);

        Page<Product> page =
                new PageImpl<>(List.of(product), pageable, 1);

        when(repo.findAll(pageable))
                .thenReturn(page);

        Page<ProductResponse> result =
                service.findAll(pageable);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);

        assertThat(result.getContent().get(0).sku())
                .isEqualTo("SKU-001");

        assertThat(result.getContent().get(0).name())
                .isEqualTo("Product 1");

        assertThat(result.getContent().get(0).price())
                .isEqualByComparingTo("100.00");

        assertThat(result.getContent().get(0).stock())
                .isEqualTo(10);

        assertThat(result.getTotalElements())
                .isEqualTo(1);

        assertThat(result.getTotalPages())
                .isEqualTo(1);

        verify(repo).findAll(pageable);
    }

    @Test
    void shouldFindProductById() {

        UUID id = UUID.randomUUID();

        Product product = new Product();

        product.setSku("SKU-001");
        product.setName("Product 1");
        product.setDescription("Product description");
        product.setPrice(new BigDecimal("100.00"));
        product.setStock(10);
        product.setActive(true);

        when(repo.findById(id))
                .thenReturn(Optional.of(product));

        ProductResponse response =
                service.findById(id);

        assertThat(response).isNotNull();
        assertThat(response.sku())
                .isEqualTo("SKU-001");

        verify(repo).findById(id);
    }

    @Test
    void shouldThrowWhenProductDoesNotExist() {

        UUID id = UUID.randomUUID();

        when(repo.findById(id))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found: " + id);

        verify(repo).findById(id);
    }
}