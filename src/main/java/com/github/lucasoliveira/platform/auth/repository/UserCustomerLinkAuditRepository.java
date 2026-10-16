package com.github.lucasoliveira.platform.auth.repository;

import com.github.lucasoliveira.platform.auth.entity.UserCustomerLinkAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserCustomerLinkAuditRepository
        extends JpaRepository<UserCustomerLinkAudit, UUID> {
}
