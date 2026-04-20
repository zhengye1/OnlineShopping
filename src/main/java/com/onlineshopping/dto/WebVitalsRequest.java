package com.onlineshopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class WebVitalsRequest {

    @NotBlank
    @Pattern(regexp = "LCP|INP|CLS|FCP|TTFB",
             message = "name must be one of LCP, INP, CLS, FCP, TTFB")
    private String name;

    @NotNull
    private Double value;

    @NotBlank
    @Pattern(regexp = "good|needs-improvement|poor",
             message = "rating must be one of good, needs-improvement, poor")
    private String rating;

    @NotBlank
    private String page;

    private String navigationType;
}
