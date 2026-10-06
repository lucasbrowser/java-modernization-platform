package com.github.lucasoliveira.platform.common.exception;

import java.time.OffsetDateTime;
import java.util.Map;

public record ApiError(OffsetDateTime timestamp, int status, String error, String message, Map<String, String> fields) {}
