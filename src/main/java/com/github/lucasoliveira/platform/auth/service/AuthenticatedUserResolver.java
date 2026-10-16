package com.github.lucasoliveira.platform.auth.service;

import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedUserResolver {

    private final UserRepository userRepository;

    public AuthenticatedUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AuthenticatedUserIdentity resolveCurrent() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new UnauthenticatedIdentityException();
        }

        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(AuthenticatedUserNotFoundException::new);

        return new AuthenticatedUserIdentity(user.getId(), user.getCustomerId());
    }
}
