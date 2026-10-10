
package com.github.lucasoliveira.platform.auth;

import com.github.lucasoliveira.platform.auth.dto.LoginRequest;
import com.github.lucasoliveira.platform.auth.dto.RegisterRequest;
import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.auth.service.AuthService;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.common.exception.EmailAlreadyRegisteredException;
import com.github.lucasoliveira.platform.common.exception.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                repository,
                passwordEncoder,
                jwtService,
                3600000L
        );
    }

    @Test
    void shouldRegisterUserWithEncodedPasswordAndUserRole() {
        RegisterRequest request = new RegisterRequest(
                "Lucas Oliveira",
                "lucas@example.com",
                "password123"
        );

        when(repository.existsByEmailIgnoreCase("lucas@example.com"))
                .thenReturn(false);
        when(passwordEncoder.encode("password123"))
                .thenReturn("$2a$10$encodedPassword");

        authService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repository).save(captor.capture());

        User savedUser = captor.getValue();

        assertThat(savedUser.getName()).isEqualTo("Lucas Oliveira");
        assertThat(savedUser.getEmail()).isEqualTo("lucas@example.com");
        assertThat(savedUser.getPassword()).isEqualTo("$2a$10$encodedPassword");
        assertThat(savedUser.getPassword()).isNotEqualTo("password123");
        assertThat(savedUser.getRole()).isEqualTo(Role.USER);

        verify(passwordEncoder).encode("password123");
    }

    @Test
    void shouldRejectDuplicateEmail() {
        RegisterRequest request = new RegisterRequest(
                "Lucas Oliveira",
                "lucas@example.com",
                "password123"
        );

        when(repository.existsByEmailIgnoreCase("lucas@example.com"))
                .thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email already registered");

        verify(repository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void shouldReturnTokenWhenCredentialsAreValid() {
        User user = new User();
        user.setName("Lucas Oliveira");
        user.setEmail("lucas@example.com");
        user.setPassword("$2a$10$encodedPassword");
        user.setRole(Role.USER);

        when(repository.findByEmailIgnoreCase("lucas@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$2a$10$encodedPassword"))
                .thenReturn(true);
        when(jwtService.generateToken("lucas@example.com", "USER"))
                .thenReturn("test-jwt");

        var response = authService.login(
                new LoginRequest("lucas@example.com", "password123")
        );

        assertThat(response.accessToken()).isEqualTo("test-jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");

        verify(jwtService).generateToken("lucas@example.com", "USER");
    }

    @Test
    void shouldRejectIncorrectPassword() {
        User user = new User();
        user.setEmail("lucas@example.com");
        user.setPassword("$2a$10$encodedPassword");
        user.setRole(Role.USER);

        when(repository.findByEmailIgnoreCase("lucas@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "$2a$10$encodedPassword"))
                .thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("lucas@example.com", "wrong-password")
        ))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");

        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    @Test
    void shouldRejectUnknownEmail() {
        when(repository.findByEmailIgnoreCase("unknown@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("unknown@example.com", "password123")
        ))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }
}
