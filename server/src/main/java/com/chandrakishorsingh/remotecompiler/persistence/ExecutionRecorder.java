package com.chandrakishorsingh.remotecompiler.persistence;

import org.springframework.stereotype.Service;

import com.chandrakishorsingh.remotecompiler.execution.ExecutionRequest;
import com.chandrakishorsingh.remotecompiler.execution.ExecutionResponse;

@Service
public class ExecutionRecorder {
    private final ExecutionRepository repository;

    public ExecutionRecorder(ExecutionRepository repository) {
        this.repository = repository;
    }

    public void record(ExecutionRequest request, ExecutionResponse response) {
        repository.save(new ExecutionEntity(
            request.language(),
            request.code(),
            response.status(),
            response.stdout(),
            response.stderr(),
            response.exitCode(),
            response.executionTimeMs(),
            response.truncated()
        ));
    }
}
