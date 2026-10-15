package com.github.lucasoliveira.platform.product;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class SecurityContextProbeController {

    @GetMapping("/test/security-context")
    Map<String, Object> securityContext(Authentication authentication) {
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return Map.of(
                "name", authentication.getName(),
                "authorities", authorities
        );
    }
}
