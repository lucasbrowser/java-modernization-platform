package com.github.lucasoliveira.platform.auth;

import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserIdentity;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserResolver;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class AuthenticatedUserProbeController {

    private final AuthenticatedUserResolver resolver;

    public AuthenticatedUserProbeController(AuthenticatedUserResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/test/authenticated-user")
    Map<String, Object> currentUser(Authentication authentication) {
        AuthenticatedUserIdentity identity = resolver.resolveCurrent();
        Map<String, Object> response = new HashMap<>();
        response.put("userId", identity.userId());
        response.put("customerId", identity.customerId());
        response.put("hasCustomerAssociation", identity.hasCustomerAssociation());
        response.put("authorities", authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .toList());
        return response;
    }
}
