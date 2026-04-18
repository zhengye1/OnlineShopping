package com.onlineshopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FrontendErrorRequest {

    @NotBlank
    @Size(max = 500)
    private String message;

    @Size(max = 5000)
    private String stack;

    @NotBlank
    @Size(max = 500)
    private String url;

    @NotBlank
    @Size(max = 500)
    private String userAgent;

    @Size(max = 100)
    private String traceId;

    @NotBlank
    @Pattern(regexp = "fatal|error|warning|info",
             message = "severity must be one of fatal, error, warning, info")
    private String severity;
}
