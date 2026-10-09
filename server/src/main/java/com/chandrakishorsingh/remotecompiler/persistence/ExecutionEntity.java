package com.chandrakishorsingh.remotecompiler.persistence;

import java.time.Instant;

import com.chandrakishorsingh.remotecompiler.execution.ExecutionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "executions")
public class ExecutionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String language;
    private String code;

    @Enumerated(EnumType.STRING)
    private ExecutionStatus status;

    private String stdout;
    private String stderr;
    private Integer exitCode;
    private long durationMs;

    private boolean truncated;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected ExecutionEntity() {}

    public ExecutionEntity(String language, String code, ExecutionStatus status, String stdout, String stderr, Integer exitCode, long durationMs, boolean truncated) {
        this.language = language;
        this.code = code;
        this.status = status;
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitCode;
        this.durationMs = durationMs;
        this.truncated = truncated;
    }

    public Long getId() { return id; }
    public String getLanguage() { return language; }
    public String getCode() { return code; }
    public ExecutionStatus getStatus() { return status; }
    public String getStdout() { return stdout; }
    public String getStderr() { return stderr; }
    public Integer getExitCode() { return exitCode; }
    public long getDurationMs() { return durationMs; }
    public boolean isTruncated() { return truncated; }
    public Instant getCreatedAt() { return createdAt; }
}
