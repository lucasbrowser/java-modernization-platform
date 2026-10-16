package com.github.lucasoliveira.platform.auth.service;

public class UnauthenticatedIdentityException extends IllegalStateException {

    public UnauthenticatedIdentityException() {
        super("An authenticated identity is required to resolve the user");
    }
}
