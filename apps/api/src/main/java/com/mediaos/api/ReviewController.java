package com.mediaos.api;

import static com.mediaos.api.Models.*;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/reviews/queue")
    public List<ReviewSummary> queue(@RequestParam(required = false) String brandId,
                                     @RequestParam(required = false) String categoryId,
                                     @RequestParam(required = false) String contentFormat,
                                     @RequestParam(required = false) String status) {
        return reviewService.queue(brandId, categoryId, contentFormat, status);
    }

    @PostMapping(value = "/content-items/{contentId}/render-artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RenderArtifact register(@PathVariable String contentId,
                                   @RequestParam("video") MultipartFile video,
                                   @RequestParam("manifest") MultipartFile manifest,
                                   @RequestParam("qaReport") MultipartFile qaReport) {
        return reviewService.registerRender(contentId, video, manifest, qaReport);
    }

    @GetMapping("/content-items/{contentId}/render-artifacts")
    public List<RenderArtifact> artifacts(@PathVariable String contentId) {
        return reviewService.listArtifacts(contentId);
    }

    @GetMapping("/render-artifacts/{artifactId}")
    public RenderArtifact artifact(@PathVariable String artifactId) {
        return reviewService.getArtifact(artifactId);
    }

    @GetMapping("/render-artifacts/{artifactId}/video")
    public ResponseEntity<Resource> video(@PathVariable String artifactId) throws IOException {
        Resource resource = reviewService.videoResource(artifactId);
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("preview.mp4").build().toString())
                .cacheControl(CacheControl.noCache()).contentLength(Files.size(resource.getFile().toPath())).body(resource);
    }

    @GetMapping("/content-items/{contentId}/review")
    public ReviewSummary review(@PathVariable String contentId) {
        return reviewService.summary(contentId);
    }

    @GetMapping("/content-items/{contentId}/review-history")
    public List<ReviewEvent> history(@PathVariable String contentId) {
        return reviewService.history(contentId);
    }

    @GetMapping("/content-items/{contentId}/approval")
    public CurrentApproval approval(@PathVariable String contentId) {
        return reviewService.currentApproval(contentId);
    }

    @PostMapping("/content-items/{contentId}/approve")
    public ReviewEvent approve(@PathVariable String contentId, @RequestBody ApprovalRequest request) {
        return reviewService.approve(contentId, request);
    }

    @PostMapping("/content-items/{contentId}/reject")
    public ReviewEvent reject(@PathVariable String contentId, @RequestBody RejectionRequest request) {
        return reviewService.reject(contentId, request);
    }

    @PostMapping("/content-items/{contentId}/resubmit")
    public ContentItem resubmit(@PathVariable String contentId, @RequestBody ResubmitRequest request) {
        return reviewService.resubmit(contentId, request);
    }

    @GetMapping("/content-items/{contentId}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String contentId) {
        ReviewService.ExportPlan plan = reviewService.prepareExport(contentId);
        ExportPackage descriptor = plan.descriptor();
        StreamingResponseBody stream = output -> reviewService.writeExport(plan, output);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(descriptor.fileName()).build().toString())
                .header("X-Content-Version", Integer.toString(descriptor.contentVersion()))
                .header("X-Approval-Event", descriptor.approvalEventId())
                .header("X-Render-SHA256", descriptor.renderSha256())
                .header("X-Metadata-SHA256", descriptor.metadataSha256())
                .body(stream);
    }

    @GetMapping("/content-items/{contentId}/export/video")
    public ResponseEntity<Resource> exportVideo(@PathVariable String contentId) throws IOException {
        ReviewService.ExportPlan plan = reviewService.prepareExport(contentId);
        Resource resource = new org.springframework.core.io.FileSystemResource(plan.video());
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("approved-video.mp4").build().toString())
                .header("X-Content-Version", Integer.toString(plan.descriptor().contentVersion()))
                .header("X-Approval-Event", plan.descriptor().approvalEventId())
                .header("X-Render-SHA256", plan.descriptor().renderSha256())
                .header("X-Metadata-SHA256", plan.descriptor().metadataSha256())
                .lastModified(Instant.now()).contentLength(Files.size(plan.video())).body(resource);
    }
}
