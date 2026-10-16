package com.github.lucasoliveira.platform.auth;

import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserIdentity;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserNotFoundException;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserResolver;
import com.github.lucasoliveira.platform.auth.service.UnauthenticatedIdentityException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticatedUserResolverTest {

    @Mock
    private UserRepository userRepository;

    private AuthenticatedUserResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AuthenticatedUserResolver(userRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldResolvePersistedUserIdAndCustomerIdFromAuthenticatedPrincipal() {
        String email = "linked.user@example.com";
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        User user = user(email, customerId, userId);
        authenticate(email);
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));

        AuthenticatedUserIdentity identity = resolver.resolveCurrent();

        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(identity.customerId()).isEqualTo(customerId);
        assertThat(identity.hasCustomerAssociation()).isTrue();
        verify(userRepository).findByEmailIgnoreCase(email);
    }

    @Test
    void shouldResolveUserWithoutCustomerAsDistinctFromMissingUser() {
        String email = "unlinked.user@example.com";
        UUID userId = UUID.randomUUID();
        User user = user(email, null, userId);
        authenticate(email);
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));

        AuthenticatedUserIdentity identity = resolver.resolveCurrent();

        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(identity.customerId()).isNull();
        assertThat(identity.hasCustomerAssociation()).isFalse();
    }

    @Test
    void shouldReportAuthenticatedIdentityWithoutPersistedUser() {
        String email = "deleted.user@example.com";
        authenticate(email);
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());

        assertThatThrownBy(resolver::resolveCurrent)
                .isInstanceOf(AuthenticatedUserNotFoundException.class);
    }

    @Test
    void shouldRejectResolutionWithoutAuthenticatedPrincipal() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(resolver::resolveCurrent)
                .isInstanceOf(UnauthenticatedIdentityException.class);
    }

    private void authenticate(String email) {
        var authentication = new UsernamePasswordAuthenticationToken(
                email,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private User user(String email, UUID customerId, UUID userId) {
        User user = new User();
        user.setName("Test user");
        user.setEmail(email);
        user.setCustomerId(customerId);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }
}
