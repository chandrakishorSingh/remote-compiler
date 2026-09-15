package com.chandrakishorsingh.remotecompiler.execution;

public record ExecutionResponse(String stdout, String stderr, int exitCode, long executionTimeMs, boolean truncated) {}
