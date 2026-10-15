package com.github.lucasoliveira.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MetricsTestEndpoint.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "security.metrics.username=prometheus",
        "security.metrics.password=test-metrics-password"
})
class PrometheusSecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    JwtService jwtService;

    @Test
    void shouldRejectScrapeWithoutCredentials() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectScrapeWithInvalidCredentials() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(httpBasic("prometheus", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnPrometheusFormatForAuthorizedScraper() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(httpBasic("prometheus", "test-metrics-password")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("# HELP test_metric Test metric\n# TYPE test_metric gauge\ntest_metric 1\n"));
    }

}
