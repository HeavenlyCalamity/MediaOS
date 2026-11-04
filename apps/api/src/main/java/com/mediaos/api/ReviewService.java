package com.mediaos.api;

import static com.mediaos.api.Models.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Local artifact registry and explicit, immutable human-review lifecycle. */
@Service
public class ReviewService {
    private static final long JSON_UPLOAD_LIMIT = 2 * 1024 * 1024;
    private static final List<String> REQUIRED_QA_CHECKS = List.of("video_stream", "dimensions", "video_codec",
            "frame_rate", "pixel_format", "audio_stream_when_required", "audio_codec_when_present", "duration",
            "readable_by_ffprobe");

    private final ObjectMapper mapper;
    private final MediaOsService contentService;
    private final Path dataDirectory;
    private final Path artifactDirectory;
    private final Path stateFile;
    private final long maxVideoBytes;
    private final String ffprobeExecutable;
    private final Map<String, RenderArtifact> artifacts = new LinkedHashMap<>();
    private final Map<String, String> currentArtifactByContent = new LinkedHashMap<>();
    private final List<ReviewEvent> events = new ArrayList<>();

    private record ReviewState(Map<String, RenderArtifact> artifacts,
                               Map<String, String> currentArtifactByContent,
                               List<ReviewEvent> events) {}

    public static final class ExportPlan {
        private final ExportPackage descriptor;
        private final Path video;
        private final byte[] metadata;
        private final byte[] sourcesAndRights;
        private final byte[] productionManifest;
        private final byte[] qaReport;
        private final byte[] approval;

        private ExportPlan(ExportPackage descriptor, Path video, byte[] metadata, byte[] sourcesAndRights,
                           byte[] productionManifest, byte[] qaReport, byte[] approval) {
            this.descriptor = descriptor;
            this.video = video;
            this.metadata = metadata;
            this.sourcesAndRights = sourcesAndRights;
            this.productionManifest = productionManifest;
            this.qaReport = qaReport;
            this.approval = approval;
        }
        public ExportPackage descriptor() { return descriptor; }
        public Path video() { return video; }
    }

    public ReviewService(ObjectMapper mapper, MediaOsService contentService,
                         @Value("${mediaos.data-dir:${user.home}/.mediaos}") String dataDirectory,
                         @Value("${mediaos.upload.max-video-bytes:524288000}") long maxVideoBytes,
                         @Value("${mediaos.ffprobe:ffprobe}") String ffprobeExecutable) {
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.contentService = contentService;
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.artifactDirectory = this.dataDirectory.resolve("artifacts");
        this.stateFile = this.dataDirectory.resolve("review-state.json");
        this.maxVideoBytes = maxVideoBytes;
        this.ffprobeExecutable = ffprobeExecutable;
        loadState();
    }

