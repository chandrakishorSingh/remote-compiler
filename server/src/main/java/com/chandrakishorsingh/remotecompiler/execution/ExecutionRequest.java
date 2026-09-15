package com.chandrakishorsingh.remotecompiler.execution;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExecutionRequest(
    @NotBlank(message = "language is required")
    String language,

    @NotBlank(message = "code is required")
    @Size(max = 65536, message = "code must be at most 65536 characters")
    String code
) {}
