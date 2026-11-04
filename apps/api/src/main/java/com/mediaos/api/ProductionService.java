package com.mediaos.api;

import static com.mediaos.api.Models.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/** Local production-job dispatcher. Provider and media execution stay in the Python worker. */
@Service
public class ProductionService implements AutoCloseable {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern VISUAL_ID = Pattern.compile("(?:mock|supplied)-visual-[1-4]");
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "RESEARCHING", "SCRIPTING",
            "GENERATING_AUDIO", "GENERATING_VISUALS", "RENDERING", "QA_PENDING", "CANCELLING");

    private record ProductionState(Map<String, ProductionJob> jobs, Map<String, String> idempotency) {}

    private final ObjectMapper mapper;
    private final MediaOsService contentService;
    private final ReviewService reviewService;
    private final Path dataDirectory;
    private final Path jobDirectory;
    private final Path stateFile;
    private final Path workerDirectory;
    private final String pythonExecutable;
    private final String providerConfigJson;
    private final boolean realProvidersEnabled;
    private final long jobTimeoutSeconds;
    private final ExecutorService executor;
    private final Map<String, ProductionJob> jobs = new LinkedHashMap<>();
    private final Map<String, String> idempotency = new LinkedHashMap<>();
    private final ProductionExecutionAdapter executionAdapter;
    private final Map<String, ProductionExecutionAdapter.Handle> processes = new LinkedHashMap<>();

    public ProductionService(ObjectMapper mapper, MediaOsService contentService, ReviewService reviewService,
            ProductionExecutionAdapter executionAdapter,
            @Value("${mediaos.data-dir:${user.home}/.mediaos}") String dataDirectory,
            @Value("${mediaos.worker.dir:../../workers/shorts-worker}") String workerDirectory,
            @Value("${mediaos.worker.python:python}") String pythonExecutable,
            @Value("${mediaos.provider.config-json:}") String providerConfigJson,
            @Value("${mediaos.provider.real-enabled:false}") boolean realProvidersEnabled,
            @Value("${mediaos.worker.job-timeout-seconds:900}") long jobTimeoutSeconds,
            @Value("${mediaos.worker.max-concurrent-jobs:1}") int maxConcurrentJobs) {
        this.mapper = mapper;
        this.contentService = contentService;
        this.reviewService = reviewService;
        this.executionAdapter = executionAdapter;
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.jobDirectory = this.dataDirectory.resolve("production");
        this.stateFile = this.dataDirectory.resolve("production-state.json");
        this.workerDirectory = Path.of(workerDirectory).toAbsolutePath().normalize();
        this.pythonExecutable = pythonExecutable;
        this.providerConfigJson = providerConfigJson;
        this.realProvidersEnabled = realProvidersEnabled;
        this.jobTimeoutSeconds = Math.max(30, Math.min(3600, jobTimeoutSeconds));
        this.executor = Executors.newFixedThreadPool(Math.max(1, Math.min(4, maxConcurrentJobs)));
        loadState();
    }

    public synchronized ProductionJob start(String contentId, ProductionStartRequest request) {
        ContentItem content = contentService.getContent(contentId);
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128)
            throw new IllegalArgumentException("idempotencyKey is required and must be 1-128 characters");
        if (Set.of("APPROVED", "EXPORT_READY", "REJECTED", "CANCELLED", "IN_REVIEW").contains(content.status()))
            throw new ConflictException("content in " + content.status() + " cannot start a new production job");
        String profileId = request.productionProfileId() == null || request.productionProfileId().isBlank()
                ? content.productionProfileId() : request.productionProfileId().trim();
        if (profileId == null) throw new IllegalArgumentException("select a production profile before starting production");
        ProductionProfile profile = contentService.getProfile(profileId);
        if (profile.brandId() != null && !profile.brandId().equals(content.brandId()))
            throw new IllegalArgumentException("production profile belongs to a different brand");
        if (profile.categoryId() != null && !profile.categoryId().equals(content.categoryId()))
            throw new IllegalArgumentException("production profile is not configured for this category");
        if (!profile.contentFormat().equals(content.contentType()))
            throw new IllegalArgumentException("production profile content format does not match the selected content item");
        String key = contentId + ":" + request.idempotencyKey().trim();
        String oldId = idempotency.get(key);
        if (oldId != null) return get(oldId);
        if (jobs.values().stream().anyMatch(job -> job.contentId().equals(contentId) && ACTIVE.contains(job.status())))
            throw new ConflictException("a production job is already active for this content item");

        Map<String, Object> workflow = resolveWorkflow(content, profile);
        validateWorkflow(workflow);
        applyBrandBudget(workflow, content.brandId());
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String workflowId = String.valueOf(workflow.getOrDefault("workflow_id", profile.id()));
        String workflowVersion = String.valueOf(workflow.getOrDefault("version", "1"));
        String mode = String.valueOf(workflow.getOrDefault("mode", "MOCK"));
        ProductionJob job = new ProductionJob(jobId, contentId, content.brandId(), profileId, workflowId,
                workflowVersion, mode, "QUEUED", "QUEUED", request.idempotencyKey().trim(), now, null, null,
                List.of(), Map.of(), Map.of(), Map.of(), List.of(), nullableUsage(), List.of(),
                List.of(), null, 0, true);
        jobs.put(jobId, job);
        idempotency.put(key, jobId);
        persistState();
        if (!"shorts-vertical-v1".equals(workflow.get("renderer"))) {
            ProductionError error = new ProductionError("unsupported_renderer",
                    "This workflow profile has no supported video renderer. Configure shorts-vertical-v1 for a compatible video workflow.", false, "render");
            ProductionJob unsupported = copy(job, "UNSUPPORTED_RENDERER", "UNSUPPORTED_RENDERER", null,
                    null, List.of(error), List.of(), null, null);
            jobs.put(jobId, unsupported);
            persistState();
            return unsupported;
        }
        executor.submit(() -> execute(jobId, content, profile, workflow));
        return job;
    }

    public synchronized ProductionJob get(String jobId) {
        ProductionJob job = requireJob(jobId);
        if (ACTIVE.contains(job.status()) && !Set.of("QUEUED", "CANCELLING").contains(job.status())) {
            Path progress = jobDirectory.resolve(job.id()).resolve("progress.json");
            if (Files.isRegularFile(progress)) {
                try {
                    Map<String, Object> update = mapper.readValue(progress.toFile(), MAP_TYPE);
                    String stage = String.valueOf(update.getOrDefault("stage", job.stage()));
                    Map<String, Object> usage = map(update.get("usage"));
                    if (Set.of("RESEARCHING", "SCRIPTING", "GENERATING_AUDIO", "GENERATING_VISUALS", "RENDERING", "QA_PENDING").contains(stage)
                            && (!stage.equals(job.stage()) || !stage.equals(job.status()) || !usage.equals(job.usage()))) {
                        job = copy(job, stage, stage, job.startedAt(), null, job.errors(), job.warnings(), job.renderArtifactId(),
                                usage.isEmpty() ? job.usage() : usage);
                        jobs.put(jobId, job);
                        persistState();
                    }
                } catch (IOException ignored) { }
            }
        }
        return job;
    }

    public synchronized List<ProductionJob> list(String contentId) {
        if (contentId != null && !contentId.isBlank()) contentService.getContent(contentId);
        return jobs.values().stream().filter(job -> contentId == null || contentId.isBlank() || job.contentId().equals(contentId))
                .map(job -> get(job.id())).toList();
    }

    public synchronized ProductionJob cancel(String jobId) {
        ProductionJob job = requireJob(jobId);
        if (!ACTIVE.contains(job.status())) throw new ConflictException("production job is not cancellable in status " + job.status());
        jobs.put(jobId, copy(job, "CANCELLING", "CANCELLING", job.startedAt(), null,
                job.errors(), job.warnings(), job.renderArtifactId(), job.usage()));
        ProductionExecutionAdapter.Handle process = processes.get(jobId);
        if (process != null) process.cancel(false);
        persistState();
        return jobs.get(jobId);
    }

    public Resource visual(String jobId, String assetId) {
        ProductionJob job = requireJob(jobId);
        if (!VISUAL_ID.matcher(assetId).matches()) throw new NotFoundException("production asset not found");
        Path directory = jobDirectory.resolve(job.id()).resolve("visuals").normalize();
        Path file = directory.resolve(assetId.replace("mock-visual-", "visual-") + ".png").normalize();
        if (assetId.startsWith("supplied-")) {
            file = directory.resolve(assetId + ".png").normalize();
            if (!Files.isRegularFile(file)) file = directory.resolve(assetId + ".jpg").normalize();
            if (!Files.isRegularFile(file)) file = directory.resolve(assetId + ".jpeg").normalize();
        }
        if (!file.startsWith(directory) || !Files.isRegularFile(file))
            throw new NotFoundException("production asset not found");
        return new FileSystemResource(file);
    }

    public String visualMimeType(String jobId, String assetId) {
        Resource file = visual(jobId, assetId);
        String name = file.getFilename() == null ? "" : file.getFilename().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : "image/png";
    }

    public Resource audio(String jobId) {
        ProductionJob job = requireJob(jobId);
        Path file = jobDirectory.resolve(job.id()).resolve("narration.wav").normalize();
        if (!Files.isRegularFile(file)) throw new NotFoundException("production audio not found");
        return new FileSystemResource(file);
    }

    public ProviderCatalog catalog() {
        boolean scriptCredential = System.getenv("MEDIAOS_OPENAI_COMPAT_API_KEY") != null
                && !System.getenv("MEDIAOS_OPENAI_COMPAT_API_KEY").isBlank();
        String assetDirectory = System.getenv("MEDIAOS_SOURCE_ASSET_DIR");
        String visualStatus = "DIRECTORY_REQUIRED";
        if (assetDirectory != null && !assetDirectory.isBlank()) {
            try { visualStatus = Files.isDirectory(Path.of(assetDirectory)) ? "ALLOWLISTED" : "DIRECTORY_UNAVAILABLE"; }
            catch (RuntimeException ignored) { visualStatus = "DIRECTORY_UNAVAILABLE"; }
        }
        return new ProviderCatalog("MOCK", realProvidersEnabled, List.of(
                new ProviderCapability("research", List.of("mock", "user-supplied"), Map.of("mock", "LOCAL_READY", "user-supplied", "LOCAL_READY")),
                new ProviderCapability("script", List.of("mock", "user-supplied", "openai-compatible"), Map.of(
                        "mock", "LOCAL_READY", "user-supplied", "LOCAL_READY",
                        "openai-compatible", !realProvidersEnabled ? "DISABLED" : scriptCredential ? "ENABLED" : "MISSING_CREDENTIAL")),
                new ProviderCapability("text_to_speech", List.of("mock"), Map.of("mock", "LOCAL_READY")),
                new ProviderCapability("visual_asset", List.of("mock", "user-supplied"), Map.of(
                        "mock", "LOCAL_READY", "user-supplied", visualStatus))));
    }

    private void execute(String jobId, ContentItem content, ProductionProfile profile, Map<String, Object> workflow) {
        Path directory = jobDirectory.resolve(jobId).normalize();
        ProductionExecutionAdapter.Handle process = null;
        try {
            Files.createDirectories(directory);
            Brand brand = contentService.getBrand(content.brandId());
            Category category = content.categoryId() == null ? null : contentService.getCategory(content.categoryId());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("job_id", jobId);
            input.put("content", content);
            input.put("brand", brand);
            input.put("category", category);
            input.put("profile", profile);
            input.put("workflow", workflow);
            Path inputFile = directory.resolve("input.json");
            mapper.writeValue(inputFile.toFile(), input);
            Path progress = directory.resolve("progress.json");
            process = executionAdapter.start(workerDirectory, pythonExecutable, inputFile, directory, progress, realProvidersEnabled);
            synchronized (this) { processes.put(jobId, process); }
            updateRunning(jobId, process);
            if (!process.waitFor(jobTimeoutSeconds, TimeUnit.SECONDS)) {
                process.cancel(true);
                throw new IllegalStateException("local production worker exceeded its configured timeout");
            }
            if (process.exitCode() != 0) {
                Path failureFile = directory.resolve("production-failure.json");
                Map<String, Object> failure = Files.isRegularFile(failureFile)
                        ? mapper.readValue(failureFile.toFile(), MAP_TYPE) : Map.of("code", "worker_failed", "message", "local worker failed without a failure report");
                String code = String.valueOf(failure.getOrDefault("code", "worker_failed"));
                String message = String.valueOf(failure.getOrDefault("message", "local worker failed"));
                throw new WorkerFailure(code, message, Boolean.TRUE.equals(failure.get("retryable")));
            }
            Map<String, Object> result = mapper.readValue(directory.resolve("production-result.json").toFile(), MAP_TYPE);
            if (!"READY_FOR_REVIEW".equals(result.get("status"))) {
                Object errors = result.get("errors");
                Object firstError = errors instanceof List<?> list && !list.isEmpty() ? list.get(0) : "unsupported renderer";
                throw new WorkerFailure("unsupported_renderer", String.valueOf(firstError), false);
            }
            Map<String, Object> render = map(result.get("render"));
            Path video = safeJobPath(directory, render.get("video_path"));
            Path manifest = safeJobPath(directory, render.get("manifest_path"));
            Path qaReport = safeJobPath(directory, render.get("qa_path"));
            synchronized (this) {
                if ("CANCELLING".equals(requireJob(jobId).status())) { finishCancelled(jobId); return; }
            }
            RenderArtifact artifact = reviewService.registerRenderFiles(content.id(), video, manifest, qaReport);
            List<String> warnings = stringList(result.get("warnings"));
            warnings.add("Generated draft is pending human review. No approval, export, or publishing action was performed.");
            synchronized (this) {
                ProductionJob old = requireJob(jobId);
                if ("CANCELLING".equals(old.status())) {
                    finishCancelled(jobId);
                    return;
                }
                ProductionJob ready = new ProductionJob(old.id(), old.contentId(), old.brandId(), old.productionProfileId(),
                        String.valueOf(result.getOrDefault("workflow_id", old.workflowId())),
                        String.valueOf(result.getOrDefault("workflow_version", old.workflowVersion())), old.mode(),
                        "READY_FOR_REVIEW", "READY_FOR_REVIEW", old.idempotencyKey(), old.createdAt(), old.startedAt(), Instant.now(),
                        castMapList(result.get("provider_executions")), map(result.get("research_packet")), map(result.get("script")),
                        map(result.get("narration")), castMapList(result.get("visuals")), map(result.get("usage")),
                        warnings, List.of(), artifact.id(), old.retryCount(), true);
                jobs.put(jobId, ready);
                persistState();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            finishCancelled(jobId);
        } catch (WorkerFailure error) {
            fail(jobId, error.code, error.getMessage(), error.retryable);
        } catch (Exception error) {
            fail(jobId, "production_failed", error.getMessage() == null ? "production job failed" : error.getMessage(), false);
        } finally {
            synchronized (this) { processes.remove(jobId); }
        }
    }

    private synchronized void updateRunning(String id, ProductionExecutionAdapter.Handle process) {
        ProductionJob job = requireJob(id);
        if ("CANCELLING".equals(job.status())) { process.cancel(false); return; }
        jobs.put(id, copy(job, "RESEARCHING", "RESEARCHING", Instant.now(), null, job.errors(), job.warnings(), null, job.usage()));
        persistState();
    }

    private synchronized void fail(String id, String code, String message, boolean retryable) {
        ProductionJob job = jobs.get(id);
        if (job == null || "CANCELLED".equals(job.status())) return;
        if ("CANCELLING".equals(job.status())) { finishCancelled(id); return; }
        ProductionError error = new ProductionError(code, message, retryable, null);
        Map<String, Object> usage = progressUsage(id);
        jobs.put(id, copy(job, "FAILED", "FAILED", job.startedAt(), Instant.now(), List.of(error), job.warnings(), null,
                usage.isEmpty() ? job.usage() : usage));
        persistState();
    }

    private Map<String, Object> progressUsage(String id) {
        Path progress = jobDirectory.resolve(id).resolve("progress.json");
        if (!Files.isRegularFile(progress)) return Map.of();
        try { return map(mapper.readValue(progress.toFile(), MAP_TYPE).get("usage")); }
        catch (IOException ignored) { return Map.of(); }
    }

    private synchronized void finishCancelled(String id) {
        ProductionJob job = jobs.get(id);
        if (job == null) return;
        ProductionError cancelled = new ProductionError("cancelled", "Production job was cancelled by the user.", false, null);
        jobs.put(id, copy(job, "CANCELLED", "CANCELLED", job.startedAt(), Instant.now(), List.of(cancelled),
                job.warnings(), null, job.usage()));
        persistState();
    }

    private Map<String, Object> resolveWorkflow(ContentItem content, ProductionProfile profile) {
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("mode", "MOCK");
        effective.put("providers", Map.of("research", "mock", "script", "mock", "text_to_speech", "mock", "visual_asset", "mock"));
        effective.put("prompt_template_id", "default-script");
        effective.put("prompt_version", "1");
        Map<String, Object> configured = parseConfig(providerConfigJson);
        merge(effective, mapOrEmpty(configured.get("defaults")));
        merge(effective, mapOrEmpty(mapOrEmpty(configured.get("brands")).get(content.brandId())));
        if (content.categoryId() != null) merge(effective, mapOrEmpty(mapOrEmpty(configured.get("categories")).get(content.categoryId())));
        merge(effective, mapOrEmpty(mapOrEmpty(configured.get("profiles")).get(profile.id())));
        String profileJson = profile.settings() == null ? null : profile.settings().get("mediaos.workflow_json");
        if (profileJson != null && !profileJson.isBlank()) merge(effective, parseConfig(profileJson));
        effective.putIfAbsent("workflow_id", profile.id());
        effective.putIfAbsent("version", "1");
        return effective;
    }

    private synchronized void applyBrandBudget(Map<String, Object> workflow, String brandId) {
        Map<String, Object> limits = mapOrEmpty(workflow.get("limits"));
        Object configured = limits.get("brand_budget_usd");
        if (configured == null) return;
        double brandBudget;
        try { brandBudget = Double.parseDouble(String.valueOf(configured)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("brand_budget_usd must be numeric"); }
        if (!Double.isFinite(brandBudget) || brandBudget < 0) throw new IllegalArgumentException("brand_budget_usd must be a non-negative finite number");
        double spent = jobs.values().stream().filter(job -> brandId.equals(job.brandId()))
                .mapToDouble(job -> number(job.usage().get("reported_cost_usd"))).sum();
        if (spent >= brandBudget) throw new ConflictException("configured brand production budget has been reached");
        if (jobs.values().stream().anyMatch(job -> brandId.equals(job.brandId()) && ACTIVE.contains(job.status())))
            throw new ConflictException("another production job for this brand is active; brand budget accounting is serialized");
        double remaining = brandBudget - spent;
        Object jobBudget = limits.get("job_budget_usd");
        if (jobBudget != null) {
            try { remaining = Math.min(remaining, Double.parseDouble(String.valueOf(jobBudget))); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("job_budget_usd must be numeric"); }
        }
        Map<String, Object> bounded = new LinkedHashMap<>(limits);
        bounded.put("job_budget_usd", remaining);
        workflow.put("limits", bounded);
    }

    private void validateWorkflow(Map<String, Object> workflow) {
        String mode = String.valueOf(workflow.getOrDefault("mode", "MOCK")).toUpperCase();
        if (!Set.of("MOCK", "REAL").contains(mode)) throw new IllegalArgumentException("workflow mode must be MOCK or REAL");
        workflow.put("mode", mode);
        Map<String, Object> providers = mapOrEmpty(workflow.get("providers"));
        Map<String, Set<String>> supported = Map.of("research", Set.of("mock", "user-supplied"),
                "script", Set.of("mock", "user-supplied", "openai-compatible"), "text_to_speech", Set.of("mock"), "visual_asset", Set.of("mock", "user-supplied"));
        for (String capability : supported.keySet()) {
            String provider = String.valueOf(providers.getOrDefault(capability, "mock"));
            if (!supported.get(capability).contains(provider)) throw new IllegalArgumentException("unsupported provider " + provider + " for capability " + capability);
            if ("MOCK".equals(mode) && !Set.of("mock", "user-supplied").contains(provider))
                throw new IllegalArgumentException("non-mock provider selection requires workflow mode REAL");
            if ("REAL".equals(mode) && !Set.of("mock", "user-supplied").contains(provider) && !realProvidersEnabled)
                throw new IllegalArgumentException("real provider calls are disabled; enable them explicitly in the API environment");
            if ("openai-compatible".equals(provider)) {
                if (System.getenv("MEDIAOS_OPENAI_COMPAT_API_KEY") == null || System.getenv("MEDIAOS_OPENAI_COMPAT_API_KEY").isBlank())
                    throw new IllegalArgumentException("MEDIAOS_OPENAI_COMPAT_API_KEY is required for the selected script provider");
                if (blank(workflow.get("base_url")) || blank(workflow.get("model")))
                    throw new IllegalArgumentException("OpenAI-compatible script provider needs profile base_url and model settings");
            }
        }
        Object renderer = workflow.get("renderer");
        if (renderer != null && !(renderer instanceof String)) throw new IllegalArgumentException("workflow renderer must be a string");
        if (workflow.containsKey("duration_seconds")) {
            double duration;
            try { duration = Double.parseDouble(String.valueOf(workflow.get("duration_seconds"))); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("duration_seconds must be numeric"); }
            if (!Double.isFinite(duration) || duration < 1.0 / 30 || duration > 600) throw new IllegalArgumentException("duration_seconds must be between one frame and 600 seconds");
        }
        workflow.put("providers", providers);
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = mapper.readValue(json, MAP_TYPE);
            ProviderConfigSecurity.rejectSecretFields(parsed);
            return parsed;
        }
        catch (IOException error) { throw new IllegalArgumentException("provider workflow configuration must be valid JSON", error); }
    }

    private static void merge(Map<String, Object> destination, Map<String, Object> overlay) {
        overlay.forEach((key, value) -> {
            if (value instanceof Map<?, ?> incoming && destination.get(key) instanceof Map<?, ?> existing) {
                Map<String, Object> combined = new LinkedHashMap<>();
                existing.forEach((nestedKey, nestedValue) -> combined.put(String.valueOf(nestedKey), nestedValue));
                Map<String, Object> typed = new LinkedHashMap<>();
                incoming.forEach((nestedKey, nestedValue) -> typed.put(String.valueOf(nestedKey), nestedValue));
                merge(combined, typed);
                destination.put(key, combined);
            } else destination.put(key, value);
        });
    }

    private Path safeJobPath(Path directory, Object value) {
        if (!(value instanceof String path) || path.isBlank()) throw new IllegalArgumentException("worker result omitted a required artifact reference");
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(directory.toAbsolutePath().normalize())) throw new IllegalArgumentException("worker output path escaped its generated job directory");
        if (!Files.isRegularFile(resolved)) throw new IllegalArgumentException("worker output artifact is missing");
        return resolved;
    }

    private synchronized ProductionJob requireJob(String id) {
        try { UUID.fromString(id); } catch (IllegalArgumentException error) { throw new NotFoundException("production job not found"); }
        ProductionJob job = jobs.get(id);
        if (job == null) throw new NotFoundException("production job not found: " + id);
        return job;
    }

    private void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            ProductionState state = mapper.readValue(stateFile.toFile(), ProductionState.class);
            if (state.jobs() != null) jobs.putAll(state.jobs());
            if (state.idempotency() != null) idempotency.putAll(state.idempotency());
            boolean changed = false;
            for (ProductionJob job : new ArrayList<>(jobs.values())) {
                if (ACTIVE.contains(job.status())) {
                    ProductionError error = new ProductionError("process_restarted", "Local worker stopped when the API process restarted.", false, null);
                    jobs.put(job.id(), copy(job, "FAILED", "FAILED", job.startedAt(), Instant.now(), List.of(error), job.warnings(), null, job.usage()));
                    changed = true;
                }
            }
            if (changed) persistState();
        } catch (IOException error) { throw new IllegalStateException("Could not load local production jobs from " + stateFile, error); }
    }

    private synchronized void persistState() {
        Path temporary = stateFile.resolveSibling("production-state.json.tmp");
        try {
            Files.createDirectories(dataDirectory);
            mapper.writeValue(temporary.toFile(), new ProductionState(jobs, idempotency));
            try { Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("Could not persist local production jobs", error);
        }
    }

    private static ProductionJob copy(ProductionJob old, String status, String stage, Instant startedAt, Instant completedAt,
                                     List<ProductionError> errors, List<String> warnings, String artifactId, Map<String, Object> usage) {
        return new ProductionJob(old.id(), old.contentId(), old.brandId(), old.productionProfileId(), old.workflowId(), old.workflowVersion(),
                old.mode(), status, stage, old.idempotencyKey(), old.createdAt(), startedAt, completedAt, old.providerExecutions(),
                old.researchPacket(), old.script(), old.narration(), old.visuals(), usage == null ? old.usage() : usage,
                warnings, errors, artifactId, old.retryCount(), old.cancellationSupported());
    }

    private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    private static Map<String, Object> mapOrEmpty(Object value) { return map(value); }
    private static List<Map<String, Object>> castMapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?>) result.add(map(item));
        return List.copyOf(result);
    }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object item : list) if (item instanceof String text) result.add(text);
        return result;
    }
    private static boolean blank(Object value) { return value == null || String.valueOf(value).isBlank(); }
    private static double number(Object value) {
        if (value instanceof Number number) return Math.max(0, number.doubleValue());
        return 0;
    }

    private static Map<String, Object> nullableUsage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("reported_cost_usd", null);
        return usage;
    }

    @Override public void close() {
        for (ProductionExecutionAdapter.Handle process : processes.values()) process.cancel(false);
        executor.shutdownNow();
    }

    private static final class WorkerFailure extends RuntimeException {
        final String code; final boolean retryable;
        WorkerFailure(String code, String message, boolean retryable) { super(message); this.code = code; this.retryable = retryable; }
    }
}