    public synchronized RenderArtifact registerRender(String contentId, MultipartFile video,
                                                       MultipartFile manifestFile, MultipartFile qaFile) {
        synchronized (contentService) {
            ContentItem content = contentService.getContent(contentId);
            if (Set.of("FAILED", "CANCELLED").contains(content.status()))
                throw new IllegalArgumentException("failed or cancelled content cannot enter review");
            if (video == null || video.isEmpty() || video.getSize() > maxVideoBytes)
                throw new IllegalArgumentException("rendered MP4 must be non-empty and within the configured upload limit");
            if (!sanitizeFilename(video.getOriginalFilename()).toLowerCase().endsWith(".mp4"))
                throw new IllegalArgumentException("rendered video must use the .mp4 file type");
            byte[] manifestBytes = readJsonUpload(manifestFile, "production manifest");
            byte[] qaBytes = readJsonUpload(qaFile, "QA report");
            JsonNode manifest = parseJson(manifestBytes, "production manifest");
            JsonNode qa = parseJson(qaBytes, "QA report");
            validateManifestAndQa(content, manifest, qa);

            String artifactId = UUID.randomUUID().toString();
            Path staging = artifactDirectory.resolve(".staging-" + artifactId);
            Path finalDirectory = artifactDirectory.resolve(artifactId);
            try {
                Files.createDirectories(staging);
                Path stagedVideo = staging.resolve("video.mp4");
                try (InputStream input = video.getInputStream()) { Files.copy(input, stagedVideo); }
                if (!Files.isRegularFile(stagedVideo) || Files.size(stagedVideo) == 0 || Files.size(stagedVideo) > maxVideoBytes)
                    throw new IllegalArgumentException("rendered MP4 is empty or exceeds the upload limit");
                String actualChecksum = sha256(stagedVideo);
                String reportedChecksum = requiredText(qa.path("sha256"), "QA report sha256");
                if (!actualChecksum.equalsIgnoreCase(reportedChecksum))
                    throw new IllegalArgumentException("QA report checksum does not match the uploaded MP4");

                JsonNode probed = probeVideo(stagedVideo);
                Map<String, Object> outputMetadata = validateActualOutput(manifest, qa, probed, stagedVideo);
                Files.write(staging.resolve("production-manifest.json"), manifestBytes);
                Files.write(staging.resolve("qa-report.json"), qaBytes);
                moveDirectory(staging, finalDirectory);

                String oldArtifactId = currentArtifactByContent.get(contentId);
                if (oldArtifactId != null) {
                    RenderArtifact old = artifacts.get(oldArtifactId);
                    if (old != null) artifacts.put(oldArtifactId, withActive(old, false));
                    invalidateCurrentApproval(contentId, "render artifact replaced");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> checks = mapper.convertValue(qa.path("checks"), Map.class);
                RenderArtifact artifact = new RenderArtifact(artifactId, contentId, content.brandId(),
                        sanitizeFilename(video.getOriginalFilename()), actualChecksum, Instant.now(),
                        manifest.path("schema_version").asText(), manifest.path("render").path("profile").asText(),
                        outputMetadata, checks, manifestProvenance(manifest),
                        subtitleSummary(qa.path("subtitle")), stringList(qa.path("warnings")), true);
                artifacts.put(artifactId, artifact);
                currentArtifactByContent.put(contentId, artifactId);
                if (!"REJECTED".equals(content.status())) contentService.setReviewStatus(contentId, "IN_REVIEW");
                persistState();
                return artifact;
            } catch (IOException error) {
                deleteTree(staging);
                deleteTree(finalDirectory);
                throw new IllegalArgumentException("could not store render artifact: " + error.getMessage(), error);
            } catch (RuntimeException error) {
                deleteTree(staging);
                deleteTree(finalDirectory);
                throw error;
            }
        }
    }

    public synchronized List<RenderArtifact> listArtifacts(String contentId) {
        contentService.getContent(contentId);
        return artifacts.values().stream().filter(item -> item.contentId().equals(contentId)).toList();
    }

    public synchronized RenderArtifact getArtifact(String artifactId) {
        return requireArtifact(artifactId);
    }

    public synchronized Resource videoResource(String artifactId) {
        RenderArtifact artifact = requireArtifact(artifactId);
        Path video = artifactPath(artifact.id()).resolve("video.mp4");
        if (!Files.isRegularFile(video)) throw new NotFoundException("render video is no longer available");
        return new FileSystemResource(video);
    }

    public synchronized ReviewSummary summary(String contentId) {
        ContentItem content = contentService.getContent(contentId);
        String brandName = contentService.getBrand(content.brandId()).name();
        String categoryName = content.categoryId() == null ? null : contentService.getCategory(content.categoryId()).name();
        String artifactId = currentArtifactByContent.get(contentId);
        RenderArtifact artifact = artifactId == null ? null : artifacts.get(artifactId);
        CurrentApproval approval = currentApproval(contentId);
        ReviewEvent latest = latestEvent(contentId);
        String status;
        if (approval.valid()) status = "APPROVED";
        else if ("REJECTED".equals(content.status())) status = "REJECTED";
        else if (latest != null && "APPROVED".equals(latest.decision()) && latest.invalidated()) status = "INVALIDATED";
        else if (artifact != null && "IN_REVIEW".equals(content.status())) status = "PENDING";
        else status = "NOT_READY";
        return new ReviewSummary(content, brandName, categoryName, status, artifact, approval);
    }

    public synchronized List<ReviewSummary> queue(String brandId, String categoryId, String format, String status) {
        return contentService.listContent(brandId).stream().map(item -> summary(item.id()))
                .filter(item -> categoryId == null || categoryId.isBlank() || categoryId.equals(item.content().categoryId()))
                .filter(item -> format == null || format.isBlank() || format.equals(item.content().contentType()))
                .filter(item -> status == null || status.isBlank() || status.equalsIgnoreCase(item.reviewStatus()))
                .toList();
    }

    public synchronized CurrentApproval currentApproval(String contentId) {
        ContentItem content = contentService.getContent(contentId);
        ReviewEvent latest = latestApproval(contentId);
        if (latest == null) return new CurrentApproval(false, null, "No approval record exists");
        if (latest.invalidated()) return new CurrentApproval(false, latest, latest.invalidationReason());
        String currentArtifactId = currentArtifactByContent.get(contentId);
        RenderArtifact currentArtifact = currentArtifactId == null ? null : artifacts.get(currentArtifactId);
        String reason = null;
        if (content.version() != latest.contentVersion()) reason = "content version changed";
        else if (currentArtifact == null || !currentArtifact.active() || !currentArtifact.id().equals(latest.renderArtifactId()))
            reason = "render artifact changed or is unavailable";
        else if (!Files.isRegularFile(artifactPath(currentArtifact.id()).resolve("video.mp4"))) reason = "render file is unavailable";
        else if (!currentArtifact.sha256().equals(latest.renderSha256())) reason = "render checksum changed";
        else if (!metadataChecksum(content).equals(latest.metadataSha256())) reason = "review metadata changed";
        if (reason != null) {
            ReviewEvent invalidated = invalidateEvent(latest, reason);
            if ("APPROVED".equals(content.status()) || "EXPORT_READY".equals(content.status()))
                contentService.setReviewStatus(contentId, "IN_REVIEW");
            persistState();
            return new CurrentApproval(false, invalidated, reason);
        }
        return new CurrentApproval(true, latest, null);
    }

    public synchronized List<ReviewEvent> history(String contentId) {
        contentService.getContent(contentId);
        currentApproval(contentId); // detect out-of-band changes before reporting validity
        return events.stream().filter(event -> event.contentId().equals(contentId)).toList();
    }

    public synchronized ReviewEvent approve(String contentId, ApprovalRequest request) {
        synchronized (contentService) {
            ContentItem content = contentService.getContent(contentId);
            RenderArtifact artifact = requireCurrentArtifact(contentId);
            validateReviewRequest(content, artifact, request == null ? null : request.expectedContentVersion(),
                    request == null ? null : request.expectedArtifactId());
            if (!"IN_REVIEW".equals(content.status())) throw new IllegalArgumentException("content must be IN_REVIEW before approval");
            requiredIdentity(request.reviewerIdentity());
            requireCompleteMetadata(content);
            String metadataSha256 = metadataChecksum(content);
            ApprovalSnapshot snapshot = snapshot(content, artifact, metadataSha256);
            ReviewEvent event = new ReviewEvent(UUID.randomUUID().toString(), contentId, request.reviewerIdentity().trim(),
                    "APPROVED", clean(request.note()), Instant.now(), content.version(), artifact.id(), artifact.sha256(),
                    metadataSha256, snapshot, false, null, null);
            events.add(event);
            contentService.setReviewStatus(contentId, "APPROVED");
            persistState();
            return event;
        }
    }

    public synchronized ReviewEvent reject(String contentId, RejectionRequest request) {
        synchronized (contentService) {
            ContentItem content = contentService.getContent(contentId);
            RenderArtifact artifact = requireCurrentArtifact(contentId);
            validateReviewRequest(content, artifact, request == null ? null : request.expectedContentVersion(),
                    request == null ? null : request.expectedArtifactId());
            if (!"IN_REVIEW".equals(content.status())) throw new IllegalArgumentException("content must be IN_REVIEW before rejection");
            String reason = requiredIdentity(request.reason());
            ApprovalSnapshot snapshot = snapshot(content, artifact, metadataChecksum(content));
            ReviewEvent event = new ReviewEvent(UUID.randomUUID().toString(), contentId,
                    requiredIdentity(request.reviewerIdentity()), "REJECTED", reason, Instant.now(), content.version(),
                    artifact.id(), artifact.sha256(), snapshot.metadataSha256(), snapshot, false, null, null);
            events.add(event);
            contentService.setReviewStatus(contentId, "REJECTED");
            persistState();
            return event;
        }
    }

    public synchronized ContentItem resubmit(String contentId, ResubmitRequest request) {
        synchronized (contentService) {
            requiredIdentity(request == null ? null : request.reviewerIdentity());
            if (request.note() == null || request.note().isBlank())
                throw new IllegalArgumentException("resubmission note is required");
            RenderArtifact artifact = requireCurrentArtifact(contentId);
            ContentItem content = contentService.resubmitContent(contentId);
            ApprovalSnapshot snapshot = snapshot(content, artifact, metadataChecksum(content));
            events.add(new ReviewEvent(UUID.randomUUID().toString(), contentId, request.reviewerIdentity().trim(),
                    "RESUBMITTED", request.note().trim(), Instant.now(), content.version(), artifact.id(), artifact.sha256(),
                    snapshot.metadataSha256(), snapshot, false, null, null));
            persistState();
            return content;
        }
    }

    /** Called after the content endpoint has committed an incremented content version. */
    public synchronized void onContentUpdated(String contentId) {
        synchronized (contentService) {
            invalidateCurrentApproval(contentId, "content metadata edited");
            ContentItem content = contentService.getContent(contentId);
            if ("APPROVED".equals(content.status()) || "EXPORT_READY".equals(content.status()))
                contentService.setReviewStatus(contentId, "IN_REVIEW");
            persistState();
        }
    }

    public synchronized ContentItem updateContent(String contentId, ContentItemRequest request) {
        synchronized (contentService) {
            ContentItem updated = contentService.updateContent(contentId, request);
            invalidateCurrentApproval(contentId, "content metadata edited");
            persistState();
            return updated;
        }
    }

    public synchronized ExportPlan prepareExport(String contentId) {
        synchronized (contentService) {
            CurrentApproval validity = currentApproval(contentId);
            if (!validity.valid()) throw new ConflictException("approved export unavailable: " + validity.reason());
            ReviewEvent approval = validity.event();
            RenderArtifact artifact = requireArtifact(approval.renderArtifactId());
            ContentItem content = contentService.getContent(contentId);
            Brand brand = contentService.getBrand(content.brandId());
            Category category = content.categoryId() == null ? null : contentService.getCategory(content.categoryId());
            Instant exportedAt = Instant.now();
            ExportPackage descriptor = new ExportPackage(UUID.randomUUID().toString(), contentId, exportedAt,
                    approval.eventId(), approval.renderSha256(), approval.metadataSha256(), approval.contentVersion(),
                    sanitizeFilename(content.title()) + "-approved-export.zip");
            Map<String, Object> metadata = new TreeMap<>();
            metadata.put("exportId", descriptor.exportId());
            metadata.put("exportedAt", exportedAt);
            metadata.put("contentId", content.id());
            metadata.put("contentVersion", approval.contentVersion());
            metadata.put("brandId", brand.id());
            metadata.put("brandName", brand.name());
            metadata.put("categoryId", category == null ? null : category.id());
            metadata.put("categoryName", category == null ? null : category.name());
            metadata.put("contentFormat", content.contentType());
            metadata.put("title", content.title());
            metadata.put("description", content.description());
            metadata.put("script", content.script());
            metadata.put("language", content.language());
            metadata.put("tags", content.tags());
            metadata.put("targetChannelIds", content.targetChannelIds());
            metadata.put("platformMetadata", content.platformMetadata());
            metadata.put("renderSha256", approval.renderSha256());
            metadata.put("metadataSha256", approval.metadataSha256());
            metadata.put("approvalEventId", approval.eventId());
            metadata.put("approval", approval);
            Map<String, Object> provenance = new TreeMap<>();
            provenance.put("sources", content.sourceRecords());
            provenance.put("assets", content.assets());
            provenance.put("renderSha256", approval.renderSha256());
            provenance.put("assetProvenance", readQa(artifact.id()).path("asset_provenance"));
            try {
                return new ExportPlan(descriptor, artifactPath(artifact.id()).resolve("video.mp4"),
                        mapper.writeValueAsBytes(metadata), mapper.writeValueAsBytes(provenance),
                        Files.readAllBytes(artifactPath(artifact.id()).resolve("production-manifest.json")),
                        Files.readAllBytes(artifactPath(artifact.id()).resolve("qa-report.json")),
                        mapper.writeValueAsBytes(approval));
            } catch (IOException error) {
                throw new IllegalStateException("could not prepare approved export package", error);
            }
        }
    }

    public void writeExport(ExportPlan plan, OutputStream output) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(output);
        addFile(zip, "video.mp4", plan.video);
        addBytes(zip, "metadata.json", plan.metadata);
        addBytes(zip, "sources-and-rights.json", plan.sourcesAndRights);
        addBytes(zip, "production-manifest.json", plan.productionManifest);
        addBytes(zip, "qa-report.json", plan.qaReport);
        addBytes(zip, "approval.json", plan.approval);
        zip.finish();
        zip.flush();
    }

