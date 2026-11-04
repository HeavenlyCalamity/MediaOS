package com.mediaos.api;

import static com.mediaos.api.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewApiTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MediaOsService contentService;
    @Autowired private ReviewService reviewService;
    @Value("${mediaos.data-dir}") private String dataDirectory;
    @TempDir Path tempDirectory;

    private Brand brand;
    private ContentItem content;
    private Path video;
    private byte[] videoBytes;
    private Map<String, Object> manifest;
    private Map<String, Object> qa;

    @BeforeEach
    void prepareFixture() throws Exception {
        String unique = UUID.randomUUID().toString();
        brand = contentService.createBrand(new BrandRequest("Review Brand", "review-" + unique, "", "en", "", List.of(), Map.of()));
        AssetRequest asset = new AssetRequest("visual", "asset://fixture/visual-1", "image/png", "Fixture owner", "owned", "", "", List.of("render"));
        SourceRecord source = new SourceRecord("Synthetic source", "https://example.test/source", "Fixture", "2026-11-04");
        content = contentService.createContent(new ContentItemRequest(brand.id(), null, "A reviewable video", "short-form video", "en",
                "fixture brief", null, List.of(), List.of(source), List.of(asset), "A complete description.", "Synthetic script for review.",
                List.of("fixture"), Map.of()));
        video = tempDirectory.resolve("synthetic.mp4");
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=1080x1920:r=30:d=1",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1", "-shortest",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", video.toString()).start();
        assertEquals(0, process.waitFor(), "FFmpeg must create the synthetic API test fixture");
        videoBytes = Files.readAllBytes(video);
        manifest = createManifest(false);
        qa = createQa(videoBytes, true);
    }

    @Test
    void rejectsWrongIdsFailedQaChecksumMismatchAndCorruptVideo() throws Exception {
        Map<String, Object> wrongManifest = createManifest(true);
        performRegister(videoBytes, wrongManifest, qa).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("content ID or brand ID")));

        Map<String, Object> failedQa = new LinkedHashMap<>(qa);
        failedQa.put("status", "failed");
        performRegister(videoBytes, manifest, failedQa).andExpect(status().isBadRequest());

        Map<String, Object> mismatchedProvenance = new LinkedHashMap<>(qa);
        Map<String, Object> wrongRights = Map.of("owner", "Different owner", "license_type", "owned", "permitted_uses", List.of("render", "review"));
        Map<String, Object> correctRights = Map.of("owner", "Fixture owner", "license_type", "owned", "permitted_uses", List.of("render", "review"));
        mismatchedProvenance.put("asset_provenance", List.of(
                Map.of("asset_id", "fixture-visual", "source_reference", "generated://review-test", "rights", wrongRights),
                Map.of("asset_id", "fixture-audio", "source_reference", "generated://review-tone", "rights", correctRights)));
        performRegister(videoBytes, manifest, mismatchedProvenance).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("provenance or rights")));

        Map<String, Object> mismatchedQa = new LinkedHashMap<>(qa);
        mismatchedQa.put("sha256", "0".repeat(64));
        performRegister(videoBytes, manifest, mismatchedQa).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("checksum")));

        byte[] corrupt = "not an MP4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        performRegister(corrupt, manifest, createQa(corrupt, true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("corrupt or unreadable")));

        performRegister(new byte[0], manifest, qa).andExpect(status().isBadRequest());
    }

    @Test
    void approvalNeedsAValidatedRenderAndRejectionNeedsAReason() throws Exception {
        ApprovalRequest approval = new ApprovalRequest("Owner", "", content.version(), "00000000-0000-0000-0000-000000000001");
        mockMvc.perform(post("/api/v1/content-items/{id}/approve", content.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(approval)))
                .andExpect(status().isBadRequest());
        RejectionRequest noRender = new RejectionRequest("Owner", "Reason", content.version(), "00000000-0000-0000-0000-000000000001");
        mockMvc.perform(post("/api/v1/content-items/{id}/reject", content.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(noRender)))
                .andExpect(status().isBadRequest());

        RenderArtifact artifact = register(videoBytes, manifest, qa);
        RejectionRequest noReason = new RejectionRequest("Owner", " ", content.version(), artifact.id());
        mockMvc.perform(post("/api/v1/content-items/{id}/reject", content.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(noReason)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registrationApprovalInvalidationRejectionReapprovalAndApprovedExport() throws Exception {
        RenderArtifact artifact = register(videoBytes, manifest, qa);
        assertEquals(content.id(), artifact.contentId());
        assertEquals(sha256(videoBytes), artifact.sha256());
        assertEquals("h264", artifact.outputMetadata().get("videoCodec"));
        assertEquals(2, artifact.assetProvenance().size());
        assertEquals("captions.srt", artifact.subtitle().get("fileName"));
        assertEquals(true, artifact.subtitle().get("burnedIn"));
        assertEquals("IN_REVIEW", contentService.getContent(content.id()).status());

        mockMvc.perform(get("/api/v1/render-artifacts/{id}/video", artifact.id()).header("Range", "bytes=0-63"))
                .andExpect(status().isPartialContent());

        ReviewEvent firstApproval = approve(content, artifact);
        assertEquals("APPROVED", firstApproval.decision());
        assertEquals(64, firstApproval.metadataSha256().length());
        assertTrue(reviewService.currentApproval(content.id()).valid());

        MvcResult pendingZip = mockMvc.perform(get("/api/v1/content-items/{id}/export", content.id()))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult downloadedZip = mockMvc.perform(asyncDispatch(pendingZip)).andExpect(status().isOk()).andReturn();
        Map<String, byte[]> httpEntries = readZip(downloadedZip.getResponse().getContentAsByteArray());
        assertEquals(Set.of("video.mp4", "metadata.json", "sources-and-rights.json", "production-manifest.json", "qa-report.json", "approval.json"), httpEntries.keySet());
        assertEquals(firstApproval.renderSha256(), sha256(httpEntries.get("video.mp4")));
        mockMvc.perform(get("/api/v1/content-items/{id}/export/video", content.id()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Approval-Event", firstApproval.eventId()));

        ReviewService.ExportPlan plan = reviewService.prepareExport(content.id());
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        reviewService.writeExport(plan, zipBytes);
        Map<String, byte[]> entries = readZip(zipBytes.toByteArray());
        assertEquals(Set.of("video.mp4", "metadata.json", "sources-and-rights.json", "production-manifest.json", "qa-report.json", "approval.json"), entries.keySet());
        assertArrayEquals(videoBytes, entries.get("video.mp4"));
        JsonNode exportedMetadata = mapper.readTree(entries.get("metadata.json"));
        assertEquals(firstApproval.eventId(), exportedMetadata.path("approvalEventId").asText());
        assertEquals(firstApproval.renderSha256(), sha256(entries.get("video.mp4")));
        assertEquals(1, mapper.readTree(entries.get("sources-and-rights.json")).path("sources").size());

        MediaOsService restartedContent = new MediaOsService(mapper, dataDirectory);
        ReviewService restartedReview = new ReviewService(mapper, restartedContent, dataDirectory, 524288000, "ffprobe");
        assertTrue(restartedReview.currentApproval(content.id()).valid(), "approval and content survive a local process restart");
        assertEquals(1, restartedReview.history(content.id()).size());

        ContentItem edited = new ContentItem(content.id(), content.brandId(), content.categoryId(), "Edited title", content.contentType(),
                content.language(), content.brief(), content.productionProfileId(), content.targetChannelIds(), content.sourceRecords(),
                content.assets(), "Edited description.", content.script(), List.of("fixture", "updated"), content.platformMetadata(),
                content.status(), content.version(), content.createdAt(), content.updatedAt());
        mockMvc.perform(put("/api/v1/content-items/{id}", content.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new ContentItemRequest(edited.brandId(), edited.categoryId(), edited.title(),
                                edited.contentType(), edited.language(), edited.brief(), edited.productionProfileId(), edited.targetChannelIds(),
                                edited.sourceRecords(), edited.assets(), edited.description(), edited.script(), edited.tags(), edited.platformMetadata()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_REVIEW"));
        assertFalse(reviewService.currentApproval(content.id()).valid());
        assertTrue(reviewService.history(content.id()).getFirst().invalidated());
        mockMvc.perform(get("/api/v1/content-items/{id}/export", content.id())).andExpect(status().isConflict());

        ContentItem changed = contentService.getContent(content.id());
        RejectionRequest rejection = new RejectionRequest("Owner", "Please revise the opening", changed.version(), artifact.id());
        mockMvc.perform(post("/api/v1/content-items/{id}/reject", content.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(rejection)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("REJECTED"));
        mockMvc.perform(get("/api/v1/content-items/{id}/export", content.id())).andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/content-items/{id}/transition", content.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/content-items/{id}/resubmit", content.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new ResubmitRequest("Owner", "Updated script and resubmitting"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_REVIEW"));
        ContentItem resubmitted = contentService.getContent(content.id());
        ReviewEvent secondApproval = approve(resubmitted, artifact);
        assertTrue(reviewService.currentApproval(content.id()).valid());

        RenderArtifact replacement = register(videoBytes, manifest, qa);
        assertNotEquals(artifact.id(), replacement.id());
        assertFalse(reviewService.currentApproval(content.id()).valid());
        assertTrue(reviewService.history(content.id()).stream().anyMatch(event -> event.eventId().equals(secondApproval.eventId()) && event.invalidated()));
        mockMvc.perform(get("/api/v1/content-items/{id}/export/video", content.id())).andExpect(status().isConflict());
    }

    private ReviewEvent approve(ContentItem item, RenderArtifact artifact) throws Exception {
        ApprovalRequest request = new ApprovalRequest("Owner", "Reviewed the exact render", item.version(), artifact.id());
        MvcResult result = mockMvc.perform(post("/api/v1/content-items/{id}/approve", item.id()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("APPROVED")).andReturn();
        return mapper.readValue(result.getResponse().getContentAsByteArray(), ReviewEvent.class);
    }

    private RenderArtifact register(byte[] videoData, Map<String, Object> productionManifest, Map<String, Object> report) throws Exception {
        MvcResult result = performRegister(videoData, productionManifest, report).andExpect(status().isOk()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsByteArray(), RenderArtifact.class);
    }

    private org.springframework.test.web.servlet.ResultActions performRegister(byte[] videoData,
            Map<String, Object> productionManifest, Map<String, Object> report) throws Exception {
        return mockMvc.perform(multipart("/api/v1/content-items/{id}/render-artifacts", content.id())
                .file(new MockMultipartFile("video", "review-render.mp4", "video/mp4", videoData))
                .file(new MockMultipartFile("manifest", "production-manifest.json", "application/json", mapper.writeValueAsBytes(productionManifest)))
                .file(new MockMultipartFile("qaReport", "render.mp4.qa.json", "application/json", mapper.writeValueAsBytes(report))));
    }

    private Map<String, Object> createManifest(boolean wrongIds) {
        String contentId = wrongIds ? "some-other-content" : content.id();
        String brandId = wrongIds ? "some-other-brand" : content.brandId();
        Map<String, Object> rights = Map.of("owner", "Fixture owner", "license_type", "owned", "permitted_uses", List.of("render", "review"));
        Map<String, Object> visual = Map.of("asset_id", "fixture-visual", "kind", "image", "path", "visual.ppm", "order", 0,
                "source_reference", "generated://review-test", "rights", rights);
        Map<String, Object> audio = Map.of("asset_id", "fixture-audio", "path", "tone.wav", "required", true,
                "source_reference", "generated://review-tone", "rights", rights);
        return Map.of("schema_version", "1.0", "job", Map.of("job_id", "job-review-1", "content_id", contentId, "brand_id", brandId),
                "content", Map.of("format", "short-form video", "production_profile_id", "shorts-profile"),
                "visuals", List.of(visual), "audio", audio,
                "subtitles", Map.of("path", "captions.srt", "settings", Map.of("font_size", 48, "margin_vertical", 160)),
                "render", Map.of("profile", "shorts-vertical-v1", "duration_seconds", 1.0, "audio_required", true),
                "expected_output", "output.mp4");
    }

    private Map<String, Object> createQa(byte[] videoData, boolean checksPass) throws Exception {
        JsonNode probe = probe(video);
        JsonNode videoStream = null;
        JsonNode audioStream = null;
        for (JsonNode stream : probe.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) videoStream = stream;
            if ("audio".equals(stream.path("codec_type").asText())) audioStream = stream;
        }
        Map<String, Boolean> checks = new LinkedHashMap<>();
        for (String key : List.of("video_stream", "dimensions", "video_codec", "frame_rate", "pixel_format", "audio_stream_when_required",
                "audio_codec_when_present", "duration", "readable_by_ffprobe")) checks.put(key, checksPass);
        double duration = probe.path("format").path("duration").asDouble();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("duration_seconds", duration); output.put("size_bytes", videoData.length);
        output.put("width", videoStream.path("width").asInt()); output.put("height", videoStream.path("height").asInt());
        output.put("video_codec", videoStream.path("codec_name").asText()); output.put("frame_rate", videoStream.path("avg_frame_rate").asText());
        output.put("pixel_format", videoStream.path("pix_fmt").asText()); output.put("audio_codec", audioStream.path("codec_name").asText());
        output.put("audio_present", true);
        Map<String, Object> rights = Map.of("owner", "Fixture owner", "license_type", "owned", "permitted_uses", List.of("render", "review"));
        List<Map<String, Object>> provenance = List.of(
                Map.of("asset_id", "fixture-visual", "source_reference", "generated://review-test", "rights", rights),
                Map.of("asset_id", "fixture-audio", "source_reference", "generated://review-tone", "rights", rights));
        return Map.ofEntries(Map.entry("status", checksPass ? "success" : "failed"), Map.entry("created_at", "2026-11-04T00:00:00Z"),
                Map.entry("manifest", "fixture-manifest.json"), Map.entry("job_id", "job-review-1"),
                Map.entry("content_id", content.id()), Map.entry("brand_id", content.brandId()), Map.entry("output", "fixture-output.mp4"),
                Map.entry("output_metadata", output), Map.entry("sha256", sha256(videoData)),
                Map.entry("tool_versions", Map.of("ffmpeg", "fixture", "ffprobe", "fixture")),
                Map.entry("subtitle", Map.of("path", "captions.srt", "settings", Map.of("font_size", 48, "margin_vertical", 160), "burned_in", true)),
                Map.entry("asset_provenance", provenance), Map.entry("checks", checks), Map.entry("warnings", List.of()),
                Map.entry("errors", List.of()));
    }

    private JsonNode probe(Path input) throws Exception {
        Path output = input.resolveSibling("test-ffprobe-output.json");
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", input.toString())
                .redirectOutput(output.toFile()).start();
        assertEquals(0, process.waitFor());
        return mapper.readTree(Files.readAllBytes(output));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, byte[]> readZip(byte[] data) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                files.put(entry.getName(), zip.readAllBytes());
                entry = zip.getNextEntry();
            }
        }
        return files;
    }
}
