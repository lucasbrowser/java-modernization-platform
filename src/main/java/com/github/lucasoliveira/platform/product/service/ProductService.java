package com.github.lucasoliveira.platform.product.service;

import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.product.dto.*;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ProductService {
    private final ProductRepository repo;
    public ProductService(ProductRepository repo) {
        this.repo = repo;
    }
    @Transactional
    public ProductResponse create(ProductRequest r) {
        if (repo.existsBySku(r.sku())) {
            throw new IllegalArgumentException("SKU already registered");
        }
        Product p = new Product();
        p.setSku(r.sku());
        p.setName(r.name());
        p.setDescription(r.description());
        p.setPrice(r.price());
        p.setStock(r.stock());
        p.setActive(r.active());
        return map(repo.save(p));
    }
    public List<ProductResponse> findAll() {
        return repo.findAll().stream()
                .map(this::map)
                .toList();
    }
    public ProductResponse findById(UUID id) {
        return map(repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id)));
    }
    private ProductResponse map(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(), p.getStock(), p.getActive(), p.getCreatedAt());
    }
}
