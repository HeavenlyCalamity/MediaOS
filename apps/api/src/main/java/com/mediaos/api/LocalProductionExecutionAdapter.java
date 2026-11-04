package com.mediaos.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Local Python process adapter. A future remote dispatcher can implement the same handle boundary. */
@Component
public class LocalProductionExecutionAdapter implements ProductionExecutionAdapter {
    @Override public Handle start(Path workerDirectory, String pythonExecutable, Path inputFile,
                                  Path outputDirectory, Path progressFile,
                                  boolean realProvidersEnabled) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, "-m", "shorts_worker.cli", "produce",
                "--input", inputFile.toString(), "--output-dir", outputDirectory.toString(),
                "--progress-file", progressFile.toString())
                .directory(workerDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        String sourcePath = workerDirectory.resolve("src").toString();
        String existing = builder.environment().get("PYTHONPATH");
        builder.environment().put("PYTHONPATH", existing == null || existing.isBlank() ? sourcePath
                : sourcePath + java.io.File.pathSeparator + existing);
        builder.environment().put("MEDIAOS_REAL_PROVIDERS_ENABLED", Boolean.toString(realProvidersEnabled));
        return new LocalHandle(builder.start());
    }

    private static final class LocalHandle implements Handle {
        private final Process process;
        private LocalHandle(Process process) { this.process = process; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return process.waitFor(timeout, unit); }
        @Override public int exitCode() { return process.exitValue(); }
        @Override public void cancel(boolean force) {
            process.toHandle().descendants().forEach(handle -> { if (force) handle.destroyForcibly(); else handle.destroy(); });
            if (force) process.destroyForcibly(); else process.destroy();
        }
    }
}
