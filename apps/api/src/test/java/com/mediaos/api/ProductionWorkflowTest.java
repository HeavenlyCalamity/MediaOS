package com.mediaos.api;

import static com.mediaos.api.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionWorkflowTest {
    @TempDir Path temporary;

    @Test
    void mockWorkflowRendersAndRegistersPendingReviewArtifact() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        MediaOsService content = new MediaOsService();
        String unique = UUID.randomUUID().toString();
        Brand brand = content.createBrand(new BrandRequest("Workflow test", "workflow-" + unique, "", "en", "", List.of(), Map.of()));
        String workflow = "{\"workflow_id\":\"test-local\",\"version\":\"1\",\"mode\":\"MOCK\",\"renderer\":\"shorts-vertical-v1\",\"duration_seconds\":1,\"providers\":{\"research\":\"mock\",\"script\":\"mock\",\"text_to_speech\":\"mock\",\"visual_asset\":\"mock\"}}";
        ProductionProfile profile = content.createProfile(new ProductionProfileRequest(brand.id(), "Test video", null, "short-form video",
                List.of("research", "script", "render"), Map.of("mediaos.workflow_json", workflow)));
        ContentItem item = content.createContent(new ContentItemRequest(brand.id(), null, "Synthetic workflow", "short-form video", "en",
                "Explain a simple test concept", profile.id(), List.of(), List.of(), List.of(), "", "", List.of(), Map.of()));
        content.transitionContent(item.id(), "RESEARCHING");
        content.transitionContent(item.id(), "RESEARCH_READY");
        content.transitionContent(item.id(), "SCRIPT_READY");
        content.transitionContent(item.id(), "ASSETS_READY");
        content.transitionContent(item.id(), "RENDERING");
        content.transitionContent(item.id(), "QA_PENDING");
        ReviewService review = new ReviewService(mapper, content, temporary.toString(), 100_000_000, "ffprobe");
        Path worker = Path.of("..", "..", "workers", "shorts-worker").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("MEDIAOS_PYTHON", "python");
        try (ProductionService production = new ProductionService(mapper, content, review, new LocalProductionExecutionAdapter(),
                temporary.toString(), worker.toString(), python, "", false, 180, 1)) {
        ProductionJob started = production.start(item.id(), new ProductionStartRequest(profile.id(), "idempotency-" + unique));
        assertEquals("QUEUED", started.status());
        assertEquals(started.id(), production.start(item.id(), new ProductionStartRequest(profile.id(), "idempotency-" + unique)).id());

        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        ProductionJob finished = production.get(started.id());
        while (SetHolder.ACTIVE.contains(finished.status()) && System.nanoTime() < deadline) {
            Thread.sleep(500);
            finished = production.get(started.id());
        }
        assertEquals("READY_FOR_REVIEW", finished.status(), "job errors=" + finished.errors());
        assertNotNull(finished.renderArtifactId());
        RenderArtifact artifact = review.getArtifact(finished.renderArtifactId());
        assertEquals(item.id(), artifact.contentId());
        assertTrue(artifact.sha256().matches("[a-f0-9]{64}"));
        assertFalse(review.currentApproval(item.id()).valid(), "production must never approve automatically");
        assertEquals("IN_REVIEW", content.getContent(item.id()).status());

        ContentItem edited = review.updateContent(item.id(), new ContentItemRequest(item.brandId(), item.categoryId(), item.title(),
                item.contentType(), item.language(), item.brief(), profile.id(), item.targetChannelIds(), item.sourceRecords(), item.assets(),
                item.description(), "An owner-edited script that must be preserved exactly.", item.tags(), item.platformMetadata()));
        assertEquals("ASSETS_READY", edited.status());
        assertFalse(review.getArtifact(artifact.id()).active(), "script edits detach the old render");
        ProductionJob second = production.start(item.id(), new ProductionStartRequest(profile.id(), "edited-" + unique));
        finished = production.get(second.id());
        while (SetHolder.ACTIVE.contains(finished.status()) && System.nanoTime() < deadline + Duration.ofMinutes(3).toNanos()) {
            Thread.sleep(500);
            finished = production.get(second.id());
        }
        assertEquals("READY_FOR_REVIEW", finished.status(), "edited-script job errors=" + finished.errors());
        assertEquals("An owner-edited script that must be preserved exactly.", finished.script().get("narration_text"));
        assertNotEquals(artifact.id(), finished.renderArtifactId());
        RenderArtifact rerendered = review.getArtifact(finished.renderArtifactId());
        assertEquals(1080, rerendered.outputMetadata().get("width"));
        assertEquals(1920, rerendered.outputMetadata().get("height"));
        assertEquals("h264", rerendered.outputMetadata().get("videoCodec"));
        assertEquals("30/1", rerendered.outputMetadata().get("frameRate"));
        assertEquals("yuv420p", rerendered.outputMetadata().get("pixelFormat"));
        assertEquals("aac", rerendered.outputMetadata().get("audioCodec"));
        double durationSeconds = ((Number) rerendered.outputMetadata().get("durationSeconds")).doubleValue();
        assertTrue(durationSeconds >= 0.9 && durationSeconds <= 1.1, "FFprobe duration=" + durationSeconds);
        assertFalse(review.currentApproval(item.id()).valid(), "the replacement remains pending explicit human approval");
        }
    }

    private static final class SetHolder { static final java.util.Set<String> ACTIVE = java.util.Set.of("QUEUED", "RUNNING", "RESEARCHING", "SCRIPTING", "GENERATING_AUDIO", "GENERATING_VISUALS", "RENDERING", "QA_PENDING", "CANCELLING"); }
}
