package com.github.lucasoliveira.platform.order.service;

import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserIdentity;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderAuthorizationService {

    private final AuthenticatedUserResolver userResolver;

    public OrderAuthorizationService(AuthenticatedUserResolver userResolver) {
        this.userResolver = userResolver;
    }

    public Scope currentScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (hasRole(authentication, "ROLE_ADMIN")) {
            return new Scope(true, null);
        }
        if (!hasRole(authentication, "ROLE_USER")) {
            throw new AccessDeniedException("Order access is not authorized");
        }

        AuthenticatedUserIdentity identity = userResolver.resolveCurrent();
        if (!identity.hasCustomerAssociation()) {
            throw new AccessDeniedException("A customer association is required for order access");
        }
        return new Scope(false, identity.customerId());
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }

    public record Scope(boolean admin, UUID customerId) {
        public boolean allows(UUID requestedCustomerId) {
            return admin || customerId.equals(requestedCustomerId);
        }
    }
}
