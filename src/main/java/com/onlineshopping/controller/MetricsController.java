package com.onlineshopping.controller;

import com.onlineshopping.dto.FrontendErrorRequest;
import com.onlineshopping.dto.WebVitalsRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/metrics")
@Tag(name = "Metrics", description = "Frontend metric ingestion (Web Vitals, errors)")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    private final MeterRegistry meterRegistry;

    public MetricsController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/vitals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordVitals(@Valid @RequestBody WebVitalsRequest req) {
        Timer timer = Timer.builder("frontend.vitals")
                .tag("name", req.getName())
                .tag("rating", req.getRating())
                .tag("page", req.getPage())
                .register(meterRegistry);
        timer.record(Duration.ofMillis(req.getValue().longValue()));
    }

    @PostMapping("/errors")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordError(@Valid @RequestBody FrontendErrorRequest req) {
        Counter.builder("frontend.errors")
                .tag("severity", req.getSeverity())
                .register(meterRegistry)
                .increment();

        log.warn("frontend_error severity={} url={} message={} traceId={}",
                req.getSeverity(), req.getUrl(), req.getMessage(), req.getTraceId());
    }
}