    private void validateManifestAndQa(ContentItem content, JsonNode manifest, JsonNode qa) {
        if (!"1.0".equals(manifest.path("schema_version").asText()))
            throw new IllegalArgumentException("unsupported production manifest schema_version");
        String manifestContent = requiredText(manifest.path("job").path("content_id"), "manifest job.content_id");
        String manifestBrand = requiredText(manifest.path("job").path("brand_id"), "manifest job.brand_id");
        if (!content.id().equals(manifestContent) || !content.brandId().equals(manifestBrand))
            throw new IllegalArgumentException("production manifest content ID or brand ID does not match the selected content item");
        String qaContent = requiredText(qa.path("content_id"), "QA report content_id");
        String qaBrand = requiredText(qa.path("brand_id"), "QA report brand_id");
        if (!content.id().equals(qaContent) || !content.brandId().equals(qaBrand))
            throw new IllegalArgumentException("QA report content ID or brand ID does not match the selected content item");
        if (!manifest.path("job").path("job_id").asText().equals(qa.path("job_id").asText()))
            throw new IllegalArgumentException("manifest job ID and QA report job ID do not match");
        if (!"success".equalsIgnoreCase(qa.path("status").asText()))
            throw new IllegalArgumentException("QA report does not report a successful render");
        JsonNode checks = qa.path("checks");
        for (String check : REQUIRED_QA_CHECKS) {
            if (!checks.path(check).isBoolean() || !checks.path(check).asBoolean())
                throw new IllegalArgumentException("QA report mandatory check failed or is missing: " + check);
        }
        requiredText(qa.path("manifest"), "QA report manifest reference");
        if (!qa.path("output_metadata").isObject()) throw new IllegalArgumentException("QA report output_metadata is missing");
        String manifestFormat = requiredText(manifest.path("content").path("format"), "manifest content.format");
        if (!manifestFormat.equals(content.contentType()))
            throw new IllegalArgumentException("production manifest content format does not match the selected content item");
        String manifestProfile = requiredText(manifest.path("content").path("production_profile_id"), "manifest content.production_profile_id");
        if (content.productionProfileId() != null && !content.productionProfileId().equals(manifestProfile))
            throw new IllegalArgumentException("production manifest profile does not match the selected content item");
        requiredText(manifest.path("render").path("profile"), "manifest render.profile");
        if (!manifest.path("visuals").isArray() || manifest.path("visuals").isEmpty())
            throw new IllegalArgumentException("production manifest must contain visual assets");
        for (JsonNode asset : manifest.path("visuals")) validateManifestAsset(asset, "visual");
        JsonNode audio = manifest.path("audio");
        if (!audio.isObject() || !audio.path("required").isBoolean())
            throw new IllegalArgumentException("production manifest audio settings are missing or invalid");
        if (audio.path("path").isTextual() && !audio.path("path").asText().isBlank()) validateManifestAsset(audio, "audio");
        else if (audio.path("required").asBoolean()) throw new IllegalArgumentException("required narration/audio path is missing");
        validateQaProvenance(manifest, qa);
        requiredText(manifest.path("subtitles").path("path"), "manifest subtitles.path");
        JsonNode subtitle = qa.path("subtitle");
        requiredText(subtitle.path("path"), "QA report subtitle.path");
        if (!subtitle.path("burned_in").asBoolean(false) || !subtitle.path("settings").isObject())
            throw new IllegalArgumentException("QA report subtitle rendering settings are missing or captions were not burned in");
        JsonNode manifestSubtitleSettings = manifest.path("subtitles").path("settings");
        if (!manifestSubtitleSettings.isMissingNode() && !mapper.valueToTree(manifestSubtitleSettings).equals(mapper.valueToTree(subtitle.path("settings"))))
            throw new IllegalArgumentException("QA subtitle settings do not match the production manifest");
        JsonNode render = manifest.path("render");
        double duration = render.path("duration_seconds").asDouble(0);
        if (duration <= 0 || duration > 600) throw new IllegalArgumentException("production manifest duration is invalid");
        if (!"shorts-vertical-v1".equals(render.path("profile").asText()))
            throw new IllegalArgumentException("unsupported Stage 2 render profile");
        requiredText(manifest.path("expected_output"), "manifest expected_output");
    }

