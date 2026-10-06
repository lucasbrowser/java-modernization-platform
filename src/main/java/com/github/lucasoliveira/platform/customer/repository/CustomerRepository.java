package com.github.lucasoliveira.platform.customer.repository;

import com.github.lucasoliveira.platform.customer.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByDocument(String document);
}
