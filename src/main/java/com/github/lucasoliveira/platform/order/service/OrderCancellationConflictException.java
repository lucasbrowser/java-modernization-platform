package com.github.lucasoliveira.platform.order.service;

public class OrderCancellationConflictException extends RuntimeException {

    public OrderCancellationConflictException(String message) {
        super(message);
    }
}
