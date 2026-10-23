package com.github.lucasoliveira.platform.auth.controller;

import com.github.lucasoliveira.platform.auth.dto.AssignCustomerLinkRequest;
import com.github.lucasoliveira.platform.auth.dto.RemoveCustomerLinkRequest;
import com.github.lucasoliveira.platform.auth.dto.UserCustomerLinkResponse;
import com.github.lucasoliveira.platform.auth.service.UserCustomerLinkResult;
import com.github.lucasoliveira.platform.auth.service.UserCustomerLinkService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/users/{userId}/customer-link")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserCustomerLinkController {

    private final UserCustomerLinkService service;

    public AdminUserCustomerLinkController(UserCustomerLinkService service) {
        this.service = service;
    }

    @PutMapping
    public UserCustomerLinkResponse assign(
            @PathVariable UUID userId,
            @Valid @RequestBody AssignCustomerLinkRequest request
    ) {
        return response(service.assignCustomer(userId, request.customerId(), request.justification()));
    }

    @PostMapping("/removal")
    public UserCustomerLinkResponse remove(
            @PathVariable UUID userId,
            @Valid @RequestBody RemoveCustomerLinkRequest request
    ) {
        return response(service.removeCustomer(userId, request.justification()));
    }

    private UserCustomerLinkResponse response(UserCustomerLinkResult result) {
        return new UserCustomerLinkResponse(result.userId(), result.customerId(), result.changed());
    }
}
