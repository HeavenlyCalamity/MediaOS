package com.mediaos.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response contracts. Category and format values remain user-defined strings. */
public final class Models {
    private Models() {}

    public record BrandRequest(String name, String slug, String description, String defaultLanguage,
                               String tone, List<String> contentPillars, Map<String, String> audienceProfile) {}
    public record Brand(String id, String name, String slug, String description, String defaultLanguage,
                        String tone, List<String> contentPillars, Map<String, String> audienceProfile,
                        boolean enabled, Instant createdAt, Instant updatedAt) {}

    public record CategoryRequest(String name, String slug, String description, String parentCategoryId) {}
    public record Category(String id, String name, String slug, String description, String parentCategoryId,
                           Instant createdAt, Instant updatedAt) {}

    public record ChannelRequest(String brandId, String platform, String displayName,
                                 String accountReference, String status) {}
    public record Channel(String id, String brandId, String platform, String displayName,
                          String accountReference, String status, Instant createdAt, Instant updatedAt) {}

    public record ProductionProfileRequest(String brandId, String name, String categoryId, String contentFormat,
                                           List<String> workflowStages, Map<String, String> settings) {}
    public record ProductionProfile(String id, String brandId, String name, String categoryId, String contentFormat,
                                    List<String> workflowStages, Map<String, String> settings,
                                    Instant createdAt, Instant updatedAt) {}

    public record SourceRecord(String title, String url, String publisher, String accessedAt) {}
    public record AssetRequest(String kind, String uri, String mimeType, String owner,
                               String licenseType, String licenseEvidence, String attribution,
                               List<String> permittedUses) {}
    public record ContentItemRequest(String brandId, String categoryId, String title, String contentType,
                                     String language, String brief, String productionProfileId,
                                     List<String> targetChannelIds, List<SourceRecord> sourceRecords,
                                     List<AssetRequest> assets, String description, String script,
                                     List<String> tags, Map<String, Map<String, String>> platformMetadata) {
        public ContentItemRequest(String brandId, String categoryId, String title, String contentType,
                                  String language, String brief, String productionProfileId,
                                  List<String> targetChannelIds, List<SourceRecord> sourceRecords,
                                  List<AssetRequest> assets) {
            this(brandId, categoryId, title, contentType, language, brief, productionProfileId,
                    targetChannelIds, sourceRecords, assets, "", "", List.of(), Map.of());
        }
    }
    public record ContentItem(String id, String brandId, String categoryId, String title, String contentType,
                              String language, String brief, String productionProfileId,
                              List<String> targetChannelIds, List<SourceRecord> sourceRecords,
                              List<AssetRequest> assets, String description, String script, List<String> tags,
                              Map<String, Map<String, String>> platformMetadata, String status, int version,
                              Instant createdAt, Instant updatedAt) {}

    public record TransitionRequest(String status) {}

    public record RenderArtifact(String id, String contentId, String brandId, String originalFileName,
                                 String sha256, Instant uploadedAt, String manifestSchemaVersion,
                                 String productionProfile, Map<String, Object> outputMetadata,
                                 Map<String, Object> checks, List<Map<String, Object>> assetProvenance,
                                 Map<String, Object> subtitle, List<String> warnings, boolean active) {}

    public record ApprovalSnapshot(String contentId, String brandId, String categoryId, String brandName,
                                   String categoryName, int contentVersion, String metadataSha256,
                                   String renderArtifactId, String renderSha256, ContentItem content,
                                   List<Channel> destinations, Instant capturedAt) {}

    public record ReviewEvent(String eventId, String contentId, String reviewerIdentity, String decision,
                              String note, Instant createdAt, int contentVersion, String renderArtifactId,
                              String renderSha256, String metadataSha256, ApprovalSnapshot snapshot,
                              boolean invalidated, Instant invalidatedAt, String invalidationReason) {}

    public record ApprovalRequest(String reviewerIdentity, String note, int expectedContentVersion,
                                  String expectedArtifactId) {}
    public record RejectionRequest(String reviewerIdentity, String reason, int expectedContentVersion,
                                   String expectedArtifactId) {}
    public record ResubmitRequest(String reviewerIdentity, String note) {}
    public record CurrentApproval(boolean valid, ReviewEvent event, String reason) {}
    public record ReviewSummary(ContentItem content, String brandName, String categoryName,
                                String reviewStatus, RenderArtifact artifact, CurrentApproval approval) {}
    public record ExportPackage(String exportId, String contentId, Instant exportedAt,
                                String approvalEventId, String renderSha256, String metadataSha256,
                                int contentVersion, String fileName) {}
}
