package com.github.lucasoliveira.platform.auth.service;

import java.util.UUID;

public record AuthenticatedUserIdentity(UUID userId, UUID customerId) {

    public boolean hasCustomerAssociation() {
        return customerId != null;
    }
}
