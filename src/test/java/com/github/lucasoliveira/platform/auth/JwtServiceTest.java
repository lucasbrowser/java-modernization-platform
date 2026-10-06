package com.github.lucasoliveira.platform.auth;
import com.github.lucasoliveira.platform.common.security.JwtService;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class JwtServiceTest { @Test void shouldGenerateAndParseToken(){JwtService service=new JwtService("test-secret-that-has-at-least-32-characters-long",3600000);String token=service.generateToken("user@example.com","USER");var claims=service.parse(token);assertEquals("user@example.com",claims.getSubject());assertEquals("USER",claims.get("role",String.class));}}
