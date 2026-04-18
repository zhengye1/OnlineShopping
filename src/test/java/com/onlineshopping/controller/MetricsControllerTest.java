package com.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineshopping.dto.WebVitalsRequest;
import com.onlineshopping.repository.es.ProductSearchRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private ProductSearchRepository productSearchRepository;

    @Test
    void postVitals_records_metric_and_returns_204() throws Exception {
        WebVitalsRequest req = new WebVitalsRequest();
        req.setName("LCP");
        req.setValue(2100.5);
        req.setRating("good");
        req.setPage("/");

        mockMvc.perform(post("/api/metrics/vitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        assertThat(meterRegistry.get("frontend.vitals")
                .tag("name", "LCP")
                .tag("rating", "good")
                .tag("page", "/")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void postVitals_with_invalid_name_returns_400() throws Exception {
        WebVitalsRequest req = new WebVitalsRequest();
        req.setName("INVALID");
        req.setValue(100.0);
        req.setRating("good");
        req.setPage("/");

        mockMvc.perform(post("/api/metrics/vitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
