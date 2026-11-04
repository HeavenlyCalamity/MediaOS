package com.mediaos.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Boundary between job lifecycle/business logic and the local or future remote job dispatcher. */
public interface ProductionExecutionAdapter {
    Handle start(Path workerDirectory, String pythonExecutable, Path inputFile, Path outputDirectory,
                 Path progressFile, boolean realProvidersEnabled) throws IOException;

    interface Handle {
        boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException;
        int exitCode();
        void cancel(boolean force);
    }
}
