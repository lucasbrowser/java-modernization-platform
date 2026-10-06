package com.github.lucasoliveira.platform.customer.service;

import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.customer.dto.*;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CustomerService {
    private final CustomerRepository repo;
    public CustomerService(CustomerRepository repo) {
        this.repo = repo;
    }
    @Transactional
    public CustomerResponse create(CustomerRequest r) {
        if (repo.existsByEmailIgnoreCase(r.email()))
        throw new IllegalArgumentException("Customer email already registered");
        if (repo.existsByDocument(r.document()))
        throw new IllegalArgumentException("Customer document already registered");
        Customer c = new Customer();
        c.setName(r.name());
        c.setEmail(r.email().toLowerCase());
        c.setDocument(r.document());
        c.setPhone(r.phone());
        return map(repo.save(c));
    }
    public List<CustomerResponse> findAll() {
        return repo.findAll().stream()
                .map(this::map)
                .toList();
    }
    public CustomerResponse findById(UUID id) {
        return map(repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id)));
    }
    private CustomerResponse map(Customer c) {
        return new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getDocument(), c.getPhone(), c.getCreatedAt());
    }
}