    private void validateManifestAsset(JsonNode asset, String kind) {
        requiredText(asset.path("asset_id"), kind + " asset_id");
        requiredText(asset.path("path"), kind + " path");
        requiredText(asset.path("source_reference"), kind + " source_reference");
        if ("visual".equals(kind)) {
            String visualKind = requiredText(asset.path("kind"), "visual kind");
            if (!Set.of("image", "video").contains(visualKind)) throw new IllegalArgumentException("visual kind must be image or video");
        }
        JsonNode rights = asset.path("rights");
        requiredText(rights.path("owner"), kind + " rights.owner");
        String license = requiredText(rights.path("license_type"), kind + " rights.license_type");
        if (!"owned".equalsIgnoreCase(license)) {
            requiredText(rights.path("license_evidence"), kind + " rights.license_evidence");
            requiredText(rights.path("attribution"), kind + " rights.attribution");
        }
        if (!rights.path("permitted_uses").isArray() || rights.path("permitted_uses").isEmpty())
            throw new IllegalArgumentException(kind + " rights.permitted_uses must not be empty");
    }

    private List<Map<String, Object>> manifestProvenance(JsonNode manifest) {
        List<Map<String, Object>> provenance = new ArrayList<>();
        for (JsonNode visual : manifest.path("visuals")) provenance.add(provenanceEntry(visual, "visual"));
        JsonNode audio = manifest.path("audio");
        if (audio.path("path").isTextual() && !audio.path("path").asText().isBlank())
            provenance.add(provenanceEntry(audio, "audio"));
        return List.copyOf(provenance);
    }

