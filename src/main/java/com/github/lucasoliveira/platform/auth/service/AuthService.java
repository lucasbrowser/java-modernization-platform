
package com.github.lucasoliveira.platform.auth.service;

import com.github.lucasoliveira.platform.auth.dto.LoginRequest;
import com.github.lucasoliveira.platform.auth.dto.LoginResponse;
import com.github.lucasoliveira.platform.auth.dto.RegisterRequest;
import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.common.exception.EmailAlreadyRegisteredException;
import com.github.lucasoliveira.platform.common.exception.InvalidCredentialsException;
import com.github.lucasoliveira.platform.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final long expirationMs;

    public AuthService(
            UserRepository repo,
            PasswordEncoder encoder,
            JwtService jwt,
            @Value("${security.jwt.expiration-ms}") long expirationMs
    ) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.expirationMs = expirationMs;
    }

    @Transactional
    public void register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);

        if (repo.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException(
                    "Email already registered"
            );
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(email);
        user.setPassword(encoder.encode(request.password()));
        user.setRole(Role.USER);

        repo.save(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = repo.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new InvalidCredentialsException(
                        "Invalid credentials"
                ));

        if (!encoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        String token = jwt.generateToken(
                user.getEmail(),
                user.getRole().name()
        );

        return new LoginResponse(
                token,
                "Bearer",
                expirationMs / 1000
        );
    }
}
