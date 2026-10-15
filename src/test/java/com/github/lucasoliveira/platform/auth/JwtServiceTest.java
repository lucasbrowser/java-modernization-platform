package com.github.lucasoliveira.platform.auth;

import com.github.lucasoliveira.platform.common.security.JwtService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-has-at-least-32-characters-long";
    private static final String DIFFERENT_SECRET = "another-test-secret-with-at-least-32-characters";

    @Test
    void shouldGenerateAndParseToken() {
        JwtService service = new JwtService(SECRET, 3_600_000);

        String token = service.generateToken("user@example.com", "USER");
        var claims = service.parse(token);

        assertEquals("user@example.com", claims.getSubject());
        assertEquals("USER", claims.get("role", String.class));
    }

    @Test
    void shouldRejectTokenWithInvalidSignature() {
        JwtService service = new JwtService(SECRET, 3_600_000);
        String tokenSignedWithDifferentKey = new JwtService(DIFFERENT_SECRET, 3_600_000)
                .generateToken("user@example.com", "USER");

        assertThrows(JwtException.class, () -> service.parse(tokenSignedWithDifferentKey));
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtService service = new JwtService(SECRET, 3_600_000);
        Instant expiredAt = Instant.parse("2000-01-01T00:00:00Z");
        String expiredToken = Jwts.builder()
                .subject("user@example.com")
                .claim("role", "USER")
                .issuedAt(Date.from(expiredAt.minusSeconds(60)))
                .expiration(Date.from(expiredAt))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(JwtException.class, () -> service.parse(expiredToken));
    }

    @Test
    void shouldRejectMalformedToken() {
        JwtService service = new JwtService(SECRET, 3_600_000);

        assertThrows(JwtException.class, () -> service.parse("not-a-jwt"));
    }
}