    private Map<String, Object> provenanceEntry(JsonNode asset, String kind) {
        Map<String, Object> entry = new TreeMap<>();
        entry.put("assetId", asset.path("asset_id").asText());
        entry.put("kind", kind.equals("visual") ? asset.path("kind").asText() : kind);
        entry.put("sourceReference", asset.path("source_reference").asText());
        entry.put("rights", mapper.convertValue(asset.path("rights"), Map.class));
        if (asset.has("order")) entry.put("order", asset.path("order").asInt());
        return entry;
    }

    private void validateQaProvenance(JsonNode manifest, JsonNode qa) {
        JsonNode reported = qa.path("asset_provenance");
        if (!reported.isArray()) throw new IllegalArgumentException("QA report asset_provenance must be an array");
        List<JsonNode> expectedAssets = new ArrayList<>();
        manifest.path("visuals").forEach(expectedAssets::add);
        JsonNode audio = manifest.path("audio");
        if (audio.path("path").isTextual() && !audio.path("path").asText().isBlank()) expectedAssets.add(audio);
        if (reported.size() != expectedAssets.size())
            throw new IllegalArgumentException("QA report asset provenance does not match the production manifest");
        for (JsonNode expected : expectedAssets) {
            JsonNode match = null;
            for (JsonNode candidate : reported) {
                if (candidate.path("asset_id").asText().equals(expected.path("asset_id").asText())) { match = candidate; break; }
            }
            if (match == null || !match.path("source_reference").equals(expected.path("source_reference"))
                    || !match.path("rights").equals(expected.path("rights")))
                throw new IllegalArgumentException("QA report asset provenance or rights do not match manifest asset "
                        + expected.path("asset_id").asText());
        }
    }

