package com.chandrakishorsingh.remotecompiler.execution;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionController {
    private final CodeExecutionService codeExecutionService;

    public ExecutionController(CodeExecutionService service) {
        this.codeExecutionService = service;
    }

    @PostMapping
    public ExecutionResponse execute(@Valid @RequestBody ExecutionRequest request) {
        return this.codeExecutionService.execute(request);
    }
}
