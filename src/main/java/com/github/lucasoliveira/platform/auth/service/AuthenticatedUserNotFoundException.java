package com.github.lucasoliveira.platform.auth.service;

public class AuthenticatedUserNotFoundException extends RuntimeException {

    public AuthenticatedUserNotFoundException() {
        super("The authenticated identity has no persisted user");
    }
}
