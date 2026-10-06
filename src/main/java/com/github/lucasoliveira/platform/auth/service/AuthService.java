package com.github.lucasoliveira.platform.auth.service;

import com.github.lucasoliveira.platform.auth.dto.*;
import com.github.lucasoliveira.platform.auth.entity.*;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final long expirationMs;
    public AuthService(UserRepository repo, PasswordEncoder encoder, JwtService jwt, @Value("${security.jwt.expiration-ms}") long expirationMs) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.expirationMs = expirationMs;
    }
    @Transactional
    public void register(RegisterRequest r) {
        if (repo.existsByEmailIgnoreCase(r.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User u = new User();
        u.setName(r.name());
        u.setEmail(r.email().toLowerCase());
        u.setPassword(encoder.encode(r.password()));
        u.setRole(Role.USER);
        repo.save(u);
    }
    public LoginResponse login(LoginRequest r) {
        User u = repo.findByEmailIgnoreCase(r.email()).orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!encoder.matches(r.password(), u.getPassword())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        return new LoginResponse(jwt.generateToken(u.getEmail(), u.getRole().name()), "Bearer", expirationMs / 1000);
    }
}
