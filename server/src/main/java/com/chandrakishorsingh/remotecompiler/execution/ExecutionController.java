package com.chandrakishorsingh.remotecompiler.execution;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chandrakishorsingh.remotecompiler.persistence.ExecutionRecorder;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionController {
    private final CodeExecutionService codeExecutionService;
    private final ExecutionRecorder executionRecorder;

    public ExecutionController(CodeExecutionService service, ExecutionRecorder recorder) {
        this.codeExecutionService = service;
        this.executionRecorder = recorder;
    }

    @PostMapping
    public ExecutionResponse execute(@Valid @RequestBody ExecutionRequest request) {
        ExecutionResponse response = this.codeExecutionService.execute(request);
        executionRecorder.record(request, response);
        return response;
    }
}
