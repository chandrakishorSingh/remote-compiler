package com.chandrakishorsingh.remotecompiler.execution;

public record ExecutionResponse(
    ExecutionStatus status,
    String stdout,
    String stderr,
    Integer exitCode,
    long executionTimeMs,
    boolean truncated
) {}
