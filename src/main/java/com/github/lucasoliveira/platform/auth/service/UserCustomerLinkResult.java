package com.github.lucasoliveira.platform.auth.service;

import java.util.UUID;

public record UserCustomerLinkResult(UUID userId, UUID customerId, boolean changed) {
}
