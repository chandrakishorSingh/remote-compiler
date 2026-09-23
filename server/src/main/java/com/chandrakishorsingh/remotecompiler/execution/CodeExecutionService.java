package com.chandrakishorsingh.remotecompiler.execution;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

@Service
public class CodeExecutionService {
    private static final long TIMEOUT_SECONDS = 5;
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

    private record Output(String text, boolean truncated) {}

    public ExecutionResponse execute(ExecutionRequest request) {
        Language language = Language.fromId(request.language());
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("exec-");
            Files.writeString(workDir.resolve(language.getFilename()), request.code());

            Path out = workDir.resolve("stdout.txt");
            Path err = workDir.resolve("stderr.txt");

            long startTime = System.nanoTime();

            Process process = new ProcessBuilder(language.getCommand())
            .directory(workDir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start();

            boolean isFinished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long endTime = System.nanoTime();
            long timeTakenMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

            Output stdout = readCapped(out);
            Output stderr = readCapped(err);
            boolean truncated = stdout.truncated() || stderr.truncated();

            if (!isFinished) {
                process.destroyForcibly().waitFor();
                return new ExecutionResponse(
                    ExecutionStatus.TIMEOUT,
                    stdout.text(),
                    stderr.text() + "\nexecution timed out after " + TIMEOUT_SECONDS + "s\n",
                    null,
                    timeTakenMs,
                    truncated
                );
            }

            return new ExecutionResponse(
                ExecutionStatus.COMPLETED,
                stdout.text(),
                stderr.text(),
                process.exitValue(),
                timeTakenMs,
                truncated
            );
        } catch (IOException e) {
            throw new UncheckedIOException("failed to run " + language.getId() + " code", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("execution was interrupted", e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private Output readCapped(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try (Reader reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] buffer = new char[MAX_OUTPUT_CHARS];
            int total = 0;
            while (total < buffer.length) {
                int read = reader.read(buffer, total, buffer.length - total);
                if (read == -1) {
                    return new Output(new String(buffer, 0, total), false);
                }
                total += read;
            }

            boolean hasMore = reader.read() != -1;
            return new Output(new String(buffer, 0, total), hasMore);
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach((path) -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best effort: a leftover temp file must not fail the request
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }
}