    private Map<String, Object> subtitleSummary(JsonNode subtitle) {
        Map<String, Object> summary = new TreeMap<>();
        summary.put("fileName", sanitizeFilename(subtitle.path("path").asText()));
        summary.put("burnedIn", subtitle.path("burned_in").asBoolean());
        summary.put("settings", mapper.convertValue(subtitle.path("settings"), Map.class));
        return summary;
    }

    private static List<String> stringList(JsonNode values) {
        if (values == null || !values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> { if (value.isTextual()) result.add(value.asText()); });
        return List.copyOf(result);
    }

    private Map<String, Object> validateActualOutput(JsonNode manifest, JsonNode qa, JsonNode probe, Path video) throws IOException {
        JsonNode streamList = probe.path("streams");
        JsonNode videoStream = null;
        JsonNode audioStream = null;
        for (JsonNode stream : streamList) {
            if ("video".equals(stream.path("codec_type").asText()) && videoStream == null) videoStream = stream;
            if ("audio".equals(stream.path("codec_type").asText()) && audioStream == null) audioStream = stream;
        }
        if (videoStream == null) throw new IllegalArgumentException("uploaded MP4 has no readable video stream");
        JsonNode format = probe.path("format");
        double duration = format.path("duration").asDouble(0);
        if (duration <= 0) throw new IllegalArgumentException("uploaded MP4 has an invalid duration");
        JsonNode render = manifest.path("render");
        if ("shorts-vertical-v1".equals(render.path("profile").asText())) {
            if (videoStream.path("width").asInt() != 1080 || videoStream.path("height").asInt() != 1920
                    || !"h264".equals(videoStream.path("codec_name").asText())
                    || !"30/1".equals(videoStream.path("avg_frame_rate").asText())
                    || !"yuv420p".equals(videoStream.path("pix_fmt").asText()))
                throw new IllegalArgumentException("uploaded MP4 metadata does not match the Shorts render profile");
            boolean audioRequired = render.path("audio_required").asBoolean(manifest.path("audio").path("required").asBoolean(true));
            if (audioRequired && audioStream == null) throw new IllegalArgumentException("uploaded MP4 has no required audio stream");
            if (audioStream != null && !"aac".equals(audioStream.path("codec_name").asText()))
                throw new IllegalArgumentException("uploaded MP4 audio codec does not match the Shorts render profile");
        }
        JsonNode reported = qa.path("output_metadata");
        if (reported.path("width").asInt(-1) != videoStream.path("width").asInt()
                || reported.path("height").asInt(-1) != videoStream.path("height").asInt()
                || !reported.path("video_codec").asText().equals(videoStream.path("codec_name").asText()))
            throw new IllegalArgumentException("QA output metadata does not match FFprobe results for the uploaded MP4");
        double reportedDuration = reported.path("duration_seconds").asDouble(-1);
        if (reportedDuration <= 0 || Math.abs(reportedDuration - duration) > 0.15)
            throw new IllegalArgumentException("QA duration does not match the uploaded MP4");
        if (!reported.path("frame_rate").asText().equals(videoStream.path("avg_frame_rate").asText())
                || !reported.path("pixel_format").asText().equals(videoStream.path("pix_fmt").asText())
                || reported.path("audio_present").asBoolean() != (audioStream != null)
                || (audioStream != null && !reported.path("audio_codec").asText().equals(audioStream.path("codec_name").asText())))
            throw new IllegalArgumentException("QA stream metadata does not match FFprobe results for the uploaded MP4");
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("width", videoStream.path("width").asInt());
        metadata.put("height", videoStream.path("height").asInt());
        metadata.put("videoCodec", videoStream.path("codec_name").asText());
        metadata.put("frameRate", videoStream.path("avg_frame_rate").asText());
        metadata.put("pixelFormat", videoStream.path("pix_fmt").asText());
        metadata.put("durationSeconds", duration);
        metadata.put("audioPresent", audioStream != null);
        metadata.put("audioCodec", audioStream == null ? null : audioStream.path("codec_name").asText());
        metadata.put("fileSizeBytes", Files.size(video));
        return metadata;
    }

