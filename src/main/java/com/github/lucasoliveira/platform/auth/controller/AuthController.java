package com.github.lucasoliveira.platform.auth.controller;
import com.github.lucasoliveira.platform.auth.dto.*; import com.github.lucasoliveira.platform.auth.service.AuthService; import jakarta.validation.Valid; import org.springframework.http.HttpStatus; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/auth")
public class AuthController { private final AuthService service; public AuthController(AuthService service){this.service=service;}
 @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED) public void register(@Valid @RequestBody RegisterRequest request){service.register(request);}
 @PostMapping("/login") public LoginResponse login(@Valid @RequestBody LoginRequest request){return service.login(request);}
}
