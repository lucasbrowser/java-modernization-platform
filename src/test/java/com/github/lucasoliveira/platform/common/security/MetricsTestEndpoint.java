package com.github.lucasoliveira.platform.common.security;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsTestEndpoint {

    @GetMapping(value = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    String scrape() {
        return "# HELP test_metric Test metric\n# TYPE test_metric gauge\ntest_metric 1\n";
    }
}