    private JsonNode probeVideo(Path video) {
        Path probeOutput = video.resolveSibling("ffprobe-output.json");
        try {
            Process process = new ProcessBuilder(ffprobeExecutable, "-v", "error", "-show_format", "-show_streams",
                    "-of", "json", video.toString()).redirectErrorStream(true)
                    .redirectOutput(probeOutput.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalArgumentException("FFprobe timed out while reading the uploaded MP4");
            }
            if (process.exitValue() != 0) throw new IllegalArgumentException("uploaded MP4 is corrupt or unreadable by FFprobe");
            return mapper.readTree(Files.readAllBytes(probeOutput));
        } catch (IOException error) {
            throw new IllegalStateException("FFprobe is required for render registration; install it and ensure it is on PATH", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFprobe inspection was interrupted", error);
        } finally {
            try { Files.deleteIfExists(probeOutput); } catch (IOException ignored) { }
        }
    }

    private void requireCompleteMetadata(ContentItem content) {
        requiredIdentity(content.title());
        requiredIdentity(content.description());
        requiredIdentity(content.contentType());
        requiredIdentity(content.language());
        for (AssetRequest asset : content.assets()) {
            if (asset == null || asset.owner() == null || asset.owner().isBlank()
                    || asset.licenseType() == null || asset.licenseType().isBlank()
                    || asset.permittedUses() == null || asset.permittedUses().isEmpty())
                throw new IllegalArgumentException("all assets need an owner, license type, and permitted uses before approval");
            if (!"owned".equalsIgnoreCase(asset.licenseType())
                    && ((asset.licenseEvidence() == null || asset.licenseEvidence().isBlank())
                    || (asset.attribution() == null || asset.attribution().isBlank())))
                throw new IllegalArgumentException("non-owned assets need license evidence and attribution before approval");
        }
        Set<String> platforms = contentService.listChannels(content.brandId()).stream()
                .filter(channel -> content.targetChannelIds().contains(channel.id())).map(Channel::platform).collect(java.util.stream.Collectors.toSet());
        for (String platform : content.platformMetadata().keySet()) {
            if (!platforms.contains(platform)) throw new IllegalArgumentException("platform metadata must match a selected destination: " + platform);
        }
    }

    private ApprovalSnapshot snapshot(ContentItem content, RenderArtifact artifact, String metadataSha256) {
        Brand brand = contentService.getBrand(content.brandId());
        Category category = content.categoryId() == null ? null : contentService.getCategory(content.categoryId());
        List<Channel> destinations = contentService.listChannels(content.brandId()).stream()
                .filter(channel -> content.targetChannelIds().contains(channel.id())).toList();
        return new ApprovalSnapshot(content.id(), content.brandId(), content.categoryId(), brand.name(),
                category == null ? null : category.name(), content.version(), metadataSha256, artifact.id(),
                artifact.sha256(), content, destinations, Instant.now());
    }

    private String metadataChecksum(ContentItem content) {
        Map<String, Object> canonical = new TreeMap<>();
        Brand brand = contentService.getBrand(content.brandId());
        Category category = content.categoryId() == null ? null : contentService.getCategory(content.categoryId());
        canonical.put("contentId", content.id());
        canonical.put("contentVersion", content.version());
        canonical.put("brandId", content.brandId());
        canonical.put("brandName", brand.name());
        canonical.put("categoryId", content.categoryId());
        canonical.put("categoryName", category == null ? null : category.name());
        canonical.put("contentFormat", content.contentType());
        canonical.put("title", content.title());
        canonical.put("description", content.description());
        canonical.put("script", content.script());
        canonical.put("brief", content.brief());
        canonical.put("language", content.language());
        canonical.put("tags", content.tags());
        canonical.put("productionProfileId", content.productionProfileId());
        canonical.put("sourceRecords", content.sourceRecords());
        canonical.put("assets", content.assets());
        canonical.put("targetChannelIds", content.targetChannelIds());
        canonical.put("platformMetadata", content.platformMetadata());
        canonical.put("destinations", contentService.listChannels(content.brandId()).stream()
                .filter(channel -> content.targetChannelIds().contains(channel.id()))
                .map(channel -> Map.of("id", channel.id(), "platform", channel.platform(), "displayName", channel.displayName())).toList());
        try { return sha256(mapper.writeValueAsBytes(canonicalize(mapper.valueToTree(canonical)))); }
        catch (IOException error) { throw new IllegalStateException("could not canonicalize review metadata", error); }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) result.set(key, canonicalize(value.get(key)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode item : value) result.add(canonicalize(item));
            return result;
        }
        return value.deepCopy();
    }

    private void validateReviewRequest(ContentItem content, RenderArtifact artifact, Integer expectedVersion, String expectedArtifactId) {
        if (expectedVersion == null || expectedVersion != content.version())
            throw new ConflictException("content version changed; refresh the review before acting");
        if (expectedArtifactId == null || !artifact.id().equals(expectedArtifactId))
            throw new ConflictException("render artifact changed; refresh the review before acting");
    }

    private RenderArtifact requireCurrentArtifact(String contentId) {
        String id = currentArtifactByContent.get(contentId);
        if (id == null) throw new IllegalArgumentException("a validated render artifact is required before review");
        RenderArtifact artifact = requireArtifact(id);
        if (!artifact.active() || !Files.isRegularFile(artifactPath(id).resolve("video.mp4")))
            throw new ConflictException("the current render artifact is unavailable");
        return artifact;
    }

