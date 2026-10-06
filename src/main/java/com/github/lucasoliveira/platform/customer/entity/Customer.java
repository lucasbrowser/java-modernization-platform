package com.github.lucasoliveira.platform.customer.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;
    @Column(nullable = false)
    String name;
    @Column(nullable = false, unique = true)
    String email;
    @Column(nullable = false, unique = true)
    String document;
    String phone;
    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;
    @PrePersist
    void pre() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
    public UUID getId() {
        return id;
    }
    public String getName() {
        return name;
    }
    public void setName(String v) {
        name = v;
    }
    public String getEmail() {
        return email;
    }
    public void setEmail(String v) {
        email = v;
    }
    public String getDocument() {
        return document;
    }
    public void setDocument(String v) {
        document = v;
    }
    public String getPhone() {
        return phone;
    }
    public void setPhone(String v) {
        phone = v;
    }
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