    private ReviewEvent latestApproval(String contentId) {
        for (int index = events.size() - 1; index >= 0; index--) {
            ReviewEvent event = events.get(index);
            if (event.contentId().equals(contentId) && "APPROVED".equals(event.decision())) return event;
        }
        return null;
    }

    private ReviewEvent latestEvent(String contentId) {
        for (int index = events.size() - 1; index >= 0; index--) {
            ReviewEvent event = events.get(index);
            if (event.contentId().equals(contentId)) return event;
        }
        return null;
    }

    private void invalidateCurrentApproval(String contentId, String reason) {
        ReviewEvent approval = latestApproval(contentId);
        if (approval != null && !approval.invalidated()) invalidateEvent(approval, reason);
    }

    private ReviewEvent invalidateEvent(ReviewEvent event, String reason) {
        ReviewEvent invalidated = new ReviewEvent(event.eventId(), event.contentId(), event.reviewerIdentity(), event.decision(),
                event.note(), event.createdAt(), event.contentVersion(), event.renderArtifactId(), event.renderSha256(),
                event.metadataSha256(), event.snapshot(), true, Instant.now(), reason);
        int index = events.indexOf(event);
        if (index >= 0) events.set(index, invalidated);
        return invalidated;
    }

    private RenderArtifact requireArtifact(String id) {
        try { UUID.fromString(id); }
        catch (IllegalArgumentException error) { throw new NotFoundException("render artifact not found"); }
        RenderArtifact artifact = artifacts.get(id);
        if (artifact == null) throw new NotFoundException("render artifact not found: " + id);
        return artifact;
    }

    private Path artifactPath(String id) { return artifactDirectory.resolve(UUID.fromString(id).toString()).normalize(); }

    private JsonNode readQa(String id) {
        try { return mapper.readTree(artifactPath(id).resolve("qa-report.json").toFile()); }
        catch (IOException error) { throw new IllegalStateException("could not read stored QA report", error); }
    }

    private byte[] readJsonUpload(MultipartFile file, String label) {
        if (file == null || file.isEmpty() || file.getSize() > JSON_UPLOAD_LIMIT)
            throw new IllegalArgumentException(label + " must be a non-empty JSON file no larger than 2 MB");
        String filename = sanitizeFilename(file.getOriginalFilename());
        if (!filename.toLowerCase().endsWith(".json")) throw new IllegalArgumentException(label + " must use the .json file type");
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > JSON_UPLOAD_LIMIT) throw new IllegalArgumentException(label + " must be no larger than 2 MB");
            return bytes;
        }
        catch (IOException error) { throw new IllegalArgumentException("could not read " + label, error); }
    }

    private JsonNode parseJson(byte[] data, String label) {
        try {
            JsonNode node = mapper.readTree(data);
            if (node == null || !node.isObject()) throw new IllegalArgumentException(label + " must contain a JSON object");
            return node;
        } catch (IOException error) { throw new IllegalArgumentException(label + " is not valid JSON", error); }
    }

    private static String requiredText(JsonNode node, String label) {
        if (node == null || !node.isTextual() || node.asText().isBlank())
            throw new IllegalArgumentException(label + " is required");
        return node.asText().trim();
    }

    private static String requiredIdentity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("reviewer identity and required metadata must not be blank");
        return value.trim();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) return "upload";
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..")) return "upload";
        return name.length() > 160 ? name.substring(name.length() - 160) : name;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    private static RenderArtifact withActive(RenderArtifact item, boolean active) {
        return new RenderArtifact(item.id(), item.contentId(), item.brandId(), item.originalFileName(), item.sha256(),
                item.uploadedAt(), item.manifestSchemaVersion(), item.productionProfile(), item.outputMetadata(),
                item.checks(), item.assetProvenance(), item.subtitle(), item.warnings(), active);
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(source, destination); }
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private void addFile(ZipOutputStream zip, String name, Path path) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(path, zip);
        zip.closeEntry();
    }

    private static void addBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            ReviewState state = mapper.readValue(stateFile.toFile(), ReviewState.class);
            if (state.artifacts() != null) artifacts.putAll(state.artifacts());
            if (state.currentArtifactByContent() != null) currentArtifactByContent.putAll(state.currentArtifactByContent());
            if (state.events() != null) events.addAll(state.events());
        } catch (IOException error) {
            throw new IllegalStateException("Could not load local review history from " + stateFile, error);
        }
    }

    private void persistState() {
        Path temporary = stateFile.resolveSibling("review-state.json.tmp");
        try {
            Files.createDirectories(dataDirectory);
            mapper.writeValue(temporary.toFile(), new ReviewState(artifacts, currentArtifactByContent, events));
            try { Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("Could not persist review history to " + stateFile, error);
        }
    }
}
